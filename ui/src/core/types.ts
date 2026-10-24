export type IdentityType = 'USER' | 'GUEST' | 'ANONYMOUS'
export type ChannelType = 'PUBLIC' | 'GROUP' | 'TOPIC'
export type AccessPolicy = 'EVERYONE' | 'AUTHENTICATED' | 'MEMBERS'
export type ComposerMode = 'INHERIT' | 'BUILTIN' | 'HALO_COMMENT'
export type SenderType = 'USER' | 'GUEST' | 'SYSTEM'

export interface Me {
  type: IdentityType
  name: string | null
  displayName: string
  avatar: string | null
  canManage: boolean
  guestEnabled: boolean
  guestUploadEnabled: boolean
  messageMaxLength: number
  guestNameMaxLength: number
  composerMode: 'BUILTIN' | 'HALO_COMMENT'
  maxFileSize: number
  maxTotalSize: number
  expireDays: number
  commentWidgetAvailable: boolean
}

export interface Announcement {
  content: string
  updatedAt?: string | null
  updatedBy?: string | null
}

export interface Channel {
  name: string
  type: ChannelType
  displayName: string
  description?: string | null
  avatar?: string | null
  owner?: string | null
  visibility: AccessPolicy
  postPolicy: AccessPolicy
  memberCount: number
  announcement?: Announcement | null
  composerMode: ComposerMode
  protectedChannel: boolean
  canPost: boolean
  lastMessageAt?: string | null
  createdAt?: string | null
}

export interface Attachment {
  fileRef: string
  name: string
  size: number
  mediaType: string | null
  image: boolean
  url: string
  expired: boolean
}

export interface Message {
  name: string
  channel: string
  senderType: SenderType
  senderName: string | null
  senderDisplayName: string | null
  senderAvatar: string | null
  contentType: 'TEXT' | 'HTML'
  content: string
  attachments: Attachment[]
  source: 'BUILTIN' | 'COMMENT' | 'ADMIN'
  onBehalf: boolean
  createdAt: string
  editedAt?: string | null
}

export interface MessagePage {
  items: Message[]
  hasMore: boolean
}

export interface GuestSession {
  guestId: string
  nickname: string
}

export interface AdminChannel extends Omit<Channel, 'canPost'> {
  members: string[]
  messageCount: number
}

export interface AdminMessagePage {
  items: Message[]
  page: number
  size: number
  total: number
}

export interface AdminFile {
  name: string
  channel: string
  messageName?: string | null
  filename: string
  size: number
  mediaType?: string | null
  uploaderType: 'USER' | 'GUEST'
  uploaderName?: string | null
  uploaderDisplayName?: string | null
  expiresAt?: string | null
  createdAt?: string | null
}

export interface AdminFilePage {
  items: AdminFile[]
  page: number
  size: number
  total: number
}

export interface StorageStats {
  usedBytes: number
  maxFileSize: number
  maxTotalSize: number
  expireDays: number
  diskUsedBytes: number
  storageRoot: string
}

export interface AdminSettings {
  general: {
    guestEnabled: boolean
    guestUploadEnabled: boolean
    guestNameMaxLength: number
    messageMaxLength: number
    guestRateLimit: number
    userRateLimit: number
  }
  files: {
    maxFileSizeMb: number
    maxTotalSizeMb: number
    expireDays: number
  }
  composer: {
    mode: 'BUILTIN' | 'HALO_COMMENT'
  }
}

export interface Overview {
  channelCount: number
  groupCount: number
  topicCount: number
  messageCount: number
  fileCount: number
  storage: StorageStats
  settings: AdminSettings
  commentWidgetAvailable: boolean
}

export interface ChatEvent {
  type: string
  channel: string
  payload: Message | null
  at: string
}

export interface UserSummary {
  name: string
  displayName?: string | null
  avatar?: string | null
  email?: string | null
}
