import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

export function sourceInfo(directory) {
  const version = readFileSync(resolve(directory, 'pom.xml'), 'utf8').match(/<version>([^<]+)<\/version>/)?.[1]
  let revision = 'unknown'
  try {
    revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: directory, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
    if (execFileSync('git', ['status', '--porcelain'], { cwd: directory, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()) revision += '-dirty'
  } catch {}
  return { version, revision }
}

export async function setupMainbot(server, token, runnerId, directory, source, fetcher = fetch) {
  const response = await fetcher(`${server}/api/v1/personal/mainbot/setup`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ runnerId, sourceWorkspace: directory, sourceVersion: source.version, sourceRevision: source.revision }),
    signal: AbortSignal.timeout(40000),
  })
  const result = await response.json()
  if (response.status === 409 && result.code === 'MAINBOT_ALREADY_BOUND') return { retained: true, message: result.message }
  if (!response.ok) throw new Error(result.message || `mainbot setup failed: HTTP ${response.status}`)
  return { retained: false, agent: result }
}
