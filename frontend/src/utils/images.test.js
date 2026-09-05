import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MAX_IMAGE_BYTES,
  MAX_TOTAL_IMAGE_BYTES,
  imageCountLabel,
  isDisplayableImageUrl,
  isSubmittableImageUrl,
  toImageRequest,
  validateImageFiles
} from './images.js'

function image(name, type = 'image/png', size = 1024) {
  return { name, type, size }
}

test('accepts supported images within count and size limits', () => {
  const result = validateImageFiles([
    image('a.png'),
    image('b.jpg', 'image/jpeg'),
    image('c.webp', 'image/webp'),
    image('d.gif', 'image/gif')
  ])

  assert.equal(result.accepted.length, 4)
  assert.deepEqual(result.errors, [])
})

test('rejects unsupported, oversized, excess-count, and excess-total images', () => {
  const unsupported = validateImageFiles([image('vector.svg', 'image/svg+xml')])
  assert.equal(unsupported.accepted.length, 0)
  assert.match(unsupported.errors[0], /only PNG/)

  const empty = validateImageFiles([image('empty.png', 'image/png', 0)])
  assert.equal(empty.accepted.length, 0)
  assert.match(empty.errors[0], /empty/)

  const oversized = validateImageFiles([image('large.png', 'image/png', MAX_IMAGE_BYTES + 1)])
  assert.equal(oversized.accepted.length, 0)
  assert.match(oversized.errors[0], /10 MB/)

  const excessCount = validateImageFiles(
    [image('fifth.png')],
    Array.from({ length: 4 }, (_, index) => ({ name: `${index}.png`, size: 1 }))
  )
  assert.equal(excessCount.accepted.length, 0)
  assert.match(excessCount.errors[0], /up to 4/)

  const excessTotal = validateImageFiles(
    [image('more.png', 'image/png', 2)],
    [{ name: 'existing.png', size: MAX_TOTAL_IMAGE_BYTES - 1 }]
  )
  assert.equal(excessTotal.accepted.length, 0)
  assert.match(excessTotal.errors[0], /20 MB/)
})

test('creates the exact public image request shape and filters unsafe URLs', () => {
  assert.deepEqual(toImageRequest([
    {
      imageId: 'local-only',
      name: 'screen.png',
      mediaType: 'image/png',
      size: 42,
      url: 'data:image/png;base64,AAAA'
    },
    {
      name: 'bad.svg',
      mediaType: 'image/svg+xml',
      url: 'data:image/svg+xml;base64,AAAA'
    },
    {
      name: 'remote.png',
      mediaType: 'image/png',
      url: 'https://example.com/image.png'
    },
    {
      name: 'mismatch.jpg',
      mediaType: 'image/jpeg',
      url: 'data:image/png;base64,AAAA'
    }
  ]), [
    {
      name: 'screen.png',
      mediaType: 'image/png',
      url: 'data:image/png;base64,AAAA'
    }
  ])

  assert.equal(isDisplayableImageUrl('/api/images/1'), true)
  assert.equal(isDisplayableImageUrl('//example.com/image.png'), false)
  assert.equal(isDisplayableImageUrl('https://example.com/image.png'), true)
  assert.equal(isDisplayableImageUrl('javascript:alert(1)'), false)
  assert.equal(isSubmittableImageUrl('https://example.com/image.png', 'image/png'), false)
  assert.equal(isSubmittableImageUrl('data:image/png;base64,AAAA', 'image/png'), true)
  assert.equal(isSubmittableImageUrl('data:image/png;base64,AAAA', 'image/jpeg'), false)
  assert.equal(imageCountLabel(1), '1 image')
  assert.equal(imageCountLabel(2), '2 images')
})
