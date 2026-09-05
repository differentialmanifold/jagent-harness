export const MAX_IMAGE_COUNT = 4
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024
export const MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024

export const ACCEPTED_IMAGE_MEDIA_TYPES = Object.freeze([
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif'
])

export const IMAGE_FILE_ACCEPT = ACCEPTED_IMAGE_MEDIA_TYPES.join(',')

const ACCEPTED_IMAGE_MEDIA_TYPE_SET = new Set(ACCEPTED_IMAGE_MEDIA_TYPES)

export function validateImageFiles(files, existingImages = []) {
  const accepted = []
  const errors = []
  let imageCount = Array.isArray(existingImages) ? existingImages.length : 0
  let totalBytes = imageBytes(existingImages)

  for (const file of Array.from(files || [])) {
    const name = String(file?.name || 'Image')
    const mediaType = String(file?.type || '').toLowerCase()
    const size = normalizedSize(file?.size)

    if (!ACCEPTED_IMAGE_MEDIA_TYPE_SET.has(mediaType)) {
      errors.push(`${name}: only PNG, JPEG, WebP, and GIF images are supported.`)
      continue
    }
    if (size === 0) {
      errors.push(`${name}: image is empty.`)
      continue
    }
    if (size > MAX_IMAGE_BYTES) {
      errors.push(`${name}: image exceeds the 10 MB limit.`)
      continue
    }
    if (imageCount >= MAX_IMAGE_COUNT) {
      errors.push(`You can attach up to ${MAX_IMAGE_COUNT} images.`)
      continue
    }
    if (totalBytes + size > MAX_TOTAL_IMAGE_BYTES) {
      errors.push('Attached images cannot exceed 20 MB in total.')
      continue
    }

    accepted.push(file)
    imageCount += 1
    totalBytes += size
  }

  return { accepted, errors }
}

export function toImageRequest(images) {
  return (Array.isArray(images) ? images : [])
    .filter((image) => image && isSubmittableImageUrl(image.url, image.mediaType))
    .map((image) => ({
      name: String(image.name || 'Image'),
      mediaType: String(image.mediaType || '').toLowerCase(),
      url: image.url
    }))
}

export function isSubmittableImageUrl(url, mediaType = '') {
  if (typeof url !== 'string' || !url) return false
  const match = /^data:(image\/(?:png|jpeg|webp|gif));base64,/i.exec(url)
  if (!match) return false
  const declaredMediaType = String(mediaType || '').toLowerCase()
  return !declaredMediaType || match[1].toLowerCase() === declaredMediaType
}

export function isDisplayableImageUrl(url) {
  if (typeof url !== 'string' || !url) return false
  if (isSubmittableImageUrl(url)) return true
  if (/^https?:\/\//i.test(url)) return true
  if (/^blob:/i.test(url)) return true
  return url.startsWith('/') && !url.startsWith('//')
}

export function readImageAttachment(file) {
  return new Promise((resolve, reject) => {
    if (typeof FileReader === 'undefined') {
      reject(new Error('Image reading is not supported by this browser.'))
      return
    }

    const reader = new FileReader()
    reader.onerror = () => reject(new Error(`Unable to read ${file?.name || 'image'}.`))
    reader.onload = () => {
      const url = typeof reader.result === 'string' ? reader.result : ''
      if (!isSubmittableImageUrl(url, file?.type)) {
        reject(new Error(`${file?.name || 'Image'} could not be converted to an image data URL.`))
        return
      }
      resolve({
        imageId: createImageId(),
        name: String(file?.name || 'Image'),
        mediaType: String(file?.type || '').toLowerCase(),
        size: normalizedSize(file?.size),
        url
      })
    }
    reader.readAsDataURL(file)
  })
}

export function imageCountLabel(count) {
  const value = Number(count) || 0
  return `${value} image${value === 1 ? '' : 's'}`
}

function imageBytes(images) {
  return (Array.isArray(images) ? images : [])
    .reduce((total, image) => total + normalizedSize(image?.size), 0)
}

function normalizedSize(size) {
  const value = Number(size)
  return Number.isFinite(value) && value > 0 ? value : 0
}

function createImageId() {
  if (globalThis.crypto?.randomUUID) {
    return `image_${globalThis.crypto.randomUUID()}`
  }
  return `image_${Date.now()}_${Math.random().toString(36).slice(2)}`
}
