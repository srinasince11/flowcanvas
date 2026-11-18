import { describe, expect, it } from 'vitest';
import type { Edge, Node } from '@xyflow/react';
import {
  computeExecutionOrder,
  detectCycle,
  graphToApi,
  hasBlockingErrors,
  validateGraph,
} from '../nodes/graph';
import { availableVariables, referencedVariables, suggestKey } from '../nodes/keys';

/** Builds a React Flow node with the data shape the app uses. */
function makeNode(id: string, type: string, x = 0, y = 0): Node {
  return {
    id,
    type,
    position: { x, y },
    data: {
      label: id,
      config: type === 'PROMPT_TEMPLATE' ? { template: '{{input}}' } : {},
      nodeType: type,
    },
  };
}

function makeEdge(id: string, source: string, target: string): Edge {
  return { id, source, target };
}

describe('detectCycle', () => {
  it('returns an empty array for an acyclic chain', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT'), makeNode('b', 'PROMPT_TEMPLATE'), makeNode('c', 'OUTPUT')];
    const edges = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'c')];

    expect(detectCycle(nodes, edges)).toEqual([]);
  });

  it('detects a two-node cycle', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT'), makeNode('b', 'PROMPT_TEMPLATE')];
    const edges = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'a')];

    const cycle = detectCycle(nodes, edges);

    expect(cycle.length).toBeGreaterThan(0);
    expect(cycle).toContain('a');
    expect(cycle).toContain('b');
  });

  it('detects a self-loop', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT')];
    const edges = [makeEdge('e1', 'a', 'a')];

    expect(detectCycle(nodes, edges).length).toBeGreaterThan(0);
  });

  it('does not treat a diamond as a cycle', () => {
    const nodes = [
      makeNode('input', 'TEXT_INPUT'),
      makeNode('left', 'PROMPT_TEMPLATE'),
      makeNode('right', 'AI_MODEL'),
      makeNode('out', 'OUTPUT'),
    ];
    const edges = [
      makeEdge('e1', 'input', 'left'),
      makeEdge('e2', 'input', 'right'),
      makeEdge('e3', 'left', 'out'),
      makeEdge('e4', 'right', 'out'),
    ];

    expect(detectCycle(nodes, edges)).toEqual([]);
  });

  it('detects a cycle behind a valid prefix', () => {
    const nodes = [
      makeNode('input', 'TEXT_INPUT'),
      makeNode('a', 'PROMPT_TEMPLATE'),
      makeNode('b', 'AI_MODEL'),
    ];
    const edges = [
      makeEdge('e1', 'input', 'a'),
      makeEdge('e2', 'a', 'b'),
      makeEdge('e3', 'b', 'a'),
    ];

    const cycle = detectCycle(nodes, edges);

    expect(cycle).toContain('a');
    expect(cycle).toContain('b');
  });
});

describe('validateGraph', () => {
  it('flags an empty graph as an error', () => {
    const issues = validateGraph([], []);

    expect(issues).toHaveLength(1);
    expect(issues[0]?.code).toBe('GRAPH_EMPTY');
    expect(hasBlockingErrors(issues)).toBe(true);
  });

  it('accepts a valid linear workflow', () => {
    const nodes = [
      makeNode('input', 'TEXT_INPUT', 0, 0),
      makeNode('tpl', 'PROMPT_TEMPLATE', 200, 0),
      makeNode('out', 'OUTPUT', 400, 0),
    ];
    const edges = [makeEdge('e1', 'input', 'tpl'), makeEdge('e2', 'tpl', 'out')];

    const issues = validateGraph(nodes, edges);

    expect(hasBlockingErrors(issues)).toBe(false);
  });

  it('rejects a Text Input node with an incoming connection', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT'), makeNode('b', 'TEXT_INPUT')];
    const edges = [makeEdge('e1', 'a', 'b')];

    const issues = validateGraph(nodes, edges);

    expect(issues.some((issue) => issue.code === 'TOO_MANY_INPUTS')).toBe(true);
    expect(hasBlockingErrors(issues)).toBe(true);
  });

  it('rejects an Output node with an outgoing connection', () => {
    const nodes = [makeNode('a', 'OUTPUT'), makeNode('b', 'OUTPUT')];
    const edges = [makeEdge('e1', 'a', 'b')];

    const issues = validateGraph(nodes, edges);

    expect(issues.some((issue) => issue.code === 'TOO_MANY_OUTPUTS')).toBe(true);
  });

  it('requires an input on a Prompt Template node', () => {
    const nodes = [makeNode('tpl', 'PROMPT_TEMPLATE')];
    const edges: Edge[] = [];

    const issues = validateGraph(nodes, edges);

    expect(issues.some((issue) => issue.code === 'MISSING_INPUT')).toBe(true);
  });

  it('reports a cycle as an error', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT'), makeNode('b', 'PROMPT_TEMPLATE')];
    const edges = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'a')];

    const issues = validateGraph(nodes, edges);

    expect(issues.some((issue) => issue.code === 'CYCLE_DETECTED')).toBe(true);
  });

  it('reports a duplicate connection', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT'), makeNode('b', 'PROMPT_TEMPLATE')];
    const edges = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'a', 'b')];

    const issues = validateGraph(nodes, edges);

    expect(issues.some((issue) => issue.code === 'DUPLICATE_EDGE')).toBe(true);
  });

  it('warns but does not block when there is no Output node', () => {
    const nodes = [makeNode('input', 'TEXT_INPUT'), makeNode('tpl', 'PROMPT_TEMPLATE')];
    const edges = [makeEdge('e1', 'input', 'tpl')];

    const issues = validateGraph(nodes, edges);

    expect(issues.some((issue) => issue.code === 'NO_OUTPUT_NODE')).toBe(true);
    expect(hasBlockingErrors(issues)).toBe(false);
  });

  it('allows an Output node to join several branches', () => {
    const nodes = [
      makeNode('input', 'TEXT_INPUT'),
      makeNode('left', 'PROMPT_TEMPLATE'),
      makeNode('right', 'AI_MODEL'),
      makeNode('out', 'OUTPUT'),
    ];
    const edges = [
      makeEdge('e1', 'input', 'left'),
      makeEdge('e2', 'input', 'right'),
      makeEdge('e3', 'left', 'out'),
      makeEdge('e4', 'right', 'out'),
    ];

    const issues = validateGraph(nodes, edges);

    expect(hasBlockingErrors(issues)).toBe(false);
  });
});

describe('computeExecutionOrder', () => {
  it('orders producers before consumers', () => {
    const nodes = [
      makeNode('input', 'TEXT_INPUT'),
      makeNode('tpl', 'PROMPT_TEMPLATE'),
      makeNode('out', 'OUTPUT'),
    ];
    const edges = [makeEdge('e1', 'input', 'tpl'), makeEdge('e2', 'tpl', 'out')];

    expect(computeExecutionOrder(nodes, edges)).toEqual(['input', 'tpl', 'out']);
  });

  it('places a node after every producer in a diamond', () => {
    const nodes = [
      makeNode('input', 'TEXT_INPUT'),
      makeNode('left', 'PROMPT_TEMPLATE'),
      makeNode('right', 'AI_MODEL'),
      makeNode('out', 'OUTPUT'),
    ];
    const edges = [
      makeEdge('e1', 'input', 'left'),
      makeEdge('e2', 'input', 'right'),
      makeEdge('e3', 'left', 'out'),
      makeEdge('e4', 'right', 'out'),
    ];

    const order = computeExecutionOrder(nodes, edges);

    expect(order.indexOf('input')).toBeLessThan(order.indexOf('left'));
    expect(order.indexOf('input')).toBeLessThan(order.indexOf('right'));
    expect(order.indexOf('left')).toBeLessThan(order.indexOf('out'));
    expect(order.indexOf('right')).toBeLessThan(order.indexOf('out'));
  });

  it('returns an empty order for a cyclic graph', () => {
    const nodes = [makeNode('a', 'TEXT_INPUT'), makeNode('b', 'PROMPT_TEMPLATE')];
    const edges = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'a')];

    expect(computeExecutionOrder(nodes, edges)).toEqual([]);
  });
});

describe('graphToApi', () => {
  it('converts nodes and edges into the API payload shape', () => {
    const nodes = [
      {
        id: 'n1',
        type: 'TEXT_INPUT',
        position: { x: 10.4, y: 20.6 },
        data: { label: 'My input', config: { text: 'hi' }, nodeType: 'TEXT_INPUT' },
      },
    ];
    const edges = [{ id: 'e1', source: 'n1', target: 'n1', sourceHandle: 'out', targetHandle: 'in' }];

    const payload = graphToApi(nodes, edges);

    expect(payload.nodes[0]).toEqual({
      id: 'n1',
      key: 'n1',
      type: 'TEXT_INPUT',
      label: 'My input',
      position: { x: 10, y: 21 },
      config: { text: 'hi' },
    });
    expect(payload.edges[0]).toEqual({
      id: 'e1',
      source: 'n1',
      target: 'n1',
      sourceHandle: 'out',
      targetHandle: 'in',
    });
  });
});

describe('node keys', () => {
  function keyed(id: string, type: string, key: string, template?: string): Node {
    return {
      id,
      type,
      position: { x: 0, y: 0 },
      data: { key, label: id, config: template === undefined ? {} : { template }, nodeType: type },
    };
  }

  it('suggests the first free readable key for a node type', () => {
    const nodes = [keyed('a', 'PROMPT_TEMPLATE', 'prompt1'), keyed('b', 'PROMPT_TEMPLATE', 'Prompt2')];

    expect(suggestKey('PROMPT_TEMPLATE', nodes)).toBe('prompt3');
    expect(suggestKey('AI_MODEL', nodes)).toBe('model1');
  });

  it('extracts each referenced variable once, tolerating inner whitespace', () => {
    expect(referencedVariables('{{a}} and {{ b }} then {{a}}')).toEqual(['a', 'b']);
  });

  it('offers input plus every ancestor key, and nothing from other branches', () => {
    const nodes = [
      keyed('t', 'TEXT_INPUT', 'topic'),
      keyed('p', 'PROMPT_TEMPLATE', 'prompt', '{{input}}'),
      keyed('m', 'AI_MODEL', 'model'),
      keyed('other', 'TEXT_INPUT', 'unrelated'),
    ];
    const edges = [makeEdge('e1', 't', 'p'), makeEdge('e2', 'p', 'm')];

    expect(availableVariables('m', nodes, edges)).toEqual(['input', 'prompt', 'topic']);
  });

  it('sends the node key, not the id, in the API payload', () => {
    expect(graphToApi([keyed('uuid-1', 'TEXT_INPUT', 'topic')], []).nodes[0]!.key).toBe('topic');
  });

  it('rejects duplicate keys case-insensitively', () => {
    const issues = validateGraph(
      [keyed('a', 'TEXT_INPUT', 'topic'), keyed('b', 'TEXT_INPUT', 'Topic')],
      [],
    );
    expect(issues.some((issue) => issue.code === 'DUPLICATE_NODE_KEY')).toBe(true);
  });

  it('rejects a template variable that is not an upstream key', () => {
    const nodes = [
      keyed('t', 'TEXT_INPUT', 'topic'),
      keyed('x', 'TEXT_INPUT', 'sibling'),
      keyed('p', 'PROMPT_TEMPLATE', 'prompt', '{{topic}} {{sibling}}'),
      keyed('o', 'OUTPUT', 'out'),
    ];
    const edges = [makeEdge('e1', 't', 'p'), makeEdge('e2', 'p', 'o'), makeEdge('e3', 'x', 'o')];

    const unknown = validateGraph(nodes, edges).filter((i) => i.code === 'UNKNOWN_TEMPLATE_VARIABLE');

    expect(unknown).toHaveLength(1);
    expect(unknown[0]!.message).toContain('sibling');
  });

  it('lets a prompt template join two branches by key', () => {
    const nodes = [
      keyed('q', 'TEXT_INPUT', 'question'),
      keyed('c', 'TEXT_INPUT', 'context'),
      keyed('p', 'PROMPT_TEMPLATE', 'join', '{{question}} / {{context}}'),
      keyed('o', 'OUTPUT', 'out'),
    ];
    const edges = [makeEdge('e1', 'q', 'p'), makeEdge('e2', 'c', 'p'), makeEdge('e3', 'p', 'o')];

    expect(hasBlockingErrors(validateGraph(nodes, edges))).toBe(false);
  });

  it('requires an AI Model to have exactly one input, and an Output to have one', () => {
    const nodes = [
      keyed('a', 'TEXT_INPUT', 'a'),
      keyed('b', 'TEXT_INPUT', 'b'),
      keyed('m', 'AI_MODEL', 'model'),
      keyed('o', 'OUTPUT', 'out'),
    ];
    const issues = validateGraph(nodes, [makeEdge('e1', 'a', 'm'), makeEdge('e2', 'b', 'm')]);

    expect(issues.some((i) => i.code === 'TOO_MANY_INPUTS' && i.nodeId === 'm')).toBe(true);
    expect(issues.some((i) => i.code === 'MISSING_INPUT' && i.nodeId === 'o')).toBe(true);
  });

  it('breaks execution-order ties by key, exactly like the server', () => {
    const nodes = [
      keyed('root', 'TEXT_INPUT', 'root'),
      keyed('n1', 'PROMPT_TEMPLATE', 'c', '{{input}}'),
      keyed('n2', 'PROMPT_TEMPLATE', 'a', '{{input}}'),
      keyed('n3', 'PROMPT_TEMPLATE', 'b', '{{input}}'),
    ];
    const edges = [makeEdge('e1', 'root', 'n1'), makeEdge('e2', 'root', 'n2'), makeEdge('e3', 'root', 'n3')];

    expect(computeExecutionOrder(nodes, edges)).toEqual(['root', 'n2', 'n3', 'n1']);
  });
});
