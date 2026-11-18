import type { Node } from '@xyflow/react';
import type { NodeType } from '../types';
import { NODE_TYPE_DEFINITIONS } from '../nodes/definitions';

interface InspectorProps {
  node: Node | null;
  /** The variables a template on this node may use: `input` plus every ancestor key. */
  variables: string[];
  /** Why the current key is unusable (malformed or taken), or null when it is fine. */
  keyError: string | null;
  onUpdateConfig: (patch: Record<string, unknown>) => void;
  onRename: (label: string) => void;
  onRenameKey: (key: string) => void;
  onDelete: () => void;
}

/** `{{name}}`, built without tripping JSX over the double braces. */
function placeholder(name: string): string {
  return '{{' + name + '}}';
}

/**
 * The right-hand panel that edits the selected node.
 *
 * <p>Each node type renders its own form, driven by the definition in
 * {@link NODE_TYPE_DEFINITIONS}. The component owns no state of its own — it renders from
 * the node and pushes changes up — which keeps the canvas graph the single source of truth.
 */
export function Inspector({
  node,
  variables,
  keyError,
  onUpdateConfig,
  onRename,
  onRenameKey,
  onDelete,
}: InspectorProps) {
  if (!node) {
    return (
      <section className="inspector" aria-label="Node inspector">
        <h2 className="panel__title">Inspector</h2>
        <div className="empty-state empty-state--compact">
          <p className="empty-state__title">No node selected</p>
          <p className="empty-state__body">
            Click a node on the canvas to configure it, or add one from the palette.
          </p>
        </div>
      </section>
    );
  }

  const data = node.data as {
    key?: string;
    label?: string;
    config?: Record<string, unknown>;
    nodeType?: NodeType;
  };
  const type = (data.nodeType ?? node.type) as NodeType;
  const definition = NODE_TYPE_DEFINITIONS[type];
  const config = data.config ?? {};
  const nodeKey = data.key ?? node.id;

  return (
    <section className="inspector" aria-label="Node inspector" data-testid="inspector">
      <h2 className="panel__title">Inspector</h2>

      <div className="inspector__type" style={{ ['--node-accent' as string]: definition?.accent }}>
        <span className="palette__dot" aria-hidden="true" />
        <div>
          <div className="inspector__type-name">{definition?.title ?? type}</div>
          <div className="inspector__type-desc">{definition?.description}</div>
        </div>
      </div>

      <label className="field">
        <span className="field__label">Label</span>
        <input
          className="field__input"
          value={data.label ?? ''}
          onChange={(event) => onRename(event.target.value)}
          placeholder="Node name"
          data-testid="inspector-label"
        />
      </label>

      <label className="field">
        <span className="field__label">Key</span>
        <input
          className={`field__input field__input--mono${keyError ? ' field__input--invalid' : ''}`}
          value={nodeKey}
          onChange={(event) => onRenameKey(event.target.value)}
          spellCheck={false}
          autoComplete="off"
          aria-invalid={keyError !== null}
          aria-describedby="inspector-key-help"
          data-testid="inspector-key"
        />
      </label>
      <p
        className={`field__note${keyError ? ' field__note--error' : ''}`}
        id="inspector-key-help"
        data-testid="inspector-key-help"
      >
        {keyError ?? (
          <>
            Downstream templates read this node as <code>{placeholder(nodeKey)}</code>.
          </>
        )}
      </p>

      {type === 'TEXT_INPUT' && (
        <label className="field">
          <span className="field__label">Text</span>
          <textarea
            className="field__input field__input--area"
            value={String(config.text ?? '')}
            onChange={(event) => onUpdateConfig({ text: event.target.value })}
            rows={6}
            placeholder="The text this node emits"
            data-testid="inspector-text"
          />
        </label>
      )}

      {type === 'PROMPT_TEMPLATE' && (
        <>
          <label className="field">
            <span className="field__label">Template</span>
            <textarea
              className="field__input field__input--area"
              value={String(config.template ?? '')}
              onChange={(event) => onUpdateConfig({ template: event.target.value })}
              rows={8}
              placeholder="Write a prompt using {{input}} or {{nodeKey}}"
              data-testid="inspector-template"
            />
          </label>
          <div className="field__note">
            <div className="variable-list__title">Available variables · click to insert</div>
            <div className="variable-list" data-testid="inspector-variables">
              {variables.map((name) => (
                <button
                  key={name}
                  type="button"
                  className="variable-chip"
                  onClick={() =>
                    onUpdateConfig({ template: String(config.template ?? '') + placeholder(name) })
                  }
                  title={
                    name === 'input'
                      ? 'The direct input. Several inputs are joined with a blank line.'
                      : `The output of upstream node "${name}".`
                  }
                >
                  {placeholder(name)}
                </button>
              ))}
            </div>
            {variables.length <= 1 && (
              <div className="variable-list__hint">
                Connect upstream nodes to reference their outputs by key.
              </div>
            )}
          </div>
        </>
      )}

      {type === 'AI_MODEL' && (
        <>
          <label className="field">
            <span className="field__label">Model</span>
            <input
              className="field__input"
              value={String(config.model ?? '')}
              onChange={(event) => onUpdateConfig({ model: event.target.value })}
              placeholder="gpt-4o-mini"
              data-testid="inspector-model"
            />
          </label>
          <label className="field">
            <span className="field__label">System prompt</span>
            <textarea
              className="field__input field__input--area"
              value={String(config.systemPrompt ?? '')}
              onChange={(event) => onUpdateConfig({ systemPrompt: event.target.value })}
              rows={4}
              placeholder="Instructions given to the model"
              data-testid="inspector-system-prompt"
            />
          </label>
          <label className="field">
            <span className="field__label">Temperature</span>
            <input
              className="field__input"
              type="number"
              min={0}
              max={2}
              step={0.1}
              value={Number(config.temperature ?? 0.7)}
              onChange={(event) => onUpdateConfig({ temperature: Number(event.target.value) })}
              data-testid="inspector-temperature"
            />
          </label>
        </>
      )}

      {type === 'OUTPUT' && (
        <label className="field">
          <span className="field__label">Format</span>
          <select
            className="field__input"
            value={String(config.format ?? 'text')}
            onChange={(event) => onUpdateConfig({ format: event.target.value })}
            data-testid="inspector-format"
          >
            <option value="text">Text</option>
            <option value="json">JSON</option>
          </select>
        </label>
      )}

      <button type="button" className="button button--danger" onClick={onDelete} data-testid="inspector-delete">
        Delete node
      </button>
    </section>
  );
}
