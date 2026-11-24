// Budget text independently of native image attachments and the tool-call envelope.
export const MAX_RESULT_BYTES = 16 * 1024
const gap = '\n...[truncated]...\n'

function tailStart(bytes: Buffer, start: number) {
  while (start < bytes.length && (bytes[start] & 0xc0) === 0x80) start++
  return start
}

export function preview(text: string, limit: number) {
  if (Buffer.byteLength(text) <= limit) return text
  const half = Math.floor((limit - gap.length) / 2)
  const head = Buffer.from(text.slice(0, half + 1))
  let end = half
  while (end > 0 && (head[end] & 0xc0) === 0x80) end--
  const tail = Buffer.from(text.slice(-half))
  return head.toString('utf8', 0, end) + gap + tail.toString('utf8', tailStart(tail, tail.length - half))
}

/** Drain both process pipes, retaining only the most recent bytes of each stream. */
export class OutputTail {
  private bytes = Buffer.alloc(0)
  private seen = 0

  constructor(private limit: number) {}

  append(chunk: Buffer) {
    this.seen += chunk.length
    const retained = chunk.subarray(Math.max(0, chunk.length - this.limit))
    const start = Math.max(0, this.bytes.length + retained.length - this.limit)
    this.bytes = Buffer.concat([this.bytes.subarray(start), retained])
  }

  text() {
    // Binary output can expand when invalid bytes are decoded as replacement characters.
    const start = this.seen > this.bytes.length ? tailStart(this.bytes, 0) : 0
    const text = this.bytes.toString('utf8', start)
    const size = Buffer.byteLength(text)
    if (this.seen <= this.limit && size <= this.limit) return text
    const marker = '[output truncated; showing tail]\n'
    const encoded = Buffer.from(text)
    const offset = Math.max(0, size - (this.limit - marker.length))
    return marker + encoded.toString('utf8', tailStart(encoded, offset))
  }
}
