#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { sourceInfo } from './lib/mainbot.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
if (process.argv.includes('--help')) {
  console.log(
    'Build the application ZIP and installable npm tarball with matching source. Requires JDK, Maven, Node.js and Git, plus zip on macOS and Linux. Output: target/jagent-<version>.zip. No upload or publication.',
  )
  process.exit(0)
}
const options = process.argv.slice(2)
if (options.length && !(options.length === 2 && options[0] === '--output'))
  throw new Error('Use node scripts/package.mjs [--output <directory>]')
const output = options.length ? resolve(options[1]) : resolve(root, 'target')
const info = sourceInfo(root)
if (info.revision === 'unknown')
  throw new Error('Package from a Git checkout so source provenance can be recorded')
function run(name, args, cwd = root) {
  if (process.platform === 'win32' && name === 'npm') {
    // Pass paths directly to Node so cmd.exe never reinterprets spaces or metacharacters.
    const cli = resolve(dirname(process.execPath), 'node_modules/npm/bin/npm-cli.js')
    execFileSync(process.execPath, [cli, ...args], { cwd, stdio: 'inherit' })
    return
  }
  const batch = process.platform === 'win32' && name === 'mvn'
  execFileSync(batch ? 'mvn.cmd' : name, args, { cwd, stdio: 'inherit', shell: batch })
}
run('mvn', ['-B', '-DskipTests', `-Dharness.source.revision=${info.revision}`, 'package'])
if (!existsSync(resolve(root, 'console/node_modules'))) run('npm', ['ci', '--prefix', 'console'])
run('npm', ['run', 'build', '--prefix', 'console'])
const destination = resolve(output, `jagent-${info.version}`)
if (['data', '.env.local'].some((name) => existsSync(resolve(destination, name))))
  throw new Error('This distribution has local settings or data. Choose another --output directory.')
rmSync(destination, { recursive: true, force: true })
mkdirSync(destination, { recursive: true })
const copy = (from, to) => {
  const path = resolve(destination, to)
  mkdirSync(dirname(path), { recursive: true })
  cpSync(resolve(root, from), path, { recursive: true })
}
copy(`examples/server-demo/target/server-demo-${info.version}.jar`, 'server/server.jar')
const sdk = resolve(root, 'sdk/node')
run('npm', ['ci', '--no-audit', '--no-fund'], sdk)
run('npm', ['pack', '--pack-destination', output], sdk)
const runnerVersion = JSON.parse(readFileSync(resolve(sdk, 'package.json'), 'utf8')).version
const archive = 'jagent-' + runnerVersion + '.tgz'
const installed = resolve(destination, 'runner')
mkdirSync(installed, { recursive: true })
run(
  'npm',
  [
    'install',
    '--prefix',
    installed,
    '--offline',
    '--ignore-scripts',
    '--no-fund',
    '--no-audit',
    '--no-save',
    resolve(output, archive),
  ],
  installed,
)
copy('console/dist', 'console')
copy('.env.example', '.env.example')
// Only source-controlled areas; never copy working data, credentials, dependencies or build output.
const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], {
  cwd: root,
  encoding: 'utf8',
})
  .split('\0')
  .filter(Boolean)
for (const file of new Set(files)) {
  if (
    !/^(jagent-api\/|jagent-harness-sdk\/|sdk\/|examples\/|console\/|protocol\/|skills\/|scripts\/|docs\/|assets\/architecture(?:\.zh-CN)?\.svg$|\.github\/|pom\.xml$|README[^/]*$|QUICK_START\.md$|CONTRIBUTING\.md$|LICENSE[^/]*$|\.gitignore$|\.env\.example$)/.test(
      file,
    )
  )
    continue
  if (
    /(^|\/)(target|node_modules|data|dist|\.git|\.npmrc)(\/|$)/.test(file) ||
    (/(^|\/)\.env(\.|$)/.test(file) && file !== '.env.example')
  )
    continue
  if (existsSync(resolve(root, file))) copy(file, `sources/${file}`)
}
for (const file of ['README.md', 'macos/start.command', 'windows/start.cmd', 'start.mjs', 'lib/console-server.mjs', 'lib/mainbot.mjs'])
  copy('scripts/' + file, 'scripts/' + file)
writeFileSync(resolve(destination, 'distribution.json'), JSON.stringify({ ...info, runnerVersion }, null, 2) + '\n')
writeFileSync(resolve(destination, 'sources/source-info.json'), JSON.stringify(info, null, 2) + '\n')
writeFileSync(
  resolve(destination, 'README.md'),
  '# JAgentHarness ' +
    info.version +
    '\n\nCopy .env.example to .env.local and set the model connection. Run `node scripts/start.mjs local` to start the Harness, Console and Runner. See [Platform shortcuts](scripts/README.md) and [Quick start](sources/QUICK_START.md).\n',
)
const zip = resolve(output, 'jagent-' + info.version + '.zip')
if (process.platform === 'win32') {
  run('jar', ['cMf', zip, '-C', output, 'jagent-' + info.version])
} else {
  // Preserve executable bits for Finder and Unix terminals when extracting the release.
  const temporary = mkdtempSync(resolve(output, '.archive-'))
  try {
    const archive = resolve(temporary, 'application.zip')
    run('zip', ['-qr', archive, 'jagent-' + info.version], output)
    renameSync(archive, zip)
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
}
console.log('Application: ' + zip + '\nRunner: ' + resolve(output, archive))
