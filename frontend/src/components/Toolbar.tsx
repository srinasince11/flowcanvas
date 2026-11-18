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

/** The top bar: identity, save, validate, run. */
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
        <span className="toolbar__logo" aria-hidden="true">
          ◈
        </span>
        <div className="toolbar__titles">
          <span className="toolbar__product">FlowCanvas</span>
          <span className="toolbar__tagline">Visual AI workflow builder</span>
        </div>
      </div>

      <div className="toolbar__name">
        <input
          className="toolbar__name-input"
          value={name}
          onChange={(event) => onRename(event.target.value)}
          placeholder="Untitled workflow"
          aria-label="Workflow name"
          data-testid="workflow-name"
        />
        {dirty && (
          <span className="toolbar__dirty" title="There are unsaved changes">
            ●
          </span>
        )}
      </div>

      <div className="toolbar__actions">
        <button type="button" className="button button--ghost" onClick={onNew} data-testid="new-workflow">
          New
        </button>
        <button
          type="button"
          className="button button--ghost"
          onClick={onClear}
          disabled={!hasWorkflow}
          data-testid="clear-graph"
        >
          Clear canvas
        </button>
        <button
          type="button"
          className="button button--ghost"
          onClick={onValidate}
          disabled={!hasWorkflow}
          data-testid="validate-workflow"
        >
          Validate
        </button>
        <button
          type="button"
          className="button button--secondary"
          onClick={onSave}
          disabled={saving || !dirty}
          data-testid="save-workflow"
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
        <button
          type="button"
          className="button button--primary"
          onClick={onRun}
          disabled={running || !canRun}
          data-testid="run-workflow"
        >
          {running ? 'Running…' : '▶ Run'}
        </button>
      </div>
    </header>
  );
}
