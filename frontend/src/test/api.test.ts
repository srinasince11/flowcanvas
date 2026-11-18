import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ApiError, api, fromApiWorkflow, toApiEdges, toApiNodes } from '../api/client';
import type { ApiWorkflow } from '../types';

function mockFetch(payload: unknown, init: { ok?: boolean; status?: number } = {}) {
  const status = init.status ?? 200;
  return vi.fn().mockResolvedValue({
    ok: init.ok ?? status < 400,
    status,
    json: async () => payload,
  });
}

describe('api client', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('requests the workflow list with pagination parameters', async () => {
    const fetchMock = mockFetch({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 });
    vi.stubGlobal('fetch', fetchMock);

    await api.listWorkflows('my flow');

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/workflows?');
    expect(url).toContain('page=0');
    expect(url).toContain('size=50');
    expect(url).toContain('search=my+flow');
  });

  it('omits the search parameter when the term is blank', async () => {
    const fetchMock = mockFetch({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 });
    vi.stubGlobal('fetch', fetchMock);

    await api.listWorkflows('   ');

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).not.toContain('search=');
  });

  it('turns a problem response into a typed ApiError with the server code', async () => {
    const fetchMock = mockFetch(
      { title: 'Workflow not found', detail: 'Workflow not found: abc', code: 'WORKFLOW_NOT_FOUND' },
      { ok: false, status: 404 },
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(api.getWorkflow('abc')).rejects.toBeInstanceOf(ApiError);

    try {
      await api.getWorkflow('abc');
    } catch (error) {
      const apiError = error as ApiError;
      expect(apiError.status).toBe(404);
      expect(apiError.code).toBe('WORKFLOW_NOT_FOUND');
      expect(apiError.message).toContain('Workflow not found');
    }
  });

  it('reports a network failure with a helpful message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    try {
      await api.listWorkflows();
      expect.unreachable('should have thrown');
    } catch (error) {
      const apiError = error as ApiError;
      expect(apiError.isNetworkError).toBe(true);
      expect(apiError.message).toContain('backend running');
    }
  });

  it('sends the graph in the body when creating a workflow', async () => {
    const fetchMock = mockFetch({ id: 'w1' });
    vi.stubGlobal('fetch', fetchMock);

    await api.createWorkflow({
      name: 'Test',
      nodes: [],
      edges: [],
    });

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/workflows');
    expect(init.method).toBe('POST');
    const body = JSON.parse(init.body as string);
    expect(body.name).toBe('Test');
    expect(body.nodes).toEqual([]);
  });

  it('uses DELETE for removing a workflow', async () => {
    const fetchMock = mockFetch(undefined, { status: 204 });
    vi.stubGlobal('fetch', fetchMock);

    await api.deleteWorkflow('w1');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/v1/workflows/w1');
    expect(init.method).toBe('DELETE');
  });
});

describe('payload conversion', () => {
  it('converts React Flow nodes into API nodes', () => {
    const result = toApiNodes([
      {
        id: 'n1',
        type: 'TEXT_INPUT',
        position: { x: 5, y: 9 },
        data: { label: 'Input', config: { text: 'x' } },
      },
    ]);

    expect(result).toEqual([
      {
        id: 'n1',
        key: 'n1',
        type: 'TEXT_INPUT',
        label: 'Input',
        position: { x: 5, y: 9 },
        config: { text: 'x' },
      },
    ]);
  });

  it('converts React Flow edges into API edges with null handles', () => {
    const result = toApiEdges([{ id: 'e1', source: 'a', target: 'b' }]);

    expect(result).toEqual([
      { id: 'e1', source: 'a', target: 'b', sourceHandle: null, targetHandle: null },
    ]);
  });

  it('converts an API workflow back into React Flow nodes and edges', () => {
    const workflow: ApiWorkflow = {
      id: 'w1',
      name: 'Round trip',
      status: 'DRAFT',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      nodes: [
        {
          id: 'n1',
          key: 'n1',
          type: 'TEXT_INPUT',
          label: 'Input',
          position: { x: 1, y: 2 },
          config: { text: 'hello' },
        },
      ],
      edges: [],
    };

    const { nodes, edges } = fromApiWorkflow(workflow);

    expect(nodes).toHaveLength(1);
    expect(nodes[0]?.id).toBe('n1');
    expect(nodes[0]?.position).toEqual({ x: 1, y: 2 });
    expect((nodes[0]?.data as { label: string }).label).toBe('Input');
    expect(edges).toHaveLength(0);
  });
});
