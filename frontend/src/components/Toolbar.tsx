interface ToolbarProps {
  name: string;
  onRename: (name: string) => void;
  dirty: boolean;
  saving: boolean;
  running: boolean;
  canRun: boolean;
  onSave: () => void;
  onRun: () => void;
  onValidate: () => void;
  onClear: () => void;
  onNew: () => void;
  hasWorkflow: boolean;
}

/** The top bar: the workflow's name, then the actions in order of consequence. */
export function Toolbar({
  name,
  onRename,
  dirty,
  saving,
  running,
  canRun,
  onSave,
  onRun,
  onValidate,
  onClear,
  onNew,
  hasWorkflow,
}: ToolbarProps) {
  return (
    <header className="toolbar">
      <div className="toolbar__brand">
        <svg className="toolbar__logo" viewBox="0 0 28 28" aria-hidden="true">
          <circle cx="6" cy="8" r="3.2" />
          <circle cx="6" cy="20" r="3.2" />
          <circle cx="22" cy="14" r="3.2" />
          <path d="M9 8c6 0 6 6 10 6M9 20c6 0 6-6 10-6" />
        </svg>
        <span className="toolbar__product">FlowCanvas</span>
      </div>

      <div className="toolbar__name">
        <input
          className="toolbar__name-input"
          value={name}
          onChange={(event) => onRename(event.target.value)}
          placeholder="Untitled workflow"
          aria-label="Workflow name"
          data-testid="workflow-name"
          spellCheck={false}
        />
        <span className="toolbar__state" aria-live="polite">
          {saving ? 'Saving…' : dirty ? 'Unsaved changes' : hasWorkflow ? 'Saved' : ''}
        </span>
      </div>

      <div className="toolbar__actions">
        <button type="button" className="button button--quiet" onClick={onNew} data-testid="new-workflow">
          New
        </button>
        <button
          type="button"
          className="button button--quiet"
          onClick={onClear}
          disabled={!hasWorkflow}
          data-testid="clear-graph"
        >
          Clear
        </button>
        <span className="toolbar__divider" aria-hidden="true" />
        <button
          type="button"
          className="button"
          onClick={onValidate}
          disabled={!hasWorkflow}
          title="Check the workflow with the server"
          data-testid="validate-workflow"
        >
          Check
        </button>
        <button
          type="button"
          className="button"
          onClick={onSave}
          disabled={saving || !dirty}
          title="Save (Ctrl+S)"
          data-testid="save-workflow"
        >
          Save
        </button>
        <button
          type="button"
          className={`button button--run${running ? ' is-running' : ''}`}
          onClick={onRun}
          disabled={running || !canRun}
          title={canRun ? 'Run (Ctrl+Enter)' : 'Fix the problems listed below to run'}
          data-testid="run-workflow"
        >
          <span className="button__led" aria-hidden="true" />
          {running ? 'Running' : 'Run'}
        </button>
      </div>
    </header>
  );
}
