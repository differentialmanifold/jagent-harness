import test from 'node:test'
import assert from 'node:assert/strict'
import { renderMarkdown } from './markdown.js'

test('renders headings, emphasis, lists, links, tables and fenced code', () => {
  const rendered = renderMarkdown('# Result\n\n**Passed** and `code`\n\n- one\n- two\n\n[docs](https://example.com)\n\n| Check | Result |\n| --- | --- |\n| add | pass |\n\n```java\nSystem.out.println("ok");\n```')
  for (const tag of ['<h1>', '<strong>', '<code>', '<ul>', '<table>', '<a href="https://example.com"', 'language-java']) assert.ok(rendered.includes(tag), tag)
})

test('escapes untrusted HTML and rejects executable link protocols', () => {
  const rendered = renderMarkdown('<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n[bad](javascript:alert%281%29)\n![remote](https://example.com/image.png)')
  assert.ok(!rendered.includes('<script'))
  assert.ok(!rendered.includes('<img'))
  assert.ok(!rendered.includes('href="javascript:'))
  assert.ok(rendered.includes('&lt;script&gt;'))
})
