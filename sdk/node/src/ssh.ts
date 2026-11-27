import { spawn } from 'node:child_process'
import * as http from 'node:http'
import * as https from 'node:https'
import { isIP } from 'node:net'
import { resolve } from 'node:path'
import { Duplex, Readable } from 'node:stream'
import { checkServerIdentity, connect } from 'node:tls'

export function routeKey(server: string, ssh?: string, config?: string) {
  return ssh ? JSON.stringify([ssh, config ? resolve(config) : null, server]) : server
}

/** OpenSSH stdio forwarding: each pooled HTTP connection owns a channel, with no local listener. */
export class SshTunnel {
  private readonly agent: http.Agent
  private readonly channels = new Set<Duplex>()
  readonly key: string
  private closed = false
  constructor(
    private destination: string,
    private target: URL,
    private config?: string,
  ) {
    if (
      !/^(?:[a-zA-Z0-9_][a-zA-Z0-9_.-]*@)?(?:[a-zA-Z0-9_][a-zA-Z0-9_.:-]*|\[[0-9a-fA-F:]+\])$/.test(
        destination,
      )
    )
      throw new Error('Expected --ssh [user@]host or an SSH config alias')
    this.key = routeKey(target.origin, destination, config)
    const secure = target.protocol === 'https:'
    const Agent = secure ? https.Agent : http.Agent
    this.agent = new Agent({ keepAlive: true, maxFreeSockets: 2 })
    this.agent.createConnection = () => {
      const stream = this.channel()
      if (!secure) return stream
      const host = this.target.hostname.replace(/^\[|\]$/g, '')
      return connect({
        socket: stream,
        host,
        servername: isIP(host) ? undefined : host,
        checkServerIdentity: (_name, cert) => checkServerIdentity(host, cert),
      })
    }
    if (!secure) {
      // The channel is a Duplex, not a TCP socket. OpenSSH owns TCP keepalives.
      this.agent.keepSocketAlive = () => true
      this.agent.reuseSocket = (_stream, req) => {
        req.reusedSocket = true
      }
    }
  }

  private channel() {
    const port = this.target.port || (this.target.protocol === 'https:' ? '443' : '80')
    const child = spawn(
      'ssh',
      [
        ...(this.config ? ['-F', resolve(this.config)] : []),
        '-T',
        '-o',
        process.stdin.isTTY ? 'StrictHostKeyChecking=ask' : 'StrictHostKeyChecking=yes',
        ...(!process.stdin.isTTY ? ['-o', 'BatchMode=yes'] : []),
        '-o',
        'ConnectTimeout=10',
        '-o',
        'ServerAliveInterval=15',
        '-o',
        'ServerAliveCountMax=3',
        '-W',
        this.target.hostname + ':' + port,
        '--',
        this.destination,
      ],
      { stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true },
    )
    const stream = Duplex.from({ readable: child.stdout, writable: child.stdin })
    this.channels.add(stream)
    child.on('error', (error) => stream.destroy(new Error('Cannot start OpenSSH: ' + error.message)))
    child.on('close', (code) => {
      if (code) stream.destroy(new Error('OpenSSH exited with status ' + code))
    })
    stream.once('close', () => {
      this.channels.delete(stream)
      child.kill()
    })
    return stream
  }

  exchange(
    path: string,
    headers: Record<string, string>,
    body: string | undefined,
    signal: AbortSignal,
  ) {
    if (this.closed) throw new Error('SSH tunnel is closed')
    const transport = this.target.protocol === 'https:' ? https : http
    return new Promise<Response>((resolve, reject) => {
      const req = transport.request(
        new URL(path, this.target),
        {
          method: body === undefined ? 'GET' : 'POST',
          headers,
          agent: this.agent,
          signal,
        },
        (res) => {
          const status = res.statusCode!
          if ([204, 205, 304].includes(status)) {
            res.resume()
            resolve(new Response(null, { status }))
          } else {
            resolve(new Response(Readable.toWeb(res) as ReadableStream<Uint8Array>, { status }))
          }
        },
      )
      req.on('error', reject)
      req.end(body)
    })
  }

  close() {
    this.closed = true
    this.agent.destroy()
    for (const stream of this.channels) stream.destroy()
  }
}
