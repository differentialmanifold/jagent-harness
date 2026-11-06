#!/usr/bin/env node
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { pathToFileURL } from 'node:url'

const id = value => {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) throw new Error('A valid ID is required')
  return encodeURIComponent(value)
}
const integer = (value, name, min, max) => {
  const number = Number(value)
  if (!Number.isInteger(number) || number < min || number > max) throw new Error(`${name} must be between ${min} and ${max}`)
  return number
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const agentSummary = ({ id, name, enabled, instructions, runnerId }) => ({ id, name, enabled, instructions, runnerId: runnerId ?? null })

export class JAgentError extends Error {
  constructor(message, { status, code, messageId } = {}) {
    super(message)
    this.name = 'JAgentError'
    this.status = status
    this.code = code
    this.messageId = messageId
  }
}

/** Dependency-free client for creating Personal Agents and delegating work to them. */
export class JAgentClient {
  constructor({ server = process.env.JAGENT_SERVER_URL || 'http://127.0.0.1:18181',
    token = process.env.JAGENT_CLIENT_TOKEN, timeoutMs = 15000 } = {}) {
    const url = new URL(server)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new Error('server must be an HTTP(S) base URL without credentials, query or fragment')
    }
    this.server = url.href.replace(/\/+$/, '')
    this.token = token
    this.timeoutMs = integer(timeoutMs, 'timeoutMs', 1, 300000)
  }

  async request(method, path, body, timeoutMs = this.timeoutMs) {
    let response
    try {
      response = await fetch(`${this.server}/api/v1${path}`, {
        method,
        redirect: 'error',
        signal: AbortSignal.timeout(timeoutMs),
        headers: {
          Accept: 'application/json',
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(this.token ? { Authorization: `Bearer ${this.token}` } : {})
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) })
      })
    } catch (error) {
      throw new JAgentError(method === 'GET'
        ? `Could not read JAgent (${error.name}). Check the server and connection.`
        : `JAgent did not confirm the request (${error.name}). Delivery is uncertain; inspect existing Agents or conversations before retrying.`,
      { code: 'CONNECTION_FAILED' })
    }
    const text = await response.text()
    let value
    try { value = text ? JSON.parse(text) : null } catch {
      throw new JAgentError('JAgent returned a non-JSON response', { status: response.status, code: 'INVALID_RESPONSE' })
    }
    if (!response.ok) throw new JAgentError(value?.message || `HTTP ${response.status}`, {
      status: response.status, code: value?.code || 'HTTP_ERROR'
    })
    return value
  }

  async agents() {
    const agents = await this.request('GET', '/personal/agents')
    return agents.map(agentSummary)
  }

  async runners() {
    const runners = await this.request('GET', '/runners')
    return runners.map(({ runnerId, name, online, runtime, workspaces, workspaceRoot, tools = [] }) => ({
      runnerId, name, online, runtime, workspaces, workspaceRoot,
      tools: tools.map(({ name, description }) => ({ name, description }))
    }))
  }

  async createAgent({ name, runnerId, instructions = '' } = {}) {
    if (typeof name !== 'string' || !name.trim() || name.length > 128) throw new Error('name must contain 1–128 characters')
    if (runnerId != null) id(runnerId)
    if (typeof instructions !== 'string' || instructions.length > 16000) throw new Error('instructions must be a string of at most 16000 characters')
    // Omit identity/revision fields: this always creates, never updates an existing Agent.
    const agent = await this.request('POST', '/personal/agents', { name: name.trim(), runnerId: runnerId ?? null, instructions })
    return agentSummary(agent)
  }

  conversations(agentId) { return this.request('GET', `/personal/agents/${id(agentId)}/conversations`) }

  createConversation(agentId, title = 'New conversation') {
    if (typeof title !== 'string' || !title.trim() || title.length > 256) throw new Error('title must contain 1–256 characters')
    return this.request('POST', `/personal/agents/${id(agentId)}/conversations`, { title })
  }

  async send(agentId, sessionId, text, { messageId = `msg_${randomUUID()}` } = {}) {
    if (typeof text !== 'string' || !text.trim() || text.length > 32000) throw new Error('text must contain 1–32000 characters')
    id(messageId)
    try {
      const message = await this.request('POST', `/personal/agents/${id(agentId)}/conversations/${id(sessionId)}`, { text, messageId })
      return { messageId: message.id, agentId: message.agentId, sessionId: message.sessionId, status: message.status }
    } catch (error) {
      error.messageId = messageId
      throw error
    }
  }

  status(agentId, sessionId, messageId, { timeoutMs = this.timeoutMs } = {}) {
    const query = messageId ? `?messageId=${id(messageId)}` : ''
    return this.request('GET', `/personal/agents/${id(agentId)}/conversations/${id(sessionId)}/status${query}`, undefined, timeoutMs)
  }

  history(agentId, sessionId, { after, limit = 40 } = {}) {
    const query = new URLSearchParams({ limit: String(integer(limit, 'limit', 1, 100)) })
    if (after) query.set('after', id(after))
    return this.request('GET', `/personal/agents/${id(agentId)}/conversations/${id(sessionId)}/history?${query}`)
  }

  async tasks(agentId, sessionId) {
    id(sessionId)
    // Verify the conversation even when there are no tasks.
    await this.status(agentId, sessionId)
    const tasks = await this.request('GET', `/personal/agents/${id(agentId)}/cards`)
    return tasks.filter(task => task.conversationId === sessionId).map(task => ({
      id: task.id, title: task.title, status: task.status, parentId: task.parentId,
      description: task.description, acceptance: task.acceptance, progress: task.progress,
      dependsOn: task.dependsOn, nextCheckAt: task.nextCheckAt, evidence: task.evidence
    }))
  }

  /** Wait for one message's decision turn. Background project work may continue afterwards. */
  async wait(agentId, sessionId, messageId, { timeoutMs = 30000, intervalMs = 2000 } = {}) {
    id(messageId)
    timeoutMs = integer(timeoutMs, 'timeoutMs', 1, 300000)
    intervalMs = integer(intervalMs, 'intervalMs', 100, 60000)
    const deadline = Date.now() + timeoutMs
    let status
    do {
      const remaining = Math.max(1, deadline - Date.now())
      try { status = await this.status(agentId, sessionId, messageId, { timeoutMs: Math.min(this.timeoutMs, remaining) }) }
      catch (error) {
        if (error.code === 'CONNECTION_FAILED' && Date.now() >= deadline) return { timedOut: true, messageId, ...(status || {}) }
        throw error
      }
      if (['DONE', 'FAILED', 'CANCELLED'].includes(status.message.status)) return { ...status, timedOut: false }
      const delay = Math.min(intervalMs, deadline - Date.now())
      if (delay <= 0) break
      await pause(delay)
    } while (Date.now() < deadline)
    return { ...status, timedOut: true }
  }
}

const help = `JAgent delegation client (Node.js 22+, no npm install)
Environment: JAGENT_SERVER_URL (default http://127.0.0.1:18181), JAGENT_CLIENT_TOKEN

  agents
  runners
  create-agent --name <name> [--runner <runnerId>] [--instructions <text> | --instructions-file <path>]
  conversations --agent <id>
  new --agent <id> --title <title>
  send --agent <id> --session <id> (--text <text> | --file <path|->) [--message <id>]
  status --agent <id> --session <id> [--message <id>]
  history --agent <id> --session <id> [--after <messageId>] [--limit <1-100>]
  tasks --agent <id> --session <id>
  wait --agent <id> --session <id> --message <id> [--timeout <seconds>] [--interval <seconds>]

All output is JSON. A send receipt confirms acceptance, not task completion.
create-agent creates an enabled Agent; omit --runner only for work using server tools.
wait observes one decision turn; status and tasks show ongoing background work.
Send follow-up requirements, including requests to stop, in the same conversation.
`

async function main(argv) {
  if (!argv.length || argv.includes('--help')) { process.stdout.write(help); return }
  const [command, ...args] = argv
  const allowed = {
    agents: [], runners: [], 'create-agent': ['name', 'runner', 'instructions', 'instructions-file'],
    conversations: ['agent'], new: ['agent', 'title'],
    send: ['agent', 'session', 'text', 'file', 'message'], status: ['agent', 'session', 'message'],
    history: ['agent', 'session', 'after', 'limit'], tasks: ['agent', 'session'],
    wait: ['agent', 'session', 'message', 'timeout', 'interval']
  }
  if (!Object.hasOwn(allowed, command)) throw new Error(`Unknown command: ${command}`)
  const options = {}
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i].startsWith('--') ? args[i].slice(2) : ''
    if (!allowed[command].includes(key) || args[i + 1] === undefined || Object.hasOwn(options, key)) throw new Error(`Invalid option: ${args[i]}`)
    options[key] = args[i + 1]
  }
  const client = new JAgentClient()
  let result
  switch (command) {
    case 'agents': result = await client.agents(); break
    case 'runners': result = await client.runners(); break
    case 'create-agent': {
      if (options.instructions !== undefined && options['instructions-file'] !== undefined) throw new Error('Provide only one of --instructions or --instructions-file')
      const instructions = options['instructions-file'] === undefined ? options.instructions : await readFile(options['instructions-file'], 'utf8')
      result = await client.createAgent({ name: options.name, runnerId: options.runner, instructions })
      break
    }
    case 'conversations': result = await client.conversations(options.agent); break
    case 'new': result = await client.createConversation(options.agent, options.title); break
    case 'send': {
      if ((options.text !== undefined) === (options.file !== undefined)) throw new Error('Provide exactly one of --text or --file')
      let content = options.text
      if (options.file === '-') {
        const chunks = []
        for await (const chunk of process.stdin) chunks.push(chunk)
        content = Buffer.concat(chunks).toString('utf8')
      } else if (options.file !== undefined) content = await readFile(options.file, 'utf8')
      result = await client.send(options.agent, options.session, content, { messageId: options.message })
      break
    }
    case 'status': result = await client.status(options.agent, options.session, options.message); break
    case 'history': result = await client.history(options.agent, options.session, { after: options.after, limit: options.limit }); break
    case 'tasks': result = await client.tasks(options.agent, options.session); break
    case 'wait': result = await client.wait(options.agent, options.session, options.message, {
      timeoutMs: options.timeout === undefined ? 30000 : Number(options.timeout) * 1000,
      intervalMs: options.interval === undefined ? 2000 : Number(options.interval) * 1000
    }); break
  }
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch(error => {
    process.stderr.write(`${JSON.stringify({ error: error.message, status: error.status, code: error.code, messageId: error.messageId })}\n`)
    process.exitCode = 1
  })
}
