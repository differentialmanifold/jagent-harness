export function markdownBlocks(content) {
  const blocks = []
  const lines = String(content || '').split(/\r?\n/)
  let inFence = false
  let fence = []
  for (const line of lines) {
    if (line.trim().startsWith('```')) {
      if (inFence) {
        blocks.push({ tag: 'pre', text: fence.join('\n'), className: 'markdown-code-block' })
        fence = []
        inFence = false
      } else {
        inFence = true
      }
      continue
    }
    if (inFence) {
      fence.push(line)
      continue
    }
    if (!line.trim()) continue
    const heading = line.match(/^(#{1,6})\s+(.+)$/)
    if (heading) {
      blocks.push({ tag: `h${Math.min(heading[1].length, 6)}`, text: heading[2], className: '' })
      continue
    }
    const listItem = line.match(/^\s*[-*]\s+(.+)$/)
    if (listItem) {
      blocks.push({ tag: 'p', text: `• ${listItem[1]}`, className: 'markdown-list-item' })
      continue
    }
    blocks.push({ tag: 'p', text: line, className: '' })
  }
  if (fence.length) {
    blocks.push({ tag: 'pre', text: fence.join('\n'), className: 'markdown-code-block' })
  }
  return blocks
}

export function countLines(content) {
  if (!content) return 0
  return String(content).split(/\r\n|\r|\n/).length
}
