import type { NodeType } from '../types';
import { NODE_TYPE_DEFINITIONS, NODE_TYPE_ORDER } from '../nodes/definitions';

interface NodePaletteProps {
  onAddNode: (type: NodeType) => void;
  disabled?: boolean;
}

/**
 * The node palette.
 *
 * <p>Nodes are added by clicking rather than by dragging. Drag-and-drop is nicer, but a
 * click works identically on touch devices and removes a whole class of pointer-event
 * bugs — the right trade for a portfolio project that must feel solid on a laptop.
 */
export function NodePalette({ onAddNode, disabled }: NodePaletteProps) {
  return (
    <section className="palette" aria-label="Node palette">
      <h2 className="panel__title">Add node</h2>
      <div className="palette__list">
        {NODE_TYPE_ORDER.map((type) => {
          const definition = NODE_TYPE_DEFINITIONS[type];
          return (
            <button
              key={type}
              type="button"
              className="palette__item"
              style={{ ['--node-accent' as string]: definition.accent }}
              onClick={() => onAddNode(type)}
              disabled={disabled}
              data-testid={`add-node-${type}`}
            >
              <span className="palette__dot" aria-hidden="true" />
              <span className="palette__text">
                <span className="palette__name">{definition.title}</span>
                <span className="palette__hint">{definition.hint}</span>
              </span>
              <span className="palette__plus" aria-hidden="true">
                +
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}
