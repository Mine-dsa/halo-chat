import type { ChatEvent } from './types'

export interface EventSubscription {
  close: () => void
}

/**
 * Subscribes to the chat SSE endpoint. Returns a disposable handle.
 */
export function subscribeEvents(
  channel: string,
  onEvent: (event: ChatEvent) => void,
  onStatus?: (connected: boolean) => void,
): EventSubscription {
  let closed = false
  let source: EventSource | null = null

  const connect = () => {
    if (closed) {
      return
    }
    source = new EventSource(`/apis/api.chat.halo.run/v1alpha1/events?channel=${encodeURIComponent(channel)}`, {
      withCredentials: true,
    })
    source.onopen = () => onStatus?.(true)
    source.onmessage = (message) => {
      try {
        const data = JSON.parse(message.data) as ChatEvent
        if (data && data.type) {
          onEvent(data)
        }
      } catch {
        // ignore malformed events / heartbeat comments
      }
    }
    source.onerror = () => {
      onStatus?.(false)
      if (source) {
        source.close()
        source = null
      }
      if (!closed) {
        window.setTimeout(connect, 3000)
      }
    }
  }

  connect()

  return {
    close() {
      closed = true
      source?.close()
      source = null
    },
  }
}
