const ALLOWED_TAGS = new Set([
  'P',
  'BR',
  'DIV',
  'SPAN',
  'STRONG',
  'B',
  'EM',
  'I',
  'U',
  'S',
  'DEL',
  'CODE',
  'PRE',
  'BLOCKQUOTE',
  'UL',
  'OL',
  'LI',
  'A',
  'IMG',
  'H1',
  'H2',
  'H3',
  'H4',
  'HR',
  'TABLE',
  'THEAD',
  'TBODY',
  'TR',
  'TH',
  'TD',
])

const ALLOWED_ATTRS = new Set(['href', 'src', 'alt', 'title', 'class', 'target', 'rel'])

function isSafeUrl(value: string, allowDataImage = false): boolean {
  const trimmed = value.trim().toLowerCase()
  if (trimmed.startsWith('javascript:') || trimmed.startsWith('vbscript:')) {
    return false
  }
  if (trimmed.startsWith('data:')) {
    return allowDataImage && trimmed.startsWith('data:image/')
  }
  return true
}

/**
 * Minimal allow-list HTML sanitizer for rich text mirrored from Halo comments.
 * Avoids pulling a large sanitizer dependency into both UI bundles.
 */
export function sanitizeHtml(html: string): string {
  if (!html) {
    return ''
  }
  const document = new DOMParser().parseFromString(html, 'text/html')

  const walk = (node: Node) => {
    const children = Array.from(node.childNodes)
    for (const child of children) {
      if (child.nodeType === Node.TEXT_NODE) {
        continue
      }
      if (child.nodeType !== Node.ELEMENT_NODE) {
        child.remove()
        continue
      }
      const element = child as HTMLElement
      if (!ALLOWED_TAGS.has(element.tagName)) {
        // keep text of unknown wrappers, drop the element itself
        const text = element.textContent || ''
        element.replaceWith(document.createTextNode(text))
        continue
      }
      for (const attribute of Array.from(element.attributes)) {
        const name = attribute.name.toLowerCase()
        if (!ALLOWED_ATTRS.has(name) || name.startsWith('on')) {
          element.removeAttribute(attribute.name)
          continue
        }
        if ((name === 'href' || name === 'src') && !isSafeUrl(attribute.value, name === 'src')) {
          element.removeAttribute(attribute.name)
        }
      }
      if (element.tagName === 'A') {
        element.setAttribute('target', '_blank')
        element.setAttribute('rel', 'noopener noreferrer nofollow')
      }
      walk(element)
    }
  }

  walk(document.body)
  return document.body.innerHTML
}
