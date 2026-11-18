import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
  type XYPosition,
} from '@xyflow/react';

import { ApiError, api, fromApiExample, fromApiWorkflow } from './api/client';
import { CanvasProvider } from './components/Canvas';
import { Dock, type DockTab } from './components/Dock';
import { Inspector } from './components/Inspector';
import { NodePalette } from './components/NodePalette';
import { RunPanel } from './components/RunPanel';
import { Toolbar } from './components/Toolbar';
import { ValidationPanel } from './components/ValidationPanel';
import { WorkflowLibrary } from './components/WorkflowLibrary';
import { NODE_TYPE_DEFINITIONS } from './nodes/definitions';
import { graphToApi, hasBlockingErrors, validateGraph, type GraphIssue } from './nodes/graph';
import { availableVariables, isValidKey, keyOf, suggestKey } from './nodes/keys';
import type {
  ApiExample,
  ApiExecution,
  ApiExecutionSummary,
  ApiIssue,
  ApiWorkflowSummary,
  NodeType,
} from './types';

function toGraphIssues(issues: ApiIssue[]): GraphIssue[] {
  return issues.map((issue) => ({
    severity: issue.severity,
    code: issue.code,
    message: issue.message,
    nodeId: issue.nodeId ?? undefined,
    edgeId: issue.edgeId ?? undefined,
  }));
}

/** Generates a unique id for a new node. */
function newId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `node-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}

const POLL_INTERVAL_MS = 400;
/** Stop polling a run that has not finished after this long (the server bounds runs anyway). */
const POLL_TIMEOUT_MS = 10 * 60 * 1000;

/**
 * The application root.
 *
 * <p>State strategy: the canvas graph lives in React state here, not in a global store.
 * A workflow graph is a single cohesive document that the canvas mutates as a unit, and
 * React's state model maps onto that directly — no store, no subscriptions, no
 * synchronisation bugs.
 *
 * <p>Two sources of truth for validity are deliberately avoided: the client runs the same
 * rules as the server for instant feedback, and the server re-validates before every run.
 * The client-side result is advisory; the server has the final word.
 */
export default function App() {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [workflowId, setWorkflowId] = useState<string | null>(null);
  const [name, setName] = useState('Untitled workflow');
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);

  const [workflows, setWorkflows] = useState<ApiWorkflowSummary[]>([]);
  const [examples, setExamples] = useState<ApiExample[]>([]);
  const [libraryLoading, setLibraryLoading] = useState(true);
  const [libraryError, setLibraryError] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [validating, setValidating] = useState(false);
  /** Bumped by every edit that can change validity; server results are tied to a version. */
  const [graphVersion, setGraphVersion] = useState(0);
  const [serverIssues, setServerIssues] = useState<{ version: number; issues: GraphIssue[] } | null>(null);
  const [dockTab, setDockTab] = useState<DockTab>('run');
  const [fitKey, setFitKey] = useState(0);
  const [focus, setFocus] = useState<{ nodeId: string; seq: number } | null>(null);
  const viewCenterRef = useRef<(() => XYPosition) | null>(null);

  const [execution, setExecution] = useState<ApiExecution | null>(null);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [toast, setToast] = useState<{ kind: 'success' | 'error'; message: string } | null>(null);
  const [history, setHistory] = useState<ApiExecutionSummary[]>([]);

  const pollTimer = useRef<number | null>(null);
  /**
   * Identifies the run currently being followed. Every poll checks it before touching
   * state, so a response for an older run — one the user has since replaced by starting
   * another run or loading another workflow — is dropped instead of overwriting the view.
   */
  const pollGeneration = useRef(0);
  const searchTimer = useRef<number | null>(null);
  /** Read by the keyboard shortcut, which must not re-subscribe on every edit. */
  const canRunRef = useRef(false);

  const stopPolling = useCallback(() => {
    pollGeneration.current += 1;
    if (pollTimer.current !== null) {
      window.clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
    setRunning(false);
  }, []);

  /** Records an edit to the graph: unsaved, and any server verdict is now stale. */
  const markChanged = useCallback(() => {
    setDirty(true);
    setGraphVersion((version) => version + 1);
  }, []);

  const loadHistory = useCallback(async (id: string | null) => {
    if (!id) {
      setHistory([]);
      return;
    }
    try {
      const runs = await api.listExecutions(id);
      setHistory(Array.isArray(runs) ? runs : []);
    } catch {
      // History is a convenience; the rest of the app works without it.
      setHistory([]);
    }
  }, []);

  // --- library ---------------------------------------------------------------------

  const loadLibrary = useCallback(async (term = '') => {
    setLibraryLoading(true);
    setLibraryError(null);
    try {
      const page = await api.listWorkflows(term);
      setWorkflows(page.items);
    } catch (error) {
      setLibraryError(
        error instanceof ApiError ? error.message : 'Could not load the workflow library.',
      );
    } finally {
      setLibraryLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadLibrary('');
    api
      .listExamples()
      .then(setExamples)
      .catch(() => setExamples([]));
  }, [loadLibrary]);

  // Debounce the search so typing does not fire a request per keystroke.
  useEffect(() => {
    if (searchTimer.current !== null) {
      window.clearTimeout(searchTimer.current);
    }
    searchTimer.current = window.setTimeout(() => {
      void loadLibrary(search);
    }, 250);
    return () => {
      if (searchTimer.current !== null) {
        window.clearTimeout(searchTimer.current);
      }
    };
  }, [search, loadLibrary]);

  // --- canvas graph ----------------------------------------------------------------

  const onNodesChange = useCallback((changes: NodeChange<Node>[]) => {
    setNodes((current) => applyNodeChanges(changes, current));
    if (changes.some((change) => change.type !== 'select' && change.type !== 'dimensions')) {
      markChanged();
    }
  }, [markChanged]);

  const onEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    setEdges((current) => applyEdgeChanges(changes, current));
    if (changes.some((change) => change.type !== 'select')) {
      markChanged();
    }
  }, [markChanged]);

  const onConnect = useCallback((connection: Connection) => {
    setEdges((current) => addEdge({ ...connection, id: newId() }, current));
    markChanged();
  }, [markChanged]);

  const onAddNode = useCallback((type: NodeType, at?: XYPosition) => {
    const definition = NODE_TYPE_DEFINITIONS[type];
    const id = newId();
    // A clicked part lands in the middle of the view, nudged so repeated clicks do not stack.
    const centre = viewCenterRef.current?.() ?? { x: 120, y: 120 };
    const position = at ?? { x: centre.x + (Math.random() - 0.5) * 60, y: centre.y + (Math.random() - 0.5) * 60 };
    setNodes((current) => [
      ...current,
      {
        id,
        type,
        position,
        data: {
          // A short, readable, unique key — what templates use to reference this node.
          key: suggestKey(type, current),
          label: definition?.title ?? type,
          config: { ...(definition?.defaultConfig ?? {}) },
          nodeType: type,
        },
      },
    ]);
    setSelectedNodeId(id);
    markChanged();
  }, [markChanged]);

  const onRenameKey = useCallback((nodeId: string, key: string) => {
    setNodes((current) =>
      current.map((node) => (node.id === nodeId ? { ...node, data: { ...node.data, key } } : node)),
    );
    markChanged();
  }, [markChanged]);

  const onDeleteNode = useCallback((nodeId: string) => {
    setNodes((current) => current.filter((node) => node.id !== nodeId));
    setEdges((current) => current.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
    setSelectedNodeId((current) => (current === nodeId ? null : current));
    markChanged();
  }, [markChanged]);

  const onUpdateNodeConfig = useCallback((nodeId: string, patch: Record<string, unknown>) => {
    setNodes((current) =>
      current.map((node) => {
        if (node.id !== nodeId) {
          return node;
        }
        const data = node.data as { config?: Record<string, unknown> };
        return {
          ...node,
          data: { ...node.data, config: { ...(data.config ?? {}), ...patch } },
        };
      }),
    );
    markChanged();
  }, [markChanged]);

  const onRenameNode = useCallback((nodeId: string, label: string) => {
    setNodes((current) =>
      current.map((node) => (node.id === nodeId ? { ...node, data: { ...node.data, label } } : node)),
    );
    markChanged();
  }, [markChanged]);

  const onClearGraph = useCallback(() => {
    stopPolling();
    setNodes([]);
    setEdges([]);
    setSelectedNodeId(null);
    setExecution(null);
    markChanged();
  }, [stopPolling, markChanged]);

  /** Puts a graph on the canvas, replacing whatever was there and any run being followed. */
  const showGraph = useCallback(
    (graph: { id: string | null; name: string; nodes: Node[]; edges: Edge[]; dirty: boolean }) => {
      stopPolling();
      setWorkflowId(graph.id);
      setName(graph.name);
      setNodes(graph.nodes);
      setEdges(graph.edges);
      setSelectedNodeId(null);
      setServerIssues(null);
      setGraphVersion((version) => version + 1);
      setExecution(null);
      setRunError(null);
      setDirty(graph.dirty);
      setDockTab('run');
      setFitKey((key) => key + 1);
      void loadHistory(graph.id);
    },
    [stopPolling, loadHistory],
  );

  const onNew = useCallback(() => {
    showGraph({ id: null, name: 'Untitled workflow', nodes: [], edges: [], dirty: false });
  }, [showGraph]);

  // --- persistence -----------------------------------------------------------------

  /**
   * Saves the canvas (create or update) and returns the workflow id. On a rejected graph
   * the server's per-node issues are shown in the validation panel, then the error rethrown.
   */
  const persist = useCallback(async (): Promise<string> => {
    const payload = { ...graphToApi(nodes, edges), name: name.trim() || 'Untitled workflow' };
    try {
      let id = workflowId;
      if (id) {
        await api.updateWorkflow(id, payload);
      } else {
        id = (await api.createWorkflow(payload)).id;
        setWorkflowId(id);
      }
      setDirty(false);
      void loadLibrary(search);
      return id;
    } catch (error) {
      if (error instanceof ApiError && error.issues.length > 0) {
        setServerIssues({ version: graphVersion, issues: toGraphIssues(error.issues) });
        setDockTab('problems');
      }
      throw error;
    }
  }, [nodes, edges, name, workflowId, loadLibrary, search, graphVersion]);

  const onSave = useCallback(async () => {
    setSaving(true);
    setRunError(null);
    try {
      await persist();
      setToast({ kind: 'success', message: 'Workflow saved.' });
    } catch (error) {
      setToast({
        kind: 'error',
        message: error instanceof ApiError ? error.message : 'Could not save the workflow.',
      });
    } finally {
      setSaving(false);
    }
  }, [persist]);

  const onSelectWorkflow = useCallback(
    async (id: string) => {
      try {
        const workflow = await api.getWorkflow(id);
        const graph = fromApiWorkflow(workflow);
        showGraph({ id: workflow.id, name: workflow.name, ...graph, dirty: false });
      } catch (error) {
        setToast({
          kind: 'error',
          message: error instanceof ApiError ? error.message : 'Could not load the workflow.',
        });
      }
    },
    [showGraph],
  );

  const onDeleteWorkflow = useCallback(
    async (id: string) => {
      if (!window.confirm('Delete this workflow? This cannot be undone.')) {
        return;
      }
      try {
        await api.deleteWorkflow(id);
        if (id === workflowId) {
          onNew();
        }
        await loadLibrary(search);
        setToast({ kind: 'success', message: 'Workflow deleted.' });
      } catch (error) {
        setToast({
          kind: 'error',
          message: error instanceof ApiError ? error.message : 'Could not delete the workflow.',
        });
      }
    },
    [workflowId, onNew, loadLibrary, search],
  );

  const onLoadExample = useCallback(
    (example: ApiExample) => {
      showGraph({ id: null, name: example.name, ...fromApiExample(example), dirty: true });
    },
    [showGraph],
  );

  // --- validation ------------------------------------------------------------------

  const onValidate = useCallback(async () => {
    setValidating(true);
    setDockTab('problems');
    try {
      const payload = graphToApi(nodes, edges);
      const result = await api.validateGraph(payload.nodes, payload.edges);
      // The server is the authority; its verdict stands until the graph changes again.
      setServerIssues({ version: graphVersion, issues: toGraphIssues(result.issues) });
    } catch {
      // The live in-browser result stays on screen; a network failure here is not fatal.
    } finally {
      setValidating(false);
    }
  }, [nodes, edges, graphVersion]);

  // --- execution -------------------------------------------------------------------

  /** Follows a run until it finishes, dropping responses if a newer run takes over. */
  const followExecution = useCallback(
    (executionId: string, workflowOfRun: string) => {
      const generation = ++pollGeneration.current;
      const deadline = Date.now() + POLL_TIMEOUT_MS;
      setRunning(true);

      const poll = async () => {
        try {
          const result = await api.getExecution(executionId);
          if (generation !== pollGeneration.current) {
            return; // superseded while the request was in flight
          }
          setExecution(result);
          const inFlight = result.status === 'RUNNING' || result.status === 'PENDING';
          if (inFlight && Date.now() < deadline) {
            pollTimer.current = window.setTimeout(() => void poll(), POLL_INTERVAL_MS);
            return;
          }
          if (inFlight) {
            setRunError('Stopped following the run: it is taking longer than expected.');
          }
          setRunning(false);
          void loadHistory(workflowOfRun);
        } catch (error) {
          if (generation !== pollGeneration.current) {
            return;
          }
          setRunning(false);
          setRunError(error instanceof ApiError ? error.message : 'Could not read the run status.');
        }
      };
      void poll();
    },
    [loadHistory],
  );

  const onRun = useCallback(async () => {
    stopPolling();
    setRunError(null);
    setExecution(null);
    setSelectedNodeId(null);
    setDockTab('run');
    setRunning(true);
    try {
      // The backend runs the stored graph, so unsaved edits are saved first.
      const id = !workflowId || dirty ? await persist() : workflowId;
      const accepted = await api.executeWorkflow(id);
      followExecution(accepted.executionId, id);
    } catch (error) {
      setRunning(false);
      setRunError(error instanceof ApiError ? error.message : 'Could not start the workflow run.');
    }
  }, [stopPolling, workflowId, dirty, persist, followExecution]);

  /** Shows a past run from the history; a run still in flight is followed live. */
  const onOpenRun = useCallback(
    async (executionId: string) => {
      stopPolling();
      setRunError(null);
      try {
        const result = await api.getExecution(executionId);
        setExecution(result);
        if ((result.status === 'RUNNING' || result.status === 'PENDING') && workflowId) {
          followExecution(executionId, workflowId);
        }
      } catch (error) {
        setRunError(error instanceof ApiError ? error.message : 'Could not load that run.');
      }
    },
    [stopPolling, followExecution, workflowId],
  );

  // Stop polling when the component unmounts.
  useEffect(
    () => () => {
      pollGeneration.current += 1;
      if (pollTimer.current !== null) {
        window.clearTimeout(pollTimer.current);
      }
    },
    [],
  );

  // Ctrl/Cmd+S saves, as in every editor.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey)) {
        return;
      }
      if (event.key.toLowerCase() === 's') {
        event.preventDefault();
        if (!saving && nodes.length > 0) {
          void onSave();
        }
      } else if (event.key === 'Enter') {
        event.preventDefault();
        if (!running && canRunRef.current) {
          void onRun();
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onSave, onRun, saving, running, nodes.length]);

  // Auto-dismiss the toast.
  useEffect(() => {
    if (!toast) {
      return;
    }
    const timer = window.setTimeout(() => setToast(null), 4000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  // --- derived state ---------------------------------------------------------------

  const selectedNode = useMemo(
    () => nodes.find((node) => node.id === selectedNodeId) ?? null,
    [nodes, selectedNodeId],
  );

  const selectedVariables = useMemo(
    () => (selectedNode ? availableVariables(selectedNode.id, nodes, edges) : []),
    [selectedNode, nodes, edges],
  );

  const selectedKeyError = useMemo(() => {
    if (!selectedNode) {
      return null;
    }
    const key = keyOf(selectedNode);
    if (!isValidKey(key)) {
      return 'Use 1–64 letters, digits, "_" or "-".';
    }
    const clash = nodes.find(
      (node) => node.id !== selectedNode.id && keyOf(node).toLowerCase() === key.toLowerCase(),
    );
    return clash ? `Another node already uses "${keyOf(clash)}".` : null;
  }, [selectedNode, nodes]);

  // The same rules as the server, re-evaluated on every edit.
  const liveIssues = useMemo(() => (nodes.length > 0 ? validateGraph(nodes, edges) : []), [nodes, edges]);
  const serverVerdictIsCurrent = serverIssues !== null && serverIssues.version === graphVersion;
  const issues = serverVerdictIsCurrent ? serverIssues.issues : liveIssues;
  const errorCount = issues.filter((issue) => issue.severity === 'ERROR').length;
  const blockingIssues = errorCount > 0;
  const problemNodeIds = useMemo(
    () => new Set(issues.filter((i) => i.severity === 'ERROR' && i.nodeId).map((i) => i.nodeId!)),
    [issues],
  );

  // A run is allowed when the client-side rules pass. The server validates again.
  const canRun = nodes.length > 0 && !hasBlockingErrors(liveIssues);
  canRunRef.current = canRun;

  const onFocusNode = useCallback((nodeId: string) => {
    setSelectedNodeId(nodeId);
    setFocus((current) => ({ nodeId, seq: (current?.seq ?? 0) + 1 }));
  }, []);

  return (
    <div className="app">
      <Toolbar
        name={name}
        onRename={(value) => {
          setName(value);
          setDirty(true);
        }}
        dirty={dirty}
        saving={saving}
        running={running}
        canRun={canRun}
        onSave={onSave}
        onRun={onRun}
        onValidate={onValidate}
        onClear={onClearGraph}
        onNew={onNew}
        hasWorkflow={nodes.length > 0}
      />

      <main className="layout">
        <aside className="layout__left">
          <WorkflowLibrary
            workflows={workflows}
            examples={examples}
            activeId={workflowId}
            loading={libraryLoading}
            error={libraryError}
            search={search}
            onSearch={setSearch}
            onSelect={(id) => void onSelectWorkflow(id)}
            onDelete={(id) => void onDeleteWorkflow(id)}
            onLoadExample={onLoadExample}
          />
          <NodePalette onAddNode={(type) => onAddNode(type)} />
        </aside>

        <section className="layout__center">
          <CanvasProvider
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onSelectNode={setSelectedNodeId}
            selectedNodeId={selectedNodeId}
            execution={execution}
            running={running}
            onDeleteNode={onDeleteNode}
            problemNodeIds={problemNodeIds}
            onDropNode={onAddNode}
            viewCenterRef={viewCenterRef}
            fitKey={fitKey}
            focus={focus}
            examples={examples}
            onLoadExample={onLoadExample}
          />
          <Dock
            tab={dockTab}
            onTab={setDockTab}
            problemCount={issues.length}
            errorCount={errorCount}
            runStatus={execution?.status ?? null}
            run={
              <RunPanel
                execution={execution}
                running={running}
                error={runError}
                history={history}
                selectedNodeId={selectedNodeId}
                onSelectNode={onFocusNode}
                onOpenRun={(id) => void onOpenRun(id)}
                onClear={() => {
                  setExecution(null);
                  setRunError(null);
                }}
              />
            }
            problems={
              <ValidationPanel
                issues={issues}
                source={serverVerdictIsCurrent ? 'server' : 'live'}
                validating={validating}
                hasNodes={nodes.length > 0}
                onFocusNode={onFocusNode}
              />
            }
          />
        </section>

        <div className="layout__right">
          <Inspector
            node={selectedNode}
            variables={selectedVariables}
            keyError={selectedKeyError}
            onUpdateConfig={(patch) => selectedNodeId && onUpdateNodeConfig(selectedNodeId, patch)}
            onRename={(label) => selectedNodeId && onRenameNode(selectedNodeId, label)}
            onRenameKey={(key) => selectedNodeId && onRenameKey(selectedNodeId, key)}
            onDelete={() => selectedNodeId && onDeleteNode(selectedNodeId)}
          />
        </div>
      </main>

      {toast && (
        <div className={`toast toast--${toast.kind}`} role="status" data-testid="toast">
          {toast.message}
        </div>
      )}

      {blockingIssues && (
        <div className="sr-only" role="status">
          The workflow has validation errors and cannot be run.
        </div>
      )}
    </div>
  );
}
