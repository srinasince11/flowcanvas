import { useState } from 'react';
import type { ApiExecution, ApiExecutionSummary, ApiNodeExecution } from '../types';
import { nodeTypeTitle } from '../nodes/definitions';
import { RunTrace } from './RunTrace';
import { formatDuration } from '../format';

interface RunPanelProps {
  execution: ApiExecution | null;
  running: boolean;
  error: string | null;
  /** Recent runs of the current workflow, newest first. */
  history: ApiExecutionSummary[];
  selectedNodeId: string | null;
  onSelectNode: (nodeId: string) => void;
  onOpenRun: (executionId: string) => void;
  onClear: () => void;
}

const STATUS_TEXT: Record<string, string> = {
  PENDING: 'Queued',
  RUNNING: 'Running',
  SUCCESS: 'Succeeded',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

/** Time of day for today's runs; the date as well for older ones. */
function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const time = date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  if (date.toDateString() === new Date().toDateString()) {
    return time;
  }
  return `${date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${time}`;
}

/** The recent runs of the workflow; selecting one replays it in the trace. */
function RunHistory({
  history,
  activeId,
  onOpenRun,
}: {
  history: ApiExecutionSummary[];
  activeId: string | undefined;
  onOpenRun: (executionId: string) => void;
}) {
  if (history.length === 0) {
    return null;
  }
  return (
    <nav className="history" aria-label="Run history" data-testid="run-history">
      <h3 className="history__title">Recent runs</h3>
      <ul className="history__list">
        {history.map((run) => {
          const active = run.id === activeId;
          return (
            <li key={run.id}>
              <button
                type="button"
                className={`history__item${active ? ' is-active' : ''}`}
                onClick={() => onOpenRun(run.id)}
                aria-current={active ? 'true' : undefined}
                title={run.error ?? STATUS_TEXT[run.status] ?? run.status}
              >
                <span className={`led led--${run.status.toLowerCase()}`} aria-hidden="true" />
                <span className="history__time">{formatTime(run.startedAt)}</span>
                <span className="sr-only">{run.status}</span>
                {run.durationMs !== undefined && run.durationMs !== null && (
                  <span className="history__duration">{run.durationMs} ms</span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/**
 * Which node's result to show when the user has not picked one: the workflow's answer
 * (the last Output that succeeded), otherwise whatever went wrong, otherwise the newest
 * result.
 */
function defaultDetail(nodes: ApiNodeExecution[]): ApiNodeExecution | undefined {
  const reversed = [...nodes].reverse();
  return (
    reversed.find((n) => n.type === 'OUTPUT' && n.status === 'SUCCESS') ??
    reversed.find((n) => n.status === 'FAILED') ??
    reversed.find((n) => n.status === 'RUNNING') ??
    reversed.find((n) => n.status === 'SUCCESS')
  );
}

function NodeResult({ node }: { node: ApiNodeExecution }) {
  const [copied, setCopied] = useState(false);
  const text = node.output ?? node.error ?? '';

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <div className="result" data-testid="run-result">
      <div className="result__head">
        <span className={`led led--${node.status.toLowerCase()}`} aria-hidden="true" />
        <span className="result__label">{node.label}</span>
        <span className="result__type">{nodeTypeTitle(node.type)}</span>
        {text.length > 0 && (
          <button type="button" className="button button--quiet result__copy" onClick={() => void copy()}>
            {copied ? 'Copied' : 'Copy'}
          </button>
        )}
      </div>
      {node.error && <pre className="result__body result__body--error">{node.error}</pre>}
      {node.output && <pre className="result__body">{node.output}</pre>}
      {!node.output && !node.error && (
        <p className="result__empty">
          {node.status === 'RUNNING'
            ? 'Running now.'
            : node.status === 'PENDING'
              ? 'Waiting for its inputs.'
              : 'This node produced no text.'}
        </p>
      )}
    </div>
  );
}

/**
 * The run tab of the bottom dock: recent runs, the execution trace, and the output of the
 * node picked in the trace (by default, the workflow's final result).
 */
export function RunPanel({
  execution,
  running,
  error,
  history,
  selectedNodeId,
  onSelectNode,
  onOpenRun,
  onClear,
}: RunPanelProps) {
  const nodes = execution?.nodeExecutions ?? [];
  const detail = nodes.find((n) => n.nodeId === selectedNodeId) ?? defaultDetail(nodes);
  const done = nodes.filter((n) => n.status !== 'PENDING' && n.status !== 'RUNNING').length;

  return (
    <section className="run-panel" aria-label="Run results" data-testid="run-panel">
      <RunHistory history={history} activeId={execution?.id} onOpenRun={onOpenRun} />

      <div className="run-panel__main">
        {error && (
          <div className="alert alert--error" role="alert">
            {error}
          </div>
        )}

        {!execution && !error && (
          <div className="run-panel__empty">
            {running ? (
              <p role="status">Starting the run…</p>
            ) : (
              <>
                <p className="run-panel__empty-title">No run yet</p>
                <p>
                  Press <strong>Run</strong> or <kbd>Ctrl</kbd>+<kbd>Enter</kbd>. Each node appears here
                  as a bar on a shared timeline, so you can see what ran when and how long it took.
                </p>
              </>
            )}
          </div>
        )}

        {execution && (
          <>
            <div className="run-panel__head">
              <span className={`led led--${execution.status.toLowerCase()}`} aria-hidden="true" />
              <span className="run-panel__status" role="status">
                {STATUS_TEXT[execution.status] ?? execution.status}
                {execution.status === 'RUNNING' && nodes.length > 0 && `, ${done} of ${nodes.length} nodes`}
              </span>
              {execution.durationMs !== undefined && execution.durationMs !== null && (
                <span className="run-panel__total">{formatDuration(execution.durationMs)} total</span>
              )}
              {!running && (
                <button type="button" className="button button--quiet" onClick={onClear}>
                  Clear results
                </button>
              )}
            </div>

            {nodes.length === 0 ? (
              <p className="run-panel__note">
                {execution.error ?? 'The run stopped before any node started.'}
              </p>
            ) : (
              <div className="run-panel__body">
                <RunTrace
                  execution={execution}
                  selectedNodeId={detail?.nodeId ?? null}
                  onSelect={onSelectNode}
                />
                {detail && <NodeResult node={detail} />}
              </div>
            )}
          </>
        )}
      </div>
    </section>
  );
}
