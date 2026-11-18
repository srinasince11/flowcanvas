import type { NodeType } from '../types';
import {
  AiModelNode,
  OutputNode,
  PromptTemplateNode,
  TextInputNode,
} from './FlowNodes';

/**
 * The React Flow node-type registry.
 *
 * <p>Kept in its own module so `FlowNodes.tsx` exports only components — that keeps
 * Vite's fast-refresh working when a node component is edited during development.
 */
export const nodeTypes = {
  TEXT_INPUT: TextInputNode,
  PROMPT_TEMPLATE: PromptTemplateNode,
  AI_MODEL: AiModelNode,
  OUTPUT: OutputNode,
} as const satisfies Record<NodeType, unknown>;
