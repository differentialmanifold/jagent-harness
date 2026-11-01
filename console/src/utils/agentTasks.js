export const taskStatusLabels = {
  TODO: 'To do', DOING: 'In progress', WAITING: 'Waiting',
  DONE: 'Done', CANCELLED: 'Cancelled',
}

export function isTaskFinished(task) {
  return ['DONE', 'CANCELLED'].includes(task.status)
}

// Build without mutating the polled records. Missing parents and malformed cycles
// remain visible as roots instead of hiding tasks or recursing indefinitely.
export function buildTaskTree(tasks) {
  const nodes = new Map(tasks.filter(task => task.id).map(task => [task.id, { task, children: [] }]))
  const roots = []
  for (const node of nodes.values()) {
    let parent = nodes.get(node.task.parentId)
    const seen = new Set([node.task.id])
    for (let ancestor = parent; ancestor; ancestor = nodes.get(ancestor.task.parentId)) {
      if (seen.has(ancestor.task.id)) { parent = null; break }
      seen.add(ancestor.task.id)
    }
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}

// Count actionable leaves, not both a goal and every step beneath that goal.
// Cancellation is separate from completion and never fills the progress bar.
export function taskProgress(nodes) {
  const summary = { total: 0, done: 0, cancelled: 0, active: 0 }
  function visit(node) {
    if (node.children.length) return node.children.forEach(visit)
    summary.total++
    if (node.task.status === 'DONE') summary.done++
    else if (node.task.status === 'CANCELLED') summary.cancelled++
    else summary.active++
  }
  nodes.forEach(visit)
  return summary
}

export function visibleTaskRows(roots, { query = '', filter = 'all', collapsed = new Set() } = {}) {
  const needle = query.trim().toLocaleLowerCase()
  const filtering = Boolean(needle) || filter !== 'all'
  function visit(node, depth) {
    const task = node.task
    const statusMatch = filter === 'active' ? !isTaskFinished(task)
      : filter === 'done' ? task.status === 'DONE'
      : filter === 'cancelled' ? task.status === 'CANCELLED' : true
    const textMatch = !needle || [task.title, task.description, task.acceptance, task.progress]
      .some(value => String(value || '').toLocaleLowerCase().includes(needle))
    const children = node.children.flatMap(child => visit(child, depth + 1))
    if (!(statusMatch && textMatch) && !children.length) return []
    const expanded = filtering || !collapsed.has(task.id)
    return [{ ...node, depth, expanded, contextOnly: !(statusMatch && textMatch) },
      ...(expanded ? children : [])]
  }
  return roots.flatMap(root => visit(root, 0))
}

export function taskDependencies(task, tasks) {
  const byId = new Map(tasks.map(item => [item.id, item]))
  return (task.dependsOn || []).map(id => ({
    id, task: byId.get(id), satisfied: byId.get(id)?.status === 'DONE',
  }))
}
