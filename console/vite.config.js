import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, urlToHttpOptions } from 'node:url'
import pkg from './package.json' with { type: 'json' }
import { buildRevision } from '../scripts/lib/build-info.mjs'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'JAGENT_')
  const apiTarget = process.env.JAGENT_SERVER_URL || env.JAGENT_SERVER_URL || 'http://127.0.0.1:18181'
  const apiUrl = new URL(apiTarget)
  // http-proxy-3 passes hostname directly to http.request; IPv6 must omit brackets.
  const target = { ...urlToHttpOptions(apiUrl), host: apiUrl.host }
  const proxy = { '/api': { target } }
  return {
    plugins: [vue(), {
      name: 'console-build-info',
      apply: 'build',
      generateBundle() {
        const info = { version: pkg.version, revision: buildRevision(fileURLToPath(new URL('../', import.meta.url))) }
        this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify(info) + '\n' })
      }
    }],
    server: { port: 5175, proxy },
    preview: { port: 4175, proxy }
  }
})
