# FlowCanvas — Database Schema

PostgreSQL 15+ (developed against 15, deployed against 16). The schema is created and
versioned by **Flyway**; Hibernate runs with `ddl-auto: validate`, so the application never
alters the schema — it only checks that the code and the schema agree.

The authoritative definition is
[`backend/src/main/resources/db/migration/V1__init_schema.sql`](../backend/src/main/resources/db/migration/V1__init_schema.sql).

---

## Entity-relationship overview

```
┌──────────────────────┐
│      workflows       │  id PK, name, description, status, created_at, updated_at
└──────┬───────────────┘
       │ 1
       │
       ├─────────────< ┌──────────────────────┐
       │      N      │    workflow_nodes     │  id PK, workflow_id FK, node_key, type,
       │              │                      │  label, position_x, position_y, config jsonb
       │              └──────────┬───────────┘
       │                         │ 1
       │        ┌────────────────┴────────────────┐
       │        │ N                              │ N
       │  ┌─────▼──────────────────────┐        │
       │  │      workflow_edges         │────────┘
       │  │ id PK, workflow_id FK,      │  source_id → workflow_nodes.id
       │  │ source_id FK, target_id FK, │  target_id → workflow_nodes.id
       │  │ source_handle, target_handle│  source_handle / target_handle
       │  └─────────────────────────────┘
       │
       │ 1
       │
       └─────────────< ┌──────────────────────┐
              N       │  workflow_executions │  id PK, workflow_id FK, status,
                       │                      │  started_at, finished_at, duration_ms, error
                       └──────────┬───────────┘
                                  │ 1
                                  │
                       ┌──────────▼───────────┐
                       │   node_executions    │  id PK, execution_id FK, node_id,
                       │                      │  node_key, node_type, label, status,
                       │                      │  sequence, output, error,
                       │                      │  started_at, finished_at, duration_ms
                       └──────────────────────┘
```

---

## Table reference

### `workflows`

The aggregate root. One row per saved workflow.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `uuid` | **PK** | Server-generated identifier. |
| `name` | `varchar(120)` | `NOT NULL` | Display name. |
| `description` | `text` | nullable | Free-text description. |
| `status` | `varchar(20)` | `NOT NULL`, `DEFAULT 'DRAFT'`, `CHECK IN ('DRAFT','PUBLISHED','ARCHIVED')` | Lifecycle state. |
| `created_at` | `timestamptz` | `NOT NULL` | Creation time. |
| `updated_at` | `timestamptz` | `NOT NULL` | Last modification time. |

**Indexes:** `idx_workflows_updated_at (updated_at DESC)` — the library list is ordered by
most-recently-updated.

---

### `workflow_nodes`

One row per node on the canvas.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `uuid` | **PK** | **Client-generated** identifier; also the React Flow node id. |
| `workflow_id` | `uuid` | `NOT NULL`, **FK → `workflows(id)` `ON DELETE CASCADE`** | Owning workflow. |
| `node_key` | `varchar(64)` | `NOT NULL` | Short key, unique within the workflow. Used by `{{nodeKey}}` placeholders. |
| `type` | `varchar(32)` | `NOT NULL`, `CHECK IN ('TEXT_INPUT','PROMPT_TEMPLATE','AI_MODEL','OUTPUT')` | Node kind. |
| `label` | `varchar(120)` | `NOT NULL` | Human-readable name shown on the canvas. |
| `position_x` | `double precision` | `NOT NULL` | Canvas X coordinate. |
| `position_y` | `double precision` | `NOT NULL` | Canvas Y coordinate. |
| `config` | `jsonb` | `NOT NULL`, `DEFAULT '{}'` | Type-specific configuration. |

**Indexes:** `idx_workflow_nodes_workflow (workflow_id)`;
`UNIQUE (workflow_id, node_key)` as `workflow_nodes_key_uq`.

**Why client-generated ids?** The browser mints the UUID, so a graph round-trips through the
API without the server remapping identifiers, and React Flow can keep using its own handles
as stable keys. The workflow id stays server-generated.

**Why `jsonb` and not columns?** The configuration shape belongs to the node type, not to
the relational schema. Adding a `systemPrompt` field to the AI Model node must not require a
database migration, and the schema must not be reshaped every time the node taxonomy grows.
`jsonb` also supports indexing and containment queries later if a feature needs them.

---

### `workflow_edges`

One row per connection between two nodes.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `uuid` | **PK** | Client-generated identifier. |
| `workflow_id` | `uuid` | `NOT NULL`, **FK → `workflows(id)` `ON DELETE CASCADE`** | Owning workflow. |
| `source_id` | `uuid` | `NOT NULL`, **FK → `workflow_nodes(id)` `ON DELETE CASCADE`** | Upstream node. |
| `target_id` | `uuid` | `NOT NULL`, **FK → `workflow_nodes(id)` `ON DELETE CASCADE`** | Downstream node. |
| `source_handle` | `varchar(64)` | nullable | React Flow source handle id. |
| `target_handle` | `varchar(64)` | nullable | React Flow target handle id. |

**Constraints:** `workflow_edges_no_self_loop CHECK (source_id <> target_id)` — a node
cannot connect to itself; `workflow_edges_uq UNIQUE (workflow_id, source_id, target_id)` —
the same pair of nodes cannot be connected twice.

**Indexes:** `idx_workflow_edges_workflow`, `idx_workflow_edges_source`, `idx_workflow_edges_target`.

> **Why a self-loop CHECK as well as application validation?**
> The database is the last line of defence. The application already rejects self-loops with
> a friendly message, but a `CHECK` constraint means a bug in the service layer can never
> corrupt the graph.

---

### `workflow_executions`

One row per workflow run.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `uuid` | **PK** | Server-generated identifier. |
| `workflow_id` | `uuid` | `NOT NULL`, **FK → `workflows(id)` `ON DELETE CASCADE`** | The workflow that was run. |
| `status` | `varchar(20)` | `NOT NULL`, `CHECK IN ('PENDING','RUNNING','SUCCESS','FAILED','CANCELLED')` | Run state. |
| `started_at` | `timestamptz` | `NOT NULL` | When the run started. |
| `finished_at` | `timestamptz` | nullable | When the run ended. |
| `duration_ms` | `bigint` | nullable | Total wall-clock duration. |
| `error` | `text` | nullable | Run-level failure message. |

**Indexes:** `idx_workflow_executions_workflow (workflow_id, started_at DESC)` — the run
history query.

---

### `node_executions`

One row per node per run: the evidence of what happened.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `uuid` | **PK** | Server-generated identifier. |
| `execution_id` | `uuid` | `NOT NULL`, **FK → `workflow_executions(id)` `ON DELETE CASCADE`** | The owning run. |
| `node_id` | `uuid` | `NOT NULL` | The node that ran. |
| `node_key` | `varchar(64)` | `NOT NULL` | Snapshot of the node key. |
| `node_type` | `varchar(32)` | `NOT NULL` | Snapshot of the node type. |
| `label` | `varchar(120)` | `NOT NULL` | Snapshot of the label. |
| `status` | `varchar(20)` | `NOT NULL`, `CHECK IN ('PENDING','RUNNING','SUCCESS','FAILED','SKIPPED')` | Node outcome. |
| `sequence` | `integer` | `NOT NULL` | **1-based position in the resolved execution order.** |
| `output` | `text` | nullable | The produced value. |
| `error` | `text` | nullable | The failure message. |
| `started_at` | `timestamptz` | `NOT NULL` | When the node started. |
| `finished_at` | `timestamptz` | nullable | When the node ended. |
| `duration_ms` | `bigint` | nullable | Wall-clock duration of the node. |

**Indexes:** `idx_node_executions_execution (execution_id, sequence)`.

**Why `node_id` has no foreign key?** The node may be deleted or reconfigured after the
run. The row keeps the node's *key*, *type* and *label* as a snapshot, so the history stays
readable even when the graph has moved on. Referencing a mutable graph from an immutable
history would be the wrong direction.

**Why `sequence`?** It makes the topological ordering directly observable in the database
and in the API response — the UI can show "ran 2nd" without recomputing anything.

---

## Referential-integrity summary

| Relationship | On delete |
|---|---|
| `workflow_nodes.workflow_id` → `workflows.id` | `CASCADE` |
| `workflow_edges.workflow_id` → `workflows.id` | `CASCADE` |
| `workflow_edges.source_id` → `workflow_nodes.id` | `CASCADE` |
| `workflow_edges.target_id` → `workflow_nodes.id` | `CASCADE` |
| `workflow_executions.workflow_id` → `workflows.id` | `CASCADE` |
| `node_executions.execution_id` → `workflow_executions.id` | `CASCADE` |

Deleting a workflow therefore removes its graph and its entire run history in one statement,
with no orphaned rows.

---

## Migrations

Flyway runs `backend/src/main/resources/db/migration` on startup.

| Version | File | Purpose |
|---|---|---|
| `V1` | `V1__init_schema.sql` | The initial schema: all five tables, constraints and indexes. |

`baseline-on-migrate` is enabled, so pointing the application at a pre-existing database
does not fail. `hibernate.ddl-auto` is `validate`: Hibernate verifies that the mapping
matches the schema and refuses to start otherwise, which turns a schema drift into a
startup failure rather than a runtime surprise.

### Adding a migration

Create `V2__<description>.sql` in the migration directory. Flyway applies it once, in
version order, inside a transaction. Never edit an applied migration — write a new one.

---

## Inspecting the data

```bash
# Connect
psql -h localhost -U postgres -d flowcanvas

# The graph of a workflow
SELECT n.node_key, n.type, n.label, n.position_x, n.position_y, n.config
FROM workflow_nodes n
WHERE n.workflow_id = '<uuid>'
ORDER BY n.position_x;

# What happened in a run, in execution order
SELECT ne.sequence, ne.node_key, ne.status, ne.duration_ms, left(ne.output, 60) AS output
FROM node_executions ne
WHERE ne.execution_id = '<uuid>'
ORDER BY ne.sequence;

# Find every cycle-free violation still in the database
SELECT w.name, e.source_id, e.target_id
FROM workflow_edges e
JOIN workflows w ON w.id = e.workflow_id
WHERE e.source_id = e.target_id;
```
