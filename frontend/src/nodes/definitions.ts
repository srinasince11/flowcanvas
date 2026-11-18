import type { NodeType } from '../types';

/**
 * The node palette definition.
 *
 * <p>One place defines what a node is called, what colour it is, what its default config
 * is and which fields the inspector should render. The canvas, the palette, the inspector
 * and the graph utilities all read from here, so adding a node type is a single-file
 * change on the frontend.
 */
export interface NodeTypeDefinition {
  type: NodeType;
  title: string;
  description: string;
  /** Accent colour used for the node header and the palette entry. */
  accent: string;
  /** The default configuration applied when a node is first created. */
  defaultConfig: Record<string, unknown>;
  /** Whether the node may be connected into (i.e. have a target handle). */
  acceptsInput: boolean;
  /** Whether the node may be connected out of (i.e. have a source handle). */
  providesOutput: boolean;
  /** A short hint shown under the node title. */
  hint: string;
}

export const NODE_TYPE_DEFINITIONS: Record<NodeType, NodeTypeDefinition> = {
  TEXT_INPUT: {
    type: 'TEXT_INPUT',
    title: 'Text Input',
    description: 'A literal piece of text that starts a workflow.',
    accent: '#0E8A7E',
    defaultConfig: { text: 'Type your prompt here…' },
    acceptsInput: false,
    providesOutput: true,
    hint: 'Starts a flow with fixed text.',
  },
  PROMPT_TEMPLATE: {
    type: 'PROMPT_TEMPLATE',
    title: 'Prompt Template',
    description: 'Renders a template using upstream values.',
    accent: '#5B4BD8',
    defaultConfig: {
      template: 'Summarise the following:\n{{input}}',
    },
    acceptsInput: true,
    providesOutput: true,
    hint: 'Builds a prompt from upstream values.',
  },
  AI_MODEL: {
    type: 'AI_MODEL',
    title: 'AI Model',
    description: 'Sends the prompt to the configured AI provider.',
    accent: '#C23B7E',
    defaultConfig: {
      model: 'gpt-4o-mini',
      systemPrompt: 'You are a helpful assistant.',
      temperature: 0.7,
    },
    acceptsInput: true,
    providesOutput: true,
    hint: 'Sends one prompt to the model.',
  },
  OUTPUT: {
    type: 'OUTPUT',
    title: 'Output',
    description: 'Collects the final result of the workflow.',
    accent: '#B7860B',
    defaultConfig: { format: 'text' },
    acceptsInput: true,
    providesOutput: false,
    hint: 'Collects the final result.',
  },
};

export const NODE_TYPE_ORDER: NodeType[] = [
  'TEXT_INPUT',
  'PROMPT_TEMPLATE',
  'AI_MODEL',
  'OUTPUT',
];

/** Human-readable label for a node type. */
export function nodeTypeTitle(type: NodeType): string {
  return NODE_TYPE_DEFINITIONS[type]?.title ?? type;
}

/** Accent colour for a node type, with a safe fallback for unknown values. */
export function nodeTypeAccent(type: NodeType): string {
  return NODE_TYPE_DEFINITIONS[type]?.accent ?? '#7A8580';
}
