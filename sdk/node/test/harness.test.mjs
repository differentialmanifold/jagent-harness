import test from 'node:test'
import { deferred } from '../dist/deferred.js'
import { AgentRunner, success } from '../dist/index.js'
import assert from 'node:assert/strict'
import { spawn, execFile } from 'node:child_process'
import { once } from 'node:events'
import { promisify } from 'node:util'
import { createServer } from 'node:http'
import { mkdtemp, readFile, writeFile, rm, access, realpath } from 'node:fs/promises'
import { tmpdir, userInfo } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const sshd = process.env.JAGENT_TEST_SSHD
const jar = process.env.JAGENT_TEST_SERVER_JAR
const cli = process.env.JAGENT_TEST_RUNNER_CLI || fileURLToPath(new URL('../dist/cli.js', import.meta.url))
async function until(check) {
  for (let i = 0; i < 150; i++) {
    const value = await check()
    if (value) return value
    await sleep(150)
  }
  assert.fail('Timed out waiting for Harness or Runner')
}
test(
  'packaged Java Harness accepts Node registration, browser tickets, parallel sessions, SSE tool results and cancellation',
  { skip: !jar, timeout: 60000 },
  async (t) => {
    const dir = await mkdtemp(join(tmpdir(), 'jagent-node-e2e-'))
    const model = createServer(async (req, res) => {
      const chunks = []
      for await (const b of req) chunks.push(b)
      const { messages } = JSON.parse(Buffer.concat(chunks))
      const last = messages.at(-1),
        slow = JSON.stringify(last.content).includes('slow run')
      const device = messages.some((m) => m.role === 'user' && m.content === 'device clock')
      const sourceQuestion = messages.some(m => m.role === 'user' && m.content === 'inspect console source')
      const readArgs = sourceQuestion ? { path: 'console/src/App.vue', limit: 3 } : { path: 'example.txt' }
      const message =
        last.role === 'tool' || (device && messages.some((m) => m.role === 'tool'))
          ? { role: 'assistant', content: 'Tool returned ' + last.content }
          : {
              role: 'assistant',
              content: null,
              tool_calls: [
                {
                  id: slow ? 'call_slow' : 'call_fast',
                  type: 'function',
                  function: device
                    ? { name: 'clock', arguments: '{}' }
                    : slow
                      ? {
                          name: 'bash',
                          arguments: JSON.stringify({
                            command: 'echo started > started.txt && sleep 30',
                          }),
                        }
                      : { name: 'read', arguments: JSON.stringify(readArgs) },
                },
              ],
            }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(
        JSON.stringify({
          id: 'chat',
          object: 'chat.completion',
          model: 'fixture',
          choices: [{ index: 0, message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }],
          usage: { prompt_tokens: 12, completion_tokens: 6, total_tokens: 18 },
        }),
      )
    })
    model.listen(0, '127.0.0.1')
    await once(model, 'listening')
    const credential = join(dir, 'user.env')
    const env = {
      ...process.env,
      JAGENT_MODEL_PROVIDER: 'openai-compatible',
      JAGENT_MODEL: 'fixture',
      JAGENT_MODEL_STREAM_ENABLED: 'false',
      JAGENT_MODEL_RETRY_ENABLED: 'false',
      JAGENT_OPENAI_API_KEY: 'fixture',
      JAGENT_OPENAI_BASE_URL: 'http://127.0.0.1:' + model.address().port + '/v1',
      JAGENT_LOCAL_TOKEN_FILE: credential,
      JAGENT_STATE_DIR: dir,
      JAGENT_DATASOURCE_URL: 'jdbc:sqlite:' + join(dir, 'harness.db'),
      AGENT_SERVER_PORT: '0',
    }
    for (const key of [
      'JAGENT_USER_TOKEN',
      'JAGENT_USER_CREDENTIALS',
      'JAGENT_RUNNER_HOME',
      'JAGENT_SERVER_URL',
      'JAGENT_CONSOLE_URL',
      'JAGENT_RG_PATH',
      'JAGENT_SSH',
      'JAGENT_SSH_CONFIG',
    ])
      delete env[key]
    const children = []
    function start(program, args) {
      const proc = spawn(program, args, { cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe'] })
      children.push(proc)
      let output = ''
      proc.stdout.on('data', (b) => {
        output = (output + b).slice(-16000)
      })
      proc.stderr.on('data', (b) => {
        output = (output + b).slice(-16000)
      })
      return () => {
        if (proc.exitCode !== null) throw new Error(program + ' exited: ' + output)
        return output
      }
    }
    t.after(async () => {
      for (const proc of children.reverse())
        if (proc.exitCode === null && proc.signalCode === null) {
          const closed = once(proc, 'close')
          proc.kill()
          const timeout = setTimeout(() => proc.kill('SIGKILL'), 4000)
          try {
            await closed
          } finally {
            clearTimeout(timeout)
          }
        }
      model.closeAllConnections()
      await new Promise((done) => model.close(done))
      await rm(dir, { recursive: true, force: true })
    })
    const serverLog = start('java', ['-Duser.home=' + dir, '-jar', resolve(jar)])
    const port = await until(() => serverLog().match(/Tomcat started on port\(s\): (\d+)/)?.[1])
    const server = 'http://127.0.0.1:' + port
    const token = (await readFile(credential, 'utf8')).match(/^JAGENT_USER_TOKEN=(.+)$/m)[1].trim()
    async function api(path, body) {
      const res = await fetch(server + '/api/v1/' + path, {
        method: body === undefined ? 'GET' : 'POST',
        headers: {
          Accept: 'application/json',
          Authorization: 'Bearer ' + token,
          'Content-Type': 'application/json',
        },
        body: body && JSON.stringify(body),
        signal: AbortSignal.timeout(20000),
      })
      assert.ok(res.ok, path + ' returned ' + res.status + (res.ok ? '' : ' ' + (await res.text())))
      return res.json()
    }
    // Opt in to the same end-to-end suite through a disposable loopback OpenSSH server.
    const sshConfig = join(dir, 'ssh_config')
    const sshArgs = []
    if (sshd) {
      const key = join(dir, 'key'),
        hostKey = join(dir, 'host_key')
      for (const file of [key, hostKey])
        await promisify(execFile)('ssh-keygen', ['-q', '-t', 'ed25519', '-N', '', '-f', file])
      const probe = createServer().listen(0, '127.0.0.1')
      await once(probe, 'listening')
      const sshPort = probe.address().port
      await new Promise((ok) => probe.close(ok))
      const config = join(dir, 'sshd_config')
      await writeFile(
        config,
        [
          'ListenAddress 127.0.0.1',
          'Port ' + sshPort,
          'PidFile "' + join(dir, 'sshd.pid') + '"',
          'HostKey "' + hostKey + '"',
          'AuthorizedKeysFile "' + key + '.pub"',
          'AuthenticationMethods publickey',
          'UsePAM no',
          'StrictModes no',
          'AllowTcpForwarding local',
          'PermitOpen 127.0.0.1:' + port,
          'MaxSessions 0',
        ].join('\n') + '\n',
      )
      const known = join(dir, 'known_hosts')
      await writeFile(known, '[127.0.0.1]:' + sshPort + ' ' + (await readFile(hostKey + '.pub', 'utf8')))
      await writeFile(
        sshConfig,
        [
          'Host test-harness',
          'HostName 127.0.0.1',
          'Port ' + sshPort,
          'User ' + userInfo().username,
          'UserKnownHostsFile "' + known + '"',
          'IdentityFile "' + key + '"',
          'IdentitiesOnly yes',
          'ControlMaster auto',
          'ControlPath "' + join(dir, 'ssh-control') + '"',
        ].join('\n') + '\n',
      )
      const sshLog = start(sshd, ['-D', '-e', '-f', config])
      await until(() => sshLog().includes('Server listening'))
      const untrusted = join(dir, 'untrusted.conf')
      await writeFile(
        untrusted,
        (await readFile(sshConfig, 'utf8')).replace(known, join(dir, 'missing-known-hosts')),
      )
      await assert.rejects(
        promisify(execFile)(
          process.execPath,
          [
            cli,
            'runner',
            '--server',
            server,
            '--ssh',
            'test-harness',
            '--ssh-config',
            untrusted,
            '--credentials',
            credential,
            '--no-browser',
          ],
          { cwd: dir, env, timeout: 6000 },
        ),
        (e) => e.code === 1 && /Host key verification failed/i.test(e.stderr),
      )
      sshArgs.push('--ssh', 'test-harness', '--ssh-config', sshConfig)
    }
    const runnerLog = start(process.execPath, [
      cli,
      'runner',
      ...sshArgs,
      '--server',
      server,
      '--home',
      join(dir, 'runner'),
      '--credentials',
      credential,
    ])
    const runner = await until(async () => {
      runnerLog()
      return (await api('runners')).find((r) => r.online && r.runtime === 'node')
    })
    for (const path of ['source/manifest', 'source/archive'])
      assert.equal((await fetch(server + '/api/v1/' + path)).status, 401)
    const { sha256, version: harnessVersion } = await api('source/manifest')
    assert.match(sha256, /^[a-f0-9]{64}$/)
    assert.equal(runner.harnessSourceSha256, sha256, runnerLog())
    const support = await api('personal/agents/agent_mainbot/skill?path=skills/jagent-help/references/environment.json')
    const environment = JSON.parse(support.content)
    assert.equal(environment.sourceWorkspace, runner.harnessSourceWorkspace)
    assert.equal(environment.sourceSha256, sha256)
    assert.equal(environment.sourceVersion, harnessVersion)
    assert.equal(environment.runnerVersion, runner.version)
    assert.ok(environment.sourceWorkspace.startsWith(await realpath(join(dir, 'runner'))))
    for (const path of ['console/src/App.vue', 'server/pom.xml', 'QUICK_START.md', 'sdk/node/src/cli.ts'])
      await access(join(environment.sourceWorkspace, path))
    await access(join(environment.runnerSourceWorkspace, 'cli.ts'))
    if (sshd) {
      // Drop the established transport just as a laptop losing its network would.
      await promisify(execFile)('ssh', ['-F', sshConfig, '-O', 'exit', 'test-harness'])
      await until(() => runnerLog().includes('Reconnecting:'))
      await until(() =>
        promisify(execFile)('ssh', ['-F', sshConfig, '-O', 'check', 'test-harness']).then(
          () => true,
          () => false,
        ),
      )
    }
    const { stdout: version } = await promisify(execFile)(process.execPath, [cli, 'version'])
    assert.equal(
      version.trim(),
      JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8')).version,
    )
    assert.equal(runner.version, version.trim())
    const names = runner.tools.map((t) => t.name).sort()
    assert.deepEqual(names, ['bash', 'edit', 'find', 'grep', 'ls', 'read', 'write'])
    const { stdout: link } = await promisify(execFile)(
      process.execPath,
      [cli, 'open', '--link', '--console', server],
      {
        env,
        cwd: dir,
      },
    )
    assert.ok(link.trim().startsWith(server + '/#jagent-ticket=jht_'))
    assert.ok(!link.includes(token))
    const cookie = await fetch(server + '/api/v1/access/exchange', {
      method: 'POST',
      headers: { 'X-JAgent-Console': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({ ticket: link.trim().split('#jagent-ticket=')[1] }),
    })
    assert.equal(cookie.status, 200)
    const status = await fetch(server + '/api/v1/access/status', {
      headers: { Cookie: cookie.headers.get('set-cookie').split(';')[0] },
    })
    assert.equal((await status.json()).mainbotOnline, true)
    const listing = await api(
      'runners/' + runner.runnerId + '/workspaces?path=' + encodeURIComponent(dir),
    )
    assert.ok(listing.directories.some((p) => p.endsWith('runner')))
    const sessions = await Promise.all(
      ['Slow', 'Fast'].map((title) =>
        api('sessions', { title, projectName: title, runnerId: runner.runnerId, workspacePath: dir }),
      ),
    )
    const ctx = await api('agent/context', { sessionId: sessions[0].sessionId })
    assert.ok(ctx.skills.some((s) => s.name === 'browser-research' && s.scope === 'builtin'))
    await writeFile(join(dir, 'example.txt'), 'verified local tool')
    const submit = (sessionId, content, stream) =>
      api('chat', { sessionId, messages: [{ role: 'user', content }], stream })
    const sourceProject = await api('sessions', {
      title: 'Inspect Console', projectName: 'Harness source', runnerId: environment.runnerId,
      workspacePath: environment.sourceWorkspace,
    })
    const inspection = await submit(sourceProject.sessionId, 'inspect console source', false)
    assert.equal(inspection.status, 'COMPLETED', inspection.answer)
    assert.match(inspection.answer, /app-shell/)
    const slow = submit(sessions[0].sessionId, 'slow run', false)
    // Attach the rejection handler immediately so assertion failures are visible while polling.
    slow.catch(() => {})
    await until(() =>
      access(join(dir, 'started.txt')).then(
        () => true,
        () => false,
      ),
    )
    const fast = await submit(sessions[1].sessionId, 'read the file', true)
    assert.equal(fast.status, 'COMPLETED', fast.answer)
    assert.match(fast.answer, /verified local tool/)
    const state = await api('sessions/' + sessions[0].sessionId + '/state')
    await api('runs/' + state.runId + '/stop', {})
    assert.equal((await slow).status, 'CANCELLED')
    // A device-only Runner can accept steering while executing, without any workspace.
    const registered = deferred()
    const device = await AgentRunner.create({
      server,
      token,
      home: join(dir, 'device'),
      onRegistered: () => registered.resolve(),
      tools: [
        {
          name: 'clock',
          description: 'Read device clock',
          parameters: { type: 'object', properties: {} },
          async execute(args, context) {
            assert.equal(context.workspace, undefined)
            await api('runs/' + context.runId + '/inputs', {
              inputId: 'time_format',
              content: 'Use UTC',
            })
            return success('12:00')
          },
        },
      ],
    })
    const serving = device.serve()
    try {
      await registered.promise
      const online = (await api('runners')).filter((r) => r.online).map((r) => r.runnerId)
      assert.ok(online.includes(device.identity.runnerId) && online.includes(runner.runnerId))
      assert.notEqual(device.identity.runnerId, runner.runnerId)
      const session = await api('sessions', {
        title: 'Device',
        projectName: 'Device',
        runnerId: device.identity.runnerId,
      })
      assert.ok(!session.workspacePath)
      const result = await submit(session.sessionId, 'device clock', false)
      assert.equal(result.status, 'COMPLETED', result.answer)
      assert.match(result.answer, /Use UTC/)
    } finally {
      await device.close()
      await serving
    }
  },
)
