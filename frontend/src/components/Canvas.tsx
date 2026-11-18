import { useCallback, useEffect, useMemo, type DragEvent, type MutableRefObject } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
  type OnConnect,
  type XYPosition,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import type { ApiExample, ApiExecution, NodeExecutionStatus, NodeType } from '../types';
import { nodeTypes } from '../nodes/registry';
import { nodeTypeAccent } from '../nodes/definitions';
import { detectCycle } from '../nodes/graph';
import { NODE_DRAG_TYPE } from './NodePalette';

export interface CanvasProps {
  nodes: Node[];
  edges: Edge[];
  onNodesChange: (changes: NodeChange<Node>[]) => void;
  onEdgesChange: (changes: EdgeChange<Edge>[]) => void;
  onConnect: (connection: Connection) => void;
  onSelectNode: (nodeId: string | null) => void;
  selectedNodeId: string | null;
  execution: ApiExecution | null;
  running: boolean;
  onDeleteNode: (nodeId: string) => void;
  /** Nodes with a blocking problem, outlined on the canvas. */
  problemNodeIds: Set<string>;
  /** Places a dragged part at a canvas position. */
  onDropNode: (type: NodeType, position: XYPosition) => void;
  /** Filled by the canvas: the flow position at the centre of the current view. */
  viewCenterRef: MutableRefObject<(() => XYPosition) | null>;
  /** Changes whenever a different graph is loaded, to re-fit the view. */
  fitKey: number;
  /** Changes whenever a node should be brought into view. */
  focus: { nodeId: string; seq: number } | null;
  examples: ApiExample[];
  onLoadExample: (example: ApiExample) => void;
}

const NODE_WIDTH = 230;
const NODE_HEIGHT = 110;

/** The interactive canvas. */
export function Canvas({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onSelectNode,
  selectedNodeId,
  execution,
  running,
  onDeleteNode,
  problemNodeIds,
  onDropNode,
  viewCenterRef,
  fitKey,
  focus,
  examples,
  onLoadExample,
}: CanvasProps) {
  const flow = useReactFlow();

  const cycleNodes = useMemo(() => new Set(detectCycle(nodes, edges)), [nodes, edges]);

  // Expose "where is the middle of the view" so a clicked part lands where the user looks.
  useEffect(() => {
    viewCenterRef.current = () => {
      const bounds = document.querySelector('.canvas')?.getBoundingClientRect();
      if (!bounds) {
        return { x: 120, y: 120 };
      }
      const centre = flow.screenToFlowPosition({
        x: bounds.left + bounds.width / 2,
        y: bounds.top + bounds.height / 2,
      });
      return { x: centre.x - NODE_WIDTH / 2, y: centre.y - NODE_HEIGHT / 2 };
    };
    return () => {
      viewCenterRef.current = null;
    };
  }, [flow, viewCenterRef]);

  // A newly loaded graph is framed once its nodes have been measured.
  useEffect(() => {
    if (fitKey === 0) {
      return;
    }
    const frame = window.requestAnimationFrame(() => {
      void flow.fitView({ padding: 0.18, duration: 350, maxZoom: 1.1 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [fitKey, flow]);

  useEffect(() => {
    if (!focus) {
      return;
    }
    const node = flow.getNode(focus.nodeId);
    if (node) {
      void flow.setCenter(node.position.x + NODE_WIDTH / 2, node.position.y + NODE_HEIGHT / 2, {
        zoom: Math.max(flow.getZoom(), 0.9),
        duration: 350,
      });
    }
  }, [focus, flow]);

  // Project the latest execution state onto the nodes so the canvas animates.
  const decoratedNodes = useMemo(() => {
    const statuses = new Map(
      (execution?.nodeExecutions ?? []).map((run) => [run.nodeId, run] as const),
    );
    return nodes.map((node) => {
      const state = statuses.get(node.id);
      return {
        ...node,
        selected: node.id === selectedNodeId,
        data: {
          ...node.data,
          executionStatus: state?.status,
          output: state?.output ?? undefined,
          error: state?.error ?? undefined,
          durationMs: state?.durationMs ?? undefined,
          inCycle: cycleNodes.has(node.id),
          hasProblem: problemNodeIds.has(node.id),
        },
      };
    });
  }, [nodes, execution, cycleNodes, problemNodeIds, selectedNodeId]);

  // Edges are "cables" in the colour of the node that drives them; a cable glows while it
  // feeds the node that is running.
  const decoratedEdges = useMemo(() => {
    const statuses = new Map<string, NodeExecutionStatus>();
    for (const run of execution?.nodeExecutions ?? []) {
      statuses.set(run.nodeId, run.status);
    }
    const typeOf = new Map(nodes.map((node) => [node.id, node.type as NodeType]));
    return edges.map((edge) => {
      const source = statuses.get(edge.source);
      const target = statuses.get(edge.target);
      const live = running && source === 'SUCCESS' && target === 'RUNNING';
      const dead = source === 'FAILED' || source === 'SKIPPED' || target === 'SKIPPED';
      const sourceType = typeOf.get(edge.source);
      return {
        ...edge,
        animated: live,
        className: live ? 'cable is-live' : dead ? 'cable is-dead' : 'cable',
        style: { stroke: sourceType ? nodeTypeAccent(sourceType) : undefined },
      };
    });
  }, [edges, nodes, execution, running]);

  const handleConnect = useCallback<OnConnect>((connection) => onConnect(connection), [onConnect]);

  const onNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => onSelectNode(node.id),
    [onSelectNode],
  );

  const onPaneClick = useCallback(() => onSelectNode(null), [onSelectNode]);

  const onNodeDoubleClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const label = String((node.data as { label?: string }).label ?? node.id);
      if (window.confirm(`Delete "${label}"?`)) {
        onDeleteNode(node.id);
      }
    },
    [onDeleteNode],
  );

  const onDragOver = useCallback((event: DragEvent) => {
    if (event.dataTransfer.types.includes(NODE_DRAG_TYPE)) {
      event.preventDefault();
      event.dataTransfer.dropEffect = 'copy';
    }
  }, []);

  const onDrop = useCallback(
    (event: DragEvent) => {
      const type = event.dataTransfer.getData(NODE_DRAG_TYPE) as NodeType;
      if (!type) {
        return;
      }
      event.preventDefault();
      const point = flow.screenToFlowPosition({ x: event.clientX, y: event.clientY });
      onDropNode(type, { x: point.x - NODE_WIDTH / 2, y: point.y - 24 });
    },
    [flow, onDropNode],
  );

  const isEmpty = nodes.length === 0;

  return (
    <div className="canvas" onDragOver={onDragOver} onDrop={onDrop}>
      <ReactFlow
        nodes={decoratedNodes}
        edges={decoratedEdges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={handleConnect}
        onNodeClick={onNodeClick}
        onNodeDoubleClick={onNodeDoubleClick}
        onPaneClick={onPaneClick}
        nodeTypes={nodeTypes}
        fitView
        fitViewOptions={{ padding: 0.18, maxZoom: 1.1 }}
        defaultEdgeOptions={{ type: 'default' }}
        minZoom={0.25}
        maxZoom={2}
        deleteKeyCode={['Backspace', 'Delete']}
        proOptions={{ hideAttribution: true }}
      >
        <Background variant={BackgroundVariant.Dots} gap={22} size={1.4} color="var(--grid)" />
        <Controls showInteractive={false} position="bottom-left" />
      </ReactFlow>

      {isEmpty && (
        <div className="canvas__empty" data-testid="canvas-empty">
          <h2 className="canvas__empty-title">Wire up an AI workflow</h2>
          <p className="canvas__empty-body">
            Drag parts in from the left and connect their sockets, or open a ready-made flow and press
            Run to watch it execute node by node.
          </p>
          {examples.length > 0 && (
            <ul className="canvas__examples">
              {examples.map((example) => (
                <li key={example.name}>
                  <button type="button" className="canvas__example" onClick={() => onLoadExample(example)}>
                    <span className="canvas__example-name">{example.name}</span>
                    <span className="canvas__example-size">
                      {example.nodes.length} nodes
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

/** The canvas wrapped in its provider, so it can use React Flow's viewport hooks. */
export function CanvasProvider(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}
