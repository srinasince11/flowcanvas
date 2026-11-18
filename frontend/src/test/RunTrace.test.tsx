import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunTrace } from '../components/RunTrace';
import { formatDuration } from '../format';
import type { ApiExecution, ApiNodeExecution } from '../types';

function node(
  sequence: number,
  key: string,
  status: ApiNodeExecution['status'],
  startMs: number | null,
  endMs: number | null,
): ApiNodeExecution {
  const at = (ms: number | null) => (ms === null ? null : new Date(Date.UTC(2026, 0, 1) + ms).toISOString());
  return {
    nodeId: `id-${key}`,
    nodeKey: key,
    label: key.toUpperCase(),
    type: 'AI_MODEL',
    sequence,
    status,
    output: null,
    error: null,
    durationMs: startMs !== null && endMs !== null ? endMs - startMs : null,
    startedAt: at(startMs) ?? at(0)!,
    finishedAt: at(endMs),
  };
}

const finishedRun: ApiExecution = {
  id: 'e1',
  workflowId: 'w1',
  status: 'FAILED',
  startedAt: new Date(Date.UTC(2026, 0, 1)).toISOString(),
  finishedAt: new Date(Date.UTC(2026, 0, 1) + 1000).toISOString(),
  durationMs: 1000,
  error: 'One or more nodes failed.',
  nodeExecutions: [
    node(1, 'first', 'SUCCESS', 0, 250),
    node(2, 'second', 'FAILED', 250, 1000),
    node(3, 'third', 'SKIPPED', null, null),
  ],
};

describe('formatDuration', () => {
  it('uses milliseconds below a second and seconds above', () => {
    expect(formatDuration(840)).toBe('840 ms');
    expect(formatDuration(1890)).toBe('1.9 s');
    expect(formatDuration(42_000)).toBe('42 s');
  });
});

describe('RunTrace', () => {
  it('places each bar at its real start, sized by its real duration', () => {
    const { container } = render(
      <RunTrace execution={finishedRun} selectedNodeId={null} onSelect={() => {}} />,
    );

    const bars = container.querySelectorAll<HTMLElement>('.trace__bar');
    // The skipped node never ran, so it has no bar.
    expect(bars).toHaveLength(2);
    expect(bars[0]!.style.left).toBe('0%');
    expect(bars[0]!.style.width).toContain('25%');
    expect(bars[1]!.style.left).toBe('25%');
    expect(bars[1]!.style.width).toContain('75%');
  });

  it('labels every row with its outcome and timing', () => {
    render(<RunTrace execution={finishedRun} selectedNodeId={null} onSelect={() => {}} />);

    expect(screen.getByRole('button', { name: /FIRST.*success, 250 ms/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /SECOND.*failed, 750 ms/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /THIRD.*skipped/i })).toBeInTheDocument();
  });

  it('reports which node was picked', async () => {
    const onSelect = vi.fn();
    render(<RunTrace execution={finishedRun} selectedNodeId="id-first" onSelect={onSelect} />);

    expect(screen.getByRole('button', { name: /FIRST/ })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.setup().click(screen.getByRole('button', { name: /SECOND/ }));

    expect(onSelect).toHaveBeenCalledWith('id-second');
  });
});
