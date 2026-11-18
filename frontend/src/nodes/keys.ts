import type { Edge, Node } from '@xyflow/react';
import type { NodeType } from '../types';

/**
 * Node keys: the names templates use to address upstream nodes, as in `{{advocate}}`.
 *
 * <p>Mirrors `com.flowcanvas.graph.TemplateVariables` on the server — the same key syntax,
 * the same placeholder syntax and the same case-insensitive matching — so a template the
 * editor accepts is a template the engine can run.
 */

/** What a key may look like: 1–64 letters, digits, `_` or `-`. */
export const KEY_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

/** `{{ name }}`, with optional inner whitespace. */
const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_-]+)\s*\}\}/g;

/** The reserved variable holding a node's direct input(s). */
export const INPUT_VARIABLE = 'input';

const KEY_PREFIX: Record<NodeType, string> = {
  TEXT_INPUT: 'text',
  PROMPT_TEMPLATE: 'prompt',
  AI_MODEL: 'model',
  OUTPUT: 'output',
};

/** The node's key, falling back to its id for graphs saved before keys were editable. */
export function keyOf(node: Node): string {
  const key = (node.data as { key?: unknown } | undefined)?.key;
  return typeof key === 'string' && key.length > 0 ? key : node.id;
}

export function isValidKey(key: string): boolean {
  return KEY_PATTERN.test(key);
}

/** The first free `<prefix><n>` key for a new node of the given type, e.g. `prompt2`. */
export function suggestKey(type: NodeType, nodes: Node[]): string {
  const taken = new Set(nodes.map((node) => keyOf(node).toLowerCase()));
  const prefix = KEY_PREFIX[type] ?? 'node';
  for (let n = 1; ; n += 1) {
    const candidate = `${prefix}${n}`;
    if (!taken.has(candidate.toLowerCase())) {
      return candidate;
    }
  }
}

/** The distinct variable names a template references, in order of first use. */
export function referencedVariables(template: string): string[] {
  const names: string[] = [];
  for (const match of template.matchAll(PLACEHOLDER)) {
    const name = match[1]!;
    if (!names.includes(name)) {
      names.push(name);
    }
  }
  return names;
}

/** Ids of every node the given node transitively depends on. Terminates on cycles. */
export function ancestorIds(nodeId: string, edges: Edge[]): Set<string> {
  const incoming = new Map<string, string[]>();
  for (const edge of edges) {
    const list = incoming.get(edge.target) ?? [];
    list.push(edge.source);
    incoming.set(edge.target, list);
  }
  const visited = new Set<string>();
  const stack = [...(incoming.get(nodeId) ?? [])];
  while (stack.length > 0) {
    const current = stack.pop()!;
    if (visited.has(current)) {
      continue;
    }
    visited.add(current);
    stack.push(...(incoming.get(current) ?? []));
  }
  visited.delete(nodeId);
  return visited;
}

/** The variables a template on this node may use: `input`, then every ancestor key. */
export function availableVariables(nodeId: string, nodes: Node[], edges: Edge[]): string[] {
  const ancestors = ancestorIds(nodeId, edges);
  const keys = nodes.filter((node) => ancestors.has(node.id)).map(keyOf).sort();
  return [INPUT_VARIABLE, ...keys];
}
