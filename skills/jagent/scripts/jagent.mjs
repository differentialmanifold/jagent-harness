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
const mainbotPath = '/personal/agents/agent_mainbot'

export class JAgentError extends Error {
  constructor(message, { status, code, messageId } = {}) {
    super(message)
    this.name = 'JAgentError'
    this.status = status
    this.code = code
    this.messageId = messageId
  }
}

const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value)
const isList = value => Array.isArray(value) && value.every(isObject)
const hasText = (value, key) => isObject(value) && typeof value[key] === 'string' && value[key].length > 0
const invalidResponse = status => new JAgentError('JAgent returned an invalid JSON response', { status, code: 'INVALID_RESPONSE' })

export class JAgentClient {
  constructor({ server = process.env.JAGENT_SERVER_URL || 'http://127.0.0.1:18181',
    token = process.env.JAGENT_USER_TOKEN, timeoutMs = 15000 } = {}) {
    const url = new URL(server)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new Error('server must be an HTTP(S) base URL without credentials, query or fragment')
    }
    this.server = url.href.replace(/\/+$/, '')
    this.token = token
    this.timeoutMs = integer(timeoutMs, 'timeoutMs', 1, 300000)
  }

  async #request(method, path, body, timeoutMs = this.timeoutMs, validate = isObject) {
    let response
    let text
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
      text = await response.text()
    } catch (error) {
      throw new JAgentError(method === 'GET'
        ? `Could not read JAgent (${error.name}). Check the server and connection.`
        : `JAgent did not confirm the request (${error.name}). Delivery is uncertain; check the mainbot conversation before retrying.`,
      { code: 'CONNECTION_FAILED' })
    }
    let value
    try { value = JSON.parse(text) } catch {
      if (response.ok) throw invalidResponse(response.status)
    }
    if (!response.ok) throw new JAgentError(value?.message || `HTTP ${response.status}`, {
      status: response.status, code: value?.code || 'HTTP_ERROR'
    })
    if (!validate(value)) throw invalidResponse(response.status)
    return value
  }

  async models() {
    const catalog = await this.#request('GET', '/models', undefined, this.timeoutMs, value => hasText(value, 'defaultModelId') && isList(value.models))
    return { defaultModelId: catalog.defaultModelId, models: catalog.models.filter(m => m.enabled).map(({ id, name, description, inputTypes, capabilities, contextWindowTokens }) => ({ id, name, description, inputTypes, capabilities, contextWindowTokens })) }
  }

  conversations() { return this.#request('GET', `${mainbotPath}/conversations`, undefined, this.timeoutMs, isList) }

  createConversation(title = 'New conversation') {
    if (typeof title !== 'string' || !title.trim() || title.length > 256) throw new Error('title must contain 1–256 characters')
    return this.#request('POST', `${mainbotPath}/conversations`, { title }, this.timeoutMs, value => hasText(value, 'sessionId'))
  }

  async send(sessionId, text, { messageId = `msg_${randomUUID()}`, modelId, images = [] } = {}) {
    if (typeof text !== 'string' || (!text.trim() && !images.length) || text.length > 32000) throw new Error('Provide text (up to 32000 characters) or images')
    if (!Array.isArray(images) || images.length > 4) throw new Error('images must be an array of at most four images')
    if (modelId != null) id(modelId)
    id(messageId)
    try {
      const message = await this.#request('POST', `${mainbotPath}/conversations/${id(sessionId)}`, { text, messageId, ...(modelId ? { modelId } : {}), ...(images.length ? { images } : {}) }, this.timeoutMs, value => hasText(value, 'id') && hasText(value, 'sessionId') && hasText(value, 'agentId') && hasText(value, 'status'))
      return { messageId: message.id, agentId: message.agentId, sessionId: message.sessionId, status: message.status }
    } catch (error) {
      error.messageId = messageId
      throw error
    }
  }

  status(sessionId, messageId, { timeoutMs = this.timeoutMs } = {}) {
    const query = messageId ? `?messageId=${id(messageId)}` : ''
    return this.#request('GET', `${mainbotPath}/conversations/${id(sessionId)}/status${query}`, undefined, timeoutMs, value => isObject(value) && (!messageId || hasText(value.message, 'status')))
  }

  history(sessionId, { after, limit = 40 } = {}) {
    const query = new URLSearchParams({ limit: String(integer(limit, 'limit', 1, 100)) })
    if (after) query.set('after', id(after))
    return this.#request('GET', `${mainbotPath}/conversations/${id(sessionId)}/history?${query}`, undefined, this.timeoutMs, value => isObject(value) && isList(value.messages))
  }

  async tasks(sessionId) {
    id(sessionId)
    // Verify the conversation even when there are no tasks.
    await this.status(sessionId)
    const tasks = await this.#request('GET', `${mainbotPath}/cards`, undefined, this.timeoutMs, isList)
    return tasks.filter(task => task.conversationId === sessionId).map(task => ({
      id: task.id, title: task.title, status: task.status, parentId: task.parentId,
      description: task.description, acceptance: task.acceptance, progress: task.progress,
      dependsOn: task.dependsOn, nextCheckAt: task.nextCheckAt, evidence: task.evidence
    }))
  }

  /** Wait for one message's decision turn. Background project work may continue afterwards. */
  async wait(sessionId, messageId, { timeoutMs = 30000, intervalMs = 2000 } = {}) {
    id(messageId)
    timeoutMs = integer(timeoutMs, 'timeoutMs', 1, 300000)
    intervalMs = integer(intervalMs, 'intervalMs', 100, 60000)
    const deadline = Date.now() + timeoutMs
    let status
    do {
      const remaining = Math.max(1, deadline - Date.now())
      try { status = await this.status(sessionId, messageId, { timeoutMs: Math.min(this.timeoutMs, remaining) }) }
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

const help = `JAgent mainbot client (Node.js ^20.19.0 or >=22.12.0, no npm install)
Environment: JAGENT_SERVER_URL (default http://127.0.0.1:18181), JAGENT_USER_TOKEN

  models
  conversations
  new --title <title>
  send --session <id> (--text <text> | --file <path|->) [--message <id>] [--model <modelId>]
  status --session <id> [--message <id>]
  history --session <id> [--after <messageId>] [--limit <1-100>]
  tasks --session <id>
  wait --session <id> --message <id> [--timeout <seconds>] [--interval <seconds>]

All output is JSON. A send receipt confirms acceptance, not task completion.
wait observes one decision turn; status and tasks show ongoing background work.
Send follow-up requirements, including requests to stop, in the same conversation.
`

async function main(argv) {
  if (!argv.length || argv.includes('--help')) { process.stdout.write(help); return }
  const [command, ...args] = argv
  const allowed = {
    models: [],
    conversations: [], new: ['title'],
    send: ['session', 'text', 'file', 'message', 'model'], status: ['session', 'message'],
    history: ['session', 'after', 'limit'], tasks: ['session'],
    wait: ['session', 'message', 'timeout', 'interval']
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
    case 'models': result = await client.models(); break
    case 'conversations': result = await client.conversations(); break
    case 'new': result = await client.createConversation(options.title); break
    case 'send': {
      if ((options.text !== undefined) === (options.file !== undefined)) throw new Error('Provide exactly one of --text or --file')
      let content = options.text
      if (options.file === '-') {
        const chunks = []
        for await (const chunk of process.stdin) chunks.push(chunk)
        content = Buffer.concat(chunks).toString('utf8')
      } else if (options.file !== undefined) content = await readFile(options.file, 'utf8')
      result = await client.send(options.session, content, { messageId: options.message, modelId: options.model })
      break
    }
    case 'status': result = await client.status(options.session, options.message); break
    case 'history': result = await client.history(options.session, { after: options.after, limit: options.limit }); break
    case 'tasks': result = await client.tasks(options.session); break
    case 'wait': result = await client.wait(options.session, options.message, {
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
