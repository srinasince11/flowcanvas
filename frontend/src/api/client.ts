import type {
  ApiEdge,
  ApiExample,
  ApiExecution,
  ApiExecutionAccepted,
  ApiExecutionSummary,
  ApiIssue,
  ApiNode,
  ApiPage,
  ApiValidationResult,
  ApiWorkflow,
  ApiWorkflowSummary,
} from '../types';
import type { Edge, Node } from '@xyflow/react';
import { graphToApi } from '../nodes/graph';

/**
 * The API client.
 *
 * <p>Every call funnels through {@link request}, which centralises three concerns:
 * <ul>
 *   <li>base URL handling (relative by default so the dev proxy and nginx both work),</li>
 *   <li>turning non-2xx responses into a typed {@link ApiError} that carries the
 *       server's {@code code} so the UI can react to specific problems,</li>
 *   <li>network-failure handling, so no caller ever has to write a try/catch around
 *       {@code fetch}.</li>
 * </ul>
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly detail?: string;
  /** Per-node problems, when the server rejected a graph (e.g. `GRAPH_NOT_STORABLE`). */
  readonly issues: ApiIssue[];

  constructor(message: string, status: number, code?: string, detail?: string, issues: ApiIssue[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.issues = issues;
  }

  /** True when the request failed because the backend was unreachable. */
  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        ...(init?.headers ?? {}),
      },
    });
  } catch (error) {
    throw new ApiError(
      'Could not reach the FlowCanvas API. Is the backend running on port 8080?',
      0,
      'NETWORK_ERROR',
      error instanceof Error ? error.message : String(error),
    );
  }

  if (!response.ok) {
    let problem: { title?: string; detail?: string; code?: string; issues?: ApiIssue[] } = {};
    try {
      problem = (await response.json()) as typeof problem;
    } catch {
      // The body was not JSON; fall through to the generic message below.
    }
    throw new ApiError(
      problem.detail ?? problem.title ?? `Request failed with status ${response.status}`,
      response.status,
      problem.code,
      problem.detail,
      Array.isArray(problem.issues) ? problem.issues : [],
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** The REST surface of the FlowCanvas backend. */
export const api = {
  listWorkflows(search?: string, page = 0, size = 50): Promise<ApiPage<ApiWorkflowSummary>> {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (search?.trim()) {
      params.set('search', search.trim());
    }
    return request<ApiPage<ApiWorkflowSummary>>(`/api/v1/workflows?${params.toString()}`);
  },

  getWorkflow(id: string): Promise<ApiWorkflow> {
    return request<ApiWorkflow>(`/api/v1/workflows/${id}`);
  },

  createWorkflow(payload: {
    name: string;
    description?: string;
    status?: string;
    nodes: ApiNode[];
    edges: ApiEdge[];
  }): Promise<ApiWorkflow> {
    return request<ApiWorkflow>('/api/v1/workflows', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
  },

  updateWorkflow(
    id: string,
    payload: {
      name: string;
      description?: string;
      status?: string;
      nodes: ApiNode[];
      edges: ApiEdge[];
    },
  ): Promise<ApiWorkflow> {
    return request<ApiWorkflow>(`/api/v1/workflows/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    });
  },

  deleteWorkflow(id: string): Promise<void> {
    return request<void>(`/api/v1/workflows/${id}`, { method: 'DELETE' });
  },

  validateGraph(nodes: ApiNode[], edges: ApiEdge[]): Promise<ApiValidationResult> {
    return request<ApiValidationResult>('/api/v1/workflows/validate', {
      method: 'POST',
      body: JSON.stringify({ nodes, edges }),
    });
  },

  listExamples(): Promise<ApiExample[]> {
    return request<ApiExample[]>('/api/v1/workflows/examples');
  },

  executeWorkflow(id: string): Promise<ApiExecutionAccepted> {
    return request<ApiExecutionAccepted>(`/api/v1/workflows/${id}/execute`, { method: 'POST' });
  },

  getExecution(id: string): Promise<ApiExecution> {
    return request<ApiExecution>(`/api/v1/executions/${id}`);
  },

  listExecutions(workflowId: string): Promise<ApiExecutionSummary[]> {
    return request<ApiExecutionSummary[]>(`/api/v1/workflows/${workflowId}/executions`);
  },
};

/**
 * Converts React Flow nodes into the API payload shape.
 *
 * <p>Thin wrappers over {@link graphToApi}, kept so call sites that only have one half of
 * the graph read naturally. There is exactly one mapping implementation.
 */
export function toApiNodes(nodes: Node[]): ApiNode[] {
  return graphToApi(nodes, []).nodes;
}

/** Converts React Flow edges into the API payload shape. */
export function toApiEdges(edges: Edge[]): ApiEdge[] {
  return graphToApi([], edges).edges;
}

/** Converts an API graph (a saved workflow or an example) into React Flow nodes and edges. */
function fromApiGraph(graph: { nodes: ApiNode[]; edges: ApiEdge[] }): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = graph.nodes.map((node) => ({
    id: node.id,
    type: node.type,
    position: node.position,
    data: { key: node.key, label: node.label, config: node.config, nodeType: node.type },
  }));
  const edges: Edge[] = graph.edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle ?? undefined,
    targetHandle: edge.targetHandle ?? undefined,
  }));
  return { nodes, edges };
}

/** Converts an API workflow into React Flow nodes and edges. */
export function fromApiWorkflow(workflow: ApiWorkflow): { nodes: Node[]; edges: Edge[] } {
  return fromApiGraph(workflow);
}

/** Converts an example graph into React Flow nodes and edges. */
export function fromApiExample(example: ApiExample): { nodes: Node[]; edges: Edge[] } {
  return fromApiGraph(example);
}
