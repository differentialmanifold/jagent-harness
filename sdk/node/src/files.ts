import { createHash, randomUUID } from 'node:crypto'
import { mkdir, open, readFile, readdir, rename, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { isDeepStrictEqual } from 'node:util'
import { unknown, type ToolCall, type ToolResult } from './types.js'

export const digest = (value: string | Buffer) => createHash('sha256').update(value).digest('hex')
export const missing = (e: unknown) => (e as NodeJS.ErrnoException).code === 'ENOENT'
export async function load<T>(file: string): Promise<T | undefined> {
  try {
    return JSON.parse(await readFile(file, 'utf8')) as T
  } catch (e) {
    if (!missing(e)) throw e
  }
}
export async function save(file: string, value: unknown) {
  const dir = dirname(file)
  await mkdir(dir, { recursive: true, mode: 0o700 })
  const temp = join(dir, '.' + randomUUID() + '.tmp')
  const out = await open(temp, 'wx', 0o600)
  try {
    try {
      await out.writeFile(JSON.stringify(value))
      await out.sync()
    } finally {
      await out.close()
    }
    await rename(temp, file)
    if (process.platform !== 'win32') {
      const directory = await open(dir, 'r')
      try {
        await directory.sync()
      } finally {
        await directory.close()
      }
    }
  } finally {
    await rm(temp, { force: true })
  }
}

// Publish a uniquely named claim before scanning peers. Two contenders cannot both
// see an empty directory. A dead process's claim can only be removed by its unique name.
export async function lease(dir: string) {
  await mkdir(dir, { recursive: true, mode: 0o700 })
  const name = process.pid + '-' + randomUUID()
  const file = join(dir, name)
  await (await open(file, 'wx', 0o600)).close()
  const release = () => rm(file, { force: true })
  try {
    for (const peer of await readdir(dir)) {
      if (peer === name || !/^\d+-[0-9a-f-]+$/.test(peer)) continue
      try {
        process.kill(Number(peer.split('-')[0]), 0)
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code === 'ESRCH') {
          await rm(join(dir, peer), { force: true })
          continue
        }
      }
      throw new Error('Runner state directory is already in use: ' + dirname(dir))
    }
    return release
  } catch (e) {
    await release()
    throw e
  }
}

export interface Identity {
  runnerId: string
  secret: string
  server: string
}
export class Journal {
  constructor(readonly dir: string) {}
  jobFile(run: string) {
    if (!/^[\w-]{1,64}$/.test(run)) throw new Error('Invalid run ID')
    return join(this.dir, 'jobs', run + '.json')
  }
  private callFile(session: string, call: string) {
    return join(this.dir, 'results', digest(session + ':' + call) + '.json')
  }
  async recorded(session: string, call: ToolCall) {
    const record = await load<{ tool: string; arguments: object; result?: ToolResult }>(
      this.callFile(session, call.toolCallId),
    )
    if (!record) return undefined
    if (record.tool !== call.name || !isDeepStrictEqual(record.arguments, call.arguments))
      throw new Error('Tool call ID reused with different arguments')
    return (
      record.result ?? unknown('Execution was interrupted; inspect the actual effects before continuing')
    )
  }
  async execute(session: string, call: ToolCall, action: () => Promise<ToolResult>) {
    const saved = await this.recorded(session, call)
    if (saved) return saved
    const file = this.callFile(session, call.toolCallId)
    const record = { tool: call.name, arguments: call.arguments }
    await save(file, record)
    let result: ToolResult
    try {
      result = await action()
      if (
        !result ||
        result.content == null ||
        !['SUCCEEDED', 'FAILED', 'UNKNOWN'].includes(result.status)
      )
        result = unknown('Tool returned no valid result')
    } catch (e) {
      result = unknown(String(e))
    }
    await save(file, { ...record, result })
    return result
  }
}
