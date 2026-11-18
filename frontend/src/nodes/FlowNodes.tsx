import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import type { NodeExecutionStatus, NodeType } from '../types';
import { nodeTypeAccent, nodeTypeTitle } from './definitions';

/**
 * The shared visual shell for every node type.
 *
 * <p>All four node types render through this component so that handles, selection styling
 * and the execution-state badge behave identically everywhere. Only the body differs.
 */
export interface FlowNodeData {
  /** The name templates use to reference this node's output, e.g. `{{advocate}}`. */
  key?: string;
  label: string;
  config: Record<string, unknown>;
  nodeType: NodeType;
  /** The live execution status, present only while a run is in progress. */
  executionStatus?: NodeExecutionStatus;
  /** The output of the last run, shown as a preview. */
  output?: string;
  /** The error of the last run. */
  error?: string;
  /** The measured duration of the last run, in milliseconds. */
  durationMs?: number;
  /** True when the node is part of a detected cycle. */
  inCycle?: boolean;
}

function statusGlyph(status?: NodeExecutionStatus): string {
  switch (status) {
    case 'RUNNING':
      return '●';
    case 'SUCCESS':
      return '✓';
    case 'FAILED':
      return '✕';
    case 'SKIPPED':
      return '–';
    case 'PENDING':
      return '○';
    default:
      return '';
  }
}

function summarise(config: Record<string, unknown>, type: NodeType): string {
  if (type === 'TEXT_INPUT') {
    return String(config.text ?? '').slice(0, 60);
  }
  if (type === 'PROMPT_TEMPLATE') {
    return String(config.template ?? '').replace(/\s+/g, ' ').slice(0, 60);
  }
  if (type === 'AI_MODEL') {
    return `${String(config.model ?? 'default')} · T=${Number(config.temperature ?? 0.7).toFixed(2)}`;
  }
  return `format: ${String(config.format ?? 'text')}`;
}

function FlowNodeShell({ id, data, selected }: NodeProps) {
  const nodeData = data as unknown as FlowNodeData;
  const type = nodeData.nodeType;
  const accent = nodeTypeAccent(type);
  const status = nodeData.executionStatus;
  const stateClass = status ? ` is-${status.toLowerCase()}` : '';
  const cycleClass = nodeData.inCycle ? ' is-cycle' : '';

  return (
    <div
      className={`flow-node${stateClass}${cycleClass}${selected ? ' is-selected' : ''}`}
      style={{ ['--node-accent' as string]: accent }}
      data-testid={`node-${id}`}
      data-status={status ?? 'idle'}
    >
      {type !== 'TEXT_INPUT' && (
        <Handle type="target" position={Position.Left} id="in" className="flow-handle" />
      )}

      <header className="flow-node__header">
        <span className="flow-node__dot" />
        <span className="flow-node__title">{nodeTypeTitle(type)}</span>
        {status && (
          <span className={`flow-node__status flow-node__status--${status.toLowerCase()}`}>
            {statusGlyph(status)}
          </span>
        )}
      </header>

      <div className="flow-node__label-row">
        <span className="flow-node__label">{nodeData.label}</span>
        <code className="flow-node__key" title="Reference this node in a template as {{key}}">
          {nodeData.key ?? id}
        </code>
      </div>
      <div className="flow-node__preview">{summarise(nodeData.config, type)}</div>

      {nodeData.error && <div className="flow-node__error">{nodeData.error}</div>}
      {!nodeData.error && nodeData.output && (
        <div className="flow-node__output">{nodeData.output.slice(0, 80)}</div>
      )}
      {nodeData.durationMs !== undefined && nodeData.durationMs > 0 && (
        <div className="flow-node__duration">{nodeData.durationMs} ms</div>
      )}

      {type !== 'OUTPUT' && (
        <Handle type="source" position={Position.Right} id="out" className="flow-handle" />
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
