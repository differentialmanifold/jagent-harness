import http from 'node:http'
import https from 'node:https'
import { createReadStream } from 'node:fs'
import { realpath, stat } from 'node:fs/promises'
import { dirname, extname, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

// Static UI and same-origin API proxy. Each browser supplies its own session cookie.
export function createConsoleServer(directory, serverUrl) {
  const upstream = new URL(serverUrl)
  const transport = upstream.protocol === 'https:' ? https : http
  const root = resolve(directory)
  const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' }
  return http.createServer(async (req, res) => {
    if (req.url.startsWith('/api/')) {
      const headers = { ...req.headers }
      const proxy = transport.request(new URL(req.url, upstream), { method: req.method, headers }, response => {
        res.writeHead(response.statusCode, response.headers)
        response.pipe(res)
        response.on('error', () => res.destroy())
      })
      proxy.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('Harness unavailable') })
      req.on('aborted', () => proxy.destroy())
      res.on('close', () => proxy.destroy())
      req.pipe(proxy)
      return
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405); res.end(); return }
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname)
      const file = await realpath(resolve(root, `.${path === '/' ? '/index.html' : path}`))
      if (!file.startsWith((await realpath(root)) + sep) || !(await stat(file)).isFile()) throw new Error('Not found')
      res.writeHead(200, { 'Content-Type': types[extname(file)] || 'application/octet-stream', 'X-Content-Type-Options': 'nosniff' })
      if (req.method === 'HEAD') res.end()
      else createReadStream(file).on('error', () => res.destroy()).pipe(res)
    } catch { res.writeHead(404); res.end('Not found') }
  })
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const root = dirname(fileURLToPath(import.meta.url))
  const port = Number(process.env.JAGENT_CONSOLE_PORT || 5175)
  const host = process.env.JAGENT_CONSOLE_HOST || '127.0.0.1'
  const server = createConsoleServer(resolve(root, 'dist'), process.env.JAGENT_SERVER_URL || 'http://127.0.0.1:18181')
  server.listen(port, host, () => console.log(`Console: http://${host}:${port}`))
}
