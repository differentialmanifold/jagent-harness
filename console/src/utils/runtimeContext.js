export function isRuntimeContextMessage(message) {
  try {
    return JSON.parse(message?.metadataJson || '{}').kind === 'runtime_context'
  } catch {
    return false
  }
}
