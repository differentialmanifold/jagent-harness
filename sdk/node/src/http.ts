import { createInterface } from 'node:readline'
import { Readable } from 'node:stream'
import type { ChatRequest, ChatResponse } from './types.js'
import type { SshTunnel } from './ssh.js'
export class HttpError extends Error {
  constructor(
    readonly status: number,
    detail: string,
  ) {
    super('Harness HTTP ' + status + ': ' + detail)
  }
}
export function origin(address: string) {
  const url = new URL(address)
  if (
    !['https:', 'http:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  )
    throw new Error(
      'Expected an http(s) Harness URL origin without credentials, path, query or fragment',
    )
  return url.href.replace(/\/+$/, '')
}
export class Harness {
  constructor(
    readonly server: string,
    private token: string,
    private identity?: { runnerId: string; secret: string },
    private tunnel?: SshTunnel,
  ) {}
  private async exchange(path: string, body: unknown, accept: string, signal: AbortSignal) {
    const headers: Record<string, string> = { Authorization: 'Bearer ' + this.token, Accept: accept }
    if (this.identity) {
      headers['X-Runner-Id'] = this.identity.runnerId
      headers['X-Runner-Secret'] = this.identity.secret
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const route = '/api/v1/' + path
    const payload = body === undefined ? undefined : JSON.stringify(body)
    const res = this.tunnel
      ? await this.tunnel.exchange(route, headers, payload, signal)
      : await fetch(this.server + route, {
          method: body === undefined ? 'GET' : 'POST',
          headers,
          redirect: 'error',
          body: payload,
          signal,
        })
    if (!res.ok) throw new HttpError(res.status, (await res.text()).slice(0, 1500))
    return res
  }
  async download(path: string, limit: number, signal: AbortSignal) {
    const timeout = AbortSignal.any([signal, AbortSignal.timeout(30000)])
    const res = await this.exchange(path, undefined, 'application/zip', timeout)
    if (!res.body) throw new Error('Missing source archive body')
    const chunks: Buffer[] = []
    let length = 0
    for await (const chunk of Readable.fromWeb(res.body)) {
      length += chunk.length
      if (length > limit) throw new Error('Source archive is too large')
      chunks.push(chunk)
    }
    return Buffer.concat(chunks, length)
  }
  async request<T>(path: string, body?: unknown, signal?: AbortSignal, timeout = 35000): Promise<T> {
    const deadline = AbortSignal.timeout(timeout)
    const res = await this.exchange(
      path,
      body,
      'application/json',
      signal ? AbortSignal.any([signal, deadline]) : deadline,
    )
    return (await res.json()) as T
  }
  async chat(
    body: ChatRequest,
    signal: AbortSignal,
    onEvent?: (event: object) => void,
  ): Promise<ChatResponse> {
    const res = await this.exchange(
      'runners/chat',
      body,
      body.stream ? 'text/event-stream' : 'application/json',
      signal,
    )
    if (!body.stream) return (await res.json()) as ChatResponse
    if (!res.body) throw new Error('Missing chat event stream')
    const stream = Readable.fromWeb(res.body)
    const lines = createInterface({ input: stream, crlfDelay: Infinity })
    let type = '',
      data: string[] = []
    try {
      for await (const line of lines) {
        if (line.startsWith('event:')) type = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
        else if (line === '') {
          if (data.length) {
            const event = JSON.parse(data.join('\n'))
            if (type === 'response') return event as ChatResponse
            if (type === 'error') throw new Error(event.message ?? 'Harness stream failed')
            onEvent?.(event)
          }
          type = ''
          data = []
        }
      }
      throw new Error('Chat stream ended before its terminal response')
    } finally {
      lines.close()
      stream.destroy()
    }
  }
}
