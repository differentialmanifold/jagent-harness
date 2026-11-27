import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { buildRevision } from './lib/build-info.mjs'

const roots = new Set(['jagent-api', 'jagent-harness-sdk', 'server', 'console', 'sdk',
  'protocol', 'docs', 'skills', 'scripts', 'assets', '.github'])
const rootFiles = new Set(['README.md', 'README.zh-CN.md', 'QUICK_START.md', 'CONTRIBUTING.md',
  'LICENSE', 'pom.xml', '.env.example', '.gitignore', '.gitattributes'])
const skip = new Set(['target', 'dist', 'data', 'node_modules', 'coverage'])

// A source build can contain new implementation files before their first commit.
// Local configuration, notes and unrelated untracked files stay out of the archive.
const implementation = [
  /^(?:server|jagent-api|jagent-harness-sdk\/[^/]+)\/src\/(?:main|test)\/java\/.+\.java$/,
  /^(?:sdk\/node|console)\/src\/.+\.(?:ts|js|vue|css)$/,
  /^(?:sdk\/node|console)\/test\/.+\.test\.mjs$/,
  /^scripts\/(?:lib\/)?[\w.-]+\.mjs$/,
  /^server\/(?:README\.md|pom\.xml|src\/main\/resources\/application\.yml)$/,
  /^console\/serve\.mjs$/,
]
const sourceExport = [
  /^(?:docs|skills|assets|\.github\/workflows)\/.+\.(?:md|mjs|svg|yml)$/,
  /^(?:server|jagent-[^/]+)(?:\/[^/]+)?\/(?:README\.md|pom\.xml)$/,
  /^(?:server|jagent-harness-sdk\/[^/]+)\/src\/(?:main|test)\/(?:build-resources|resources)\/.+\.(?:sql|md|imports|properties|playwright-cli)$/,
  /^(?:sdk\/node|console|protocol)\/(?:README(?:\.zh-CN)?\.md|LICENSE|(?:package(?:-lock)?|tsconfig)\.json)$/,
  /^protocol\/(?:openapi\.json|schema\.json|schema\.test\.mjs|fixtures\/[\w-]+\.json)$/,
  /^console\/(?:index\.html|vite\.config\.js)$/,
  /^sdk\/node\/src\/coding\/catalog\.json$/,
  /^scripts\/(?:README\.md|macos\/start\.command|windows\/start\.cmd)$/,
]

let entries
if (existsSync('.git')) {
  const listing = execFileSync('git', ['ls-files', '-t', '-z', '--cached', '--others', '--exclude-standard'],
    { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })
  entries = new Map(listing.split('\0').filter(Boolean).map(line => [line.slice(2), line[0]]))
}
function include(path) {
  // In a Git checkout, respect ignored files even under a source directory.
  if (entries && !entries.has(path)) return false
  if (rootFiles.has(path) || (entries && entries.get(path) !== '?')) return true
  return implementation.some(pattern => pattern.test(path))
    || (!entries && sourceExport.some(pattern => pattern.test(path)))
}

const files = []
function visit(directory) {
  for (const item of readdirSync(directory || '.', { withFileTypes: true })) {
    const name = item.name
    const path = directory ? directory + '/' + name : name
    if (!directory && !roots.has(name) && !rootFiles.has(name)) continue
    if (name.startsWith('.') && !rootFiles.has(path) && path !== '.github') continue
    if (skip.has(name) || item.isSymbolicLink()) continue
    if (item.isDirectory()) visit(path)
    else if (item.isFile() && include(path)) {
      if (/[\r\n*?,]/.test(path)) throw new Error('Unsupported source filename: ' + path)
      files.push(path)
    }
  }
}
visit('')
const output = process.argv[2]
writeFileSync(join(output, 'source-files.txt'), files.sort().join('\n') + '\n')
writeFileSync(join(output, 'source-revision.txt'), buildRevision('.'))
