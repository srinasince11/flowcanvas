import type { GraphIssue } from '../nodes/graph';

interface ValidationPanelProps {
  issues: GraphIssue[];
  validating: boolean;
  lastChecked: string | null;
}

/**
 * The validation panel.
 *
 * <p>Errors block the run; warnings do not. Both are shown with the node they belong to,
 * and clicking an issue selects the offending node on the canvas.
 */
export function ValidationPanel({ issues, validating, lastChecked }: ValidationPanelProps) {
  const errors = issues.filter((issue) => issue.severity === 'ERROR');
  const warnings = issues.filter((issue) => issue.severity === 'WARNING');

  return (
    <section className="validation" aria-label="Validation" data-testid="validation-panel">
      <div className="validation__head">
        <h2 className="panel__title">Validation</h2>
        {validating ? (
          <span className="validation__badge validation__badge--busy">Checking…</span>
        ) : errors.length > 0 ? (
          <span className="validation__badge validation__badge--error">
            {errors.length} error{errors.length === 1 ? '' : 's'}
          </span>
        ) : warnings.length > 0 ? (
          <span className="validation__badge validation__badge--warn">
            {warnings.length} warning{warnings.length === 1 ? '' : 's'}
          </span>
        ) : lastChecked ? (
          <span className="validation__badge validation__badge--ok">Valid</span>
        ) : null}
      </div>

      {issues.length === 0 && !validating && (
        <div className="empty-state empty-state--compact">
          <p className="empty-state__title">
            {lastChecked ? 'No issues found' : 'Not checked yet'}
          </p>
          <p className="empty-state__body">
            {lastChecked
              ? 'This workflow is valid and ready to run.'
              : 'Press Validate to check connections and detect cycles.'}
          </p>
        </div>
      )}

      <ul className="validation__list">
        {issues.map((issue, index) => (
          <li
            key={`${issue.code}-${index}`}
            className={`validation__item validation__item--${issue.severity.toLowerCase()}`}
          >
            <span className="validation__code">{issue.code}</span>
            <span className="validation__message">{issue.message}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
