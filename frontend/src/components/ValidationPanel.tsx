import type { GraphIssue } from '../nodes/graph';

interface ValidationPanelProps {
  issues: GraphIssue[];
  /** Whether the list came from the server check or from the live in-browser rules. */
  source: 'live' | 'server';
  validating: boolean;
  hasNodes: boolean;
  onFocusNode: (nodeId: string) => void;
}

/**
 * The problems tab of the bottom dock.
 *
 * <p>It updates as the graph changes, using the same rules the server applies, so the user
 * never has to ask whether the workflow is runnable. Errors block a run; warnings do not.
 * An issue that belongs to a node focuses that node on the canvas when clicked.
 */
export function ValidationPanel({ issues, source, validating, hasNodes, onFocusNode }: ValidationPanelProps) {
  const errors = issues.filter((issue) => issue.severity === 'ERROR');

  return (
    <section className="problems" aria-label="Problems" data-testid="validation-panel">
      <p className="problems__summary" role="status">
        {validating
          ? 'Checking with the server…'
          : !hasNodes
            ? 'Add a node to start building.'
            : errors.length > 0
              ? `${errors.length} ${errors.length === 1 ? 'problem blocks' : 'problems block'} this workflow from running.`
              : issues.length > 0
                ? 'Ready to run. The notes below will not stop it.'
                : 'Ready to run. Nothing to fix.'}
        <span className="problems__source">
          {source === 'server' ? 'Confirmed by the server' : 'Checked as you edit'}
        </span>
      </p>

      {issues.length > 0 && (
        <ul className="problems__list">
          {issues.map((issue, index) => {
            const body = (
              <>
                <span className={`problems__mark problems__mark--${issue.severity.toLowerCase()}`}>
                  {issue.severity === 'ERROR' ? 'Error' : 'Note'}
                </span>
                <span className="problems__message">{issue.message}</span>
                <code className="problems__code">{issue.code}</code>
              </>
            );
            return (
              <li key={`${issue.code}-${issue.nodeId ?? issue.edgeId ?? ''}-${index}`}>
                {issue.nodeId ? (
                  <button
                    type="button"
                    className="problems__item"
                    onClick={() => onFocusNode(issue.nodeId!)}
                    title="Show this node"
                  >
                    {body}
                  </button>
                ) : (
                  <div className="problems__item">{body}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
