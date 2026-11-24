import { readFile } from 'node:fs/promises'
import { basename } from 'node:path'
import { diffArrays } from 'diff'
import { digest } from '../files.js'
import { success, type Arguments, type ToolContext } from '../types.js'
import { decode, display, FileProblem, location, mutate, replaceFile, text, utf8 } from './files.js'

const eol = /\r\n|\r|\n/g
const logical = (s: string) => s.replace(eol, '\n')
const lines = (s: string) => logical(s).match(/[^\n]*\n|[^\n]+$/g) ?? []
const lineAt = (s: string, i: number) => 1 + (s.slice(0, i).match(eol)?.length ?? 0)
const indent = (s: string) => /^[ \t]*/.exec(s)![0]
function commonIndent(parts: string[]) {
  const prefixes = parts.filter((s) => s.trim()).map(indent)
  return prefixes.reduce((short, next) => (next.length < short.length ? next : short), prefixes[0] ?? '')
}
function unindent(s: string) {
  const parts = logical(s).split('\n')
  const prefix = commonIndent(parts)
  return parts.map((line) => line.slice(Math.min(indent(line).length, prefix.length))).join('\n')
}
// Keep byte-independent JS string offsets into the original while comparing normalized text.
function view(s: string, trim: boolean) {
  const starts = new Uint32Array(s.length),
    ends = new Uint32Array(s.length)
  const parts: string[] = []
  let cursor = 0,
    offset = 0
  for (const separator of s.matchAll(eol)) {
    let keep = separator.index
    if (trim) while (keep > cursor && (s[keep - 1] === ' ' || s[keep - 1] === '\t')) keep--
    parts.push(s.slice(cursor, keep), '\n')
    for (let i = cursor; i < keep; i++) {
      starts[offset] = i
      ends[offset++] = i + 1
    }
    cursor = separator.index + separator[0].length
    starts[offset] = separator.index
    ends[offset++] = cursor
  }
  parts.push(s.slice(cursor))
  for (let i = cursor; i < s.length; i++) {
    starts[offset] = i
    ends[offset++] = i + 1
  }
  return { s: parts.join(''), starts, ends }
}
type Span = { start: number; end: number }
function matches(doc: ReturnType<typeof view>, search: string) {
  const spans: Span[] = []
  if (search)
    for (let i = doc.s.indexOf(search); i >= 0; i = doc.s.indexOf(search, i + 1)) {
      spans.push({ start: doc.starts[i], end: doc.ends[i + search.length - 1] })
    }
  return spans
}
function locate(
  source: string,
  original: ReturnType<typeof view>,
  trimmed: ReturnType<typeof view>,
  search: string,
) {
  let spans = matches(original, logical(search))
  if (spans.length)
    return {
      spans,
      strategy:
        spans.length === 1 && source.slice(spans[0].start, spans[0].end) === search
          ? 'exact'
          : 'line_endings',
    }
  spans = matches(trimmed, view(search, true).s)
  if (spans.length) return { spans, strategy: 'trailing_whitespace' }
  const expected = unindent(view(search, true).s),
    count = lines(search).length,
    terminated = expected.endsWith('\n')
  if (!expected.trim()) return { spans, strategy: 'common_indentation' }
  const rows = [...source.matchAll(/[^\r\n]*(?:\r\n|\r|\n)|[^\r\n]+$/g)]
  for (let i = 0; i + count <= rows.length; i++) {
    const start = rows[i].index,
      last = rows[i + count - 1]
    const end = last.index + (terminated ? last[0] : last[0].replace(/[\r\n]+$/, '')).length
    if (unindent(view(source.slice(start, end), true).s) === expected) spans.push({ start, end })
  }
  return { spans, strategy: 'common_indentation' }
}
function changes(before: string, after: string) {
  const a = lines(before),
    b = lines(after)
  // Bound the cost of diffing a completely replaced large file.
  const chunks = diffArrays(a, b, { maxEditLength: 2048 }) ?? [
    { removed: true, added: false, value: a },
    { removed: false, added: true, value: b },
  ]
  let old = 1,
    next = 1,
    additions = 0,
    deletions = 0
  const rows = chunks.flatMap((chunk) =>
    chunk.value.map((s) => {
      if (chunk.added) additions++
      if (chunk.removed) deletions++
      return {
        type: chunk.added ? 'added' : chunk.removed ? 'removed' : 'context',
        oldLine: chunk.added ? null : old++,
        newLine: chunk.removed ? null : next++,
        lineTerminated: s.endsWith('\n'),
        content: s.replace(/\n$/, ''),
      }
    }),
  )
  const ranges: [number, number][] = []
  for (const [i, row] of rows.entries())
    if (row.type !== 'context') {
      const start = Math.max(0, i - 3),
        end = Math.min(rows.length, i + 4)
      const last = ranges.at(-1)
      if (last && start <= last[1]) last[1] = end
      else ranges.push([start, end])
    }
  const hunks = ranges.map(([start, end]) => {
    const lines = rows.slice(start, end)
    const old = lines.flatMap((l) => l.oldLine ?? []),
      next = lines.flatMap((l) => l.newLine ?? [])
    return {
      oldStart: old[0] ?? 1,
      oldLines: old.length,
      newStart: next[0] ?? 1,
      newLines: next.length,
      lines,
    }
  })
  return { additions, deletions, diff: { hunks } }
}
export async function edit(args: Arguments, ctx: ToolContext) {
  const path = location(ctx, text(args, 'path')),
    name = display(ctx, path)
  if (
    !Array.isArray(args.edits) ||
    !args.edits.length ||
    args.edits.length > 100 ||
    Object.keys(args).some((k) => !['path', 'edits'].includes(k))
  )
    throw new Error('Expected path and 1–100 edits')
  const edits = args.edits.map((entry) => {
    if (!entry || Object.keys(entry).some((k) => !['oldText', 'newText'].includes(k)))
      throw new Error('Invalid edit fields')
    const oldText = text(entry, 'oldText', true),
      newText = text(entry, 'newText', true)
    if (!oldText || oldText === newText)
      throw new Error('Each edit must replace distinct, nonempty oldText')
    utf8(oldText)
    utf8(newText)
    return { oldText, newText }
  })
  return mutate(path, ctx, async (target) => {
    let initialHash = ''
    for (let attempt = 0; attempt < 2; attempt++) {
      const bytes = await readFile(target, { signal: ctx.signal })
      const source = decode(bytes),
        original = view(source, false),
        trimmed = view(source, true)
      const previousHash = digest(bytes)
      initialHash ||= previousHash
      const plan = edits
        .map(({ oldText, newText }, index) => {
          const { spans, strategy } = locate(source, original, trimmed, oldText)
          if (spans.length !== 1)
            throw new FileProblem(
              attempt
                ? 'CONCURRENT_MODIFICATION'
                : spans.length
                  ? 'AMBIGUOUS_MATCH'
                  : 'SEARCH_NOT_FOUND',
              'Expected one match for edit ' + index + ' in ' + name,
              {
                path: name,
                editIndex: index,
                matchCount: spans.length,
                contentHash: previousHash,
                matchStrategy: strategy,
                candidates: spans.slice(0, 5).map((s) => ({
                  startLine: lineAt(source, s.start),
                  endLine: lineAt(source, s.end - 1),
                  content: source.slice(s.start, Math.min(s.end, s.start + 250)),
                })),
              },
            )
          const { start, end } = spans[0],
            matched = source.slice(start, end)
          const ending =
            (matched.match(eol) ??
              source.slice(end).match(eol) ??
              source.slice(0, start).match(eol)?.slice(-1))?.[0] ?? '\n'
          const prefix = commonIndent(logical(matched).split('\n'))
          const replacement =
            strategy === 'common_indentation'
              ? unindent(newText)
                  .split('\n')
                  .map((s) => (s ? prefix + s : s))
                  .join('\n')
              : newText
          return {
            start,
            end,
            replacement: replacement.replace(eol, ending),
            index,
            matchStrategy: strategy,
            startLine: lineAt(source, start),
            endLine: lineAt(source, end - 1),
          }
        })
        .sort((a, b) => a.start - b.start)
      let cursor = 0,
        previousIndex = -1,
        updated = ''
      for (const op of plan) {
        if (op.start < cursor)
          throw new FileProblem('OVERLAPPING_EDITS', 'Replacements overlap in ' + name, {
            path: name,
            conflictingEditIndexes: [previousIndex, op.index],
            contentHash: previousHash,
          })
        updated += source.slice(cursor, op.start) + op.replacement
        cursor = op.end
        previousIndex = op.index
      }
      updated += source.slice(cursor)
      const bom = bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]))
      const output = Buffer.from((bom ? '\uFEFF' : '') + updated)
      const changed = !bytes.equals(output)
      const latest = changed
        ? await replaceFile(target, output, ctx.signal, bytes)
        : bytes.equals(await readFile(target, { signal: ctx.signal }))
      if (!latest) continue
      return success({
        path: name,
        fileName: basename(path),
        initialHash,
        previousHash,
        contentHash: digest(output),
        changed,
        rebased: attempt > 0,
        editCount: plan.length,
        appliedEdits: plan
          .sort((a, b) => a.index - b.index)
          .map(({ index, matchStrategy, startLine, endLine }) => ({
            index,
            matchStrategy,
            startLine,
            endLine,
          })),
        ...changes(source, updated),
      })
    }
    throw new FileProblem('CONCURRENT_MODIFICATION', 'File changed during edit; read it and retry', {
      path: name,
    })
  })
}
