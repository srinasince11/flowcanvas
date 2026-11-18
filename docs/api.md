# FlowCanvas — API Contract

Base URL: `/api/v1` · Content type: `application/json` · Auth: none (see [limitations](./architecture.md#deliberately-out-of-scope))

Every response is JSON. Errors use **RFC 7807** problem responses with an additional
stable `code` field, so a client can react to a specific problem without matching on
human-readable text.

---

## Conventions

| Rule | Detail |
|---|---|
| Identifiers | UUID v4. Node and edge ids are generated **by the client**; workflow and execution ids by the server. |
| Timestamps | ISO-8601 with offset, e.g. `2026-10-28T12:00:45.208446Z`. |
| Durations | Integer milliseconds. |
| Node types | `TEXT_INPUT`, `PROMPT_TEMPLATE`, `AI_MODEL`, `OUTPUT`. |
| Paging | `page` is 0-based; `size` is clamped to 1..100. |
| Errors | `application/problem+json` with `title`, `status`, `detail`, `code`. |

---

## Error codes

| `code` | Status | Meaning |
|---|---|---|
| `WORKFLOW_NOT_FOUND` | 404 | No workflow with that id. |
| `EXECUTION_NOT_FOUND` | 404 | No execution with that id. |
| `VALIDATION_FAILED` | 400 | A required field is missing or malformed. `errors[]` lists the fields. |
| `INVALID_ARGUMENT` | 400 | A value is syntactically fine but semantically wrong (e.g. an unknown status). |
| `INVALID_PARAMETER` | 400 | A path variable could not be parsed (e.g. a malformed UUID). |
| `MALFORMED_REQUEST` | 400 | The body could not be parsed at all. |
| `GRAPH_NOT_STORABLE` | 422 | The graph breaks a storage rule (duplicate or malformed node key, duplicate edge, self-loop). `issues[]` lists each problem with its `nodeId`/`edgeId`; nothing is written. |
| `CONFLICT` | 409 | A write lost a race with a concurrent change (e.g. a delete while saving). Reload and retry. |
| `INTERNAL_ERROR` | 500 | An unexpected server fault. The cause is logged server-side, never returned. |

---

## Workflow resource

### `GET /api/v1/workflows`

Lists saved workflows, newest-updated first.

**Query parameters**

| Name | Type | Default | Description |
|---|---|---|---|
| `search` | string | — | Case-insensitive match on name or description. |
| `page` | int | `0` | 0-based page index. |
| `size` | int | `20` | Page size (1..100). |

**200 OK**

```json
{
  "items": [
    {
      "id": "e1f152fa-34fe-45fb-a385-85eeb9255984",
      "name": "Customer Support Reply",
      "description": "Turns a raw customer message into a polished support reply.",
      "status": "PUBLISHED",
      "nodeCount": 4,
      "edgeCount": 3,
      "createdAt": "2026-10-28T12:00:45.208446Z",
      "updatedAt": "2026-10-28T12:00:45.208446Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

---

### `POST /api/v1/workflows`

Creates a workflow together with its graph.

**Request**

```json
{
  "name": "Customer Support Reply",
  "description": "Optional description",
  "status": "DRAFT",
  "nodes": [
    {
      "id": "9b1d5b7e-0f1a-4a3c-9c2e-1f0a2b3c4d5e",
      "key": "customerMessage",
      "type": "TEXT_INPUT",
      "label": "Customer Message",
      "position": { "x": 80, "y": 120 },
      "config": { "text": "Hi, my order #A-2213 arrived two days late." }
    }
  ],
  "edges": [
    {
      "id": "1c2d3e4f-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
      "source": "9b1d5b7e-0f1a-4a3c-9c2e-1f0a2b3c4d5e",
      "target": "9b1d5b7e-0f1a-4a3c-9c2e-1f0a2b3c4d5e",
      "sourceHandle": "out",
      "targetHandle": "in"
    }
  ]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | 1..120 chars. |
| `description` | string | no | ≤ 2000 chars. |
| `status` | string | no | `DRAFT` \| `PUBLISHED` \| `ARCHIVED`. Defaults to `DRAFT`. |
| `nodes[].id` | uuid | yes | Client-generated. Must be unique within the graph. |
| `nodes[].key` | string | yes | ≤ 64 chars. Used by `{{nodeKey}}` template placeholders. |
| `nodes[].type` | enum | yes | See the node type table. |
| `nodes[].label` | string | yes | ≤ 120 chars. Shown on the canvas. |
| `nodes[].position` | object | yes | `{ "x": number, "y": number }`, canvas pixels. |
| `nodes[].config` | object | no | Type-specific. See the node type table. |
| `edges[].id` | uuid | yes | Client-generated. |
| `edges[].source` / `edges[].target` | uuid | yes | Must reference nodes in the same graph. |
| `edges[].sourceHandle` / `targetHandle` | string | no | ≤ 64 chars. |

**201 Created** — the full workflow, `Location` header set.

**400 Bad Request** — `VALIDATION_FAILED` (field-level problems) or `INVALID_ARGUMENT`.

---

### `GET /api/v1/workflows/{id}`

Returns the workflow with its complete graph.

**200 OK** — same shape as the `POST` response.

**404 Not Found** — `WORKFLOW_NOT_FOUND`.

---

### `PUT /api/v1/workflows/{id}`

Updates the metadata and **replaces the whole graph**. Nodes and edges that are absent from
the request are deleted; edges pointing at nodes that are not in the request are ignored.

**Request** — identical to `POST`.

**200 OK** — the updated workflow.

---

### `DELETE /api/v1/workflows/{id}`

Deletes the workflow. Nodes, edges, executions and node executions are removed by
`ON DELETE CASCADE`.

**204 No Content** · **404 Not Found**

---

### `POST /api/v1/workflows/validate`

Validates a graph **without saving it**. The frontend calls this before every run.

**Request** — `{ "nodes": [...], "edges": [...] }`

**200 OK**

```json
{
  "valid": true,
  "issues": [],
  "executionOrder": [
    { "sequence": 1, "nodeId": "…", "nodeKey": "customerMessage", "label": "Customer Message", "type": "TEXT_INPUT" },
    { "sequence": 2, "nodeId": "…", "nodeKey": "supportPrompt",  "label": "Support Prompt",  "type": "PROMPT_TEMPLATE" }
  ]
}
```

When the graph is invalid, `valid` is `false`, `executionOrder` is empty, and `issues`
describes the problems:

```json
{
  "valid": false,
  "issues": [
    {
      "severity": "ERROR",
      "code": "CYCLE_DETECTED",
      "message": "The workflow contains a cycle: A → B → A.",
      "nodeId": "…",
      "edgeId": null
    }
  ],
  "executionOrder": []
}
```

#### Issue codes

| `code` | Severity | Meaning |
|---|---|---|
| `GRAPH_EMPTY` | ERROR | The graph has no nodes. |
| `CYCLE_DETECTED` | ERROR | A cycle exists; the message names the nodes in cycle order. |
| `SELF_LOOP` | ERROR | A node is connected to itself. |
| `EDGE_SOURCE_MISSING` / `EDGE_TARGET_MISSING` | ERROR | An edge references a node that is not in the graph. |
| `DUPLICATE_EDGE` | ERROR | Two nodes are connected more than once. |
| `INVALID_NODE_KEY` | ERROR | A node key is not 1–64 letters, digits, `_` or `-`. |
| `DUPLICATE_NODE_KEY` | ERROR | Two nodes share a key (case-insensitively). |
| `EMPTY_TEMPLATE` | ERROR | A Prompt Template has no template text. |
| `UNKNOWN_TEMPLATE_VARIABLE` | ERROR | A template uses `{{x}}` but no ancestor of that node has the key `x`. |
| `MISSING_INPUT` | ERROR | A node that needs an input has none. |
| `TOO_MANY_INPUTS` | ERROR | A node has more incoming edges than its type allows. |
| `TOO_MANY_OUTPUTS` | ERROR | A node has more outgoing edges than its type allows. |
| `MISSING_OUTPUT` | ERROR | A node needs at least one outgoing connection. |
| `NO_OUTPUT_NODE` | WARNING | The graph has no Output node. |
| `UNCONNECTED_NODE` | WARNING | A node's result is discarded because nothing consumes it. |

**Ordering guarantee:** errors come before warnings, and within the errors the root cause
comes first — `CYCLE_DETECTED` is always ahead of the `TOO_MANY_INPUTS` complaints it
causes.

---

### `GET /api/v1/workflows/examples`

Returns the built-in example graphs. They are also seeded into the database on first start.

**200 OK**

```json
[
  {
    "name": "Customer Support Reply",
    "description": "Turns a raw customer message into a polished support reply.",
    "nodes": [ /* … */ ],
    "edges": [ /* … */ ]
  }
]
```

---

### `POST /api/v1/workflows/{id}/execute`

Starts a run of a stored workflow.

**202 Accepted**

```json
{
  "executionId": "fdb8e51f-547e-44fb-be70-10d554c519c8",
  "workflowId": "e1f152fa-34fe-45fb-a385-85eeb9255984",
  "status": "RUNNING"
}
```

The run executes asynchronously. Poll `GET /api/v1/executions/{executionId}` for progress.

**404 Not Found** — `WORKFLOW_NOT_FOUND`.

---

### `GET /api/v1/workflows/{id}/executions`

Run history, newest first (up to 20 runs).

**200 OK**

```json
[
  {
    "id": "fdb8e51f-547e-44fb-be70-10d554c519c8",
    "status": "SUCCESS",
    "startedAt": "2026-10-28T12:00:45.208446Z",
    "finishedAt": "2026-10-28T12:00:45.214301Z",
    "durationMs": 3,
    "error": null
  }
]
```

---

## Execution resource

### `GET /api/v1/executions/{id}`

Returns a run and its per-node results.

**200 OK**

```json
{
  "id": "fdb8e51f-547e-44fb-be70-10d554c519c8",
  "workflowId": "e1f152fa-34fe-45fb-a385-85eeb9255984",
  "workflowName": "Customer Support Reply",
  "status": "SUCCESS",
  "startedAt": "2026-10-28T12:00:45.208446Z",
  "finishedAt": "2026-10-28T12:00:45.214301Z",
  "durationMs": 3,
  "error": null,
  "nodeExecutions": [
    {
      "nodeId": "9b1d5b7e-0f1a-4a3c-9c2e-1f0a2b3c4d5e",
      "nodeKey": "customerMessage",
      "label": "Customer Message",
      "type": "TEXT_INPUT",
      "sequence": 1,
      "status": "SUCCESS",
      "output": "Hi, my order #A-2213 arrived two days late.",
      "error": null,
      "durationMs": 0,
      "startedAt": "2026-10-28T12:00:45.209001Z",
      "finishedAt": "2026-10-28T12:00:45.209120Z"
    }
  ]
}
```

#### Run status

`PENDING` → `RUNNING` → `SUCCESS` | `FAILED`

#### Node status

| Status | Meaning |
|---|---|
| `PENDING` | Not started yet. |
| `RUNNING` | Currently executing. |
| `SUCCESS` | Completed; `output` holds the result. |
| `FAILED` | Failed; `error` holds the reason. |
| `SKIPPED` | An ancestor failed, so this node was not attempted. |

`sequence` is the 1-based position in the resolved execution order — it makes the
dependency ordering directly observable through the API.

**404 Not Found** — `EXECUTION_NOT_FOUND`.

---

## Node types

| Type | Incoming | Outgoing | `config` | Output |
|---|---|---|---|---|
| `TEXT_INPUT` | 0 | 0..n | `{ "text": string }` | The literal text. |
| `PROMPT_TEMPLATE` | 1..n | 0..n | `{ "template": string }` | The rendered template. |
| `AI_MODEL` | exactly 1 | 0..n | `{ "model": string, "systemPrompt": string, "temperature": number }` | The provider's completion. |
| `OUTPUT` | 1..n | 0 | `{ "format": "text" \| "json" }` | The collected input(s). |

### Template placeholders

| Placeholder | Resolves to |
|---|---|
| `{{input}}` | The node's direct input. When the node joins several branches, every input in key order, separated by a blank line. |
| `{{nodeKey}}` | The output of an **ancestor** of the node, addressed by its key (case-insensitive). |

Variables are **lexically scoped**: a template sees its own ancestors and nothing else. A
node on an unrelated branch is invisible even if it happens to run earlier, so what a
template means never depends on execution-order tie-breaking.

References are checked **statically** by `/validate` and before every run
(`UNKNOWN_TEMPLATE_VARIABLE`), so a typo is reported while editing — not halfway through a
run, after paid model calls have already been made.

---

## Operational endpoints

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness/readiness probe. Returns `{"status":"UP"}`. |
| `GET /actuator/info` | Build information. |
| `GET /actuator/metrics` | Micrometer metrics. |
| `GET /swagger-ui.html` | Interactive OpenAPI documentation. |
| `GET /v3/api-docs` | The OpenAPI document. |

---

## Worked example

```bash
# 1. Create a workflow
curl -X POST http://localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "Demo",
    "nodes": [
      { "id":"11111111-1111-1111-1111-111111111111","key":"in","type":"TEXT_INPUT","label":"Input","position":{"x":0,"y":0},"config":{"text":"hello"} },
      { "id":"22222222-2222-2222-2222-222222222222","key":"tpl","type":"PROMPT_TEMPLATE","label":"Template","position":{"x":200,"y":0},"config":{"template":"Say hi to {{input}}"} },
      { "id":"33333333-3333-3333-3333-333333333333","key":"out","type":"OUTPUT","label":"Output","position":{"x":400,"y":0},"config":{"format":"text"} }
    ],
    "edges": [
      { "id":"44444444-4444-4444-4444-444444444440","source":"11111111-1111-1111-1111-111111111111","target":"22222222-2222-2222-2222-222222222222" },
      { "id":"44444444-4444-4444-4444-444444444441","source":"22222222-2222-2222-2222-222222222222","target":"33333333-3333-3333-3333-333333333333" }
    ]
  }'

# 2. Validate it
curl -X POST http://localhost:8080/api/v1/workflows/validate \
  -H 'Content-Type: application/json' -d @graph.json
# → { "valid": true, "issues": [], "executionOrder": [ … ] }

# 3. Run it
curl -X POST http://localhost:8080/api/v1/workflows/$ID/execute
# → 202 { "executionId": "…", "status": "RUNNING" }

# 4. Poll for the result
curl http://localhost:8080/api/v1/executions/$EXECUTION_ID
# → { "status": "SUCCESS", "nodeExecutions": [ { "sequence": 1, … }, … ] }
```
