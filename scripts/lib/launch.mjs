#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { existsSync, readFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseEnv } from 'node:util'
import { createServer, createConnection } from 'node:net'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const win = process.platform === 'win32'
const processes = new Set()
let stopping = false
const version = readFileSync(resolve(root, 'pom.xml'), 'utf8').match(/<version>([^<]+)<\/version>/)[1]
const serverVersion = readFileSync(resolve(root, 'examples/server-demo/pom.xml'), 'utf8').match(/<artifactId>server-demo<\/artifactId>\s*<version>([^<]+)<\/version>/)[1]
export async function launch(component) {
  const options = process.argv.slice(2)
  const supported = ['--skip-build', '--help', ...(component === 'all' ? ['--client-only'] : [])]
  if (options.includes('--help')) {
    console.log(`Start ${component === 'all' ? 'the server, coding client, and frontend' : component + ' only'}.\nOptions: ${supported.join(', ')}\nConfiguration: .env.local (shell environment takes precedence).`)
    return
  }
  const localEnv = resolve(root, '.env.local')
  if (existsSync(localEnv)) {
    for (const [key, value] of Object.entries(parseEnv(readFileSync(localEnv, 'utf8')))) {
      if (process.env[key] === undefined) process.env[key] = value
    }
  }
  const env = { ...process.env }
  const clientOnly = options.includes('--client-only')
  const startServer = component === 'server' || (component === 'all' && !clientOnly)
  const startClient = component === 'client' || component === 'all'
  const startFrontend = component === 'frontend' || component === 'all'
  const serverPort = Number(env.AGENT_SERVER_PORT || 18181)
  const clientPort = Number(env.CODING_CLIENT_PORT || 18180)
  const frontendPort = Number(env.JAGENT_CONSOLE_PORT || 5175)
  const serverUrl = env.JAGENT_SERVER_URL || `http://127.0.0.1:${serverPort}`
  const token = env.JAGENT_CLIENT_TOKEN || ''
  const localServerUrl = `http://127.0.0.1:${serverPort}`
  const clientUrl = `http://127.0.0.1:${clientPort}`
  const frontendTarget = component === 'frontend' ? (env.JAGENT_API_TARGET || clientUrl) : clientUrl

  function start(command, args, cwd, childEnv = env) {
    const child = spawn(command, args, { cwd, env: childEnv, stdio: 'inherit', detached: !win, shell: win && command.endsWith('.cmd') })
    processes.add(child)
    child.once('error', () => processes.delete(child))
    child.on('exit', () => processes.delete(child))
    return child
  }
  function command(name, args, cwd = root) {
    return new Promise((resolveDone, reject) => {
      const child = start(win ? `${name}.cmd` : name, args, cwd)
      child.once('error', reject)
      child.once('exit', code => code === 0 ? resolveDone() : reject(new Error(`${name} exited with code ${code}`)))
    })
  }
  function service(name, commandName, args, childEnv) {
    const child = start(commandName, args, root, childEnv)
    child.once('error', error => { console.error(`${name}: ${error.message}`); shutdown(1) })
    child.once('exit', code => { if (!stopping) { console.error(`${name} exited with code ${code}`); shutdown(code || 1) } })
  }
  async function available(port) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`Invalid port: ${port}`)
    // Some systems allow a loopback bind beside a wildcard listener. Check both connect and bind.
    await new Promise((resolveDone, reject) => {
      const probe = createConnection({ host: '127.0.0.1', port })
      probe.setTimeout(1000)
      probe.once('connect', () => { probe.destroy(); reject(new Error(`Port ${port} is already serving another application`)) })
      probe.once('error', error => { probe.destroy(); error.code === 'ECONNREFUSED' ? resolveDone() : reject(error) })
      probe.once('timeout', () => { probe.destroy(); reject(new Error(`Could not verify port ${port}`)) })
    })
    await new Promise((resolveDone, reject) => {
      const probe = createServer()
      probe.once('error', () => reject(new Error(`Port ${port} is occupied. Stop that instance or set another backend port in .env.local.`)))
      probe.listen(port, '127.0.0.1', () => probe.close(resolveDone))
    })
  }
  async function ready(url) {
    const end = Date.now() + 90000
    while (!stopping && Date.now() < end) {
      try {
        const response = await fetch(`${url}/api/v1/sessions`, { headers: token ? { Authorization: `Bearer ${token}` } : {}, signal: AbortSignal.timeout(2000) })
        if (response.ok) return
        if (response.status === 401) throw new Error('Client/server token mismatch')
      } catch (error) {
        if (error.message === 'Client/server token mismatch') throw error
      }
      await new Promise(resolveDone => setTimeout(resolveDone, 500))
    }
    throw new Error(`Timed out waiting for ${url}`)
  }
  function shutdown(code = 0) {
    if (stopping) return
    stopping = true
    for (const child of processes) {
      if (!child.pid) continue
      if (win) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
      else { try { process.kill(-child.pid, 'SIGTERM') } catch (error) { if (error.code !== 'ESRCH') console.error(error.message) } }
    }
    setTimeout(() => {
      for (const child of processes) { try { if (win) child.kill('SIGKILL'); else process.kill(-child.pid, 'SIGKILL') } catch {} }
      process.exit(code)
    }, 1500)
  }
  process.on('SIGINT', () => shutdown(0))
  process.on('SIGTERM', () => shutdown(0))

  try {
    for (const option of options) {
      if (!supported.includes(option)) throw new Error(`Unknown option: ${option}. Supported options: ${supported.join(', ')}.`)
    }
    const [major, minor] = process.versions.node.split('.').map(Number)
    if (!((major === 20 && minor >= 19) || (major === 22 && minor >= 12) || major > 22)) throw new Error('Node.js ^20.19 or >=22.12 is required')
    if (startServer && (!env.JAGENT_MODEL || !env.JAGENT_OPENAI_BASE_URL)) throw new Error('Set JAGENT_MODEL and JAGENT_OPENAI_BASE_URL in .env.local; see .env.example')
    if (startServer && startClient && new URL(serverUrl).origin !== new URL(localServerUrl).origin && new URL(serverUrl).origin !== `http://localhost:${serverPort}`) {
      throw new Error('JAGENT_SERVER_URL must match AGENT_SERVER_PORT for an all-in-one launch. Use --client-only to connect to a remote server.')
    }
    const ports = [...(startServer ? [serverPort] : []), ...(startClient ? [clientPort] : []), ...(startFrontend ? [frontendPort] : [])]
    if (new Set(ports).size !== ports.length) throw new Error('Server, client, and console ports must be distinct')
    for (const port of ports) await available(port)
    if (startServer) mkdirSync(resolve(root, 'data'), { recursive: true })
    if (!options.includes('--skip-build') && (startServer || startClient)) {
      const modules = [...(startServer ? ['examples/server-demo'] : []), ...(startClient ? ['examples/coding-java'] : [])]
      await command('mvn', ['-q', '-pl', modules.join(','), '-am', '-DskipTests', 'package'])
    }
    if (startFrontend && !existsSync(resolve(root, 'console/node_modules'))) await command('npm', ['ci'], resolve(root, 'console'))
    if (startServer) {
      console.log(`Starting Java Agent server at ${localServerUrl}`)
      service('Agent server', 'java', ['-jar', `examples/server-demo/target/server-demo-${serverVersion}.jar`], {
        ...env, AGENT_SERVER_PORT: String(serverPort), JAGENT_DATASOURCE_URL: env.JAGENT_DATASOURCE_URL || 'jdbc:sqlite:data/agent-server.db',
        JAGENT_CONFIG_ROOT: env.JAGENT_CONFIG_ROOT || resolve(root, 'data/server-config')
      })
      await ready(localServerUrl)
    }
    const clientEnv = { ...env, CODING_CLIENT_PORT: String(clientPort), JAGENT_SERVER_URL: serverUrl, JAGENT_CONSOLE_PORT: String(frontendPort) }
    // Model and database settings belong to the server, not the client or frontend.
    for (const key of Object.keys(clientEnv)) if (/^JAGENT_(OPENAI_|MODEL|DATASOURCE|TEMPERATURE|COMPACTION|CONTEXT|CONFIG_ROOT)/.test(key)) delete clientEnv[key]
    if (startClient) {
      await ready(serverUrl)
      console.log(`Starting Coding client at ${clientUrl}`)
      service('Coding client', 'java', ['-jar', `examples/coding-java/target/jagent-coding-java-${version}.jar`], clientEnv)
      await ready(clientUrl)
    }
    if (startFrontend) {
      const ui = start(win ? 'npm.cmd' : 'npm', ['run', 'dev:coding', '--', '--port', String(frontendPort)], resolve(root, 'console'), { ...clientEnv, JAGENT_API_TARGET: frontendTarget })
      ui.once('error', error => { console.error(error.message); shutdown(1) })
      ui.once('exit', code => { if (!stopping) shutdown(code || 1) })
      console.log(`\nFrontend: http://127.0.0.1:${frontendPort} (API: ${frontendTarget})`)
    }
    console.log('Ctrl+C stops only the processes started by this launcher.\n')
  } catch (error) {
    console.error(error.message)
    shutdown(1)
  }
}
