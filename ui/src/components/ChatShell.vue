<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { publicApi, ApiError } from '../core/api'
import type { AdminChannel, Channel, ChatEvent, Me, Message } from '../core/types'
import { formatLimit, formatTime } from '../core/format'
import { subscribeEvents, type EventSubscription } from '../core/sse'
import { useToast } from '../core/toast'
import UserAvatar from '../components/UserAvatar.vue'
import MessageItem from '../components/MessageItem.vue'
import ChatComposer from '../components/ChatComposer.vue'
import CommentComposer from '../components/CommentComposer.vue'
import GuestNameDialog from '../components/GuestNameDialog.vue'
import ToastHost from '../components/ToastHost.vue'
import CreateChannelDialog from '../components/CreateChannelDialog.vue'

const props = withDefaults(defineProps<{ mode?: 'console' | 'web' }>(), { mode: 'console' })

const toast = useToast()
const me = ref<Me | null>(null)
const channels = ref<Channel[]>([])
const activeName = ref('')
const messages = ref<Message[]>([])
const hasMore = ref(false)
const loadingChannels = ref(true)
const loadingMessages = ref(false)
const sending = ref(false)
const uploading = ref(false)
const mobileChat = ref(false)
const showGuestDialog = ref(false)
const showCreateChannel = ref(false)
const guestBusy = ref(false)
const previewUrl = ref<string | null>(null)
const allowAnonymousComments = ref(false)
const forceBuiltinComposer = ref(false)
const sseConnected = ref(false)
const messagesElement = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
let subscription: EventSubscription | null = null
let pendingText = ''
let pendingFile: File | null = null

const activeChannel = computed(() => channels.value.find((channel) => channel.name === activeName.value))
const isAnonymous = computed(() => me.value?.type === 'ANONYMOUS')
const isGuest = computed(() => me.value?.type === 'GUEST')
const canManage = computed(() => me.value?.canManage === true)
const effectiveComposerMode = computed(() => {
  if (!activeChannel.value || !me.value) {
    return 'BUILTIN'
  }
  if (activeChannel.value.composerMode === 'INHERIT') {
    return me.value.composerMode
  }
  return activeChannel.value.composerMode
})
const useCommentComposer = computed(() => {
  const channel = activeChannel.value
  const currentMe = me.value
  if (!channel || !currentMe) {
    return false
  }
  if (effectiveComposerMode.value !== 'HALO_COMMENT') {
    return false
  }
  if (!currentMe.commentWidgetAvailable || forceBuiltinComposer.value) {
    return false
  }
  if (currentMe.type === 'USER') {
    return true
  }
  // Guests / anonymous visitors can use the comment widget only when the
  // official plugin allows anonymous comments for the public channel.
  return channel.type === 'PUBLIC' && allowAnonymousComments.value
})
const canPost = computed(() => activeChannel.value?.canPost === true)
const canUpload = computed(() => {
  if (!canPost.value || !me.value) {
    return false
  }
  if (me.value.type === 'USER') {
    return true
  }
  if (me.value.type === 'GUEST') {
    return me.value.guestUploadEnabled
  }
  return false
})
const channelTypeLabel = (channel: Channel) => {
  if (channel.type === 'PUBLIC') return '公共'
  if (channel.type === 'GROUP') return '群组'
  return '话题'
}

function channelIcon(channel: Channel) {
  if (channel.avatar) return channel.avatar
  if (channel.type === 'PUBLIC') return '📢'
  if (channel.type === 'GROUP') return '👥'
  return '💬'
}

async function loadMe() {
  try {
    me.value = await publicApi.me()
  } catch (error) {
    console.warn('Failed to resolve current chat identity', error)
  }
}

async function loadAllowAnonymousComments() {
  try {
    const info = await publicApi.globalInfo()
    allowAnonymousComments.value = info.allowAnonymousComments === true
  } catch {
    allowAnonymousComments.value = false
  }
}

async function loadChannels(preserveActive = false) {
  loadingChannels.value = true
  try {
    const response = await publicApi.channels()
    const items = response.items || []
    channels.value = items
    const queryChannel = new URLSearchParams(window.location.search).get('channel')
    const stored = sessionStorage.getItem('halo-chat-active-channel')
    const preferred = [queryChannel, preserveActive ? activeName.value : null, stored, items[0]?.name]
      .find((name): name is string => Boolean(name) && items.some((item) => item.name === name))
    if (preferred && preferred !== activeName.value) {
      await selectChannel(preferred)
    } else if (!items.length) {
      activeName.value = ''
    }
  } catch (error) {
    toast.error(errorMessage(error))
  } finally {
    loadingChannels.value = false
  }
}

async function selectChannel(name: string) {
  if (name === activeName.value && messages.value.length) {
    mobileChat.value = true
    return
  }
  activeName.value = name
  mobileChat.value = true
  sessionStorage.setItem('halo-chat-active-channel', name)
  forceBuiltinComposer.value = false
  await loadMessages(true)
  subscribe(name)
}

async function loadMessages(reset: boolean) {
  const channel = activeName.value
  if (!channel) {
    return
  }
  if (reset) {
    messages.value = []
    hasMore.value = false
  }
  loadingMessages.value = true
  try {
    const before = reset ? null : messages.value[0]?.createdAt || null
    const page = await publicApi.messages(channel, before)
    if (reset) {
      messages.value = page.items || []
    } else {
      messages.value = [...(page.items || []), ...messages.value]
    }
    hasMore.value = page.hasMore
    if (reset) {
      await scrollToBottom()
    }
  } catch (error) {
    toast.error(errorMessage(error))
  } finally {
    loadingMessages.value = false
  }
}

function subscribe(channel: string) {
  subscription?.close()
  subscription = subscribeEvents(channel, onEvent, (connected) => {
    sseConnected.value = connected
  })
}

function onEvent(event: ChatEvent) {
  if (event.type === 'message.created' || event.type === 'message.updated') {
    const message = event.payload as Message | null
    if (message && message.channel === activeName.value) {
      upsertMessage(message, event.type === 'message.created')
    }
    return
  }
  if (event.type === 'message.deleted') {
    const message = event.payload as Message | null
    if (message) {
      messages.value = messages.value.filter((item) => item.name !== message.name)
    }
    return
  }
  if (event.type.startsWith('channel.') || event.type === 'announcement.updated') {
    loadChannels(true)
  }
}

function upsertMessage(message: Message, allowScroll: boolean) {
  const index = messages.value.findIndex((item) => item.name === message.name)
  if (index >= 0) {
    messages.value.splice(index, 1, message)
    return
  }
  messages.value.push(message)
  messages.value.sort((left, right) => left.createdAt.localeCompare(right.createdAt))
  if (allowScroll) {
    scrollToBottom()
  }
}

async function scrollToBottom() {
  await nextTick()
  const element = messagesElement.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}

function onMessagesScroll() {
  const element = messagesElement.value
  if (!element || element.scrollTop > 40 || !hasMore.value || loadingMessages.value) {
    return
  }
  loadMessages(false)
}

async function sendText(content: string) {
  if (!activeName.value) {
    return
  }
  if (!me.value || me.value.type === 'ANONYMOUS') {
    pendingText = content
    pendingFile = null
    showGuestDialog.value = true
    return
  }
  sending.value = true
  try {
    const message = await publicApi.send(activeName.value, content)
    upsertMessage(message, true)
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      pendingText = content
      showGuestDialog.value = true
    } else {
      toast.error(errorMessage(error))
    }
  } finally {
    sending.value = false
  }
}

async function uploadFile(file: File) {
  if (!activeName.value) {
    return
  }
  const limit = me.value?.maxFileSize || 0
  if (limit > 0 && file.size > limit) {
    toast.error(`文件超过单文件上限 ${formatLimit(limit)}`)
    return
  }
  if (!me.value || me.value.type === 'ANONYMOUS') {
    pendingFile = file
    pendingText = ''
    showGuestDialog.value = true
    return
  }
  uploading.value = true
  try {
    const message = await publicApi.upload(activeName.value, file)
    upsertMessage(message, true)
    toast.success('文件已发送')
  } catch (error) {
    toast.error(errorMessage(error))
  } finally {
    uploading.value = false
  }
}

async function submitGuestName(nickname: string) {
  guestBusy.value = true
  try {
    await publicApi.createGuestSession(nickname)
    await loadMe()
    // channel permissions depend on the identity, refresh canPost flags
    await loadChannels(true)
    showGuestDialog.value = false
    toast.success(`欢迎，${nickname}`)
    if (pendingText) {
      const content = pendingText
      pendingText = ''
      await sendText(content)
    } else if (pendingFile) {
      const file = pendingFile
      pendingFile = null
      await uploadFile(file)
    }
  } catch (error) {
    toast.error(errorMessage(error))
  } finally {
    guestBusy.value = false
  }
}

function openGuestDialog() {
  showGuestDialog.value = true
}

async function handleChannelCreated(channel: AdminChannel) {
  showCreateChannel.value = false
  await loadChannels(true)
  if (channel?.name) {
    await selectChannel(channel.name)
  }
}

function openAdmin() {
  window.location.href = '/console/chat/admin'
}

function openPublicPage() {
  window.location.href = '/plugins/halo-chat/'
}

function openConsole() {
  window.location.href = '/console/'
}

async function deleteMessage(message: Message) {
  if (!window.confirm('确认删除这条消息吗？')) {
    return
  }
  try {
    await publicApi.deleteMessage(message.name)
    messages.value = messages.value.filter((item) => item.name !== message.name)
  } catch (error) {
    toast.error(errorMessage(error))
  }
}

function pickFile(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    uploadFile(file)
  }
  target.value = ''
}

function errorMessage(error: unknown) {
  if (error instanceof Error) {
    return error.message
  }
  return '操作失败，请稍后再试'
}

onMounted(async () => {
  await Promise.all([loadMe(), loadAllowAnonymousComments()])
  await loadChannels()
})

onBeforeUnmount(() => subscription?.close())
</script>

<template>
  <div class="hc-root" :class="{ 'hc-mode-web': props.mode === 'web', 'is-mobile-chat': mobileChat }">
    <aside class="hc-sidebar">
      <div class="hc-brand">
        <span class="hc-brand__logo">💬</span>
        <span>聊天</span>
        <span class="hc-brand__spacer" />
        <a v-if="props.mode === 'console' && canManage" class="hc-brand__link" @click.prevent="openAdmin">
          管理
        </a>
        <a v-if="props.mode === 'console'" class="hc-brand__link" @click.prevent="openPublicPage">
          公开页
        </a>
        <a v-else class="hc-brand__link" @click.prevent="openConsole">登录 Halo</a>
      </div>

      <div class="hc-sidebar__user">
        <UserAvatar :name="me?.name" :display-name="me?.displayName" :src="me?.avatar" :size="36" />
        <div class="hc-sidebar__user-info">
          <div class="hc-sidebar__user-name">
            {{ me ? me.displayName : '加载中…' }}
          </div>
          <div class="hc-sidebar__user-meta">
            <template v-if="me?.type === 'USER'">已登录 Halo</template>
            <template v-else-if="me?.type === 'GUEST'">游客会话</template>
            <template v-else>未登录 · 可昵称参与</template>
          </div>
        </div>
        <button
          v-if="me && me.type !== 'USER'"
          class="hc-sidebar__user-action"
          @click="openGuestDialog"
        >
          {{ me.type === 'GUEST' ? '改昵称' : '设昵称' }}
        </button>
      </div>

      <div class="hc-channel-list">
        <div v-if="loadingChannels" class="hc-sidebar__footer">正在加载频道…</div>
        <button
          v-if="canManage"
          class="hc-channel-create"
          type="button"
          @click="showCreateChannel = true"
        >
          ＋ 新建群组 / 话题
        </button>
        <button
          v-for="channel in channels"
          :key="channel.name"
          class="hc-channel"
          :class="{ 'is-active': channel.name === activeName }"
          @click="selectChannel(channel.name)"
        >
          <span class="hc-channel__icon">
            <img v-if="channel.avatar" :src="channel.avatar" alt="" />
            <template v-else>{{ channelIcon(channel) }}</template>
          </span>
          <span class="hc-channel__body">
            <span class="hc-channel__title">
              <span class="hc-channel__title-text">{{ channel.displayName }}</span>
              <span v-if="channel.type === 'PUBLIC'" class="hc-chip hc-chip--public">公共</span>
            </span>
            <span class="hc-channel__preview">
              {{ channel.description || (channel.memberCount ? `${channel.memberCount} 位成员` : '暂无消息') }}
            </span>
          </span>
          <span class="hc-channel__time">
            {{ channel.lastMessageAt ? formatTime(channel.lastMessageAt) : '' }}
          </span>
        </button>
        <div v-if="!loadingChannels && !channels.length" class="hc-sidebar__footer">
          暂无可以访问的频道
        </div>
      </div>

      <div class="hc-sidebar__footer">
        <template v-if="props.mode === 'web'">
          <a href="/console/" style="display: inline-block; margin-bottom: 4px">前往 Halo 控制台登录</a>
          <div>游客昵称与消息仅用于本站聊天</div>
        </template>
        <template v-else>
          <div>公共频道置顶 · 群组/话题由管理员创建</div>
        </template>
      </div>
    </aside>

    <main class="hc-main">
      <template v-if="activeChannel">
        <header class="hc-chat-header">
          <button class="hc-chat-header__back" @click="mobileChat = false">←</button>
          <UserAvatar
            :name="activeChannel.name"
            :display-name="activeChannel.displayName"
            :src="activeChannel.avatar"
            :size="34"
          />
          <div class="hc-chat-header__body">
            <div class="hc-chat-header__title">
              {{ activeChannel.displayName }}
              <span class="hc-chip">{{ channelTypeLabel(activeChannel) }}</span>
              <span v-if="sseConnected" class="hc-chip hc-chip--public">实时</span>
            </div>
            <div class="hc-chat-header__meta">
              <span>{{ activeChannel.description || '暂无简介' }}</span>
              <span v-if="activeChannel.memberCount">· {{ activeChannel.memberCount }} 位成员</span>
              <span>· 单文件上限 {{ formatLimit(me?.maxFileSize) }}</span>
            </div>
          </div>
          <button v-if="props.mode === 'console' && canManage" class="hc-link-btn" @click="openAdmin">
            管理频道
          </button>
        </header>

        <div v-if="activeChannel.announcement?.content" class="hc-announcement">
          <span class="hc-announcement__icon">📣</span>
          <span style="white-space: pre-wrap">{{ activeChannel.announcement.content }}</span>
        </div>

        <div ref="messagesElement" class="hc-messages" @scroll="onMessagesScroll">
          <button v-if="hasMore" class="hc-load-more" :disabled="loadingMessages" @click="loadMessages(false)">
            {{ loadingMessages ? '加载中…' : '加载更早的消息' }}
          </button>
          <div v-if="loadingMessages && !messages.length" class="hc-empty">正在加载消息…</div>
          <MessageItem
            v-for="message in messages"
            :key="message.name"
            :message="message"
            :me="me"
            @delete="deleteMessage"
            @preview="previewUrl = $event"
          />
          <div v-if="!loadingMessages && !messages.length" class="hc-empty">
            <span class="hc-empty__emoji">🫧</span>
            <span>还没有消息，来发第一条吧</span>
          </div>
        </div>

        <template v-if="useCommentComposer">
          <CommentComposer
            :channel="activeChannel.name"
            @submitted="loadMessages(true)"
            @fallback="forceBuiltinComposer = true"
          />
          <div class="hc-composer" style="display: flex; align-items: center; gap: 8px">
            <button class="hc-icon-btn" title="发送文件" :disabled="!canUpload || uploading" @click="fileInput?.click()">
              📎
            </button>
            <input ref="fileInput" type="file" hidden @change="pickFile" />
            <span style="font-size: 11.5px; color: #6b7280">
              以上为 Halo 评论组件输入区；也可以单独上传文件（上限 {{ formatLimit(me?.maxFileSize) }}）
            </span>
            <span v-if="uploading" style="font-size: 11.5px; color: #6b7280">上传中…</span>
          </div>
        </template>
        <ChatComposer
          v-else-if="canPost"
          :disabled="sending"
          :uploading="uploading"
          :can-upload="canUpload"
          :max-length="me?.messageMaxLength || 2000"
          @send="sendText"
          @upload="uploadFile"
        />
        <div v-else class="hc-composer-locked">
          <template v-if="isAnonymous">
            <span>输入昵称即可参与聊天</span>
            <button class="hc-link-btn" @click="openGuestDialog">设置昵称</button>
          </template>
          <template v-else>
            <span>当前账号没有在该频道发言的权限</span>
          </template>
        </div>
      </template>
      <div v-else class="hc-empty">
        <span class="hc-empty__emoji">💬</span>
        <span>{{ loadingChannels ? '正在加载频道…' : '还没有可以访问的频道' }}</span>
      </div>
    </main>

    <GuestNameDialog
      :open="showGuestDialog"
      :initial="isGuest ? me?.displayName : ''"
      :max-length="me?.guestNameMaxLength || 20"
      :busy="guestBusy"
      :title="isGuest ? '修改游客昵称' : '输入昵称参与聊天'"
      @close="showGuestDialog = false"
      @submit="submitGuestName"
    />

    <CreateChannelDialog
      :open="showCreateChannel"
      @close="showCreateChannel = false"
      @created="handleChannelCreated"
    />

    <Teleport to="body">
      <div v-if="previewUrl" class="hc-image-preview" @click="previewUrl = null">
        <img :src="previewUrl" alt="预览" />
      </div>
    </Teleport>
    <ToastHost />
  </div>
</template>
