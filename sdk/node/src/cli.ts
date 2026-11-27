#!/usr/bin/env node
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { readFile, readdir } from 'node:fs/promises'
import { createInterface } from 'node:readline/promises'
import { parseArgs } from 'node:util'
import { codingEnvironment } from './coding/index.js'
import { digest, load, missing, save, type Identity } from './files.js'
import { Harness, origin } from './http.js'
import { routeKey, SshTunnel } from './ssh.js'
import { AgentRunner, connection } from './runner.js'
import { errorText } from './types.js'

const help = [
  'JAgent Coding Runner for Node.js',
  '  jagent version (also --version or -v)',
  '  jagent runner [--ssh <[user@]host>] [--server <url>] [--credentials <user.env>]',
  '  jagent open [--link] [--console <browser-url>] [--profile <id>]',
  '  jagent pair <code> [--profile <id>]',
  '  jagent recover --session <id>',
  'Start without arguments to serve. The default Harness is http://127.0.0.1:18181.',
  '--ssh opens a tunnel with system OpenSSH. --server is the origin as seen from the SSH host.',
  'Other options: --home <directory>, --name <name>, --description <text>, --console <url>,',
  '  --ssh-config <OpenSSH config file>, --idle-disconnect-timeout <seconds> (default 86400, 0 disables the timeout).',
].join('\n')
type Profile = {
  ssh?: string
  sshConfig?: string
  server: string
  user: string
  home: string
  console?: string
  credentials?: string
}
async function listProfiles(dir: string) {
  const names = await readdir(dir).catch((e) => {
    if (!missing(e)) throw e
    return [] as string[]
  })
  const profiles = new Map<string, Profile>()
  for (const name of names.filter((n) => /^[a-f0-9]{12}\.json$/.test(n))) {
    const profile = await load<Profile>(join(dir, name))
    if (profile) profiles.set(name.slice(0, -5), profile)
  }
  return profiles
}
async function link(http: Harness, consoleUrl: string) {
  const { ticket } = await http.request<{ ticket: string }>('access/browser', {})
  if (!ticket) throw new Error('Harness did not issue a browser ticket')
  return origin(consoleUrl) + '/#jagent-ticket=' + encodeURIComponent(ticket)
}
async function openBrowser(url: string) {
  const program =
    process.platform === 'darwin' ? 'open' : process.platform === 'win32' ? 'rundll32.exe' : 'xdg-open'
  const args = process.platform === 'win32' ? ['url.dll,FileProtocolHandler', url] : [url]
  const proc = spawn(program, args, { stdio: 'ignore', detached: true })
  await new Promise<void>((ok, fail) => {
    const timer = setTimeout(ok, 750)
    proc.once('error', (e) => {
      clearTimeout(timer)
      fail(e)
    })
    proc.once('exit', (code) => {
      clearTimeout(timer)
      code === 0 ? ok() : fail(new Error('Browser launcher failed'))
    })
  })
  proc.unref()
}
async function main() {
  const { values: f, positionals: words } = parseArgs({
    allowPositionals: true,
    options: {
      help: { type: 'boolean' },
      version: { type: 'boolean', short: 'v' },
      'no-browser': { type: 'boolean' }, // Accepted by older launch scripts.
      link: { type: 'boolean' },
      credentials: { type: 'string' },
      server: { type: 'string' },
      ssh: { type: 'string' },
      'ssh-config': { type: 'string' },
      console: { type: 'string' },
      name: { type: 'string' },
      description: { type: 'string' },
      'idle-disconnect-timeout': { type: 'string' },
      home: { type: 'string' },
      profile: { type: 'string' },
      session: { type: 'string' },
    },
  })
  const pkg = createRequire(import.meta.url)('../package.json')
  if (f.version || (words.length === 1 && words[0] === 'version')) {
    console.log(pkg.version)
    return
  }
  if (f.help) {
    console.log(help)
    return
  }
  const command = words[0] ?? 'runner',
    code = words[1]
  if (
    !['serve', 'runner', 'open', 'pair', 'recover'].includes(command) ||
    words.length > (command === 'pair' ? 2 : 1) ||
    (command === 'pair' && !/^[a-z2-9]{4}-?[a-z2-9]{4}$/i.test(code ?? '')) ||
    (command === 'recover') !== !!f.session ||
    (f.link && command !== 'open')
  )
    throw new Error(help)
  const env = process.env
  const state = resolve(env.JAGENT_STATE_DIR ?? join(homedir(), '.jagent')),
    profilesDir = join(state, 'node-profiles')
  const selectedServer = f.server ?? env.JAGENT_SERVER_URL,
    selectedHome = f.home ?? env.JAGENT_RUNNER_HOME
  const configPath = f['ssh-config'] ?? env.JAGENT_SSH_CONFIG
  if (configPath === '') throw new Error('Missing OpenSSH config path')
  const selectedConfig = configPath && resolve(configPath)
  const selectedSsh = f.ssh ?? env.JAGENT_SSH
  if (selectedSsh === '') throw new Error('Missing SSH destination')
  let profile: Profile | undefined
  if (
    f.profile ||
    command === 'open' ||
    command === 'pair' ||
    command === 'recover' ||
    (['runner', 'serve'].includes(command) &&
      !f.credentials &&
      !env.JAGENT_USER_TOKEN &&
      !env.JAGENT_USER_CREDENTIALS)
  ) {
    const profiles = await listProfiles(profilesDir)
    const matches = [...profiles].filter(
      ([id, p]) =>
        (!f.profile || id === f.profile) &&
        (!selectedConfig || selectedConfig === p.sshConfig) &&
        (selectedSsh
          ? p.ssh === selectedSsh
          : !selectedServer || !!f.profile || !['runner', 'serve'].includes(command) || !p.ssh) &&
        (!selectedServer || p.server === origin(selectedServer)) &&
        (!selectedHome || p.home === resolve(selectedHome)),
    )
    if (f.profile && !matches.length) throw new Error('No matching Node Runner profile')
    if (matches.length > 1)
      throw new Error(
        'Choose --profile: ' +
          matches
            .map(
              ([id, p]) =>
                id +
                ' (' +
                p.server +
                (p.ssh ? ' via ' + p.ssh : '') +
                ', ' +
                p.user +
                ', ' +
                p.home +
                ')',
            )
            .join(', '),
      )
    profile = matches[0]?.[1]
  }
  const server = origin(selectedServer ?? profile?.server ?? 'http://127.0.0.1:18181')
  const ssh = selectedSsh ?? profile?.ssh
  const sshConfig = selectedConfig ?? profile?.sshConfig
  if (sshConfig && !ssh) throw new Error('--ssh-config requires --ssh')
  const key = routeKey(server, ssh, sshConfig)
  const home = resolve(selectedHome ?? profile?.home ?? join(homedir(), '.jagent-runner'))
  const browserAddress = f.console ?? env.JAGENT_CONSOLE_URL ?? profile?.console
  const consoleUrl =
    browserAddress ?? (server === 'http://127.0.0.1:18181' ? 'http://127.0.0.1:5175' : server)
  const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(new URL(server).hostname)
  if (!loopback && server.startsWith('http:'))
    console.error(
      ssh
        ? 'HTTP from the SSH host to the Harness is unencrypted. Use a trusted network or HTTPS.'
        : 'Plain HTTP sends credentials unencrypted. Use a trusted network or HTTPS.',
    )
  let credentials = f.credentials ?? env.JAGENT_USER_CREDENTIALS ?? profile?.credentials
  let token = env.JAGENT_USER_TOKEN
  if (!token || f.credentials) {
    const paths = credentials
      ? [credentials]
      : loopback && !ssh
        ? env.JAGENT_STATE_DIR
          ? [join(state, 'data', 'user.env')]
          : [resolve('data/user.env'), join(state, 'data', 'user.env')]
        : []
    for (const path of paths) {
      const file = await readFile(path, 'utf8').catch((e) => {
        if (!missing(e)) throw e
        return ''
      })
      const match = /^JAGENT_USER_TOKEN=(.+)$/m.exec(file)
      if (match) {
        token = match[1].trim()
        credentials = resolve(path)
        break
      }
      if (credentials) throw new Error('No Harness-issued credential found in ' + path)
    }
  } else credentials = undefined
  if (!token) throw new Error('Supply --credentials <user.env> or set JAGENT_USER_TOKEN')
  if (command === 'open' || command === 'pair') {
    if (ssh && command === 'open' && !browserAddress)
      throw new Error('Supply --console <browser-accessible Console URL> to open the remote Console')
    const tunnel = ssh ? new SshTunnel(ssh, new URL(server), sshConfig) : undefined
    try {
      const { userId, dir } = await connection(server, token, home, profile?.user, tunnel)
      const identity = await load<Identity>(join(dir, 'config.json'))
      if (!identity || identity.server !== server || !identity.runnerId || !identity.secret)
        throw new Error('Start the Node Runner first')
      const http = new Harness(server, token, identity, tunnel)
      if (command === 'open') {
        const url = await link(http, consoleUrl)
        if (f.link) console.log(url)
        else await openBrowser(url)
      } else {
        const { browser } = await http.request<{ browser: string }>('access/pair/info', { code })
        if (!process.stdin.isTTY || !process.stdout.isTTY)
          throw new Error('Confirm pairing in an interactive terminal on this Runner')
        const input = createInterface({ input: process.stdin, output: process.stdout })
        try {
          const answer = await input.question(
            'Allow ' +
              browser +
              ' (' +
              code +
              ') to access ' +
              userId +
              ' on ' +
              server +
              ' via ' +
              identity.runnerId +
              '? Type yes: ',
          )
          if (answer.trim().toLowerCase() === 'yes') {
            await http.request('access/pair/approve', { code })
            console.log('Browser approved.')
          } else console.log('Pairing cancelled.')
        } finally {
          input.close()
        }
      }
    } finally {
      tunnel?.close()
    }
    return
  }
  const coding = codingEnvironment()
  console.log('Search backend (find, grep): ' + coding.diagnostics.grep)
  const runner = await AgentRunner.create({
    ...coding,
    server,
    ssh,
    sshConfig,
    token,
    home,
    userId: profile?.user,
    name: f.name ?? env.JAGENT_RUNNER_NAME,
    description:
      f.description ??
      env.JAGENT_RUNNER_DESCRIPTION ??
      'Coding environment with local file, search and shell tools. Installed SDKs must be verified before use.',
    version: pkg.version,
    buildRevision: pkg.gitHead,
    idleDisconnectSeconds: Number(
      f['idle-disconnect-timeout'] ?? env.JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS ?? 86400,
    ),
    onStatus: console.log,
    onEvent: (event) => {
      if ('type' in event && event.type === 'tool_execution_start' && 'payload' in event)
        console.log('Executing ' + (event.payload as { toolName: string }).toolName)
    },
    async onRegistered(r) {
      const id = digest(key + '\n' + r.userId + '\n' + home).slice(0, 12)
      await save(join(profilesDir, id + '.json'), {
        user: r.userId,
        server,
        home,
        ssh,
        sshConfig,
        console: browserAddress,
        credentials,
      }).catch((e) => console.error('Could not save Runner profile: ' + errorText(e)))
      process.send?.({ type: 'registered', runnerId: r.identity.runnerId, profileId: id })
      console.log('Runner online: ' + r.identity.runnerId + ' (profile ' + id + ')')
    },
  })
  const shutdown = () => {
    runner.stop()
  }
  process.once('SIGINT', shutdown)
  process.once('SIGTERM', shutdown)
  try {
    if (command === 'recover') {
      const result = await runner.recover(f.session!)
      console.log(result.status, result.answer ?? '')
    } else await runner.serve()
  } finally {
    process.off('SIGINT', shutdown)
    process.off('SIGTERM', shutdown)
    await runner.close()
  }
}
main().catch((e) => {
  console.error('Runner: ' + errorText(e))
  process.exitCode = 1
})
