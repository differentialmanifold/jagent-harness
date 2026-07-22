export function formatDate(value) {
  if (!value) return ''
  return new Date(value).toLocaleString()
}

export function formatDuration(value) {
  if (value === null || value === undefined || value === '') return ''
  const durationMillis = Number(value)
  if (!Number.isFinite(durationMillis) || durationMillis < 0) return ''
  if (durationMillis < 100) return '<0.1s'
  if (durationMillis < 60_000) return `${(durationMillis / 1000).toFixed(1)}s`

  const totalSeconds = Math.round(durationMillis / 1000)
  const seconds = totalSeconds % 60
  const totalMinutes = Math.floor(totalSeconds / 60)
  if (totalMinutes < 60) return `${totalMinutes}m ${seconds}s`

  const hours = Math.floor(totalMinutes / 60)
  const minutes = totalMinutes % 60
  return `${hours}h ${minutes}m ${seconds}s`
}
