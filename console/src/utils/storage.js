let storageUser = ''
export function setStorageUser(user) { storageUser = user || '' }
function scopedKey(key) { return storageUser ? `user:${storageUser}:${key}` : key }

export function loadJson(key, fallback) {
  try {
    const raw = window.localStorage.getItem(scopedKey(key))
    return raw ? JSON.parse(raw) : fallback
  } catch {
    return fallback
  }
}

export function saveJson(key, value) {
  window.localStorage.setItem(scopedKey(key), JSON.stringify(value))
}
