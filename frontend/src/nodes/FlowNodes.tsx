import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { NodeExecutionStatus, NodeType } from '../types';
import { nodeTypeAccent, nodeTypeTitle } from './definitions';

/**
 * The shared shell for every node type.
 *
 * <p>All four node types render through this component, so sockets, selection and the
 * run-state light behave identically everywhere. Only the summary line differs.
 */
export interface FlowNodeData {
  /** The name templates use to reference this node's output, e.g. `{{advocate}}`. */
  key?: string;
  label: string;
  config: Record<string, unknown>;
  nodeType: NodeType;
  /** The live execution status, present only once a run has touched the node. */
  executionStatus?: NodeExecutionStatus;
  output?: string;
  error?: string;
  durationMs?: number;
  /** True when the node is part of a detected cycle. */
  inCycle?: boolean;
  /** True when a blocking validation problem belongs to this node. */
  hasProblem?: boolean;
}

const STATUS_TEXT: Record<NodeExecutionStatus, string> = {
  PENDING: 'Waiting',
  RUNNING: 'Running',
  SUCCESS: 'Done',
  FAILED: 'Failed',
  SKIPPED: 'Skipped',
};

function summarise(config: Record<string, unknown>, type: NodeType): string {
  if (type === 'TEXT_INPUT') {
    return String(config.text ?? '');
  }
  if (type === 'PROMPT_TEMPLATE') {
    return String(config.template ?? '');
  }
  if (type === 'AI_MODEL') {
    return `${String(config.model ?? 'default model')}, temperature ${Number(config.temperature ?? 0.7).toFixed(1)}`;
  }
  return String(config.format ?? 'text') === 'json' ? 'Collects inputs as JSON' : 'Collects inputs as text';
}

function FlowNodeShell({ id, data, selected }: NodeProps) {
  const nodeData = data as unknown as FlowNodeData;
  const type = nodeData.nodeType;
  const status = nodeData.executionStatus;
  const classes = [
    'flow-node',
    status ? `is-${status.toLowerCase()}` : '',
    nodeData.inCycle ? 'is-cycle' : '',
    nodeData.hasProblem ? 'has-problem' : '',
    selected ? 'is-selected' : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      className={classes}
      style={{ ['--node-accent' as string]: nodeTypeAccent(type) }}
      data-testid={`node-${id}`}
      data-status={status ?? 'idle'}
    >
      {type !== 'TEXT_INPUT' && (
        <Handle type="target" position={Position.Left} id="in" className="socket socket--in" />
      )}

      <header className="flow-node__header">
        <span className="flow-node__type">{nodeTypeTitle(type)}</span>
        <code className="flow-node__key" title="Reference this node in a template by this key">
          {nodeData.key ?? id}
        </code>
      </header>

      <div className="flow-node__label">{nodeData.label}</div>
      <div className="flow-node__summary">{summarise(nodeData.config, type)}</div>

      {status && (
        <footer className="flow-node__status">
          <span className={`led led--${status.toLowerCase()}`} aria-hidden="true" />
          <span>{STATUS_TEXT[status]}</span>
          {(status === 'SUCCESS' || status === 'FAILED') && nodeData.durationMs !== undefined && (
            <span className="flow-node__duration">{nodeData.durationMs} ms</span>
          )}
        </footer>
      )}
      {status === 'FAILED' && nodeData.error && <div className="flow-node__error">{nodeData.error}</div>}

      {type !== 'OUTPUT' && (
        <Handle type="source" position={Position.Right} id="out" className="socket socket--out" />
      )}
    </div>
  );
}

/** The Text Input node. */
export const TextInputNode = memo((props: NodeProps) => <FlowNodeShell {...props} />);
TextInputNode.displayName = 'TextInputNode';

/** The Prompt Template node. */
export const PromptTemplateNode = memo((props: NodeProps) => <FlowNodeShell {...props} />);
PromptTemplateNode.displayName = 'PromptTemplateNode';

/** The AI Model node. */
export const AiModelNode = memo((props: NodeProps) => <FlowNodeShell {...props} />);
AiModelNode.displayName = 'AiModelNode';

/** The Output node. */
export const OutputNode = memo((props: NodeProps) => <FlowNodeShell {...props} />);
OutputNode.displayName = 'OutputNode';
