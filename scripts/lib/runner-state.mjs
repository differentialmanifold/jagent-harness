import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

// Keep in sync with AgentRunner.stateDirectory in the Java client SDK.
export function runnerStateDirectory(server, stateRoot) {
  const normalized = server.replace(/\/$/, '')
  const url = new URL(normalized)
  // Validate without reserializing: Java hashes the original origin spelling too.
  if (!/^https?:\/\/[^/?#]+\/?$/i.test(normalized) ||
      !['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      normalized.includes('@') || normalized.includes('?') || normalized.includes('#') ||
      (url.pathname !== '' && url.pathname !== '/')) {
    throw new Error('Use a plain http(s) server origin')
  }
  const root = resolve(stateRoot)
  const scoped = resolve(root, 'servers', createHash('sha256').update(normalized, 'utf8').digest('hex'))
  const legacy = resolve(root, 'config.json')
  if (!existsSync(resolve(scoped, 'config.json')) && existsSync(legacy)) {
    if (JSON.parse(readFileSync(legacy, 'utf8')).server === normalized) return root
  }
  return scoped
}
