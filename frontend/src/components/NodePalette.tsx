import type { DragEvent } from 'react';
import type { NodeType } from '../types';
import { NODE_TYPE_DEFINITIONS, NODE_TYPE_ORDER } from '../nodes/definitions';

/** The drag payload type, shared with the canvas drop handler. */
export const NODE_DRAG_TYPE = 'application/x-flowcanvas-node';

interface NodePaletteProps {
  onAddNode: (type: NodeType) => void;
  disabled?: boolean;
}

/**
 * The parts bin. Drag a part onto the canvas to place it exactly, or click it to drop it
 * in the middle of the current view — the click path also covers keyboard and touch use.
 */
export function NodePalette({ onAddNode, disabled }: NodePaletteProps) {
  const onDragStart = (event: DragEvent<HTMLButtonElement>, type: NodeType) => {
    event.dataTransfer.setData(NODE_DRAG_TYPE, type);
    event.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <section className="palette" aria-label="Node palette">
      <h2 className="panel__title">Parts</h2>
      <p className="palette__note">Drag onto the canvas, or click to add.</p>
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
              onDragStart={(event) => onDragStart(event, type)}
              draggable={!disabled}
              disabled={disabled}
              data-testid={`add-node-${type}`}
            >
              <span className="palette__jack" aria-hidden="true" />
              <span className="palette__text">
                <span className="palette__name">{definition.title}</span>
                <span className="palette__hint">{definition.hint}</span>
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}
