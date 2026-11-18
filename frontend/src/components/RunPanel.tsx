import type { ApiExecution, ApiExecutionSummary } from '../types';
import { nodeTypeTitle } from '../nodes/definitions';

interface RunPanelProps {
  execution: ApiExecution | null;
  running: boolean;
  error: string | null;
  /** Recent runs of the current workflow, newest first. */
  history: ApiExecutionSummary[];
  onOpenRun: (executionId: string) => void;
  onClear: () => void;
}

function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

/** The recent runs of the workflow; selecting one replays its results on the canvas. */
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
                title={run.error ?? run.status}
              >
                <span
                  className={`history__dot history__dot--${run.status.toLowerCase()}`}
                  aria-hidden="true"
                />
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
 * The run results panel.
 *
 * <p>Shows the ordered list of node executions with their status, output, error and
 * duration — the evidence that the workflow actually ran in dependency order. While a run
 * is in flight the whole plan is listed, so pending nodes are visible before they start.
 */
export function RunPanel({ execution, running, error, history, onOpenRun, onClear }: RunPanelProps) {
  if (!execution && !running && !error) {
    return (
      <section className="run-panel" aria-label="Run results" data-testid="run-panel">
        <h2 className="panel__title">Run results</h2>
        <div className="empty-state empty-state--compact">
          <p className="empty-state__title">No run yet</p>
          <p className="empty-state__body">
            Press <strong>Run</strong> to execute the workflow and see per-node results here.
          </p>
        </div>
        <RunHistory history={history} activeId={undefined} onOpenRun={onOpenRun} />
      </section>
    );
  }

  const nodeExecutions = execution?.nodeExecutions ?? [];
  const done = nodeExecutions.filter((n) => n.status !== 'PENDING' && n.status !== 'RUNNING').length;

  return (
    <section className="run-panel" aria-label="Run results" data-testid="run-panel">
      <div className="run-panel__head">
        <h2 className="panel__title">Run results</h2>
        {execution && (
          <span className={`run-panel__status run-panel__status--${execution.status.toLowerCase()}`}>
            {execution.status}
            {execution.status === 'RUNNING' && nodeExecutions.length > 0
              ? ` · ${done}/${nodeExecutions.length}`
              : ''}
            {execution.durationMs !== undefined && execution.durationMs !== null
              ? ` · ${execution.durationMs} ms`
              : ''}
          </span>
        )}
      </div>

      {error && (
        <div className="alert alert--error" role="alert">
          {error}
        </div>
      )}

      {running && nodeExecutions.length === 0 && (
        <div className="alert alert--info" role="status">
          Executing workflow…
        </div>
      )}

      {execution && nodeExecutions.length === 0 && execution.status !== 'RUNNING' && (
        <div className="empty-state empty-state--compact">
          <p className="empty-state__title">The run produced no node results</p>
          <p className="empty-state__body">{execution.error ?? 'Check the validation issues for details.'}</p>
        </div>
      )}

      <ol className="run-list">
        {nodeExecutions.map((nodeExecution) => (
          <li key={nodeExecution.nodeId} className={`run-item run-item--${nodeExecution.status.toLowerCase()}`}>
            <div className="run-item__head">
              <span className="run-item__sequence">#{nodeExecution.sequence}</span>
              <span className="run-item__label">{nodeExecution.label}</span>
              <span className="run-item__type">{nodeTypeTitle(nodeExecution.type)}</span>
              <span className={`run-item__status run-item__status--${nodeExecution.status.toLowerCase()}`}>
                {nodeExecution.status}
              </span>
              {nodeExecution.durationMs !== undefined &&
                nodeExecution.durationMs !== null &&
                nodeExecution.status !== 'PENDING' &&
                nodeExecution.status !== 'RUNNING' && (
                  <span className="run-item__duration">{nodeExecution.durationMs} ms</span>
                )}
            </div>
            {nodeExecution.error && <pre className="run-item__error">{nodeExecution.error}</pre>}
            {nodeExecution.output && nodeExecution.output.length > 0 && (
              <pre className="run-item__output">{nodeExecution.output}</pre>
            )}
          </li>
        ))}
      </ol>

      {execution && !running && (
        <button type="button" className="button button--ghost" onClick={onClear}>
          Clear results
        </button>
      )}

      <RunHistory history={history} activeId={execution?.id} onOpenRun={onOpenRun} />
    </section>
  );
}
