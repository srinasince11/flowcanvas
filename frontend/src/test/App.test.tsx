import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App';

/** A minimal, complete workflow payload as the API would return it. */
const workflowPayload = {
  id: 'w1',
  name: 'Support reply',
  description: 'A test workflow',
  status: 'DRAFT',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  nodes: [
    {
      id: 'n1',
      key: 'n1',
      type: 'TEXT_INPUT',
      label: 'Input',
      position: { x: 0, y: 0 },
      config: { text: 'hello' },
    },
  ],
  edges: [],
};

function jsonResponse(payload: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    json: async () => payload,
  };
}

/**
 * Installs a fetch mock for the endpoints the app calls.
 *
 * <p>Routes are matched on method + URL substring, longest pattern first, so a
 * specific route such as `/api/v1/workflows/examples` wins over the generic
 * `/api/v1/workflows`. The method matters: the same path is used for the
 * collection GET (a paged list) and for the collection POST (a create).
 */
function installFetchMock(
  overrides: Record<string, () => unknown> = {},
  options: { listItems?: unknown[] } = {},
) {
  const patterns = Object.keys(overrides).sort((a, b) => b.length - a.length);
  const listItems = options.listItems ?? [];

  const fetchMock = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
    const url = String(input);

    for (const pattern of patterns) {
      if (url.includes(pattern)) {
        return jsonResponse(overrides[pattern]!());
      }
    }
    if (url.includes('/api/v1/workflows/examples')) {
      return jsonResponse([]);
    }
    if (url.includes('/api/v1/workflows?')) {
      return jsonResponse({
        items: listItems,
        page: 0,
        size: 50,
        totalItems: listItems.length,
        totalPages: listItems.length > 0 ? 1 : 0,
      });
    }
    return jsonResponse({});
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('App', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders the three-panel layout with empty states', async () => {
    installFetchMock();

    render(<App />);

    expect(screen.getByLabelText('Workflow library')).toBeInTheDocument();
    expect(screen.getByLabelText('Node palette')).toBeInTheDocument();
    expect(screen.getByTestId('validation-panel')).toBeInTheDocument();

    // The inspector shows a helpful empty state when nothing is selected.
    await waitFor(() => {
      expect(screen.getByText('No node selected')).toBeInTheDocument();
    });
  });

  it('shows an empty-state message for a workflow library with no entries', async () => {
    installFetchMock();

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('No workflows yet')).toBeInTheDocument();
    });
  });

  it('surfaces an API failure in the library panel', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/examples')) {
          return jsonResponse([]);
        }
        return {
          ok: false,
          status: 500,
          json: async () => ({ title: 'Server error', detail: 'Database is unreachable' }),
        };
      }),
    );

    render(<App />);

    await waitFor(() => {
      expect(screen.getByText('Database is unreachable')).toBeInTheDocument();
    });
  });

  it('adds a node to the canvas from the palette', async () => {
    const user = userEvent.setup();
    installFetchMock();

    render(<App />);

    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));

    // The inspector opens for the new node, proving it was created and selected.
    await waitFor(() => {
      expect(screen.getByTestId('inspector')).toBeInTheDocument();
    });
    expect(screen.getByTestId('inspector-label')).toHaveValue('Text Input');
  });

  it('edits a node label through the inspector', async () => {
    const user = userEvent.setup();
    installFetchMock();

    render(<App />);

    await user.click(screen.getByTestId('add-node-PROMPT_TEMPLATE'));
    const labelInput = await screen.findByTestId('inspector-label');
    await user.clear(labelInput);
    await user.type(labelInput, 'My template');

    expect(labelInput).toHaveValue('My template');
  });

  it('edits the prompt template body', async () => {
    const user = userEvent.setup();
    installFetchMock();

    render(<App />);

    await user.click(screen.getByTestId('add-node-PROMPT_TEMPLATE'));
    const template = await screen.findByTestId('inspector-template');
    await user.clear(template);
    // `{{` is a special sequence for user-event (it escapes a literal brace),
    // so the template is pasted rather than typed character by character.
    await user.click(template);
    await user.paste('Hello {{input}}');

    expect(template).toHaveValue('Hello {{input}}');
  });
  it('disables the Run button until the workflow has nodes', async () => {
    installFetchMock();

    render(<App />);

    expect(screen.getByTestId('run-workflow')).toBeDisabled();
  });

  it('saves a workflow and confirms with a toast', async () => {
    const user = userEvent.setup();
    const fetchMock = installFetchMock({
      '/api/v1/workflows/validate': () => ({ valid: true, issues: [], executionOrder: [] }),
    });

    render(<App />);

    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));
    await user.click(screen.getByTestId('save-workflow'));

    await waitFor(() => {
      expect(screen.getByTestId('toast')).toHaveTextContent('Workflow saved.');
    });

    const postCall = fetchMock.mock.calls.find(
      (call) => (call[1] as RequestInit | undefined)?.method === 'POST',
    );
    expect(postCall).toBeDefined();
  });

  it('reports a validation error from the server in the validation panel', async () => {
    const user = userEvent.setup();
    installFetchMock({
      '/api/v1/workflows/validate': () => ({
        valid: false,
        issues: [
          {
            severity: 'ERROR',
            code: 'CYCLE_DETECTED',
            message: 'This workflow contains a cycle: A → B → A.',
            nodeId: 'n1',
            edgeId: null,
          },
        ],
        executionOrder: [],
      }),
    });

    render(<App />);

    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));
    await user.click(screen.getByTestId('validate-workflow'));

    await waitFor(() => {
      expect(screen.getByText('CYCLE_DETECTED')).toBeInTheDocument();
    });
    expect(screen.getByText('This workflow contains a cycle: A → B → A.')).toBeInTheDocument();
  });

  it('loads a saved workflow into the canvas', async () => {
    const user = userEvent.setup();
    installFetchMock(
      {
        '/api/v1/workflows/w1': () => workflowPayload,
      },
      {
        listItems: [
          {
            id: 'w1',
            name: 'Support reply',
            description: 'A test workflow',
            status: 'DRAFT',
            nodeCount: 1,
            edgeCount: 0,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ],
      },
    );

    render(<App />);

    const item = await screen.findByTestId('workflow-item-w1');
    await user.click(within(item).getByTestId('workflow-open-w1'));

    await waitFor(() => {
      expect(screen.getByTestId('workflow-name')).toHaveValue('Support reply');
    });
  });

  it('filters the workflow list as the user types', async () => {
    const user = userEvent.setup();
    installFetchMock();

    render(<App />);

    const search = screen.getByTestId('workflow-search');
    await user.type(search, 'support');

    await waitFor(() => {
      const fetchMock = globalThis.fetch as unknown as ReturnType<typeof vi.fn>;
      const called = fetchMock.mock.calls.some((call) => String(call[0]).includes('search=support'));
      expect(called).toBe(true);
    });
  });

  it('shows the run results panel after a successful execution', async () => {
    const user = userEvent.setup();
    installFetchMock({
      '/api/v1/executions/': () => ({
        id: 'e1',
        workflowId: 'w1',
        status: 'SUCCESS',
        startedAt: '2026-01-01T00:00:00Z',
        finishedAt: '2026-01-01T00:00:01Z',
        durationMs: 42,
        error: null,
        nodeExecutions: [
          {
            nodeId: 'n1',
            nodeKey: 'n1',
            label: 'Input',
            type: 'TEXT_INPUT',
            sequence: 1,
            status: 'SUCCESS',
            output: 'hello',
            error: null,
            durationMs: 3,
            startedAt: '2026-01-01T00:00:00Z',
            finishedAt: '2026-01-01T00:00:00Z',
          },
        ],
      }),
    });

    render(<App />);

    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));
    await user.click(screen.getByTestId('run-workflow'));

    await waitFor(
      () => {
        expect(screen.getByTestId('run-panel')).toBeInTheDocument();
        expect(screen.getByText('hello')).toBeInTheDocument();
      },
      { timeout: 4000 },
    );
  });

  it('gives new nodes readable, unique keys', async () => {
    const user = userEvent.setup();
    installFetchMock();

    render(<App />);

    await user.click(screen.getByTestId('add-node-PROMPT_TEMPLATE'));
    expect(await screen.findByTestId('inspector-key')).toHaveValue('prompt1');
    await user.click(screen.getByTestId('add-node-PROMPT_TEMPLATE'));
    await waitFor(() => expect(screen.getByTestId('inspector-key')).toHaveValue('prompt2'));

    // A template with no upstream connection can only use {{input}}.
    expect(within(screen.getByTestId('inspector-variables')).getAllByRole('button')).toHaveLength(1);
    expect(screen.getByText('{{input}}', { selector: 'button' })).toBeInTheDocument();
  });

  it('warns in the inspector when a key is already taken', async () => {
    const user = userEvent.setup();
    installFetchMock();

    render(<App />);

    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));
    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));
    const key = await screen.findByTestId('inspector-key');
    await waitFor(() => expect(key).toHaveValue('text2'));
    await user.clear(key);
    await user.type(key, 'TEXT1');

    expect(screen.getByTestId('inspector-key-help')).toHaveTextContent('Another node already uses "text1"');
    expect(key).toHaveAttribute('aria-invalid', 'true');
  });

  it('lists recent runs after a run and can reopen one', async () => {
    const user = userEvent.setup();
    const execution = {
      id: 'e1',
      workflowId: 'w1',
      status: 'SUCCESS',
      startedAt: '2026-01-01T10:00:00Z',
      finishedAt: '2026-01-01T10:00:01Z',
      durationMs: 42,
      error: null,
      nodeExecutions: [
        {
          nodeId: 'n1',
          nodeKey: 'text1',
          label: 'Input',
          type: 'TEXT_INPUT',
          sequence: 1,
          status: 'SUCCESS',
          output: 'first run output',
          error: null,
          durationMs: 3,
          startedAt: '2026-01-01T10:00:00Z',
          finishedAt: '2026-01-01T10:00:00Z',
        },
      ],
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      if (url.includes('/api/v1/workflows/examples')) return jsonResponse([]);
      if (url.includes('/api/v1/workflows/w1/executions')) {
        return jsonResponse([{ id: 'e1', status: 'SUCCESS', startedAt: execution.startedAt, durationMs: 42 }]);
      }
      if (url.includes('/execute')) return jsonResponse({ executionId: 'e1', workflowId: 'w1', status: 'RUNNING' });
      if (url.includes('/api/v1/executions/e1')) return jsonResponse(execution);
      if (method === 'POST' && url.endsWith('/api/v1/workflows')) {
        return jsonResponse({ ...workflowPayload, id: 'w1' });
      }
      return jsonResponse({ items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 });
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<App />);

    await user.click(screen.getByTestId('add-node-TEXT_INPUT'));
    await user.click(screen.getByTestId('run-workflow'));

    const historyPanel = await screen.findByTestId('run-history', {}, { timeout: 4000 });
    const runButton = within(historyPanel).getByRole('button');
    expect(runButton).toHaveTextContent('42 ms');

    await user.click(screen.getByText('Clear results'));
    expect(screen.queryByText('first run output', { selector: 'pre' })).not.toBeInTheDocument();

    await user.click(within(screen.getByTestId('run-history')).getByRole('button'));
    await waitFor(() =>
      expect(screen.getByText('first run output', { selector: 'pre' })).toBeInTheDocument(),
    );
  });
});
