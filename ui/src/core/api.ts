import type {
  AdminChannel,
  AdminFilePage,
  AdminMessagePage,
  AdminSettings,
  Channel,
  GuestSession,
  Me,
  Message,
  MessagePage,
  Overview,
  StorageStats,
  UserSummary,
} from './types'

const PUBLIC_BASE = '/apis/api.chat.halo.run/v1alpha1'
const ADMIN_BASE = '/apis/console.api.chat.halo.run/v1alpha1'

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (init?.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('X-Requested-With', 'XMLHttpRequest')
  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers,
  })
  if (!response.ok) {
    let message = response.statusText || `请求失败 (${response.status})`
    try {
      const problem = await response.json()
      message = problem.detail || problem.title || message
    } catch {
      // ignore non JSON errors
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  const contentType = response.headers.get('content-type') || ''
  if (contentType.includes('application/json')) {
    return (await response.json()) as T
  }
  return (await response.text()) as unknown as T
}

export const publicApi = {
  me: () => request<Me>(`${PUBLIC_BASE}/me`),
  createGuestSession: (nickname: string) =>
    request<GuestSession>(`${PUBLIC_BASE}/guest-sessions`, {
      method: 'POST',
      body: JSON.stringify({ nickname }),
    }),
  channels: () => request<{ items: Channel[] }>(`${PUBLIC_BASE}/channels`),
  channel: (name: string) => request<Channel>(`${PUBLIC_BASE}/channels/${name}`),
  messages: (channel: string, before?: string | null, limit = 30) => {
    const params = new URLSearchParams({ limit: String(limit) })
    if (before) {
      params.set('before', before)
    }
    return request<MessagePage>(
      `${PUBLIC_BASE}/channels/${channel}/messages?${params.toString()}`,
    )
  },
  send: (channel: string, content: string) =>
    request<Message>(`${PUBLIC_BASE}/channels/${channel}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content }),
    }),
  upload: (channel: string, file: File, caption = '') => {
    const form = new FormData()
    form.append('file', file, file.name)
    if (caption) {
      form.append('caption', caption)
    }
    return request<Message>(`${PUBLIC_BASE}/channels/${channel}/files`, {
      method: 'POST',
      body: form,
    })
  },
  deleteMessage: (name: string) =>
    request<void>(`${PUBLIC_BASE}/messages/${name}`, { method: 'DELETE' }),
  eventsUrl: (channel: string) =>
    `${PUBLIC_BASE}/events?channel=${encodeURIComponent(channel)}`,
  globalInfo: () => request<{ allowAnonymousComments?: boolean }>('/actuator/globalinfo'),
}

export const adminApi = {
  overview: () => request<Overview>(`${ADMIN_BASE}/overview`),
  settings: () =>
    request<{ settings: AdminSettings; commentWidgetAvailable: boolean }>(
      `${ADMIN_BASE}/settings`,
    ),
  channels: () => request<{ items: AdminChannel[] }>(`${ADMIN_BASE}/channels`),
  createChannel: (body: Record<string, unknown>) =>
    request<AdminChannel>(`${ADMIN_BASE}/channels`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  updateChannel: (name: string, body: Record<string, unknown>) =>
    request<AdminChannel>(`${ADMIN_BASE}/channels/${name}`, {
      method: 'PATCH',
      body: JSON.stringify(body),
    }),
  deleteChannel: (name: string) =>
    request<void>(`${ADMIN_BASE}/channels/${name}`, { method: 'DELETE' }),
  channelMessages: (name: string, page = 1, size = 20) =>
    request<AdminMessagePage>(
      `${ADMIN_BASE}/channels/${name}/messages?page=${page}&size=${size}`,
    ),
  clearMessages: (name: string) =>
    request<void>(`${ADMIN_BASE}/channels/${name}/messages`, { method: 'DELETE' }),
  sendAsUser: (channel: string, username: string, content: string) =>
    request<Message>(`${ADMIN_BASE}/channels/${channel}/messages/as-user`, {
      method: 'POST',
      body: JSON.stringify({ username, content }),
    }),
  editMessage: (name: string, content: string) =>
    request<Message>(`${ADMIN_BASE}/messages/${name}`, {
      method: 'PATCH',
      body: JSON.stringify({ content }),
    }),
  deleteMessage: (name: string) =>
    request<void>(`${ADMIN_BASE}/messages/${name}`, { method: 'DELETE' }),
  updateAnnouncement: (channel: string, content: string) =>
    request<AdminChannel>(`${ADMIN_BASE}/channels/${channel}/announcement`, {
      method: 'POST',
      body: JSON.stringify({ content }),
    }),
  deleteAnnouncement: (channel: string) =>
    request<AdminChannel>(`${ADMIN_BASE}/channels/${channel}/announcement`, {
      method: 'DELETE',
    }),
  files: (channel?: string, page = 1, size = 20) => {
    const params = new URLSearchParams({ page: String(page), size: String(size) })
    if (channel) {
      params.set('channel', channel)
    }
    return request<AdminFilePage>(`${ADMIN_BASE}/files?${params.toString()}`)
  },
  deleteFile: (name: string) => request<void>(`${ADMIN_BASE}/files/${name}`, { method: 'DELETE' }),
  users: (keyword = '') =>
    request<{ items: UserSummary[] }>(
      `${ADMIN_BASE}/users?keyword=${encodeURIComponent(keyword)}`,
    ),
  storage: () => request<StorageStats>(`${ADMIN_BASE}/storage`),
  maintenanceCleanup: () =>
    request<{ expiredFiles: number; tempFiles: number; inactiveGuests: number }>(
      `${ADMIN_BASE}/maintenance/cleanup`,
      { method: 'POST' },
    ),
}
