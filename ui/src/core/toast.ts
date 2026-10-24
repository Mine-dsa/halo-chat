import { reactive } from 'vue'

export interface ToastItem {
  id: number
  message: string
  type: 'success' | 'error' | 'info'
}

const state = reactive<{ items: ToastItem[] }>({ items: [] })
let nextId = 1

function push(message: string, type: ToastItem['type']) {
  const item = { id: nextId++, message, type }
  state.items.push(item)
  window.setTimeout(() => {
    const index = state.items.findIndex((entry) => entry.id === item.id)
    if (index >= 0) {
      state.items.splice(index, 1)
    }
  }, type === 'error' ? 5000 : 2500)
}

export function useToast() {
  return {
    items: state.items,
    success: (message: string) => push(message, 'success'),
    error: (message: string) => push(message, 'error'),
    info: (message: string) => push(message, 'info'),
  }
}
