/**
 * The shared type vocabulary of the FlowCanvas frontend.
 *
 * These mirror the backend DTOs one-to-one. Keeping them in one file makes the API
 * contract easy to review and keeps accidental drift visible in a single diff.
 */

/** The node taxonomy, matching `com.flowcanvas.domain.NodeType` on the server. */
export type NodeType = 'TEXT_INPUT' | 'PROMPT_TEMPLATE' | 'AI_MODEL' | 'OUTPUT';

/** Lifecycle of a workflow run. */
export type ExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';

/** Lifecycle of a single node inside a run. */
export type NodeExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED'
  | 'SKIPPED';

/** Severity of a validation issue. */
export type IssueSeverity = 'ERROR' | 'WARNING';

/** A node as exchanged with the API. */
export interface ApiNode {
  id: string;
  key: string;
  type: NodeType;
  label: string;
  position: { x: number; y: number };
  config: Record<string, unknown>;
}

/** An edge as exchanged with the API. */
export interface ApiEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
}

/** A workflow as exchanged with the API. */
export interface ApiWorkflow {
  id: string;
  name: string;
  description?: string | null;
  status: string;
  nodes: ApiNode[];
  edges: ApiEdge[];
  createdAt: string;
  updatedAt: string;
}

/** A workflow entry in the library list. */
export interface ApiWorkflowSummary {
  id: string;
  name: string;
  description?: string | null;
  status: string;
  nodeCount: number;
  edgeCount: number;
  createdAt: string;
  updatedAt: string;
}

/** A paged list response. */
export interface ApiPage<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** A validation problem. */
export interface ApiIssue {
  severity: IssueSeverity;
  code: string;
  message: string;
  nodeId?: string | null;
  edgeId?: string | null;
}

/** One position in the resolved execution order. */
export interface ApiExecutionOrderEntry {
  sequence: number;
  nodeId: string;
  nodeKey: string;
  label: string;
  type: NodeType;
}

/** The result of validating a graph. */
export interface ApiValidationResult {
  valid: boolean;
  issues: ApiIssue[];
  executionOrder: ApiExecutionOrderEntry[];
}

/** A per-node result within a run. */
export interface ApiNodeExecution {
  nodeId: string;
  nodeKey: string;
  label: string;
  type: NodeType;
  sequence: number;
  status: NodeExecutionStatus;
  output?: string | null;
  error?: string | null;
  durationMs?: number | null;
  startedAt: string;
  finishedAt?: string | null;
}

/** A workflow run. */
export interface ApiExecution {
  id: string;
  workflowId: string;
  workflowName?: string | null;
  status: ExecutionStatus;
  startedAt: string;
  finishedAt?: string | null;
  durationMs?: number | null;
  error?: string | null;
  nodeExecutions: ApiNodeExecution[];
}

/** The response to the execute endpoint. */
export interface ApiExecutionAccepted {
  executionId: string;
  workflowId: string;
  status: ExecutionStatus;
}

/** A run history row. */
export interface ApiExecutionSummary {
  id: string;
  status: ExecutionStatus;
  startedAt: string;
  finishedAt?: string | null;
  durationMs?: number | null;
  error?: string | null;
}

/** A ready-made example graph. */
export interface ApiExample {
  name: string;
  description: string;
  nodes: ApiNode[];
  edges: ApiEdge[];
}

/** An RFC 7807 problem response from the API. */
export interface ApiProblem {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  instance?: string;
}
