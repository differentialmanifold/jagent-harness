import { createWriteStream } from 'node:fs'
import { lstat, mkdir, mkdtemp, realpath, rename, rm } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { pipeline } from 'node:stream/promises'
import { fromBufferPromise } from 'yauzl'
import { digest, missing } from './files.js'
import type { Harness } from './http.js'

const maxArchive = 32 * 1024 * 1024
const maxExpanded = 128 * 1024 * 1024

async function unpack(bytes: Buffer, directory: string, signal: AbortSignal) {
  const zip = await fromBufferPromise(bytes, { strictFileNames: true })
  try {
    if (!zip.entryCount || zip.entryCount > 20000) throw new Error('Invalid source archive entry count')
    let total = 0
    for await (const entry of zip.eachEntry()) {
      signal.throwIfAborted()
      const parts = entry.fileName.split('/')
      // The Harness archive contains only regular files with portable names.
      if (parts.some(p => !p || p === '.' || p === '..' || /[<>:"|?*\\\x00-\x1f]|[. ]$/.test(p) ||
        /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(p)))
        throw new Error('Invalid source archive path')
      const kind = (entry.externalFileAttributes >>> 16) & 0o170000
      if (kind && kind !== 0o100000) throw new Error('Unexpected source archive entry type')
      total += entry.uncompressedSize
      if (total > maxExpanded) throw new Error('Expanded source archive is too large')
      const path = join(directory, ...parts)
      await mkdir(dirname(path), { recursive: true, mode: 0o700 })
      await pipeline(
        await zip.openReadStreamPromise(entry),
        createWriteStream(path, { flags: 'wx', mode: 0o400 }),
        { signal },
      )
    }
  } finally {
    zip.close()
  }
}

export async function syncSource(http: Harness, cache: string, signal: AbortSignal) {
  const manifest = await http.request<{ sha256: string }>('source/manifest', undefined, signal, 5000)
  if (typeof manifest.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(manifest.sha256))
    throw new Error('Invalid Harness source manifest')
  const directory = join(cache, manifest.sha256)
  const exists = await lstat(directory).catch(error => {
    if (!missing(error)) throw error
  })
  if (!exists) {
    await mkdir(cache, { recursive: true, mode: 0o700 })
    const staging = await mkdtemp(join(cache, '.extract-'))
    try {
      const bytes = await http.download('source/archive', maxArchive, signal)
      if (digest(bytes) !== manifest.sha256) throw new Error('Harness source checksum mismatch')
      await unpack(bytes, staging, signal)
      await rename(staging, directory)
    } finally {
      await rm(staging, { recursive: true, force: true })
    }
  } else if (!exists.isDirectory()) throw new Error('Invalid source cache directory')
  return { harnessSourceWorkspace: await realpath(directory), harnessSourceSha256: manifest.sha256 }
}
