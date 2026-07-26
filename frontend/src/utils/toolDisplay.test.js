import assert from 'node:assert/strict'
import test from 'node:test'

import {
  summarizeToolArguments,
  toolMessageSubtitle,
  toolMessageTitle,
  toolResultItems
} from './toolDisplay.js'

test('summarizes the clean find and grep schemas', () => {
  assert.equal(
    summarizeToolArguments({
      name: 'find',
      argumentsJson: JSON.stringify({ pattern: '**/*.java', path: 'src' })
    }),
    '**/*.java in src'
  )
  assert.equal(
    summarizeToolArguments({
      name: 'grep',
      argumentsJson: JSON.stringify({
        pattern: 'a+b',
        path: '.',
        glob: '*.java',
        literal: true,
        ignoreCase: true
      })
    }),
    '"a+b" in . · *.java · literal · ignore case'
  )
})

test('shows truncation and the selected search backend', () => {
  assert.equal(
    toolMessageSubtitle({
      toolName: 'find',
      content: JSON.stringify({
        pattern: '*.java',
        path: '.',
        files: ['App.java'],
        truncated: true,
        engine: 'ripgrep'
      })
    }),
    '*.java in . | 1+ files | rg'
  )
  assert.equal(
    toolMessageSubtitle({
      toolName: 'grep',
      argumentsJson: JSON.stringify({
        pattern: 'class',
        path: 'src',
        glob: '*.java',
        literal: true,
        ignoreCase: true
      }),
      content: JSON.stringify({
        pattern: 'class',
        path: 'src',
        glob: '*.java',
        matches: [],
        truncated: false,
        engine: 'java'
      })
    }),
    '"class" in src · *.java · literal · ignore case | 0 matches | Java fallback'
  )
})

test('uses exact search tool names in every execution state', () => {
  for (const toolName of ['grep', 'find']) {
    assert.equal(toolMessageTitle({ toolName, running: true }), toolName)
    assert.equal(toolMessageTitle({ toolName, content: '{}' }), toolName)
    assert.equal(toolMessageTitle({ toolName, failed: true }), toolName)
    assert.equal(toolMessageTitle({ toolName, stopped: true }), toolName)
  }
})

test('renders compact string file results', () => {
  const items = toolResultItems({
    toolName: 'find',
    content: JSON.stringify({
      files: ['src/App.java', 'src/AppTest.java']
    })
  })

  assert.deepEqual(items, [
    {
      key: '0:src/App.java:',
      title: 'src/App.java',
      detail: ''
    },
    {
      key: '1:src/AppTest.java:',
      title: 'src/AppTest.java',
      detail: ''
    }
  ])
})
