import type { Edge, Node } from '@xyflow/react';
import type { ApiEdge, ApiNode, NodeType } from '../types';
import { availableVariables, isValidKey, keyOf, referencedVariables } from './keys';

/**
 * Pure graph helpers shared by the canvas and the tests.
 *
 * <p>These deliberately mirror the backend's rules so the UI can give instant feedback.
 * The backend remains the authority — it re-validates before every run — but duplicating
 * the checks client-side means the user is not told about a cycle only after a round trip.
 */

/** A client-side validation issue. */
export interface GraphIssue {
  severity: 'ERROR' | 'WARNING';
  code: string;
  message: string;
  nodeId?: string;
  edgeId?: string;
}

/** Computes in/out degree for every node. */
export function computeDegrees(
  nodes: Node[],
  edges: Edge[],
): Map<string, { incoming: number; outgoing: number }> {
  const degrees = new Map<string, { incoming: number; outgoing: number }>();
  for (const node of nodes) {
    degrees.set(node.id, { incoming: 0, outgoing: 0 });
  }
  for (const edge of edges) {
    const source = degrees.get(edge.source);
    const target = degrees.get(edge.target);
    if (source) {
      source.outgoing += 1;
    }
    if (target) {
      target.incoming += 1;
    }
  }
  return degrees;
}

/**
 * Detects a cycle with an iterative depth-first search.
 *
 * <p>Iterative rather than recursive so a pathological graph cannot overflow the stack,
 * and it returns the participating node ids so the UI can highlight them.
 */
export function detectCycle(nodes: Node[], edges: Edge[]): string[] {
  const adjacency = new Map<string, string[]>();
  for (const node of nodes) {
    adjacency.set(node.id, []);
  }
  for (const edge of edges) {
    adjacency.get(edge.source)?.push(edge.target);
  }

  const WHITE = 0;
  const GREY = 1;
  const BLACK = 2;
  const colour = new Map<string, number>();
  for (const node of nodes) {
    colour.set(node.id, WHITE);
  }

  for (const node of nodes) {
    if (colour.get(node.id) !== WHITE) {
      continue;
    }
    // Each frame is [nodeId, index into its adjacency list].
    const stack: Array<[string, number]> = [[node.id, 0]];
    const path: string[] = [];
    colour.set(node.id, GREY);
    path.push(node.id);

    while (stack.length > 0) {
      const frame = stack[stack.length - 1]!;
      const [currentId, index] = frame;
      const neighbours = adjacency.get(currentId) ?? [];
      if (index < neighbours.length) {
        frame[1] = index + 1;
        const next = neighbours[index]!;
        const nextColour = colour.get(next) ?? WHITE;
        if (nextColour === GREY) {
          // Back edge: the cycle is the slice of the current path from `next` onwards.
          const start = path.indexOf(next);
          return start >= 0 ? [...path.slice(start), next] : [next];
        }
        if (nextColour === WHITE) {
          colour.set(next, GREY);
          path.push(next);
          stack.push([next, 0]);
        }
      } else {
        colour.set(currentId, BLACK);
        stack.pop();
        path.pop();
      }
    }
  }
  return [];
}

/**
 * Runs the client-side validation rules.
 *
 * <p>Mirrors `GraphValidator` on the server: the same node-type arity rules, the same
 * cycle detection, the same severity split between blocking errors and advisory warnings.
 */
export function validateGraph(nodes: Node[], edges: Edge[]): GraphIssue[] {
  const issues: GraphIssue[] = [];

  if (nodes.length === 0) {
    return [
      {
        severity: 'ERROR',
        code: 'GRAPH_EMPTY',
        message: 'This workflow has no nodes yet. Add one from the palette to get started.',
      },
    ];
  }

  const degrees = computeDegrees(nodes, edges);
  const nodeIds = new Set(nodes.map((node) => node.id));

  for (const edge of edges) {
    if (!nodeIds.has(edge.source)) {
      issues.push({
        severity: 'ERROR',
        code: 'EDGE_SOURCE_MISSING',
        message: 'A connection points at a node that no longer exists.',
        edgeId: edge.id,
      });
    }
    if (!nodeIds.has(edge.target)) {
      issues.push({
        severity: 'ERROR',
        code: 'EDGE_TARGET_MISSING',
        message: 'A connection points at a node that no longer exists.',
        edgeId: edge.id,
      });
    }
    if (edge.source === edge.target) {
      issues.push({
        severity: 'ERROR',
        code: 'SELF_LOOP',
        message: 'A node cannot be connected to itself.',
        nodeId: edge.source,
        edgeId: edge.id,
      });
    }
  }

  const seenPairs = new Set<string>();
  for (const edge of edges) {
    const pair = `${edge.source}->${edge.target}`;
    if (seenPairs.has(pair)) {
      issues.push({
        severity: 'ERROR',
        code: 'DUPLICATE_EDGE',
        message: 'These two nodes are connected more than once.',
        edgeId: edge.id,
      });
    }
    seenPairs.add(pair);
  }

  const seenKeys = new Map<string, string>();
  for (const node of nodes) {
    const key = keyOf(node);
    const label = labelOf(node);
    if (!isValidKey(key)) {
      issues.push({
        severity: 'ERROR',
        code: 'INVALID_NODE_KEY',
        message: `"${label}" has an invalid key "${key}". Use 1–64 letters, digits, "_" or "-".`,
        nodeId: node.id,
      });
      continue;
    }
    const clash = seenKeys.get(key.toLowerCase());
    if (clash !== undefined) {
      issues.push({
        severity: 'ERROR',
        code: 'DUPLICATE_NODE_KEY',
        message: `"${clash}" and "${label}" share the key "${key}". Keys must be unique.`,
        nodeId: node.id,
      });
    } else {
      seenKeys.set(key.toLowerCase(), label);
    }
  }

  for (const node of nodes) {
    const type = node.type as NodeType;
    const degree = degrees.get(node.id) ?? { incoming: 0, outgoing: 0 };
    const label = labelOf(node);

    if (type === 'TEXT_INPUT' && degree.incoming > 0) {
      issues.push({
        severity: 'ERROR',
        code: 'TOO_MANY_INPUTS',
        message: `Text Input "${label}" cannot have an incoming connection.`,
        nodeId: node.id,
      });
    }
    if (type === 'AI_MODEL' && degree.incoming > 1) {
      issues.push({
        severity: 'ERROR',
        code: 'TOO_MANY_INPUTS',
        message: `AI Model "${label}" takes one prompt. Join branches with a Prompt Template first.`,
        nodeId: node.id,
      });
    }
    if (type === 'OUTPUT' && degree.outgoing > 0) {
      issues.push({
        severity: 'ERROR',
        code: 'TOO_MANY_OUTPUTS',
        message: `Output "${label}" is a terminal node and cannot connect to anything else.`,
        nodeId: node.id,
      });
    }
    if (type !== 'TEXT_INPUT' && degree.incoming === 0) {
      issues.push({
        severity: 'ERROR',
        code: 'MISSING_INPUT',
        message: `${label} needs an incoming connection before it can run.`,
        nodeId: node.id,
      });
    }
  }

  const cycle = detectCycle(nodes, edges);
  if (cycle.length === 0) {
    issues.push(...validateTemplates(nodes, edges));
  }
  if (cycle.length > 0) {
    const labels = cycle
      .map((id) => (nodes.find((n) => n.id === id)?.data as { label?: string } | undefined)?.label ?? id)
      .join(' → ');
    issues.push({
      severity: 'ERROR',
      code: 'CYCLE_DETECTED',
      message: `This workflow contains a cycle: ${labels}.`,
      nodeId: cycle[0],
    });
  }

  if (!nodes.some((node) => node.type === 'OUTPUT')) {
    issues.push({
      severity: 'WARNING',
      code: 'NO_OUTPUT_NODE',
      message: 'There is no Output node, so this workflow will not produce a result.',
    });
  }
  for (const node of nodes) {
    if (node.type === 'OUTPUT') {
      continue;
    }
    const degree = degrees.get(node.id) ?? { incoming: 0, outgoing: 0 };
    if (degree.outgoing === 0) {
      const label = (node.data as { label?: string } | undefined)?.label ?? node.id;
      issues.push({
        severity: 'WARNING',
        code: 'UNCONNECTED_NODE',
        message: `"${label}" is not connected to anything, so its result is discarded.`,
        nodeId: node.id,
      });
    }
  }

  return issues;
}

function labelOf(node: Node): string {
  return (node.data as { label?: string } | undefined)?.label ?? node.id;
}

/**
 * Checks every Prompt Template statically: it must not be empty, and each `{{variable}}`
 * must be `input` or the key of one of the node's ancestors. Catching a typo here means it
 * is reported while editing, not halfway through a run.
 */
function validateTemplates(nodes: Node[], edges: Edge[]): GraphIssue[] {
  const issues: GraphIssue[] = [];
  for (const node of nodes) {
    if (node.type !== 'PROMPT_TEMPLATE') {
      continue;
    }
    const config = (node.data as { config?: Record<string, unknown> } | undefined)?.config ?? {};
    const template = String(config.template ?? '');
    if (template.trim().length === 0) {
      issues.push({
        severity: 'ERROR',
        code: 'EMPTY_TEMPLATE',
        message: `Prompt template "${labelOf(node)}" is empty.`,
        nodeId: node.id,
      });
      continue;
    }
    const available = new Set(
      availableVariables(node.id, nodes, edges).map((name) => name.toLowerCase()),
    );
    for (const name of referencedVariables(template)) {
      if (!available.has(name.toLowerCase())) {
        issues.push({
          severity: 'ERROR',
          code: 'UNKNOWN_TEMPLATE_VARIABLE',
          message: `Prompt template "${labelOf(node)}" uses {{${name}}}, but no upstream node has that key.`,
          nodeId: node.id,
        });
      }
    }
  }
  return issues;
}

/** Convenience: are there any blocking errors? */
export function hasBlockingErrors(issues: GraphIssue[]): boolean {
  return issues.some((issue) => issue.severity === 'ERROR');
}

/**
 * Computes the execution order the backend will use.
 *
 * <p>Uses Kahn's algorithm with a deterministic tie-break on node id, matching the
 * server's ordering closely enough for the UI to preview it.
 */
export function computeExecutionOrder(nodes: Node[], edges: Edge[]): string[] {
  const inDegree = new Map<string, number>();
  const adjacency = new Map<string, string[]>();
  for (const node of nodes) {
    inDegree.set(node.id, 0);
    adjacency.set(node.id, []);
  }
  for (const edge of edges) {
    if (!inDegree.has(edge.source) || !inDegree.has(edge.target)) {
      continue;
    }
    adjacency.get(edge.source)!.push(edge.target);
    inDegree.set(edge.target, (inDegree.get(edge.target) ?? 0) + 1);
  }

  // Same tie-break as the server: ascending node key, then id. Ordinal (UTF-16) comparison,
  // like Java's String.compareTo — localeCompare would order "B" and "a" differently.
  const keys = new Map(nodes.map((node) => [node.id, keyOf(node)]));
  const ordinal = (a: string, b: string): number => (a < b ? -1 : a > b ? 1 : 0);
  const compare = (a: string, b: string): number =>
    ordinal(keys.get(a) ?? a, keys.get(b) ?? b) || ordinal(a, b);
  const ready = nodes
    .filter((node) => (inDegree.get(node.id) ?? 0) === 0)
    .map((node) => node.id)
    .sort(compare);
  const ordered: string[] = [];

  while (ready.length > 0) {
    const current = ready.shift()!;
    ordered.push(current);
    for (const next of adjacency.get(current) ?? []) {
      const remaining = (inDegree.get(next) ?? 0) - 1;
      inDegree.set(next, remaining);
      if (remaining === 0) {
        ready.push(next);
        ready.sort(compare);
      }
    }
  }

  return ordered.length === nodes.length ? ordered : [];
}

/** Converts the canvas graph into the API payload. */
export function graphToApi(nodes: Node[], edges: Edge[]): { nodes: ApiNode[]; edges: ApiEdge[] } {
  return {
    nodes: nodes.map((node) => {
      const data = node.data as { label?: string; config?: Record<string, unknown> } | undefined;
      return {
        id: node.id,
        key: keyOf(node),
        type: node.type as NodeType,
        label: data?.label ?? node.id,
        position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
        config: data?.config ?? {},
      };
    }),
    edges: edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourceHandle ?? null,
      targetHandle: edge.targetHandle ?? null,
    })),
  };
}
