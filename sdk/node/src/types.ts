export type Arguments = Record<string, unknown>
export interface ToolDescriptor {
  name: string
  description: string
  parameters: object
}
export interface ToolContext {
  sessionId: string
  runId: string
  signal: AbortSignal
  turnId?: string
  toolCallId?: string
  toolName?: string
  workspace?: string
}
export interface ToolResult {
  status: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
  content: unknown
  images?: { name: string; mediaType: string; url: string }[]
}
export interface ToolDefinition extends ToolDescriptor {
  execute(args: Arguments, context: ToolContext): Promise<ToolResult> | ToolResult
}
export interface ToolCall {
  toolCallId: string
  turnId: string
  name: string
  arguments: Arguments
}
export interface ChatRequest {
  sessionId: string
  runId: string
  messages: object[]
  clientTools: ToolDescriptor[]
  stream?: boolean
  modelId?: string
  clientInstructions?: string
}
export interface ChatResponse {
  sessionId: string
  runId: string
  status: string
  answer?: string
  toolCalls?: ToolCall[]
  attachmentIds?: string[]
}
export interface Job {
  runnerId: string
  workspace?: string
  request: ChatRequest
  cancelled?: boolean
}
export interface WorkspaceRequest {
  requestId: string
  path: string
}
export interface Workspaces {
  root: string
  resolve(path?: string): Promise<string>
  browse(path?: string): Promise<{
    path: string
    parent: string | null
    roots: string[]
    directories: string[]
    truncated: boolean
  }>
}
export interface Poll {
  job?: Job
  cancelledRuns?: string[]
  workspaceRequest?: WorkspaceRequest
}
export const success = (content: unknown): ToolResult => ({ status: 'SUCCEEDED', content })
export const unknown = (content: unknown): ToolResult => ({ status: 'UNKNOWN', content })
export function errorText(error: unknown) {
  return error instanceof Error ? error.message : String(error)
}
