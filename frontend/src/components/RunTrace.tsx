import { useEffect, useMemo, useState } from 'react';
import type { ApiExecution, ApiNodeExecution } from '../types';
import { nodeTypeAccent, nodeTypeTitle } from '../nodes/definitions';
import { formatDuration } from '../format';

interface RunTraceProps {
  execution: ApiExecution;
  selectedNodeId: string | null;
  onSelect: (nodeId: string) => void;
}

const MIN_SPAN_MS = 50;

function parse(iso: string | null | undefined): number | null {
  if (!iso) {
    return null;
  }
  const value = Date.parse(iso);
  return Number.isNaN(value) ? null : value;
}

/** Re-renders on every animation frame while `active`, so running bars grow smoothly. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) {
      return;
    }
    let frame = 0;
    const tick = () => {
      setNow(Date.now());
      frame = window.requestAnimationFrame(tick);
    };
    frame = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(frame);
  }, [active]);
  return now;
}

interface Bar {
  node: ApiNodeExecution;
  start: number | null;
  end: number | null;
}

/**
 * The run as a waterfall: one row per node in execution order, each bar placed at the
 * node's real start time and sized by its real duration.
 *
 * <p>It is the picture of what the engine guarantees — a consumer's bar never starts
 * before its producers' bars end, a failure stops only its own branch (the skipped rows
 * have no bar at all), and the slow node is obvious at a glance.
 */
export function RunTrace({ execution, selectedNodeId, onSelect }: RunTraceProps) {
  const inFlight = execution.status === 'RUNNING' || execution.status === 'PENDING';
  const now = useNow(inFlight);

  const { bars, origin, span } = useMemo(() => {
    const rows: Bar[] = execution.nodeExecutions.map((node) => {
      const started = node.status === 'PENDING' ? null : parse(node.startedAt);
      const finished =
        node.status === 'RUNNING' ? null : parse(node.finishedAt) ?? (started !== null ? started : null);
      return { node, start: started, end: finished };
    });
    const starts = rows.map((row) => row.start).filter((value): value is number => value !== null);
    const runStart = parse(execution.startedAt);
    const t0 = Math.min(...starts, runStart ?? Number.POSITIVE_INFINITY);
    const ends = rows.map((row) => row.end ?? (row.start !== null ? now : null))
      .filter((value): value is number => value !== null);
    const runEnd = inFlight ? now : parse(execution.finishedAt) ?? Math.max(...ends, t0);
    const origin = Number.isFinite(t0) ? t0 : now;
    return { bars: rows, origin, span: Math.max(MIN_SPAN_MS, Math.max(runEnd, ...ends) - origin) };
  }, [execution, inFlight, now]);

  // Three labels stay legible even when the lane is narrow; the lane itself draws quarters.
  const ticks = [0, 0.5, 1];

  return (
    <div className="trace">
      <div className="trace__axis" aria-hidden="true">
        {ticks.map((fraction) => (
          <span key={fraction} className="trace__tick" style={{ left: `${fraction * 100}%` }}>
            {formatDuration(span * fraction)}
          </span>
        ))}
      </div>
      <ol className="trace__rows" aria-label="Execution timeline">
      {bars.map(({ node, start, end }) => {
        const effectiveEnd = end ?? (start !== null ? now : null);
        const left = start === null ? 0 : ((start - origin) / span) * 100;
        const width =
          start === null || effectiveEnd === null ? 0 : ((effectiveEnd - start) / span) * 100;
        const status = node.status.toLowerCase();
        const duration =
          node.status === 'SUCCESS' || node.status === 'FAILED'
            ? formatDuration(node.durationMs ?? (effectiveEnd ?? 0) - (start ?? 0))
            : node.status === 'RUNNING' && start !== null
              ? formatDuration(now - start)
              : node.status === 'SKIPPED'
                ? 'skipped'
                : 'waiting';
        return (
          <li key={node.nodeId}>
          <button
            type="button"
            aria-pressed={node.nodeId === selectedNodeId}
            className={`trace__row trace__row--${status}${node.nodeId === selectedNodeId ? ' is-selected' : ''}`}
            style={{ ['--node-accent' as string]: nodeTypeAccent(node.type) }}
            onClick={() => onSelect(node.nodeId)}
            aria-label={`${node.sequence}. ${node.label}, ${nodeTypeTitle(node.type)}, ${node.status.toLowerCase()}, ${duration}`}
          >
            <span className="trace__seq">{node.sequence}</span>
            <span className="trace__name">
              <span className="trace__label">{node.label}</span>
              <code className="trace__key">{node.nodeKey}</code>
            </span>
            <span className="trace__lane">
              {start !== null && node.status !== 'SKIPPED' && (
                <span
                  className="trace__bar"
                  style={{ left: `${left}%`, width: `max(3px, ${width}%)` }}
                />
              )}
            </span>
            <span className="trace__duration">{duration}</span>
          </button>
          </li>
        );
      })}
      </ol>
    </div>
  );
}
