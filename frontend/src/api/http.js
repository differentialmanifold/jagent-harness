export async function request(url, options = {}) {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  const text = await response.text()
  if (!response.ok) {
    let message = response.statusText
    if (text) {
      try {
        message = JSON.parse(text).message || message
      } catch {
        message = text
      }
    }
    throw new Error(message)
  }
  return text ? JSON.parse(text) : null
}
