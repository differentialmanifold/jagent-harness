import test from 'node:test'
import assert from 'node:assert/strict'
import { buildTaskTree, taskProgress, visibleTaskRows, taskDependencies } from './agentTasks.js'

const tasks = [
  { id: 'goal', title: 'Deliver file storage', status: 'DOING' },
  { id: 'plan', title: 'Implementation', status: 'DOING', parentId: 'goal' },
  { id: 'save', title: 'Save files', status: 'DONE', parentId: 'plan' },
  { id: 'load', title: 'Load files', status: 'WAITING', parentId: 'plan', progress: 'Waiting for a sample file' },
  { id: 'old', title: 'Old approach', status: 'CANCELLED', parentId: 'goal' },
]

test('progress counts leaves once and keeps cancelled work separate from completed work', () => {
  assert.deepEqual(taskProgress(buildTaskTree(tasks)), { total: 3, done: 1, cancelled: 1, active: 1 })
  assert.equal(tasks[0].children, undefined)
})

test('search reveals a matching descendant with its ancestors even when the goal is collapsed', () => {
  const rows = visibleTaskRows(buildTaskTree(tasks), { query: 'sample file', collapsed: new Set(['goal']) })
  assert.deepEqual(rows.map(row => [row.task.id, row.depth, row.contextOnly]), [
    ['goal', 0, true], ['plan', 1, true], ['load', 2, false],
  ])
})

test('status filters retain hierarchy and distinguish cancellation from completion', () => {
  const roots = buildTaskTree(tasks)
  assert.deepEqual(visibleTaskRows(roots, { filter: 'done' }).map(row => row.task.id), ['goal', 'plan', 'save'])
  assert.deepEqual(visibleTaskRows(roots, { filter: 'active' }).map(row => row.task.id), ['goal', 'plan', 'load'])
  assert.deepEqual(visibleTaskRows(roots, { filter: 'cancelled' }).map(row => row.task.id), ['goal', 'old'])
  assert.deepEqual(visibleTaskRows(roots, { collapsed: new Set(['plan']) }).map(row => row.task.id), ['goal', 'plan', 'old'])
})

test('orphans and cyclic legacy tasks stay visible without duplicating or looping', () => {
  const legacy = [
    { id: 'orphan', parentId: 'missing' }, { id: 'a', parentId: 'b' },
    { id: 'b', parentId: 'a' }, { id: 'child', parentId: 'a' }, { id: 'self', parentId: 'self' },
  ]
  assert.deepEqual(visibleTaskRows(buildTaskTree(legacy)).map(row => row.task.id).sort(), legacy.map(task => task.id).sort())
})

test('cancelled and missing prerequisites remain blockers', () => {
  const dependencies = taskDependencies({ dependsOn: ['save', 'old', 'missing'] }, tasks)
  assert.deepEqual(dependencies.map(item => item.satisfied), [true, false, false])
  assert.equal(dependencies[2].task, undefined)
})
