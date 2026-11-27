import { fileURLToPath } from 'node:url'
import catalog from './catalog.json' with { type: 'json' }
import type { ToolDefinition } from '../types.js'
import { LocalWorkspaces } from '../workspaces.js'
import { read, ls, write, FileProblem } from './files.js'
import { search, ripgrep } from './search.js'
import { bash } from './bash.js'
import { edit } from './edit.js'

export function codingEnvironment(options: { rg?: string; workspaceRoot?: string } = {}) {
  const rg = ripgrep(options.rg),
    backend = rg ? 'ripgrep (' + rg + ')' : 'Node.js fallback'
  const handlers: Record<string, ToolDefinition['execute']> = {
    read,
    ls,
    write,
    edit,
    bash,
    find: (a, c) => search('find', rg, a, c),
    grep: (a, c) => search('grep', rg, a, c),
  }
  const tools: ToolDefinition[] = catalog.tools.map((def) => ({
    ...def,
    async execute(args, ctx) {
      ctx.signal.throwIfAborted()
      try {
        return await handlers[def.name](args, ctx)
      } catch (e) {
        if (e instanceof FileProblem)
          return {
            status: 'FAILED',
            content: {
              code: e.code,
              error: e.message,
              retry: 'Read the current file and retry with its latest contents.',
              ...e.detail,
            },
          }
        throw e
      }
    },
  }))
  return {
    tools,
    runnerSourceWorkspace: fileURLToPath(new URL('../../src/', import.meta.url)),
    diagnostics: { find: backend, grep: backend },
    instructions: catalog.instructions,
    capabilities: ['coding', 'browser-research'],
    workspaces: new LocalWorkspaces(options.workspaceRoot),
  }
}
