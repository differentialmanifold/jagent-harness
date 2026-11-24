import { homedir } from 'node:os'
import { isAbsolute, join, parse, relative, resolve, dirname, sep } from 'node:path'
import { access, mkdir, realpath, readdir, stat } from 'node:fs/promises'
import type { Workspaces } from './types.js'

export class LocalWorkspaces implements Workspaces {
  readonly root: string
  constructor(root = join(homedir(), '.jagent', 'workspaces')) {
    this.root = resolve(root)
  }
  async resolve(input?: string) {
    if (!input || !isAbsolute(input))
      throw new Error('Select an absolute project workspace on the Runner')
    const path = resolve(input)
    const managed = relative(this.root, path).split(sep).join('/')
    if (/^(user|agents\/agent_[\w-]+)\/project_[\w-]+$/.test(managed))
      await mkdir(path, { recursive: true })
    if (!(await stat(path)).isDirectory()) throw new Error('Not a directory: ' + path)
    return realpath(path)
  }
  async browse(input = '') {
    const path = await this.resolve(input || homedir())
    const root = parse(path).root
    const roots =
      process.platform === 'win32'
        ? (
            await Promise.all(
              Array.from('ABCDEFGHIJKLMNOPQRSTUVWXYZ', async (drive) => {
                const name = drive + ':\\'
                try {
                  await access(name)
                  return name
                } catch {
                  return ''
                }
              }),
            )
          ).filter(Boolean)
        : [root]
    const dirs: string[] = []
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const full = join(path, entry.name)
      if (
        entry.isDirectory() ||
        (entry.isSymbolicLink() && (await stat(full).catch(() => null))?.isDirectory())
      )
        dirs.push(full)
      if (dirs.length > 1000) break
    }
    return {
      path,
      parent: path === root ? null : dirname(path),
      roots,
      directories: dirs.slice(0, 1000).sort((a, b) => a.localeCompare(b)),
      truncated: dirs.length > 1000,
    }
  }
}
