import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
  type OnConnect,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import type { ApiExecution, NodeExecutionStatus, NodeType } from '../types';
import { nodeTypes } from '../nodes/registry';
import { NODE_TYPE_DEFINITIONS } from '../nodes/definitions';
import { detectCycle } from '../nodes/graph';

export interface CanvasProps {
  nodes: Node[];
  edges: Edge[];
  onNodesChange: (changes: NodeChange<Node>[]) => void;
  onEdgesChange: (changes: EdgeChange<Edge>[]) => void;
  onConnect: (connection: Connection) => void;
  onSelectNode: (nodeId: string | null) => void;
  execution: ApiExecution | null;
  running: boolean;
  onDeleteNode: (nodeId: string) => void;
}

/** The interactive canvas. */
export function Canvas({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onSelectNode,
  execution,
  running,
  onDeleteNode,
}: CanvasProps) {
  const [cycleNodes, setCycleNodes] = useState<Set<string>>(new Set());

  // Recompute the highlighted cycle whenever the graph changes.
  useEffect(() => {
    setCycleNodes(new Set(detectCycle(nodes, edges)));
  }, [nodes, edges]);

  // Project the latest execution state onto the nodes so the canvas animates.
  const decoratedNodes = useMemo(() => {
    const statuses = new Map<
      string,
      { status: NodeExecutionStatus; output?: string; error?: string; durationMs?: number }
    >();
    for (const nodeExecution of execution?.nodeExecutions ?? []) {
      statuses.set(nodeExecution.nodeId, {
        status: nodeExecution.status,
        output: nodeExecution.output ?? undefined,
        error: nodeExecution.error ?? undefined,
        durationMs: nodeExecution.durationMs ?? undefined,
      });
    }
    return nodes.map((node) => {
      const state = statuses.get(node.id);
      return {
        ...node,
        data: {
          ...node.data,
          executionStatus: state?.status,
          output: state?.output,
          error: state?.error,
          durationMs: state?.durationMs,
          inCycle: cycleNodes.has(node.id),
        },
      };
    });
  }, [nodes, execution, cycleNodes]);

  const decoratedEdges = useMemo(() => {
    const statuses = new Map<string, NodeExecutionStatus>();
    for (const nodeExecution of execution?.nodeExecutions ?? []) {
      statuses.set(nodeExecution.nodeId, nodeExecution.status);
    }
    return edges.map((edge) => {
      const sourceStatus = statuses.get(edge.source);
      const targetStatus = statuses.get(edge.target);
      const failed = sourceStatus === 'FAILED' || targetStatus === 'FAILED';
      const active = sourceStatus === 'SUCCESS' && targetStatus === 'RUNNING';
      return {
        ...edge,
        animated: running && active,
        className: failed ? 'edge-failed' : active ? 'edge-active' : undefined,
      };
    });
  }, [edges, execution, running]);

  const handleConnect = useCallback<OnConnect>(
    (connection) => {
      onConnect(connection);
    },
    [onConnect],
  );

  const onNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      onSelectNode(node.id);
    },
    [onSelectNode],
  );

  const onPaneClick = useCallback(() => {
    onSelectNode(null);
  }, [onSelectNode]);

  const onNodeDoubleClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      const label = String((node.data as { label?: string }).label ?? node.id);
      if (window.confirm(`Delete "${label}"?`)) {
        onDeleteNode(node.id);
      }
    },
    [onDeleteNode],
  );

  const isEmpty = nodes.length === 0;

  return (
    <div className="canvas">
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
        fitViewOptions={{ padding: 0.2 }}
        defaultEdgeOptions={{ type: 'smoothstep', animated: false }}
        minZoom={0.2}
        maxZoom={2}
        deleteKeyCode={['Backspace', 'Delete']}
      >
        <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#33415580" />
        <Controls showInteractive={false} position="bottom-left" />
        <MiniMap
          pannable
          zoomable
          position="bottom-right"
          nodeColor={(node) => {
            const type = node.type as NodeType;
            return NODE_TYPE_DEFINITIONS[type]?.accent ?? '#64748b';
          }}
          maskColor="rgba(2, 6, 23, 0.7)"
        />
      </ReactFlow>

      {isEmpty && (
        <div className="canvas__empty" data-testid="canvas-empty">
          <div className="canvas__empty-icon" aria-hidden="true">
            ◈
          </div>
          <p className="canvas__empty-title">Your canvas is empty</p>
          <p className="canvas__empty-body">
            Add a node from the palette on the left, or load one of the example workflows to
            see the builder in action.
          </p>
        </div>
      )}
    </div>
  );
}

/** The canvas wrapped in its provider, so the surrounding app can use React Flow hooks. */
export function CanvasProvider(props: CanvasProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}
