import { spawn, spawnSync } from 'node:child_process'
import { opendir, stat } from 'node:fs/promises'
import { createReadStream, existsSync } from 'node:fs'
import { basename, delimiter, dirname, isAbsolute, join, relative, resolve } from 'node:path'
import { createInterface } from 'node:readline'
import { minimatch } from 'minimatch'
import { success, type Arguments, type ToolContext } from '../types.js'
import { display, integer, location, text } from './files.js'

const excluded = ['.git', 'target', 'node_modules', 'dist', 'build']
export function ripgrep(configured = process.env.JAGENT_RG_PATH) {
  if (configured && !isAbsolute(configured)) throw new Error('JAGENT_RG_PATH must be absolute')
  const executable = process.platform === 'win32' ? 'rg.exe' : 'rg'
  const candidates = configured
    ? [configured]
    : (process.env.PATH ?? '')
        .split(delimiter)
        .filter((p) => isAbsolute(p))
        .map((p) => join(p, executable))
  for (const command of candidates) {
    if (!existsSync(command)) continue
    const probe = spawnSync(command, ['--version'], {
      encoding: 'utf8',
      timeout: 3000,
      windowsHide: true,
    })
    if (probe.status === 0 && probe.stdout.startsWith('ripgrep ')) return command
  }
  if (configured) throw new Error('Could not run the specified ripgrep executable: ' + configured)
}
function globMatch(pattern: string, path: string) {
  return (
    !pattern ||
    minimatch(path.replaceAll('\\', '/'), pattern, {
      dot: true,
      matchBase: true,
      nonegate: true,
      nocomment: true,
    })
  )
}
async function* walk(dir: string, signal: AbortSignal): AsyncGenerator<string> {
  for await (const entry of await opendir(dir)) {
    signal.throwIfAborted()
    const path = join(dir, entry.name)
    if (entry.isDirectory() && !excluded.includes(entry.name)) yield* walk(path, signal)
    else if (entry.isFile()) yield path
  }
}
async function* records(
  command: string,
  args: string[],
  cwd: string,
  delimiter: string,
  signal: AbortSignal,
) {
  const child = spawn(command, args, {
    cwd,
    signal,
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  })
  let diagnostics = ''
  child.stderr.setEncoding('utf8').on('data', (s) => {
    if (diagnostics.length < 2000) diagnostics += s
  })
  const closed = new Promise<{ code: number | null; error?: Error }>((done) => {
    child.on('error', (error) => done({ code: null, error }))
    child.on('close', (code) => done({ code }))
  })
  let pending = ''
  try {
    for await (const chunk of child.stdout.setEncoding('utf8')) {
      const values = (pending + chunk).split(delimiter)
      pending = values.pop()!
      for (const value of values) if (value) yield value
    }
    if (pending) yield pending
    const { code, error } = await closed
    signal.throwIfAborted()
    if (code !== 0 && code !== 1) throw error ?? new Error(diagnostics.trim() || 'ripgrep failed')
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill()
    await closed
  }
}
function regex(pattern: string, literal: boolean, insensitive: boolean) {
  if (/[\r\n]/.test(pattern)) throw new Error('pattern must be single-line')
  if (!literal && /\\(?:[0-9ceGhHnQERvVXZ]|k<)|\(\?(?:[=!>]|<[=!])/.test(pattern))
    throw new Error('Unsupported feature in portable regular expression')
  if (literal) return new RegExp(pattern.replace(/[.*+?^$()|[\]\\]/g, '\\$&'), insensitive ? 'iu' : 'u')
  // Java and ripgrep use Unicode character classes. JavaScript defaults \d and \w to ASCII.
  const word = '\\p{Alphabetic}\\p{Mark}\\p{Decimal_Number}\\p{Connector_Punctuation}\\p{Join_Control}'
  let inside = false
  const expanded = pattern.replace(/\\[\s\S]|[\[\]]/g, (token) => {
    if (token === '[' || token === ']') {
      inside = token === '['
      return token
    }
    if (token === '\\d') return '\\p{Decimal_Number}'
    if (token === '\\D') return '\\P{Decimal_Number}'
    if (token === '\\w') return inside ? word : '[' + word + ']'
    if (token === '\\W' && !inside) return '[^' + word + ']'
    return token
  })
  return new RegExp(expanded, insensitive ? 'iu' : 'u')
}
type Match = { path: string; line: number; preview: string }
export async function search(
  kind: 'find' | 'grep',
  rg: string | undefined,
  args: Arguments,
  ctx: ToolContext,
) {
  const pattern = text(args, 'pattern')
  const rawGlob = (kind === 'find' ? pattern : text({ glob: '', ...args }, 'glob', true)).replaceAll(
    '\\',
    '/',
  )
  const glob = rawGlob.replace(/^(?:\.\/)+/, '')
  if ((rawGlob && !glob) || glob.startsWith('!') || glob.startsWith('/') || glob.endsWith('/'))
    throw new Error('Expected a relative file include glob')
  const target = location(ctx, text({ path: '.', ...args }, 'path'))
  const dir = (await stat(target)).isDirectory(),
    root = dir ? target : dirname(target)
  if (kind === 'find' && !dir) throw new Error('Expected a directory')
  const limit = integer(args.limit, 100, 1000)
  const files: string[] = [],
    matches: Match[] = []
  const literal = args.literal === true,
    insensitive = args.ignoreCase === true
  const compiled = kind === 'grep' ? regex(pattern, literal, insensitive) : undefined
  const match = (path: string, line: number, s: string) => {
    s = s.replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '')
    matches.push({
      path: display(ctx, path),
      line,
      preview: s.length > 2000 ? s.slice(0, 2000) + '...' : s,
    })
  }
  if (rg) {
    const options = [
      '--no-config',
      '--hidden',
      ...excluded.flatMap((name) => ['--glob', '!**/' + name + '/**']),
    ]
    if (kind === 'find') {
      for await (const path of records(
        rg,
        [...options, '--files', '--null', '--', '.'],
        root,
        '\0',
        ctx.signal,
      )) {
        const full = resolve(root, path)
        if (globMatch(glob, relative(root, full))) files.push(display(ctx, full))
        if (files.length > limit) break
      }
    } else {
      const command = [
        ...options,
        '--json',
        '--text',
        '--crlf',
        ...(literal ? ['--fixed-strings'] : []),
        ...(insensitive ? ['--ignore-case'] : []),
        '-e',
        pattern,
        '--',
        dir ? '.' : basename(target),
      ]
      for await (const row of records(rg, command, root, '\n', ctx.signal)) {
        const { type, data } = JSON.parse(row)
        if (type !== 'match' || !data.path.text || typeof data.lines.text !== 'string') continue
        const full = resolve(root, data.path.text)
        if (globMatch(glob, relative(root, full)))
          match(full, data.line_number, data.lines.text.replace(/\r?\n$/, ''))
        if (matches.length > limit) break
      }
    }
  } else {
    for await (const path of dir ? walk(root, ctx.signal) : [target]) {
      if (!globMatch(glob, relative(root, path))) continue
      if (!compiled) files.push(display(ctx, path))
      else {
        const input = createReadStream(path, { encoding: 'utf8', signal: ctx.signal })
        const reader = createInterface({ input, crlfDelay: Infinity })
        try {
          let number = 0
          for await (const line of reader) {
            ctx.signal.throwIfAborted()
            number++
            if (line.includes('\uFFFD')) break
            if (compiled.test(line)) match(path, number, line)
            if (matches.length > limit) break
          }
        } catch (e) {
          if ((e as NodeJS.ErrnoException).code !== 'EACCES') throw e
        } finally {
          reader.close()
          input.destroy()
        }
      }
      if (files.length > limit || matches.length > limit) break
    }
  }
  const result = {
    pattern,
    path: display(ctx, target),
    ...(kind === 'grep' && glob ? { glob } : {}),
    limit,
    truncated: Math.max(files.length, matches.length) > limit,
    engine: rg ? 'ripgrep' : 'node',
  }
  return success(
    kind === 'find'
      ? { ...result, files: files.slice(0, limit).sort() }
      : {
          ...result,
          matches: matches
            .slice(0, limit)
            .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : a.line - b.line)),
        },
  )
}
