#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { existsSync, readFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildRevision, harnessVersion } from './lib/build-info.mjs'
import { parseEnv } from 'node:util'
import { createServer, createConnection } from 'node:net'
import { setTimeout as sleep } from 'node:timers/promises'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const win = process.platform === 'win32'
const usage =
  'Usage: node scripts/start.mjs [local|harness|frontend] [--skip-build]\nRemote Runner: jagent runner --server <url> --credentials <file>'
const args = process.argv.slice(2)
if (args.includes('--help')) {
  console.log(usage)
  process.exit(0)
}
const [component = 'local', ...options] = args
if (!['local', 'harness', 'frontend'].includes(component) || options.some((x) => x !== '--skip-build')) {
  console.error(usage)
  process.exit(1)
}

async function launch() {
  const state = resolve(process.env.JAGENT_STATE_DIR || root)
  const localEnv = resolve(state, '.env.local')
  const env = {
    ...(existsSync(localEnv) ? parseEnv(readFileSync(localEnv, 'utf8')) : {}),
    ...process.env,
  }
  const local = component === 'local'
  const harness = local || component === 'harness'
  const frontend = local || component === 'frontend'
  const serverPort = Number(env.AGENT_SERVER_PORT || 18181)
  const frontendPort = Number(env.JAGENT_CONSOLE_PORT || 5175)
  const localServerUrl = 'http://127.0.0.1:' + serverPort
  const serverUrl = env.JAGENT_SERVER_URL || localServerUrl
  const consoleUrl = env.JAGENT_CONSOLE_URL || 'http://127.0.0.1:' + frontendPort
  const credentials = resolve(
    state,
    env.JAGENT_USER_CREDENTIALS || env.JAGENT_LOCAL_TOKEN_FILE || 'data/user.env',
  )
  const runnerHome = resolve(state, env.JAGENT_RUNNER_HOME || 'data/coding-runner')
  const toolsEnv = { ...env, JAGENT_SERVER_URL: serverUrl, JAGENT_CONSOLE_PORT: String(frontendPort) }
  for (const key of Object.keys(toolsEnv))
    if (
      /^JAGENT_(OPENAI_|MODEL|DATASOURCE|TEMPERATURE|COMPACTION|CONTEXT|CONFIG_ROOT|USER_TOKEN|USER_CREDENTIALS|LOCAL_TOKEN_FILE)/.test(
        key,
      )
    )
      delete toolsEnv[key]
  const processes = new Set()
  let stopping = false
  function start(command, args, cwd, childEnv = env, ipc = false) {
    const child = spawn(command, args, {
      cwd,
      env: childEnv,
      stdio: ipc ? ['inherit', 'inherit', 'inherit', 'ipc'] : 'inherit',
      detached: !win,
      shell: win && command.endsWith('.cmd'),
    })
    processes.add(child)
    child.once('error', () => processes.delete(child))
    child.on('exit', () => processes.delete(child))
    return child
  }
  function command(name, args, cwd = root, commandEnv = env) {
    return new Promise((resolveDone, reject) => {
      const child = start(
        win && ['mvn', 'npm'].includes(name) ? `${name}.cmd` : name,
        args,
        cwd,
        commandEnv,
      )
      child.once('error', reject)
      child.once('exit', (code) =>
        code === 0 ? resolveDone() : reject(new Error(`${name} exited with code ${code}`)),
      )
    })
  }
  function service(name, commandName, args, childEnv, cwd = state, ipc = false) {
    const child = start(commandName, args, cwd, childEnv, ipc)
    child.once('error', (error) => {
      console.error(`${name}: ${error.message}`)
      shutdown(1)
    })
    child.once('exit', (code) => {
      if (!stopping) {
        console.error(`${name} exited with code ${code}`)
        shutdown(code || 1)
      }
    })
    return child
  }
  async function available(port) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`Invalid port: ${port}`)
    // Some systems allow a loopback bind beside a wildcard listener. Check both connect and bind.
    await new Promise((resolveDone, reject) => {
      const probe = createConnection({ host: '127.0.0.1', port })
      probe.setTimeout(1000)
      probe.once('connect', () => {
        probe.destroy()
        reject(new Error(`Port ${port} is already serving another application`))
      })
      probe.once('error', (error) => {
        probe.destroy()
        error.code === 'ECONNREFUSED' ? resolveDone() : reject(error)
      })
      probe.once('timeout', () => {
        probe.destroy()
        reject(new Error(`Could not verify port ${port}`))
      })
    })
    await new Promise((resolveDone, reject) => {
      const probe = createServer()
      probe.once('error', () =>
        reject(
          new Error(
            `Port ${port} is occupied. Stop that instance or set another backend port in .env.local.`,
          ),
        ),
      )
      probe.listen(port, '127.0.0.1', () => probe.close(resolveDone))
    })
  }
  async function ready(url) {
    const deadline = Date.now() + 90000
    while (!stopping && Date.now() < deadline) {
      try {
        const response = await fetch(url + '/api/v1/health', {
          redirect: 'error',
          signal: AbortSignal.timeout(2000),
        })
        if (response.ok && (await response.json()).status === 'ok') return
      } catch {}
      await sleep(300)
    }
    throw new Error(stopping ? 'Startup stopped' : 'Timed out waiting for ' + url)
  }
  function shutdown(code = 0) {
    if (stopping) return
    stopping = true
    for (const child of processes) {
      if (!child.pid) continue
      if (win) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
      else {
        try {
          process.kill(-child.pid, 'SIGTERM')
        } catch (error) {
          if (error.code !== 'ESRCH') console.error(error.message)
        }
      }
    }
    process.exitCode = code
    const deadline = setTimeout(() => {
      for (const child of processes) {
        try {
          if (win) child.kill('SIGKILL')
          else process.kill(-child.pid, 'SIGKILL')
        } catch {}
      }
      process.exit(code)
    }, 4000)
    deadline.unref()
  }
  process.on('SIGINT', () => shutdown(0))
  process.on('SIGTERM', () => shutdown(0))

  try {
    if (harness && (!env.JAGENT_MODEL || !env.JAGENT_OPENAI_BASE_URL))
      throw new Error('Set JAGENT_MODEL and JAGENT_OPENAI_BASE_URL in .env.local or the shell')
    if (local && ![localServerUrl, 'http://localhost:' + serverPort].includes(new URL(serverUrl).origin))
      throw new Error(
        'For a remote Harness, start the frontend and run jagent runner on the execution machine',
      )
    if (local && frontendPort === serverPort)
      throw new Error('Server and Console ports must be distinct')
    if (harness) await available(serverPort)
    if (frontend) await available(frontendPort)
    const build = !options.includes('--skip-build')
    if (build && harness)
      await command('mvn', [
        '-q',
        '-pl',
        'server',
        '-am',
        '-DskipTests',
        '-Dharness.source.revision=' + buildRevision(root),
        'package',
      ])
    if (build && frontend && !existsSync(resolve(root, 'console/node_modules')))
      await command('npm', ['ci'], resolve(root, 'console'), toolsEnv)
    if (build && local) {
      const sdk = resolve(root, 'sdk/node')
      if (!existsSync(resolve(sdk, 'node_modules'))) await command('npm', ['ci'], sdk, toolsEnv)
      await command('npm', ['run', 'build'], sdk, toolsEnv)
    }
    if (harness) {
      mkdirSync(resolve(state, 'data'), { recursive: true })
      const jar = 'server/target/jagent-server-' + harnessVersion(root) + '.jar'
      service('Harness', 'java', ['-jar', resolve(root, jar)], {
        ...env,
        AGENT_SERVER_PORT: String(serverPort),
        JAGENT_DATASOURCE_URL: env.JAGENT_DATASOURCE_URL || 'jdbc:sqlite:data/agent-server.db',
        JAGENT_LOCAL_TOKEN_FILE: resolve(state, env.JAGENT_LOCAL_TOKEN_FILE || 'data/user.env'),
      })
      if (local) await ready(localServerUrl)
      console.log('Harness: ' + localServerUrl)
    }
    if (frontend) {
      service(
        'Console',
        process.execPath,
        [
          resolve(root, 'console/node_modules/vite/bin/vite.js'),
          '--strictPort',
          '--port',
          String(frontendPort),
          '--host',
          env.JAGENT_CONSOLE_HOST || '127.0.0.1',
        ],
        toolsEnv,
        resolve(root, 'console'),
      )
      console.log('Console port: ' + frontendPort + ' (Harness: ' + serverUrl + ')')
    }
    if (local) {
      await ready('http://127.0.0.1:' + frontendPort)
      const cli = resolve(root, 'sdk/node/dist/cli.js')
      const runnerEnv = {
        ...toolsEnv,
        JAGENT_CONSOLE_URL: consoleUrl,
        JAGENT_RUNNER_NAME: env.JAGENT_RUNNER_NAME || 'coding-local',
      }
      const flags = ['runner', '--home', runnerHome]
      if (env.JAGENT_USER_TOKEN) runnerEnv.JAGENT_USER_TOKEN = env.JAGENT_USER_TOKEN
      else flags.push('--credentials', credentials)
      const runner = service('Runner', process.execPath, [cli, ...flags], runnerEnv, state, true)
      runner.once('message', (msg) => {
        if (msg?.type !== 'registered' || !msg.runnerId) return
        if (env.JAGENT_OPEN_BROWSER?.toLowerCase() !== 'false')
          command(process.execPath, [cli, 'open', '--profile', msg.profileId, '--console', consoleUrl], state, runnerEnv)
            .catch(e => { if (!stopping) console.error('Open the Console with jagent open:', e.message) })
      })
    }
  } catch (e) {
    console.error(e.message)
    shutdown(1)
  }
}
await launch()
