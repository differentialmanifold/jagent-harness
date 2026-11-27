import { syncSource } from './source.js'
import { SshTunnel } from './ssh.js'
import { deferred } from './deferred.js'
import { hostname, homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { randomBytes, randomUUID } from 'node:crypto'
import { isDeepStrictEqual } from 'node:util'
import { setTimeout as sleep } from 'node:timers/promises'
import { digest, Journal, lease, load, save, type Identity } from './files.js'
import { MAX_RESULT_BYTES, preview } from './output.js'
import { Harness, HttpError, origin } from './http.js'
import {
  errorText,
  unknown,
  type ChatResponse,
  type ChatRequest,
  type Job,
  type Poll,
  type ToolCall,
  type ToolDefinition,
  type ToolDescriptor,
  type ToolResult,
  type Workspaces,
} from './types.js'

export interface RunnerOptions {
  server: string
  ssh?: string
  sshConfig?: string
  token: string
  home?: string
  userId?: string
  name?: string
  description?: string
  tools: ToolDefinition[]
  workspaces?: Workspaces
  capabilities?: string[]
  instructions?: string
  diagnostics?: Record<string, string>
  version?: string
  runnerSourceWorkspace?: string
  buildRevision?: string
  idleDisconnectSeconds?: number
  onStatus?: (text: string) => void
  onEvent?: (event: object) => void
  onRegistered?: (runner: AgentRunner) => void | Promise<void>
}
type Receipt = ChatResponse | { started: true }
const terminal = new Set(['COMPLETED', 'CANCELLED', 'INTERRUPTED'])
const descriptor = ({ name, description, parameters }: ToolDescriptor) => ({
  name,
  description,
  parameters,
})
function toolMessage(call: ToolCall, result: ToolResult) {
  const text = typeof result.content === 'string' ? result.content : JSON.stringify(result.content)
  const content =
    text && Buffer.byteLength(text) > MAX_RESULT_BYTES ? preview(text, MAX_RESULT_BYTES) : result.content
  return { role: 'tool', toolCallId: call.toolCallId, ...result, content }
}
export async function connection(
  server: string,
  token: string,
  home: string,
  expectedUser?: string,
  tunnel?: SshTunnel,
) {
  server = origin(server)
  const { userId } = await new Harness(server, token, undefined, tunnel).request<{ userId: string }>(
    'access/identity',
  )
  if (!userId || (expectedUser && userId !== expectedUser))
    throw new Error('Credential does not match the Runner profile')
  if (!/^[A-Za-z0-9_-]+$/.test(userId)) throw new Error('Invalid user identity')
  const root = resolve(home)
  let base = join(root, 'servers', digest(tunnel?.key ?? server))
  // Existing identities retain their project and mainbot bindings across upgrades.
  if (
    !tunnel &&
    !(await load<Identity>(join(base, 'config.json'))) &&
    (await load<Identity>(join(root, 'config.json')))?.server === server
  )
    base = root
  const preview = join(base, digest(userId))
  const dir = (await load<Identity>(join(preview, 'config.json')))
    ? preview
    : userId === 'local'
      ? base
      : join(base, 'users', userId)
  return { server, userId, dir }
}

export class AgentRunner {
  readonly journal: Journal
  readonly http: Harness
  readonly endpoint: string
  private readonly stopped = new AbortController()
  private active = new Map<string, { abort: AbortController; done: Promise<void> }>()
  private catalog: Map<string, ToolDefinition>
  private closed = false
  private running = false
  private registered = false
  private retrySourceAt = Infinity
  private readonly finished = deferred<void>()
  private constructor(
    readonly options: RunnerOptions,
    readonly identity: Identity,
    readonly userId: string,
    dir: string,
    private release: () => Promise<void>,
    private tunnel?: SshTunnel,
  ) {
    this.journal = new Journal(dir)
    this.http = new Harness(identity.server, options.token, identity, tunnel)
    this.endpoint = 'runners/' + identity.runnerId
    this.catalog = new Map(options.tools.map((tool) => [tool.name, tool]))
  }
  static async create(options: RunnerOptions) {
    if (!options.tools.length || new Set(options.tools.map((t) => t.name)).size !== options.tools.length)
      throw new Error('Register at least one tool with unique names')
    if (
      options.idleDisconnectSeconds !== undefined &&
      (!Number.isFinite(options.idleDisconnectSeconds) || options.idleDisconnectSeconds < 0)
    )
      throw new Error('Invalid idle disconnect timeout')
    if (options.sshConfig && !options.ssh) throw new Error('SSH config requires an SSH destination')
    const tunnel = options.ssh
      ? new SshTunnel(options.ssh, new URL(origin(options.server)), options.sshConfig)
      : undefined
    try {
      return await AgentRunner.connect(options, tunnel)
    } catch (error) {
      tunnel?.close()
      throw error
    }
  }

  private static async connect(options: RunnerOptions, tunnel?: SshTunnel) {
    const { server, userId, dir } = await connection(
      options.server,
      options.token,
      options.home ?? join(homedir(), '.jagent-runner'),
      options.userId,
      tunnel,
    )
    const release = await lease(join(dir, 'leases'))
    try {
      const file = join(dir, 'config.json')
      let identity = await load<Identity>(file)
      if (!identity) {
        identity = {
          server,
          runnerId: 'runner_' + randomUUID().replaceAll('-', ''),
          secret: randomBytes(32).toString('hex'),
        }
        await save(file, identity)
      }
      if (identity.server !== server || !/^runner_[\w-]+$/.test(identity.runnerId) || !identity.secret)
        throw new Error('Invalid saved Runner identity')
      return new AgentRunner(options, identity, userId, dir, release, tunnel)
    } catch (e) {
      await release()
      throw e
    }
  }
  private async register() {
    const o = this.options
    let source
    if (o.runnerSourceWorkspace) {
      try {
        source = await syncSource(this.http, join(this.journal.dir, 'sources'), this.stopped.signal)
        this.retrySourceAt = Infinity
      } catch (error) {
        if (this.stopped.signal.aborted) throw error
        // Older Harness versions can still run jobs. Check again on reconnect.
        this.retrySourceAt = error instanceof HttpError && error.status === 404 ? Infinity : Date.now() + 60000
        o.onStatus?.('Harness source unavailable: ' + errorText(error))
      }
    }
    await this.http.request(
      'runners',
      {
        runnerId: this.identity.runnerId,
        name: o.name ?? hostname(),
        runtime: 'node',
        platform: process.platform + ' ' + process.arch,
        version: o.version,
        buildRevision: o.buildRevision,
        runnerSourceWorkspace: o.runnerSourceWorkspace,
        ...source,
        protocolVersion: '1',
        description: o.description ?? '',
        capabilities: o.capabilities ?? [],
        workspaces: !!o.workspaces,
        workspaceRoot: o.workspaces?.root,
        instructions: o.instructions ?? '',
        tools: o.tools.map(descriptor),
        toolDiagnostics: o.diagnostics ?? {},
      },
      this.stopped.signal,
    )
    const first = !this.registered
    this.registered = true
    if (first) await o.onRegistered?.(this)
  }
  private validate(job: Job) {
    if (
      !job.request?.sessionId ||
      !/^[\w-]{1,64}$/.test(job.request.runId ?? '') ||
      job.runnerId !== this.identity.runnerId
    )
      throw new Error('Invalid job or Runner assignment')
    const allowed = new Map<string, ToolDefinition>()
    for (const desc of job.request.clientTools) {
      const tool = this.catalog.get(desc.name)
      if (!tool || !isDeepStrictEqual(desc.parameters, tool.parameters))
        throw new Error('Job requires a different tool catalog')
      allowed.set(desc.name, tool)
    }
    return allowed
  }
  private notify(event: object) {
    try {
      this.options.onEvent?.(event)
    } catch (e) {
      this.options.onStatus?.('Event listener failed: ' + errorText(e))
    }
  }
  private async converse(job: Job, request: ChatRequest, signal: AbortSignal): Promise<ChatResponse> {
    const { runId, sessionId } = request
    try {
      const allowed = this.validate(job)
      const workspace = await this.options.workspaces?.resolve(job.workspace)
      if (!request.messages?.length) throw new Error('Messages are required')
      if (signal.aborted && (request.messages[0] as { role?: string }).role !== 'tool')
        signal.throwIfAborted()
      for (;;) {
        const response = await this.http.chat(request, this.stopped.signal, (e) => this.notify(e))
        if (response.sessionId !== sessionId || response.runId !== runId)
          throw new Error('Mismatched chat response')
        if (terminal.has(response.status)) return response
        const calls = response.toolCalls
        if (response.status !== 'REQUIRES_ACTION' || !calls?.length)
          throw new Error('Invalid chat response')
        const seen = new Set<string>()
        for (const call of calls) {
          if (
            !call?.toolCallId ||
            seen.has(call.toolCallId) ||
            !allowed.has(call.name) ||
            !call.arguments ||
            Array.isArray(call.arguments) ||
            typeof call.arguments !== 'object'
          )
            throw new Error('Invalid or unadvertised tool call')
          seen.add(call.toolCallId)
        }
        const messages = []
        for (const call of calls) {
          const event = {
            eventId: 'client_' + call.toolCallId + '_tool_execution_start',
            type: 'tool_execution_start',
            sessionId,
            runId,
            turnId: call.turnId,
            createdAt: new Date().toISOString(),
            payload: { toolCallId: call.toolCallId, toolName: call.name },
          }
          this.notify(event)
          await this.http
            .request(this.endpoint + '/events', event, this.stopped.signal, 5000)
            .catch(() => this.options.onStatus?.('Tool progress event could not be delivered'))
          const result = await this.journal.execute(sessionId, call, async () => {
            if (signal.aborted) return { status: 'FAILED', content: 'Cancelled before execution' }
            return await allowed.get(call.name)!.execute(call.arguments, {
              sessionId,
              runId,
              workspace,
              signal,
              turnId: call.turnId,
              toolCallId: call.toolCallId,
              toolName: call.name,
            })
          })
          messages.push(toolMessage(call, result))
        }
        request = { ...request, messages }
      }
    } catch (e) {
      return {
        runId,
        sessionId,
        status: signal.aborted ? 'CANCELLED' : 'INTERRUPTED',
        answer: errorText(e),
      }
    }
  }
  private async complete(response: ChatResponse) {
    await save(this.journal.jobFile(response.runId), response)
    await this.http.request(this.endpoint + '/complete', response)
  }
  private async execute(job: Job, signal: AbortSignal) {
    const { runId, sessionId } = job.request
    const file = this.journal.jobFile(runId)
    const receipt =
      (await load<Receipt>(file)) ??
      (await load<Receipt>(join(this.journal.dir, 'jobs', digest(runId) + '.json')))
    let response: ChatResponse
    if (!receipt) {
      await save(file, { started: true })
      response = await this.converse(job, job.request, signal)
    } else if ('status' in receipt) response = receipt
    else
      response = {
        runId,
        sessionId,
        status: 'INTERRUPTED',
        answer:
          'Runner restarted after accepting this job. Inspect history and recover pending results.',
      }
    await this.complete(response)
  }
  async serve() {
    if (this.closed || this.running) throw new Error('Runner is closed or already active')
    this.running = true
    let reconnect = true,
      failures = 0,
      idleSince: number | undefined
    const signal = this.stopped.signal
    const idleLimit = (this.options.idleDisconnectSeconds ?? 86400) * 1000
    try {
      while (!signal.aborted) {
        try {
          if (reconnect || Date.now() >= this.retrySourceAt) {
            await this.register()
            reconnect = false
          }
          const query = new URLSearchParams({
            activeRun: [...this.active.keys()].join(','),
            availableSlots: '1',
          })
          const poll = await this.http.request<Poll>(this.endpoint + '/poll?' + query, undefined, signal)
          failures = 0
          idleSince = undefined
          for (const id of poll.cancelledRuns ?? []) this.active.get(id)?.abort.abort()
          if (poll.workspaceRequest) {
            const { path, requestId } = poll.workspaceRequest
            let listing
            try {
              if (!this.options.workspaces) throw new Error('This Runner has no workspaces')
              listing = await this.options.workspaces.browse(path)
            } catch (e) {
              listing = { error: errorText(e) }
            }
            await this.http.request(
              this.endpoint + '/workspaces/result',
              { ...listing, requestId },
              signal,
            )
          }
          const job = poll.job
          if (
            job &&
            job.runnerId === this.identity.runnerId &&
            /^[\w-]{1,64}$/.test(job.request?.runId ?? '') &&
            !this.active.has(job.request.runId)
          ) {
            const abort = new AbortController()
            if (job.cancelled) abort.abort()
            const done = this.execute(job, AbortSignal.any([abort.signal, signal]))
              .catch((e) => this.options.onStatus?.('Job delivery pending: ' + errorText(e)))
              .finally(() => {
                this.active.delete(job.request.runId)
                idleSince = undefined
              })
            this.active.set(job.request.runId, { abort, done })
          }
          if (poll.cancelledRuns?.length) await sleep(200, undefined, { signal })
        } catch (e) {
          if (signal.aborted) break
          if (e instanceof HttpError && [401, 403].includes(e.status)) throw e
          if (this.active.size) idleSince = undefined
          else idleSince ??= performance.now()
          const remaining =
            idleLimit && idleSince !== undefined ? idleLimit - (performance.now() - idleSince) : Infinity
          if (remaining <= 0) {
            this.options.onStatus?.('Idle disconnect timeout reached')
            break
          }
          reconnect = true
          this.options.onStatus?.('Reconnecting: ' + errorText(e))
          const delay =
            Math.min(60000, 1000 * 2 ** Math.min(failures++, 6)) * (0.8 + 0.2 * Math.random())
          await sleep(Math.min(remaining, delay), undefined, { signal }).catch(() => {})
        }
      }
    } finally {
      this.running = false
      await this.close()
    }
  }
  async recover(session: string) {
    if (this.running || this.closed) throw new Error('Stop the serving Runner before recovery')
    this.running = true
    try {
      await this.register()
      const pending = await this.http.request<ChatResponse>(
        'sessions/' + encodeURIComponent(session) + '/state',
      )
      if (
        pending.status !== 'REQUIRES_ACTION' ||
        pending.sessionId !== session ||
        !pending.toolCalls?.length
      )
        throw new Error('No pending tool results')
      const job = await this.http.request<Job>('runners/runs/' + encodeURIComponent(pending.runId))
      this.validate(job)
      if (job.request.sessionId !== session || job.request.runId !== pending.runId)
        throw new Error('Mismatched recovery job')
      const messages = []
      for (const call of pending.toolCalls)
        messages.push(
          toolMessage(
            call,
            (await this.journal.recorded(session, call)) ??
              unknown('No saved result; inspect the actual effects'),
          ),
        )
      await this.http.request(this.endpoint + '/runs/' + pending.runId + '/recover', {})
      await save(this.journal.jobFile(pending.runId), { started: true })
      const response = await this.converse(job, { ...job.request, messages }, this.stopped.signal)
      await this.complete(response)
      return response
    } finally {
      this.running = false
      await this.close()
    }
  }
  stop() {
    this.stopped.abort()
  }
  async close() {
    this.stop()
    if (this.running || this.closed) return this.finished.promise
    this.closed = true
    try {
      await Promise.all(Array.from(this.active.values(), (w) => w.done))
      if (this.registered)
        await this.http
          .request(this.endpoint + '/disconnect', {}, undefined, 3000)
          .catch((e) => this.options.onStatus?.('Could not report disconnect: ' + errorText(e)))
    } finally {
      try {
        await this.release()
      } finally {
        this.tunnel?.close()
        this.finished.resolve()
      }
    }
  }
}
