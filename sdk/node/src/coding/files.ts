import { createHash, randomUUID } from 'node:crypto'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from 'node:fs/promises'
import { setTimeout as sleep } from 'node:timers/promises'
import { missing } from '../files.js'
import { success, type Arguments, type ToolContext } from '../types.js'

export function text(args: Arguments, key: string, empty = false): string {
  const value = args[key]
  if (typeof value !== 'string' || (!empty && !value.trim())) throw new Error('Expected string: ' + key)
  return value
}
export function integer(value: unknown, fallback: number, max: number) {
  const n = value ?? fallback
  if (typeof n !== 'number' || !Number.isSafeInteger(n) || n < 1 || n > max)
    throw new Error('Expected an integer from 1 to ' + max)
  return n
}
export function location(ctx: ToolContext, input: string) {
  if (!ctx.workspace) throw new Error('A workspace is required')
  return resolve(ctx.workspace, input.replaceAll('\\', '/'))
}
export function display(ctx: ToolContext, path: string) {
  const rel = relative(ctx.workspace!, path)
  return (rel === '..' || rel.startsWith('..' + sep) || isAbsolute(rel) ? path : rel).replaceAll(
    '\\',
    '/',
  )
}
export function utf8(value: string) {
  if (!value.isWellFormed() || value.includes('\0'))
    throw new Error('Expected valid UTF-8 text without NUL')
  return Buffer.from(value)
}
export function decode(buffer: Buffer) {
  const value = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
  if (value.includes('\0')) throw new Error('Expected a UTF-8 text file')
  return value
}
function mediaType(b: Buffer) {
  if (b.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))) return 'image/png'
  if (b.subarray(0, 3).equals(Buffer.from('ffd8ff', 'hex'))) return 'image/jpeg'
  if (/^GIF8[79]a/.test(b.toString('latin1', 0, 6))) return 'image/gif'
  if (b.toString('latin1', 0, 4) === 'RIFF' && b.toString('latin1', 8, 12) === 'WEBP')
    return 'image/webp'
}
export async function read(args: Arguments, ctx: ToolContext) {
  const path = location(ctx, text(args, 'path')),
    name = display(ctx, path)
  const file = await open(path, 'r')
  try {
    const meta = await file.stat()
    if (!meta.isFile()) throw new Error('File not found: ' + name)
    const header = Buffer.alloc(12)
    const n = await file.read(header, 0, 12, 0)
    const type = mediaType(header.subarray(0, n.bytesRead))
    if (type) {
      if ('offset' in args || 'limit' in args) throw new Error('Line ranges apply only to text files')
      if (meta.size > 10 * 1024 * 1024) throw new Error('Each image must be at most 10 MB')
      const chunks: Buffer[] = []
      let size = 0
      for await (const chunk of file.createReadStream({ autoClose: false, signal: ctx.signal })) {
        size += chunk.length
        if (size > 10 * 1024 * 1024) throw new Error('Each image must be at most 10 MB')
        chunks.push(chunk)
      }
      const data = Buffer.concat(chunks, size)
      return {
        ...success({ type: 'image', path: name }),
        images: [
          {
            name: basename(path),
            mediaType: type,
            url: 'data:' + type + ';base64,' + data.toString('base64'),
          },
        ],
      }
    }
    const offset = integer(args.offset, 1, Number.MAX_SAFE_INTEGER),
      limit = integer(args.limit, 2000, 2000)
    const hash = createHash('sha256'),
      decoder = new TextDecoder('utf-8', { fatal: true })
    const lines: string[] = []
    let totalLines = 0,
      remainder = '',
      priorCR = false
    function consume(chunk: string) {
      if (!chunk) return
      if (chunk.includes('\0')) throw new Error('Expected a UTF-8 text file')
      if (priorCR && chunk.startsWith('\n')) chunk = chunk.slice(1)
      priorCR = chunk.endsWith('\r')
      const parts = chunk.split(/\r\n|\r|\n/)
      parts[0] = remainder + parts[0]
      remainder = parts.pop()!
      for (const line of parts) if (++totalLines >= offset && lines.length < limit) lines.push(line)
    }
    for await (const buffer of file.createReadStream({ autoClose: false, signal: ctx.signal })) {
      hash.update(buffer)
      consume(decoder.decode(buffer, { stream: true }))
    }
    consume(decoder.decode())
    if (remainder && ++totalLines >= offset && lines.length < limit) lines.push(remainder)
    return success({
      path: name,
      type: 'text',
      offset,
      limit,
      lines: lines.length,
      totalLines,
      truncated: offset - 1 + lines.length < totalLines,
      contentHash: hash.digest('hex'),
      content: lines.join('\n'),
    })
  } finally {
    await file.close()
  }
}
export async function ls(args: Arguments, ctx: ToolContext) {
  const root = location(ctx, text({ path: '.', ...args }, 'path'))
  const entries = await Promise.all(
    (await readdir(root, { withFileTypes: true })).map(async (entry) => {
      const full = join(root, entry.name)
      const dir =
        entry.isDirectory() ||
        (entry.isSymbolicLink() && (await stat(full).catch(() => null))?.isDirectory())
      return { name: entry.name, path: display(ctx, full), type: dir ? 'directory' : 'file' }
    }),
  )
  entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
  return success({ path: display(ctx, root), entries })
}

export class FileProblem extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly detail: Arguments = {},
  ) {
    super(message)
  }
}
const writers = new Map<string, Promise<void>>()
export async function mutate<T>(
  requested: string,
  ctx: ToolContext,
  action: (target: string) => Promise<T>,
) {
  const target = await canonical(requested)
  const prior = writers.get(target) ?? Promise.resolve()
  let unlock!: () => void
  const turn = new Promise<void>((done) => (unlock = done))
  writers.set(target, turn)
  try {
    await prior
    ctx.signal.throwIfAborted()
    if ((await canonical(requested)) !== target)
      throw new FileProblem('PATH_CHANGED', 'The target path changed', { target })
    return await action(target)
  } finally {
    unlock()
    if (writers.get(target) === turn) writers.delete(target)
  }
}
async function canonical(path: string): Promise<string> {
  try {
    return await realpath(path)
  } catch (e) {
    if (!missing(e) || dirname(path) === path) throw e
    return join(await canonical(dirname(path)), basename(path))
  }
}
export async function replaceFile(path: string, bytes: Buffer, signal: AbortSignal, expected?: Buffer) {
  const before = await lstat(path, { bigint: true }).catch((e) => {
    if (!missing(e)) throw e
    return null
  })
  if (before && !before.isFile())
    throw new FileProblem('PATH_CHANGED', 'Target is not a regular file', { target: path })
  if (before && before.nlink > 1n)
    throw new FileProblem(
      'HARD_LINK_UNSUPPORTED',
      'Use an intentional in-place update for a hard-linked file',
      { target: path, linkCount: Number(before.nlink) },
    )
  const temp = join(dirname(path), '.jagent-' + randomUUID() + '.tmp')
  const out = await open(temp, 'wx', before ? 0o600 : 0o666)
  try {
    try {
      await out.writeFile(bytes, { signal })
      if (before && process.platform !== 'win32') {
        const metadata = await out.stat({ bigint: true })
        if (metadata.uid !== before.uid || metadata.gid !== before.gid)
          await out.chown(Number(before.uid), Number(before.gid))
        await out.chmod(Number(before.mode & 0o7777n))
      }
      await out.sync()
    } finally {
      await out.close()
    }
    for (let i = 0; ; i++) {
      signal.throwIfAborted()
      if ((await canonical(path)) !== path)
        throw new FileProblem('PATH_CHANGED', 'The target path changed', { target: path })
      const now = await lstat(path, { bigint: true }).catch((e) => {
        if (!missing(e)) throw e
        return null
      })
      if (
        now?.ino !== before?.ino ||
        now?.dev !== before?.dev ||
        now?.ctimeNs !== before?.ctimeNs ||
        now?.size !== before?.size
      )
        return false
      if (expected && (!now || !expected.equals(await readFile(path)))) return false
      try {
        await rename(temp, path)
        return true
      } catch (e) {
        if (!['EBUSY', 'EPERM', 'EACCES'].includes((e as NodeJS.ErrnoException).code ?? '')) throw e
        if (i === 3)
          throw new FileProblem('FILE_BUSY', 'File busy or permissions prevent replacement', {
            target: path,
          })
        await sleep(25 * (i + 1), undefined, { signal })
      }
    }
  } finally {
    await rm(temp, { force: true })
  }
}
export async function write(args: Arguments, ctx: ToolContext) {
  const path = location(ctx, text(args, 'path')),
    bytes = utf8(text(args, 'content', true))
  await mkdir(dirname(path), { recursive: true })
  return mutate(path, ctx, async (target) => {
    for (let i = 0; i < 3; i++)
      if (await replaceFile(target, bytes, ctx.signal))
        return success({ path: display(ctx, path), bytes: bytes.length })
    throw new FileProblem('CONCURRENT_MODIFICATION', 'File changed during write; retry', { target })
  })
}
