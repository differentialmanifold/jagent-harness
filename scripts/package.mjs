#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { sourceInfo } from './lib/mainbot.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
if (process.argv.includes('--help')) {
  console.log('Build an offline application ZIP with server, Coding Runner, Console and matching source. Requires JDK, Maven, Node.js and Git. Output: target/jagent-<version>.zip. No upload or publication.')
  process.exit(0)
}
if (process.argv.length > 2) throw new Error('Use node scripts/package.mjs [--help]')
const info = sourceInfo(root)
if (info.revision === 'unknown') throw new Error('Package from a Git checkout so source provenance can be recorded')
const run = (name, args, cwd = root) => execFileSync(process.platform === 'win32' && ['npm', 'mvn'].includes(name) ? `${name}.cmd` : name, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' && ['npm', 'mvn'].includes(name) })
run('mvn', ['-B', '-DskipTests', `-Dharness.source.revision=${info.revision}`, 'package'])
if (!existsSync(resolve(root, 'console/node_modules'))) run('npm', ['ci', '--prefix', 'console'])
run('npm', ['run', 'build', '--prefix', 'console'])
const destination = resolve(root, 'target', `jagent-${info.version}`)
rmSync(destination, { recursive: true, force: true })
mkdirSync(destination, { recursive: true })
const copy = (from, to) => {
  const path = resolve(destination, to)
  mkdirSync(dirname(path), { recursive: true })
  cpSync(resolve(root, from), path, { recursive: true })
}
copy(`examples/server-demo/target/server-demo-${info.version}.jar`, 'server/server.jar')
copy(`examples/coding-runner/target/jagent-coding-runner-${info.version}.jar`, 'runner/runner.jar')
copy('examples/coding-runner/target/runners', 'runners')
copy('console/dist', 'console')
copy('.env.example', '.env.example')
// Only source-controlled areas; never copy working data, credentials, dependencies or build output.
const files = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean)
for (const file of new Set(files)) {
  if (!/^(jagent-api\/|jagent-harness-sdk\/|sdk\/|examples\/|console\/|protocol\/|skills\/|scripts\/|docs\/|\.github\/|pom\.xml$|README[^/]*$|QUICK_START\.md$|CONTRIBUTING\.md$|LICENSE[^/]*$|\.gitignore$|\.env\.example$)/.test(file)) continue
  if (/(^|\/)(target|node_modules|data|dist|\.git)(\/|$)/.test(file) || /(^|\/)\.env(\.|$)/.test(file) && file !== '.env.example') continue
  if (existsSync(resolve(root, file))) copy(file, `sources/${file}`)
}
for (const file of ['dev.mjs', 'dev.command', 'dev.cmd', 'start-server.mjs', 'start-client.mjs', 'start-frontend.mjs', 'lib/launch.mjs', 'lib/console-server.mjs', 'lib/mainbot.mjs', 'lib/runner-state.mjs']) copy(`scripts/${file}`, `scripts/${file}`)
writeFileSync(resolve(destination, 'distribution.json'), JSON.stringify(info, null, 2) + '\n')
writeFileSync(resolve(destination, 'sources/source-info.json'), JSON.stringify(info, null, 2) + '\n')
writeFileSync(resolve(destination, 'README.md'), `# JAgentHarness ${info.version}\n\nRequires Java 8+ and Node.js 22.12+. Copy .env.example to .env.local and configure the model. Run:\n\n\`\`\`sh\nnode scripts/dev.mjs\n\`\`\`\n\nOr use scripts/dev.command (macOS) or scripts/dev.cmd (Windows). Open http://127.0.0.1:5175 and talk to mainbot. The launcher starts the server, Coding Runner and bundled Console, and registers the sources directory for mainbot. No Git, Maven or npm installation is needed at runtime. Model connectivity is still required. Ctrl+C stops these processes.\n\nIdentity, database and projects remain under data/. Restart this same directory to retain them. Do not copy a running Runner identity to another machine. The source snapshot is ${info.revision}; a dirty snapshot includes local changes and is not a clean Git release.\n\nBuilt from the Maven examples; the published Maven libraries remain independently usable. See sources/docs/README.md for documentation.\n`)
const zip = resolve(root, 'target', `jagent-${info.version}.zip`)
run('jar', ['cMf', zip, '-C', resolve(root, 'target'), `jagent-${info.version}`])
console.log(`Offline application: ${zip}`)
