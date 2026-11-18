# FlowCanvas — Architecture

## 1. What the system is

FlowCanvas is a **visual builder for AI workflows**. A user arranges typed nodes on a canvas,
connects them, configures them, and then runs the graph. The backend validates the graph,
executes it in dependency order, and reports exactly what happened at each node.

The system has three moving parts:

| Part | Technology | Responsibility |
|---|---|---|
| **Web client** | React 18 · TypeScript · Vite · [React Flow](https://github.com/xyflow/xyflow) (MIT) | The canvas, the node inspector, the run viewer |
| **API** | Java 21 · Spring Boot 3.5 | Graph persistence, validation, execution, AI integration |
| **Database** | PostgreSQL 16 | Workflow graphs and execution history |

The client never decides whether a workflow is valid — it renders the server's verdict. The
server never knows about the canvas — it works on a plain graph value object. The seam
between them is a small, documented JSON contract ([`api.md`](./api.md)).

---

## 2. High-level architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Browser (React 18 + TypeScript)                 │
│                                                                          │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │  Library   │  │    Canvas    │  │  Inspector   ���  │   Run Viewer   │  │
│  │ (saved     │  │ (React Flow) │  │ (node        │  │ (per-node      │  │
│  │  workflows)│  │              │  │  config)     │  │  status, output│  │
│  └─────┬──────┘  └──────┬───────┘  └──────┬───────┘  │  , duration)   │  │
│        │                │                 │          └────────┬───────┘  │
│        └────────────────┴────────┬────────┴───────────────────┘          │
│                                  │                                       │
│                     ┌────────────▼─────────────┐                         │
│                     │   api/client.ts          │                         │
│                     │  (typed fetch wrapper)    │                         │
│                     └────────────┬─────────────┘                         │
└──────────────────────────────────┼───────────────────────────────────────┘
                                   │  HTTP / JSON  (see api.md)
┌──────────────────────────────────▼───────────────────────────────────────┐
│                        Spring Boot API (Java 21)                         │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ api/          WorkflowController · ExecutionController             │  │
│  │               ApiExceptionHandler (RFC 7807 problem responses)     │  │
│  └────────────────────────────────┬───────────────────────────────────┘  │
│                                   │                                      │
│  ┌────────────────────────────────▼───────────────────────────────────┐  │
│  │ service/      WorkflowService   ExecutionService   ExecutionRunner │  │
│  │               ExampleWorkflowService                              │  │
│  └───────┬────────────────────────────────────────┬───────────────────┘  │
│          │                                        │                      │
│  ┌───────▼──────────────────┐          ┌──────────▼──────────────────┐   │
│  │ graph/                    │          │ engine/                    │   │
│  │  GraphValidator          │          │  WorkflowEngine            │   │
│  │  WorkflowGraph (value)   │─────────▶│  NodeExecutor per type     │   │
│  │  ValidationIssue         │  order   │  PromptTemplateExecutor…   │   │
│  └──────────────────────────┘          └──────────┬──────────────────┘   │
│                                                  │                      │
│                                       ┌──────────▼──────────────────┐   │
│                                       │ ai/     AiProvider (SPI)    │   │
│                                       │  MockAiProvider             │   │
│                                       │  OpenAiCompatibleProvider   │   │
│                                       └─────────────────────────────┘   │
└──────────────────────────────────┬───────────────────────────────────────┘
                                   │  JDBC
┌──────────────────────────────────▼───────────────────────────────────────┐
│                          PostgreSQL 16                                  │
│  workflows · workflow_nodes · workflow_edges                            │
│  workflow_executions · node_executions                                  │
└───────────────────────────────────────────────────────────────────────���───┘
```

---

## 3. Backend design

### 3.1 Layering

```
api/       Controllers + DTOs + error handling.   Knows HTTP; knows nothing about JPA.
service/   Transactions and orchestration.        Owns the transactional boundaries.
graph/     Pure graph model + validation.          No Spring state, no database.
engine/    Execution.                              No HTTP, no database.
ai/        Provider abstraction.                   No knowledge of workflows.
domain/    JPA entities.                           Knows the database, nothing else.
```

The rule that keeps this honest: **`graph/`, `engine/` and `ai/` never import from `api/`
or `domain/`**. They operate on plain values (`GraphNode`, `GraphEdge`, `WorkflowGraph`), so
the interesting logic is unit-testable without Spring, without a database, and without
an HTTP server. That is why the execution-order and cycle tests are fast and precise.

### 3.2 The graph model

A workflow is a directed graph. Two representations exist:

| Type | Lifetime | Used by |
|---|---|---|
| `WorkflowGraph` (immutable value) | One request | validator, engine, API mapping |
| `WorkflowNode` / `WorkflowEdge` (JPA entities) | Persistent | repositories |

`GraphMapper` converts between them and between them and the API payloads. Because the
in-memory model is immutable, it can be handed to another thread for asynchronous execution
without any defensive copying — which is exactly what the async runner does.

### 3.3 Validation

`GraphValidator` runs two classes of check:

**Structural**
- edges referencing nodes that do not exist
- self-loops
- duplicate edges
- per-node-type arity (see the table below)

**Cyclomatic**
- a depth-first search with a three-colour marking scheme (`WHITE`/`GREY`/`BLACK`)
  detects back edges and reports the **exact cycle**, not merely "a cycle exists"

**Advisory (warnings, non-blocking)**
- no Output node in the graph
- a node with no outgoing connection whose result is therefore discarded

Errors are sorted so the **root cause comes first**: a cycle is reported above the
"too many inputs" complaints it causes, because fixing the cycle fixes those too.

| Node type | Incoming | Outgoing | Config |
|---|---|---|---|
| `TEXT_INPUT` | 0 | 0..n | `{ text }` |
| `PROMPT_TEMPLATE` | 1..n | 0..n | `{ template }` |
| `AI_MODEL` | exactly 1 | 0..n | `{ model, systemPrompt, temperature }` |
| `OUTPUT` | 1..n | 0 | `{ format }` |

The arity rules live in one place — the `NodeType` enum — so the validator, the API
documentation and the frontend cannot drift apart.

### 3.4 Execution

`WorkflowEngine.execute(graph, listener)` is a pure function of the graph that reports
each step to an `ExecutionListener`:

1. **Validate.** Any error ⇒ the run fails immediately with the issues attached.
2. **Order.** Kahn's algorithm produces a topological order. Ties are broken by node key so
   the order is **deterministic** — the same graph always executes in the same sequence,
   which makes the tests assertable and the UI predictable.
3. **Execute** each node in order:
   - the node receives the outputs of its direct predecessors (`inputs`, ordered by key) and
     the outputs of its **ancestors** (`variables`) — which is what makes `{{nodeKey}}`
     templates work across a join. Nothing outside the ancestor set is visible;
   - duration is measured per node with `System.nanoTime()` (wall clock);
   - a failure is caught, recorded against that node, and the run continues.

**Failure isolation** is the important property. When a node fails, its descendants are
marked `SKIPPED` rather than attempted with missing inputs — while **independent branches
still run**. One failed AI call therefore costs you that branch, not the whole workflow.
Because nodes run in topological order, "is any direct producer failed or skipped?" is
enough to decide a skip — it propagates transitively in O(V + E) for the whole run.

### 3.5 Asynchronous runs and the transaction boundary

`POST /workflows/{id}/execute` returns `202 Accepted` with an execution id; the client
polls `GET /executions/{id}`. This keeps a slow model call from holding an HTTP thread
open and gives the UI a real run to animate.

The transaction structure here is subtle and was the source of a real bug during
development:

```
ExecutionService.startAsync(workflowId)          ← NOT @Transactional
  ├─ workflowService.getEntity(...)              ← own read-only transaction
  ├─ workflowService.loadGraph(...)              ← own read-only transaction
  ├─ workflowService.saveExecution(execution)    ← own transaction — COMMITS here
  └─ executor.execute(() -> ExecutionRunner.run(executionId, graph))
                                                  ↑ dispatched only AFTER the commit
```

The rules that follow:

1. **The execution row must be committed before the worker starts.** The worker inserts
   `node_executions` rows that reference it; if it ran first, the foreign key would fail.
   (This was a genuine race, caught by running the app in a browser — the unit test had
   passed by luck. There is now a regression test that starts five concurrent runs.)
2. **The worker lives in a separate bean** (`ExecutionRunner`). Spring applies
   `@Transactional` through a proxy, so a method calling another `@Transactional` method
   *on itself* bypasses the proxy and runs with no transaction at all. A separate bean
   makes the boundary real.

3. **No transaction spans a model call.** `ExecutionRunner.run` is deliberately *not*
   transactional. Each engine event — the plan, a node starting, a node finishing — is
   written in its own short transaction. A transaction around the whole run would pin a
   pooled connection for as long as the slowest model takes to answer; a few slow runs
   would then starve the whole API of connections.
4. **Progress is visible while the run is in flight.** The full plan is written up front as
   `PENDING` rows, and each node flips to `RUNNING` and then to its terminal status as it
   happens, so the polling UI animates the real run rather than a spinner.
5. **A run always terminates.** An unexpected failure marks the execution `FAILED`, and at
   startup `InterruptedRunRecovery` fails any run a previous process left `RUNNING` (the
   worker pool is in memory, so a restart orphans in-flight runs).

### 3.6 The AI provider seam

```java
public interface AiProvider {
    String name();
    default boolean isRemote() { return false; }
    AiResponse complete(AiRequest request);
}
```

The engine only ever sees this interface. Two implementations ship:

| Provider | Selected by | Behaviour |
|---|---|---|
| `MockAiProvider` | `AI_PROVIDER=mock` (default) | Offline, deterministic, zero credentials |
| `OpenAiCompatibleProvider` | `AI_PROVIDER=openai` | Any `/chat/completions` endpoint |

The mock provider is **not a stub**. It hashes the prompt to produce a stable fingerprint,
so the same input always yields the same output — which keeps golden tests reproducible and
the UI from flickering between runs. It also simulates failure (`[[fail]]` in the prompt)
and latency (`[[slow]]`), so the error and loading paths are real code paths rather than
hard-coded UI states.

Switching to a real provider is an environment variable, not a code change:

```bash
AI_PROVIDER=openai
AI_API_KEY=sk-...          # never committed; lives in .env or the host's secret store
AI_BASE_URL=https://api.openai.com/v1
AI_MODEL=gpt-4o-mini
```

The same `AI_BASE_URL` mechanism points at Azure OpenAI, Together, Groq, Ollama or vLLM —
anything that speaks the OpenAI shape.

### 3.7 Secrets

There are **no credentials in the repository**. `AI_API_KEY` is read from the environment
through Spring's `${...}` placeholder syntax, defaults to blank, and is never logged — the
provider reports only *whether* a key is present. `.env` is git-ignored; `.env.example`
documents every variable with a safe default.

---

## 4. Frontend design

### 4.1 State

The canvas graph lives in React state in `App.tsx`. There is no global store.

The reasoning: a workflow graph is a **single cohesive document** that the canvas mutates
as a unit. React's state model maps onto that directly. A store would add indirection and
a class of synchronisation bugs without buying anything, because there is exactly one
consumer of the graph — the canvas — and exactly one writer — the user.

The derived state is computed with `useMemo`, not stored:

```ts
const canRun = useMemo(() => !hasBlockingErrors(validateGraph(nodes, edges)), [nodes, edges]);
```

### 4.2 Validation on both sides

The frontend re-implements the server's rules in `src/nodes/graph.ts` so the user gets
**instant** feedback instead of a round trip. The server re-validates before every run,
because the server is the authority on what may execute.

The duplication is deliberate and bounded: both implementations are short, both are covered
by parallel test suites, and the rules are declared once per side in a single
`NodeType`/`NODE_TYPE_DEFINITIONS` structure.

### 4.3 Component layout

| Component | Responsibility |
|---|---|
| `Toolbar` | Name, save, validate, run |
| `WorkflowLibrary` | Saved workflows, search, examples, loading/empty/error states |
| `NodePalette` | Adds a node of a given type |
| `Canvas` | React Flow surface, custom nodes, cycle highlighting, execution projection |
| `Inspector` | Edits the selected node's label and type-specific config |
| `ValidationPanel` | Errors and warnings, root cause first |
| `RunPanel` | Per-node status, output, error and duration |

All four node types render through **one shared shell** (`FlowNodeShell`), so handles,
selection styling and the execution badge behave identically everywhere; only the body
differs. The per-type presentation rules live in `src/nodes/definitions.ts`, so adding a
fifth node type is a one-file change.

### 4.4 Projection of execution state

The backend returns per-node results keyed by node id. The canvas projects them onto the
nodes it renders, which is what makes the nodes light up green/red as the run progresses.
Edges are coloured by the state of the endpoints.

### 4.5 Responsive behaviour

Three breakpoints: a three-column desktop layout, a stacked single-column layout below
1024px, and a compact toolbar with a full-width name field below 720px.

---

## 5. Data model

See [`database-schema.md`](./database-schema.md) for the full schema, constraints and
indexes. In summary:

```
workflows ─┬─< workflow_nodes ──< workflow_edges >── workflow_nodes ─┘
           │        (source_id, target_id)
           │
           └─< workflow_executions ──< node_executions
```

Design decisions:

- **Client-generated ids for nodes and edges.** The browser generates the UUIDs, so a graph
  round-trips through the API without server-side remapping and React Flow can keep using
  its own handles. Workflow ids are server-generated.
- **JSONB for node configuration.** Its shape belongs to the node type and evolves without
  the relational schema changing — adding a prompt-template field requires no migration.
- **Execution history is first-class.** `workflow_executions` and `node_executions` are real
  tables, so a run can be inspected long after it finished, and the per-node `sequence`
  column makes the resolved execution order directly observable in the database.
- **Cascading deletes.** Removing a workflow removes its nodes, edges and runs.
  The service flushes explicitly after a delete, because the cascade only happens once the
  parent row's `DELETE` is actually sent.

---

## 6. Testing strategy

| Layer | Tool | What it proves |
|---|---|---|
| Backend unit | JUnit 5 | Cycle detection, arity rules, topological order, node executors, failure isolation |
| Backend integration | JUnit 5 + **embedded PostgreSQL** | Flyway migrations, JSONB round-trip, cascade deletes, the full REST surface, run persistence |
| Frontend unit | Vitest | Graph rules, API client behaviour and error mapping, payload conversion |
| Frontend component | Vitest + Testing Library | Rendering, inspector editing, save/run flows, empty/loading/error states |
| End-to-end | Playwright (optional) | The real UI in a real browser against the real backend |

**Why embedded PostgreSQL rather than Testcontainers?** Testcontainers needs a Docker
daemon, which makes the suite unrunnable on a plain laptop or in a CI job without
Docker-in-Docker. The embedded server is a *real* PostgreSQL — real `jsonb`, real
constraints, real transactional semantics — with no external dependency, and it starts in
about a second. The CI workflow therefore needs no database service container, and
`mvn test` means the same thing everywhere.

---

## 7. Trade-offs and known limitations

| Decision | Trade-off |
|---|---|
| Replace-the-graph on save | Full rewrite instead of a diff. For graphs of tens of nodes the write cost is irrelevant, and it removes stale rows and orphaned edges entirely. |
| Client-side validation duplicated on the server | Two implementations of the same rules. Bounded, tested on both sides, and it buys instant feedback. |
| Polling instead of SSE/WebSocket | Simpler and robust; a live stream would cut latency but adds connection management. The run is fast enough that polling is invisible. |
| Sequential execution | Nodes run in order, not in parallel. Independent branches could run concurrently; the ordering guarantee and per-node reporting were worth more for the MVP. |
| `jsonb` requires PostgreSQL | A NoSQL store was never a candidate — the data is highly relational (edges reference nodes). |
| No authentication | The project is a portfolio demo. Adding auth would mean a user table, a token flow and per-workflow ownership — deliberately out of scope. |

### Deliberately out of scope

Streaming token output, parallel branch execution, workflow versioning, a template gallery
beyond the three built-in examples, and multi-user collaboration.
