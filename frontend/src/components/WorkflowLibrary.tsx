import type { ApiExample, ApiWorkflowSummary } from '../types';

interface WorkflowLibraryProps {
  workflows: ApiWorkflowSummary[];
  examples: ApiExample[];
  activeId: string | null;
  loading: boolean;
  error: string | null;
  search: string;
  onSearch: (term: string) => void;
  onSelect: (id: string) => void;
  onDelete: (id: string) => void;
  onLoadExample: (example: ApiExample) => void;
}

/**
 * The left sidebar: the saved-workflow library plus the example gallery.
 *
 * <p>Handles its own loading, empty and error states so the app never shows a blank panel
 * without an explanation.
 */
export function WorkflowLibrary({
  workflows,
  examples,
  activeId,
  loading,
  error,
  search,
  onSearch,
  onSelect,
  onDelete,
  onLoadExample,
}: WorkflowLibraryProps) {
  return (
    <aside className="library" aria-label="Workflow library">
      <h2 className="panel__title">Workflows</h2>

      <input
        className="field__input library__search"
        type="search"
        placeholder="Search workflows…"
        value={search}
        onChange={(event) => onSearch(event.target.value)}
        aria-label="Search workflows"
        data-testid="workflow-search"
      />

      {error && (
        <div className="alert alert--error" role="alert">
          {error}
        </div>
      )}

      {loading && (
        <div className="skeleton-list" aria-busy="true" aria-label="Loading workflows">
          {[0, 1, 2].map((index) => (
            <div key={index} className="skeleton skeleton--row" />
          ))}
        </div>
      )}

      {!loading && !error && workflows.length === 0 && (
        <div className="empty-state empty-state--compact">
          <p className="empty-state__title">
            {search ? 'No matches' : 'No workflows yet'}
          </p>
          <p className="empty-state__body">
            {search
              ? `Nothing matches “${search}”. Try a different search.`
              : 'Save the workflow you are working on, or start from an example below.'}
          </p>
        </div>
      )}

      <ul className="library__list">
        {workflows.map((workflow) => (
          <li key={workflow.id}>
            <div
              className={`library__item${workflow.id === activeId ? ' is-active' : ''}`}
              data-testid={`workflow-item-${workflow.id}`}
            >
              <button
                type="button"
                className="library__open"
                onClick={() => onSelect(workflow.id)}
                aria-current={workflow.id === activeId}
                data-testid={`workflow-open-${workflow.id}`}
              >
                <span className="library__name">{workflow.name}</span>
                <span className="library__meta">
                  {workflow.nodeCount} node{workflow.nodeCount === 1 ? '' : 's'} ·{' '}
                  {workflow.edgeCount} connection{workflow.edgeCount === 1 ? '' : 's'}
                </span>
              </button>
              <button
                type="button"
                className="library__delete"
                onClick={() => onDelete(workflow.id)}
                aria-label={`Delete ${workflow.name}`}
                title="Delete workflow"
              >
                ×
              </button>
            </div>
          </li>
        ))}
      </ul>

      <h2 className="panel__title panel__title--spaced">Examples</h2>
      <ul className="library__list">
        {examples.map((example) => (
          <li key={example.name}>
            <button
              type="button"
              className="library__example"
              onClick={() => onLoadExample(example)}
              data-testid={`example-${example.name}`}
            >
              <span className="library__name">{example.name}</span>
              <span className="library__meta">{example.description}</span>
            </button>
          </li>
        ))}
      </ul>
    </aside>
  );
}
