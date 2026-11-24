import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join, delimiter } from 'node:path'
import { OutputTail } from '../output.js'
import { digest } from '../files.js'
import type { Arguments, ToolContext, ToolResult } from '../types.js'
import { display, integer, location, text } from './files.js'

function gitBash() {
  const dirs = [
    process.env.ProgramFiles,
    process.env['ProgramFiles(x86)'],
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Programs'),
  ]
  const candidates = [
    ...dirs.flatMap((d) =>
      d ? [join(d, 'Git', 'bin', 'bash.exe'), join(d, 'Git', 'usr', 'bin', 'bash.exe')] : [],
    ),
    ...(process.env.PATH ?? '')
      .split(delimiter)
      .filter((d) => /[\\/]git[\\/]/i.test(d))
      .map((d) => join(d, 'bash.exe')),
  ]
  const exe = candidates.find((p) => existsSync(p))
  if (!exe) throw new Error('Install Git for Windows to run the bash tool')
  return exe
}
export async function bash(args: Arguments, ctx: ToolContext): Promise<ToolResult> {
  ctx.signal.throwIfAborted()
  const command = text(args, 'command'),
    cwd = location(ctx, text({ cwd: '.', ...args }, 'cwd'))
  const timeout = integer(
    typeof args.timeoutSeconds === 'number' && args.timeoutSeconds <= 0 ? 60 : args.timeoutSeconds,
    60,
    2147483,
  )
  const windows = process.platform === 'win32'
  const child = spawn(windows ? gitBash() : '/bin/sh', windows ? ['-l', '-s'] : ['-lc', command], {
    cwd,
    windowsHide: true,
    detached: !windows,
    env: {
      ...process.env,
      ...(windows ? { CHERE_INVOKING: '1' } : {}),
      PLAYWRIGHT_CLI_SESSION: 'ja-' + digest(ctx.sessionId).slice(0, 20),
    },
  })
  const stdout = new OutputTail(6 * 1024)
  const stderr = new OutputTail(6 * 1024)
  child.stdout.on('data', (chunk: Buffer) => stdout.append(chunk))
  child.stderr.on('data', (chunk: Buffer) => stderr.append(chunk))
  const closed = new Promise<number | null>((done, fail) => {
    child.once('error', fail)
    child.once('close', done)
  })
  child.stdin.on('error', () => {})
  child.stdin.end(windows ? command + '\n' : undefined)
  function stop() {
    if (!child.pid) return
    if (windows)
      spawn('taskkill', ['/pid', String(child.pid), '/t', '/f'], {
        stdio: 'ignore',
        windowsHide: true,
      }).on('error', () => child.kill())
    else
      try {
        process.kill(-child.pid, 'SIGKILL')
      } catch (e) {
        if ((e as NodeJS.ErrnoException).code !== 'ESRCH') child.kill()
      }
  }
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    stop()
  }, 1000 * timeout)
  ctx.signal.addEventListener('abort', stop, { once: true })
  try {
    const exitCode = (await closed) ?? -1
    return {
      status: ctx.signal.aborted || timedOut ? 'UNKNOWN' : exitCode === 0 ? 'SUCCEEDED' : 'FAILED',
      content: {
        command,
        cwd: display(ctx, cwd),
        timedOut,
        exitCode,
        stdout: stdout.text(),
        stderr: stderr.text(),
      },
    }
  } finally {
    clearTimeout(timer)
    ctx.signal.removeEventListener('abort', stop)
  }
}
