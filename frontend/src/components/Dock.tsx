import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import type { ExecutionStatus } from '../types';

export type DockTab = 'run' | 'problems';

interface DockProps {
  tab: DockTab;
  onTab: (tab: DockTab) => void;
  problemCount: number;
  errorCount: number;
  runStatus: ExecutionStatus | null;
  run: ReactNode;
  problems: ReactNode;
}

const TABS: DockTab[] = ['run', 'problems'];

/**
 * The panel under the canvas, with the run trace and the problems list as tabs.
 *
 * <p>Both panels stay mounted (the inactive one is hidden) so switching is instant and a
 * run keeps animating in the background. Arrow keys move between tabs, per the WAI-ARIA
 * tabs pattern.
 */
export function Dock({ tab, onTab, problemCount, errorCount, runStatus, run, problems }: DockProps) {
  const tabRefs = useRef<Record<DockTab, HTMLButtonElement | null>>({ run: null, problems: null });

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
      return;
    }
    const next = TABS[(TABS.indexOf(tab) + 1) % TABS.length]!;
    onTab(next);
    tabRefs.current[next]?.focus();
    event.preventDefault();
  };

  return (
    <div className="dock">
      <div className="dock__tabs" role="tablist" aria-label="Run and problems" onKeyDown={onKeyDown}>
        <button
          ref={(element) => {
            tabRefs.current.run = element;
          }}
          type="button"
          role="tab"
          id="dock-tab-run"
          aria-selected={tab === 'run'}
          aria-controls="dock-panel-run"
          tabIndex={tab === 'run' ? 0 : -1}
          className="dock__tab"
          onClick={() => onTab('run')}
        >
          {runStatus && <span className={`led led--${runStatus.toLowerCase()}`} aria-hidden="true" />}
          Run
        </button>
        <button
          ref={(element) => {
            tabRefs.current.problems = element;
          }}
          type="button"
          role="tab"
          id="dock-tab-problems"
          aria-selected={tab === 'problems'}
          aria-controls="dock-panel-problems"
          tabIndex={tab === 'problems' ? 0 : -1}
          className="dock__tab"
          onClick={() => onTab('problems')}
        >
          Problems
          {problemCount > 0 && (
            <span className={`dock__count${errorCount > 0 ? ' dock__count--error' : ''}`}>
              {problemCount}
            </span>
          )}
        </button>
      </div>
      <div
        className="dock__panel"
        role="tabpanel"
        id="dock-panel-run"
        aria-labelledby="dock-tab-run"
        hidden={tab !== 'run'}
      >
        {run}
      </div>
      <div
        className="dock__panel"
        role="tabpanel"
        id="dock-panel-problems"
        aria-labelledby="dock-tab-problems"
        hidden={tab !== 'problems'}
      >
        {problems}
      </div>
    </div>
  );
}
