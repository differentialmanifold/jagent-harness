export function workspaceKey(workspacePath) {
  return workspacePath || '__default__'
}

export function projectName(workspacePath) {
  if (!workspacePath) return 'Default Workspace'
  const normalized = workspacePath.replace(/[\\/]+$/, '')
  const parts = normalized.split(/[\\/]/).filter(Boolean)
  return parts.length > 0 ? parts[parts.length - 1] : normalized
}

export function projectPathLabel(workspacePath) {
  return workspacePath || 'Default workspace'
}
