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
const options = process.argv.slice(2)
if (options.length && !(options.length === 2 && options[0] === '--output')) throw new Error('Use node scripts/package.mjs [--output <directory>]')
const output = options.length ? resolve(options[1]) : resolve(root, 'target')
const info = sourceInfo(root)
if (info.revision === 'unknown') throw new Error('Package from a Git checkout so source provenance can be recorded')
const run = (name, args, cwd = root) => execFileSync(process.platform === 'win32' && ['npm', 'mvn'].includes(name) ? `${name}.cmd` : name, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' && ['npm', 'mvn'].includes(name) })
run('mvn', ['-B', '-DskipTests', `-Dharness.source.revision=${info.revision}`, 'package'])
if (!existsSync(resolve(root, 'console/node_modules'))) run('npm', ['ci', '--prefix', 'console'])
run('npm', ['run', 'build', '--prefix', 'console'])
const destination = resolve(output, `jagent-${info.version}`)
if (['data', '.env.local'].some(name => existsSync(resolve(destination, name)))) throw new Error('This distribution has local settings or data. Choose another --output directory.')
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
  if (!/^(jagent-api\/|jagent-harness-sdk\/|sdk\/|examples\/|console\/|protocol\/|skills\/|scripts\/|docs\/|assets\/architecture(?:\.zh-CN)?\.svg$|\.github\/|pom\.xml$|README[^/]*$|QUICK_START\.md$|CONTRIBUTING\.md$|LICENSE[^/]*$|\.gitignore$|\.env\.example$)/.test(file)) continue
  if (/(^|\/)(target|node_modules|data|dist|\.git)(\/|$)/.test(file) || /(^|\/)\.env(\.|$)/.test(file) && file !== '.env.example') continue
  if (existsSync(resolve(root, file))) copy(file, `sources/${file}`)
}
for (const file of ['install.sh', 'install.cmd', 'install.ps1', 'uninstall.sh', 'uninstall.cmd', 'uninstall.ps1', 'jagent', 'jagent.cmd', 'global.mjs', 'dev.mjs', 'dev.command', 'dev.cmd', 'start-server.mjs', 'start-client.mjs', 'start-frontend.mjs', 'lib/launch.mjs', 'lib/console-server.mjs', 'lib/mainbot.mjs', 'lib/runner-state.mjs']) copy(`scripts/${file}`, `scripts/${file}`)
writeFileSync(resolve(destination, 'distribution.json'), JSON.stringify(info, null, 2) + '\n')
writeFileSync(resolve(destination, 'sources/source-info.json'), JSON.stringify(info, null, 2) + '\n')
writeFileSync(resolve(destination, 'README.md'), `# JAgentHarness ${info.version}

This package runs your personal mainbot, the web Console and a Coding Runner on one machine. Install Java 8+ and Node.js 22.12+, then:

1. Run \`sh scripts/install.sh\` (macOS/Linux) or \`scripts\\install.cmd\` (Windows) from this directory.
2. Edit \`~/.jagent/.env.local\` with your model name, OpenAI-compatible endpoint and API key.
3. Open a new terminal and run \`jagent\`. The Runner opens the Console. Send your goal in **Agents → mainbot → Chat**.

[Try a first task](sources/QUICK_START.md#give-mainbot-its-first-job) · [Architecture](sources/README.md#architecture) · [Linux service setup](sources/QUICK_START.md#linux-servers-and-existing-services) · [PostgreSQL settings](sources/QUICK_START.md#choose-a-database) · [Remote access](sources/docs/guides/access.md)

For portable startup, copy \`.env.example\` to \`.env.local\`, configure the model and run \`node scripts/dev.mjs\` here. Portable startup keeps data under this directory; the global installation uses \`~/.jagent\`. Stop the launcher with Ctrl+C. To uninstall the global command, run \`sh scripts/uninstall.sh\` or \`scripts\\uninstall.cmd\`. Personal data is retained.

The source snapshot in \`sources/\` corresponds to \`${info.revision}\`. mainbot can consult it through a Project on the local Coding Runner.
`)
const zip = resolve(output, `jagent-${info.version}.zip`)
run('jar', ['cMf', zip, '-C', output, `jagent-${info.version}`])
console.log(`Offline application: ${zip}`)
