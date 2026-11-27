import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

export function harnessVersion(directory) {
  const pom = readFileSync(resolve(directory, 'pom.xml'), 'utf8')
  const version = pom.match(/<version>([^<]+)<\/version>/)?.[1]
  if (!version) throw new Error('Missing Harness version in pom.xml')
  return version
}

export function buildRevision(directory) {
  if (!existsSync(resolve(directory, '.git'))) return 'unknown'
  const options = { cwd: directory, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }
  try {
    const commit = execFileSync('git', ['rev-parse', 'HEAD'], options).trim()
    const changed = execFileSync('git', ['status', '--porcelain'], options).trim()
    return changed ? commit + '-dirty' : commit
  } catch {
    return 'unknown'
  }
}
