<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { adminApi } from '../core/api'
import type {
  AdminChannel,
  AdminFilePage,
  AdminMessagePage,
  AdminSettings,
  Message,
  Overview,
  UserSummary,
} from '../core/types'
import { formatDateTime, formatLimit, formatSize, formatTime } from '../core/format'
import { useToast } from '../core/toast'
import ModalDialog from '../components/ModalDialog.vue'
import ToastHost from '../components/ToastHost.vue'
import UserAvatar from '../components/UserAvatar.vue'

type Tab = 'overview' | 'channels' | 'messages' | 'files' | 'settings'

const toast = useToast()
const activeTab = ref<Tab>('overview')
const loading = ref(true)
const overview = ref<Overview | null>(null)
const channels = ref<AdminChannel[]>([])
const settingsInfo = ref<{ settings: AdminSettings; commentWidgetAvailable: boolean } | null>(null)

const channelDialog = ref<{
  open: boolean
  mode: 'create' | 'edit'
  name: string
  form: {
    type: string
    displayName: string
    description: string
    visibility: string
    postPolicy: string
    composerMode: string
    members: string[]
  }
}>({
  open: false,
  mode: 'create',
  name: '',
  form: {
    type: 'GROUP',
    displayName: '',
    description: '',
    visibility: 'AUTHENTICATED',
    postPolicy: 'AUTHENTICATED',
    composerMode: 'INHERIT',
    members: [],
  },
})

const memberSearch = ref('')
const memberResults = ref<UserSummary[]>([])
const memberSearching = ref(false)

const messageChannel = ref('')
const messagePage = ref<AdminMessagePage | null>(null)
const messagePageNo = ref(1)
const messageLoading = ref(false)

const sendDialog = ref({
  open: false,
  channel: '',
  keyword: '',
  username: '',
  displayName: '',
  content: '',
  results: [] as UserSummary[],
})

const editDialog = ref({ open: false, name: '', content: '' })
const announcementDialog = ref({ open: false, channel: '', content: '' })

const filePage = ref<AdminFilePage | null>(null)
const fileChannel = ref('')
const fileLoading = ref(false)
const filePageNo = ref(1)

const stats = computed(() => overview.value?.storage)
const currentSettings = computed(() => settingsInfo.value?.settings || overview.value?.settings)

async function loadOverview() {
  try {
    overview.value = await adminApi.overview()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function loadChannels() {
  try {
    const response = await adminApi.channels()
    channels.value = response.items || []
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function loadSettings() {
  try {
    settingsInfo.value = await adminApi.settings()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function loadAll() {
  loading.value = true
  await Promise.all([loadOverview(), loadChannels(), loadSettings()])
  loading.value = false
  const first = channels.value[0]
  if (!messageChannel.value && first) {
    messageChannel.value = first.name
  }
}

function openCreateChannel() {
  channelDialog.value = {
    open: true,
    mode: 'create',
    name: '',
    form: {
      type: 'GROUP',
      displayName: '',
      description: '',
      visibility: 'AUTHENTICATED',
      postPolicy: 'AUTHENTICATED',
      composerMode: 'INHERIT',
      members: [],
    },
  }
  memberResults.value = []
  memberSearch.value = ''
}

function openEditChannel(channel: AdminChannel) {
  channelDialog.value = {
    open: true,
    mode: 'edit',
    name: channel.name,
    form: {
      type: channel.type === 'PUBLIC' ? 'GROUP' : channel.type,
      displayName: channel.displayName,
      description: channel.description || '',
      visibility: channel.visibility,
      postPolicy: channel.postPolicy,
      composerMode: channel.composerMode === 'INHERIT' ? 'INHERIT' : channel.composerMode,
      members: [...(channel.members || [])],
    },
  }
  memberResults.value = []
  memberSearch.value = ''
}

async function submitChannel() {
  const form = channelDialog.value.form
  if (!form.displayName.trim()) {
    toast.error('请输入频道名称')
    return
  }
  const body = {
    type: form.type,
    displayName: form.displayName.trim(),
    description: form.description,
    visibility: form.visibility,
    postPolicy: form.postPolicy,
    composerMode: form.composerMode,
    members: form.members,
  }
  try {
    if (channelDialog.value.mode === 'create') {
      await adminApi.createChannel(body)
      toast.success('频道已创建')
    } else {
      await adminApi.updateChannel(channelDialog.value.name, body)
      toast.success('频道已更新')
    }
    channelDialog.value.open = false
    await loadChannels()
    await loadOverview()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function removeChannel(channel: AdminChannel) {
  if (
    !window.confirm(
      `确认解散「${channel.displayName}」？该频道的所有消息与文件都会被删除，此操作不可恢复。`,
    )
  ) {
    return
  }
  try {
    await adminApi.deleteChannel(channel.name)
    toast.success('频道已解散')
    await loadChannels()
    await loadOverview()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function searchMembers() {
  if (!memberSearch.value.trim()) {
    memberResults.value = []
    return
  }
  memberSearching.value = true
  try {
    const response = await adminApi.users(memberSearch.value.trim())
    memberResults.value = response.items || []
  } catch (error) {
    toast.error(errorText(error))
  } finally {
    memberSearching.value = false
  }
}

function addMember(user: UserSummary) {
  if (!channelDialog.value.form.members.includes(user.name)) {
    channelDialog.value.form.members.push(user.name)
  }
  memberSearch.value = ''
  memberResults.value = []
}

function removeMember(name: string) {
  channelDialog.value.form.members = channelDialog.value.form.members.filter(
    (member) => member !== name,
  )
}

async function loadMessages(page = messagePageNo.value) {
  if (!messageChannel.value) {
    return
  }
  messageLoading.value = true
  messagePageNo.value = page
  try {
    messagePage.value = await adminApi.channelMessages(messageChannel.value, page, 20)
  } catch (error) {
    toast.error(errorText(error))
  } finally {
    messageLoading.value = false
  }
}

function selectMessageChannel(name: string) {
  messageChannel.value = name
  messagePageNo.value = 1
  loadMessages(1)
}

async function removeMessage(message: Message) {
  if (!window.confirm('确认删除这条消息吗？')) {
    return
  }
  try {
    await adminApi.deleteMessage(message.name)
    toast.success('消息已删除')
    await loadMessages()
  } catch (error) {
    toast.error(errorText(error))
  }
}

function openEditMessage(message: Message) {
  editDialog.value = { open: true, name: message.name, content: message.content }
}

async function submitEditMessage() {
  if (!editDialog.value.content.trim()) {
    toast.error('消息内容不能为空')
    return
  }
  try {
    await adminApi.editMessage(editDialog.value.name, editDialog.value.content)
    toast.success('消息已修改')
    editDialog.value.open = false
    await loadMessages()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function clearMessages() {
  if (!messageChannel.value) {
    return
  }
  if (!window.confirm('确认清空该频道的全部聊天记录吗？')) {
    return
  }
  try {
    await adminApi.clearMessages(messageChannel.value)
    toast.success('聊天记录已清空')
    await loadMessages(1)
    await loadOverview()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function openSendDialog() {
  if (!messageChannel.value) {
    toast.error('请先选择频道')
    return
  }
  sendDialog.value = {
    open: true,
    channel: messageChannel.value,
    keyword: '',
    username: '',
    displayName: '',
    content: '',
    results: [],
  }
  await searchSendUsers()
}

async function searchSendUsers() {
  try {
    const response = await adminApi.users(sendDialog.value.keyword)
    sendDialog.value.results = response.items || []
  } catch (error) {
    toast.error(errorText(error))
  }
}

function chooseSendUser(user: UserSummary) {
  sendDialog.value.username = user.name
  sendDialog.value.displayName = user.displayName || user.name
}

async function submitSendAsUser() {
  if (!sendDialog.value.username) {
    toast.error('请选择要模拟的用户')
    return
  }
  if (!sendDialog.value.content.trim()) {
    toast.error('请输入消息内容')
    return
  }
  try {
    await adminApi.sendAsUser(
      sendDialog.value.channel,
      sendDialog.value.username,
      sendDialog.value.content,
    )
    toast.success('消息已发送')
    sendDialog.value.open = false
    if (messageChannel.value === sendDialog.value.channel) {
      await loadMessages(1)
    }
  } catch (error) {
    toast.error(errorText(error))
  }
}

function openAnnouncement(channel: AdminChannel) {
  announcementDialog.value = {
    open: true,
    channel: channel.name,
    content: channel.announcement?.content || '',
  }
}

async function submitAnnouncement() {
  try {
    if (!announcementDialog.value.content.trim()) {
      await adminApi.deleteAnnouncement(announcementDialog.value.channel)
      toast.success('公告已清除')
    } else {
      await adminApi.updateAnnouncement(
        announcementDialog.value.channel,
        announcementDialog.value.content,
      )
      toast.success('公告已更新')
    }
    announcementDialog.value.open = false
    await loadChannels()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function loadFiles(page = filePageNo.value) {
  fileLoading.value = true
  filePageNo.value = page
  try {
    filePage.value = await adminApi.files(fileChannel.value || undefined, page, 20)
  } catch (error) {
    toast.error(errorText(error))
  } finally {
    fileLoading.value = false
  }
}

async function removeFile(name: string) {  if (!window.confirm('确认删除该文件？消息中的附件也会被移除。')) {
    return
  }
  try {
    await adminApi.deleteFile(name)
    toast.success('文件已删除')
    await loadFiles()
    await loadOverview()
  } catch (error) {
    toast.error(errorText(error))
  }
}

async function runCleanup() {
  try {
    const result = await adminApi.maintenanceCleanup()
    toast.success(
      `清理完成：过期文件 ${result.expiredFiles} 个，临时文件 ${result.tempFiles} 个，游客会话 ${result.inactiveGuests} 个`,
    )
    await loadOverview()
    await loadFiles(1)
    await loadChannels()
  } catch (error) {
    toast.error(errorText(error))
  }
}

function openPluginSettings() {
  window.location.href = '/console/plugins/halo-chat'
}

function openChat() {
  window.location.href = '/console/chat'
}

function errorText(error: unknown) {
  return error instanceof Error ? error.message : '操作失败，请稍后再试'
}

function messagePreview(message: Message) {
  if (message.contentType === 'HTML') {
    return message.content.replace(/<[^>]+>/g, ' ').slice(0, 80)
  }
  return message.content.slice(0, 80)
}

function sourceLabel(message: Message) {
  if (message.source === 'COMMENT') return '评论'
  if (message.source === 'ADMIN') return '代发'
  return '聊天'
}

function policyLabel(policy: string) {
  if (policy === 'EVERYONE') return '所有人'
  if (policy === 'AUTHENTICATED') return '登录用户'
  if (policy === 'MEMBERS') return '指定成员'
  return policy
}

onMounted(async () => {
  await loadAll()
  if (messageChannel.value) {
    await loadMessages(1)
  }
  await loadFiles(1)
})
</script>

<template>
  <div class="admin-root">
    <header class="admin-header">
      <div>
        <div class="admin-title">聊天管理</div>
        <div class="admin-subtitle">管理频道、聊天记录、文件、公告以及输入方式</div>
      </div>
      <div class="admin-header__actions">
        <button class="hc-btn" @click="loadAll">刷新</button>
        <button class="hc-btn" @click="openChat">返回聊天</button>
        <button class="hc-btn" @click="openPluginSettings">插件设置</button>
      </div>
    </header>

    <nav class="admin-tabs">
      <button :class="{ 'is-active': activeTab === 'overview' }" @click="activeTab = 'overview'">概览</button>
      <button :class="{ 'is-active': activeTab === 'channels' }" @click="activeTab = 'channels'">
        频道管理
      </button>
      <button :class="{ 'is-active': activeTab === 'messages' }" @click="activeTab = 'messages'">
        聊天记录
      </button>
      <button :class="{ 'is-active': activeTab === 'files' }" @click="activeTab = 'files'">文件管理</button>
      <button :class="{ 'is-active': activeTab === 'settings' }" @click="activeTab = 'settings'">设置</button>
    </nav>

    <div class="admin-body">
      <div v-if="loading" class="admin-loading">加载中…</div>

      <template v-else-if="activeTab === 'overview'">
        <div class="admin-cards">
          <div class="admin-card">
            <div class="admin-card__label">频道</div>
            <div class="admin-card__value">{{ overview?.channelCount ?? 0 }}</div>
            <div class="admin-card__hint">
              群组 {{ overview?.groupCount ?? 0 }} · 话题 {{ overview?.topicCount ?? 0 }}
            </div>
          </div>
          <div class="admin-card">
            <div class="admin-card__label">消息</div>
            <div class="admin-card__value">{{ overview?.messageCount ?? 0 }}</div>
            <div class="admin-card__hint">包含评论组件同步的消息</div>
          </div>
          <div class="admin-card">
            <div class="admin-card__label">文件</div>
            <div class="admin-card__value">{{ overview?.fileCount ?? 0 }}</div>
            <div class="admin-card__hint">
              已用 {{ formatSize(stats?.usedBytes) }} / {{ formatLimit(stats?.maxTotalSize) }}
            </div>
          </div>
          <div class="admin-card">
            <div class="admin-card__label">单文件上限</div>
            <div class="admin-card__value" style="font-size: 20px">
              {{ formatLimit(stats?.maxFileSize) }}
            </div>
            <div class="admin-card__hint">
              过期：{{ stats?.expireDays ? `${stats.expireDays} 天` : '永不过期' }}
            </div>
          </div>
        </div>

        <section class="admin-panel">
          <h3>运行状态</h3>
          <dl class="admin-kv">
            <dt>存储目录</dt>
            <dd>{{ stats?.storageRoot || '-' }}</dd>
            <dt>磁盘占用</dt>
            <dd>{{ formatSize(stats?.diskUsedBytes) }}</dd>
            <dt>官方评论组件</dt>
            <dd>
              <span v-if="overview?.commentWidgetAvailable" class="hc-chip hc-chip--public">已安装</span>
              <span v-else class="hc-chip hc-chip--warn">未安装</span>
            </dd>
            <dt>当前输入方式</dt>
            <dd>
              {{ overview?.settings?.composer?.mode === 'HALO_COMMENT' ? 'Halo 评论组件' : '插件自带输入框' }}
            </dd>
            <dt>游客聊天</dt>
            <dd>{{ overview?.settings?.general?.guestEnabled ? '允许' : '关闭' }}</dd>
          </dl>
        </section>
      </template>

      <template v-else-if="activeTab === 'channels'">
        <div class="admin-toolbar">
          <button class="hc-btn is-primary" @click="openCreateChannel">创建群组 / 话题</button>
          <span class="admin-toolbar__hint">公共频道固定置顶，不能解散</span>
        </div>
        <div class="admin-table-wrap">
          <table class="admin-table">
            <thead>
              <tr>
                <th>频道</th>
                <th>类型</th>
                <th>可见范围</th>
                <th>发言权限</th>
                <th>消息数</th>
                <th>公告</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="channel in channels" :key="channel.name">
                <td>
                  <div class="admin-channel">
                    <UserAvatar :name="channel.name" :display-name="channel.displayName" :src="channel.avatar" :size="30" />
                    <div>
                      <div class="admin-channel__name">
                        {{ channel.displayName }}
                        <span v-if="channel.protectedChannel" class="hc-chip hc-chip--public">公共</span>
                      </div>
                      <div class="admin-channel__desc">{{ channel.description || '-' }}</div>
                    </div>
                  </div>
                </td>
                <td>{{ channel.type === 'GROUP' ? '群组' : channel.type === 'TOPIC' ? '话题' : '公共频道' }}</td>
                <td>{{ policyLabel(channel.visibility) }}</td>
                <td>{{ policyLabel(channel.postPolicy) }}</td>
                <td>{{ channel.messageCount }}</td>
                <td>{{ channel.announcement?.content ? '已设置' : '-' }}</td>
                <td>
                  <div class="admin-row-actions">
                    <button class="admin-link" @click="openEditChannel(channel)">编辑</button>
                    <button class="admin-link" @click="selectMessageChannel(channel.name); activeTab = 'messages'">
                      消息
                    </button>
                    <button class="admin-link" @click="openAnnouncement(channel)">公告</button>
                    <button
                      v-if="!channel.protectedChannel"
                      class="admin-link is-danger"
                      @click="removeChannel(channel)"
                    >
                      解散
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else-if="activeTab === 'messages'">
        <div class="admin-toolbar">
          <select v-model="messageChannel" class="admin-select" @change="loadMessages(1)">
            <option value="" disabled>选择频道</option>
            <option v-for="channel in channels" :key="channel.name" :value="channel.name">
              {{ channel.displayName }}
            </option>
          </select>
          <button class="hc-btn is-primary" @click="openSendDialog">以用户身份发送</button>
          <button class="hc-btn is-danger" @click="clearMessages">清空聊天记录</button>
          <span class="admin-toolbar__hint">共 {{ messagePage?.total ?? 0 }} 条</span>
        </div>
        <div class="admin-table-wrap">
          <table class="admin-table">
            <thead>
              <tr>
                <th>时间</th>
                <th>发送者</th>
                <th>来源</th>
                <th>内容</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="message in messagePage?.items || []" :key="message.name">
                <td style="white-space: nowrap">{{ formatDateTime(message.createdAt) }}</td>
                <td>
                  {{ message.senderDisplayName || message.senderName }}
                  <span v-if="message.onBehalf" class="hc-chip hc-chip--warn">代发</span>
                </td>
                <td>{{ sourceLabel(message) }}</td>
                <td class="admin-message-content">
                  {{ messagePreview(message) }}
                  <span v-if="message.attachments?.length" class="hc-chip">附件 {{ message.attachments.length }}</span>
                </td>
                <td>
                  <div class="admin-row-actions">
                    <button class="admin-link" @click="openEditMessage(message)">修改</button>
                    <button class="admin-link is-danger" @click="removeMessage(message)">删除</button>
                  </div>
                </td>
              </tr>
              <tr v-if="!messagePage?.items?.length">
                <td colspan="5" class="admin-empty">暂无消息</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="admin-pagination">
          <button class="hc-btn" :disabled="messagePageNo <= 1" @click="loadMessages(messagePageNo - 1)">
            上一页
          </button>
          <span>第 {{ messagePageNo }} 页</span>
          <button
            class="hc-btn"
            :disabled="!messagePage || messagePage.items.length < messagePage.size"
            @click="loadMessages(messagePageNo + 1)"
          >
            下一页
          </button>
        </div>
      </template>

      <template v-else-if="activeTab === 'files'">
        <div class="admin-toolbar">
          <select v-model="fileChannel" class="admin-select" @change="loadFiles(1)">
            <option value="">全部频道</option>
            <option v-for="channel in channels" :key="channel.name" :value="channel.name">
              {{ channel.displayName }}
            </option>
          </select>
          <button class="hc-btn" @click="loadFiles(filePageNo)">刷新</button>
          <span class="admin-toolbar__hint">
            {{ fileLoading ? '加载中…' : `共 ${filePage?.total ?? 0} 个文件` }}
          </span>
        </div>
        <div class="admin-table-wrap">
          <table class="admin-table">
            <thead>
              <tr>
                <th>文件</th>
                <th>频道</th>
                <th>上传者</th>
                <th>大小</th>
                <th>上传时间</th>
                <th>过期时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="file in filePage?.items || []" :key="file.name">
                <td>
                  <a :href="`/apis/api.chat.halo.run/v1alpha1/files/${file.name}`" target="_blank">
                    {{ file.filename }}
                  </a>
                </td>
                <td>{{ channels.find((channel) => channel.name === file.channel)?.displayName || file.channel }}</td>
                <td>{{ file.uploaderDisplayName || file.uploaderName || '-' }}</td>
                <td>{{ formatSize(file.size) }}</td>
                <td>{{ formatDateTime(file.createdAt) }}</td>
                <td>{{ file.expiresAt ? formatDateTime(file.expiresAt) : '永不过期' }}</td>
                <td>
                  <button class="admin-link is-danger" @click="removeFile(file.name)">删除</button>
                </td>
              </tr>
              <tr v-if="!filePage?.items?.length">
                <td colspan="7" class="admin-empty">暂无文件</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else>
        <section class="admin-panel">
          <h3>全局设置</h3>
          <p class="admin-hint">
            设置项位于插件设置页面，修改后立即生效；将「单文件大小上限」「总存储上限」「文件过期天数」设为 0 表示不限制。
          </p>
          <dl class="admin-kv">
            <dt>游客聊天</dt>
            <dd>{{ currentSettings?.general?.guestEnabled ? '允许' : '关闭' }}</dd>
            <dt>游客上传文件</dt>
            <dd>{{ currentSettings?.general?.guestUploadEnabled ? '允许' : '禁止' }}</dd>
            <dt>单条消息最大长度</dt>
            <dd>{{ currentSettings?.general?.messageMaxLength }} 字符</dd>
            <dt>限流</dt>
            <dd>
              游客 {{ currentSettings?.general?.guestRateLimit || '不限' }} 条/分钟 · 用户
              {{ currentSettings?.general?.userRateLimit || '不限' }} 条/分钟
            </dd>
            <dt>单文件上限</dt>
            <dd>{{ formatLimit(currentSettings?.files ? currentSettings.files.maxFileSizeMb * 1024 * 1024 : 0) }}</dd>
            <dt>总存储上限</dt>
            <dd>{{ formatLimit(currentSettings?.files ? currentSettings.files.maxTotalSizeMb * 1024 * 1024 : 0) }}</dd>
            <dt>文件过期</dt>
            <dd>{{ currentSettings?.files?.expireDays ? `${currentSettings.files.expireDays} 天` : '永不过期' }}</dd>
            <dt>输入方式</dt>
            <dd>{{ currentSettings?.composer?.mode === 'HALO_COMMENT' ? 'Halo 评论组件' : '插件自带输入框' }}</dd>
          </dl>
          <button class="hc-btn is-primary" @click="openPluginSettings">打开插件设置</button>
          <button class="hc-btn" @click="runCleanup">立即清理过期文件</button>
        </section>
      </template>
    </div>

    <ModalDialog
      :open="channelDialog.open"
      :title="channelDialog.mode === 'create' ? '创建群组 / 话题' : '编辑频道'"
      width="560px"
      @close="channelDialog.open = false"
    >
      <div class="hc-field">
        <label>频道名称</label>
        <input v-model="channelDialog.form.displayName" maxlength="60" placeholder="例如：前端交流群" />
      </div>
      <div class="admin-form-row">
        <div class="hc-field">
          <label>类型</label>
          <select v-model="channelDialog.form.type">
            <option value="GROUP">群组</option>
            <option value="TOPIC">话题</option>
          </select>
        </div>
        <div class="hc-field">
          <label>输入方式</label>
          <select v-model="channelDialog.form.composerMode">
            <option value="INHERIT">跟随全局</option>
            <option value="BUILTIN">插件自带输入框</option>
            <option value="HALO_COMMENT">Halo 评论组件</option>
          </select>
        </div>
      </div>
      <div class="admin-form-row">
        <div class="hc-field">
          <label>可见范围</label>
          <select v-model="channelDialog.form.visibility">
            <option value="EVERYONE">所有人（含游客）</option>
            <option value="AUTHENTICATED">登录用户</option>
            <option value="MEMBERS">指定成员</option>
          </select>
        </div>
        <div class="hc-field">
          <label>发言权限</label>
          <select v-model="channelDialog.form.postPolicy">
            <option value="EVERYONE">所有人（含游客）</option>
            <option value="AUTHENTICATED">登录用户</option>
            <option value="MEMBERS">指定成员</option>
          </select>
        </div>
      </div>
      <div class="hc-field">
        <label>频道简介</label>
        <textarea v-model="channelDialog.form.description" maxlength="500" rows="2" />
      </div>
      <div
        v-if="
          channelDialog.form.visibility === 'MEMBERS' || channelDialog.form.postPolicy === 'MEMBERS'
        "
        class="hc-field"
      >
        <label>指定成员</label>
        <div class="admin-member-chips">
          <span v-for="member in channelDialog.form.members" :key="member" class="admin-member-chip">
            {{ member }}
            <button @click="removeMember(member)">×</button>
          </span>
          <span v-if="!channelDialog.form.members.length" class="admin-hint">尚未添加成员</span>
        </div>
        <div style="display: flex; gap: 6px">
          <input v-model="memberSearch" placeholder="搜索用户名 / 昵称" @keydown.enter.prevent="searchMembers" />
          <button class="hc-btn" @click="searchMembers">
            {{ memberSearching ? '搜索中…' : '搜索' }}
          </button>
        </div>
        <div v-if="memberResults.length" class="admin-user-results">
          <button
            v-for="user in memberResults"
            :key="user.name"
            class="admin-user-result"
            @click="addMember(user)"
          >
            <UserAvatar :name="user.name" :display-name="user.displayName" :src="user.avatar" :size="24" />
            <span>{{ user.displayName || user.name }}</span>
            <span class="admin-hint">{{ user.name }}</span>
          </button>
        </div>
      </div>
      <div class="hc-modal__footer">
        <button class="hc-btn" @click="channelDialog.open = false">取消</button>
        <button class="hc-btn is-primary" @click="submitChannel">保存</button>
      </div>
    </ModalDialog>

    <ModalDialog :open="sendDialog.open" title="以用户身份发送消息" width="560px" @close="sendDialog.open = false">
      <div class="hc-field">
        <label>选择用户</label>
        <div style="display: flex; gap: 6px">
          <input v-model="sendDialog.keyword" placeholder="搜索用户名 / 昵称" @keydown.enter.prevent="searchSendUsers" />
          <button class="hc-btn" @click="searchSendUsers">搜索</button>
        </div>
        <div v-if="sendDialog.results.length" class="admin-user-results">
          <button
            v-for="user in sendDialog.results"
            :key="user.name"
            class="admin-user-result"
            :class="{ 'is-selected': sendDialog.username === user.name }"
            @click="chooseSendUser(user)"
          >
            <UserAvatar :name="user.name" :display-name="user.displayName" :src="user.avatar" :size="24" />
            <span>{{ user.displayName || user.name }}</span>
            <span class="admin-hint">{{ user.name }}</span>
          </button>
        </div>
        <span class="hc-field__help">
          当前选择：{{ sendDialog.username ? `${sendDialog.displayName} (${sendDialog.username})` : '未选择' }}
        </span>
      </div>
      <div class="hc-field">
        <label>消息内容</label>
        <textarea v-model="sendDialog.content" rows="4" maxlength="2000" />
      </div>
      <div class="hc-modal__footer">
        <button class="hc-btn" @click="sendDialog.open = false">取消</button>
        <button class="hc-btn is-primary" @click="submitSendAsUser">发送</button>
      </div>
    </ModalDialog>

    <ModalDialog :open="editDialog.open" title="修改消息" width="560px" @close="editDialog.open = false">
      <div class="hc-field">
        <label>消息内容</label>
        <textarea v-model="editDialog.content" rows="4" maxlength="2000" />
      </div>
      <div class="hc-modal__footer">
        <button class="hc-btn" @click="editDialog.open = false">取消</button>
        <button class="hc-btn is-primary" @click="submitEditMessage">保存</button>
      </div>
    </ModalDialog>

    <ModalDialog
      :open="announcementDialog.open"
      title="设置频道公告"
      width="520px"
      @close="announcementDialog.open = false"
    >
      <div class="hc-field">
        <label>公告内容（留空则清除公告）</label>
        <textarea v-model="announcementDialog.content" rows="4" maxlength="1000" />
      </div>
      <div class="hc-modal__footer">
        <button class="hc-btn" @click="announcementDialog.open = false">取消</button>
        <button class="hc-btn is-primary" @click="submitAnnouncement">保存</button>
      </div>
    </ModalDialog>

    <ToastHost />
  </div>
</template>

<style scoped>
.admin-root {
  height: 100dvh;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #f6f8fa;
  color: #1f2937;
  font-size: 13px;
}
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  flex: 0 0 auto;
}
.admin-title {
  font-size: 17px;
  font-weight: 600;
}
.admin-subtitle {
  font-size: 11.5px;
  color: #6b7280;
  margin-top: 3px;
}
.admin-header__actions {
  display: flex;
  gap: 8px;
}
.admin-tabs {
  display: flex;
  gap: 4px;
  padding: 0 18px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  flex: 0 0 auto;
}
.admin-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 11px 14px;
  font-size: 13px;
  color: #4b5563;
  cursor: pointer;
}
.admin-tabs button.is-active {
  color: #0f9d58;
  border-bottom-color: #0f9d58;
  font-weight: 600;
}
.admin-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 22px 28px;
}
.admin-loading {
  padding: 40px;
  text-align: center;
  color: #6b7280;
}
.admin-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.admin-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 14px 16px;
}
.admin-card__label {
  font-size: 12px;
  color: #6b7280;
}
.admin-card__value {
  font-size: 26px;
  font-weight: 700;
  margin: 6px 0 2px;
}
.admin-card__hint {
  font-size: 11.5px;
  color: #9ca3af;
}
.admin-panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;
}
.admin-panel h3 {
  margin: 0 0 10px;
  font-size: 14px;
}
.admin-kv {
  display: grid;
  grid-template-columns: 140px 1fr;
  gap: 8px 12px;
  margin: 0;
  font-size: 12.5px;
}
.admin-kv dt {
  color: #6b7280;
}
.admin-kv dd {
  margin: 0;
  word-break: break-all;
}
.admin-hint {
  font-size: 11.5px;
  color: #6b7280;
  line-height: 1.6;
}
.admin-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.admin-toolbar__hint {
  font-size: 11.5px;
  color: #6b7280;
}
.admin-select {
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 12.5px;
  background: #fff;
}
.admin-table-wrap {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  overflow: auto;
}
.admin-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}
.admin-table th,
.admin-table td {
  text-align: left;
  padding: 10px 12px;
  border-bottom: 1px solid #f1f5f9;
  vertical-align: middle;
}
.admin-table th {
  background: #f8fafc;
  color: #6b7280;
  font-weight: 500;
  white-space: nowrap;
}
.admin-table tr:last-child td {
  border-bottom: 0;
}
.admin-table a {
  color: #0f9d58;
  text-decoration: none;
}
.admin-channel {
  display: flex;
  align-items: center;
  gap: 8px;
}
.admin-channel__name {
  font-weight: 500;
  display: flex;
  gap: 6px;
  align-items: center;
}
.admin-channel__desc {
  color: #9ca3af;
  font-size: 11px;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.admin-row-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.admin-link {
  border: 0;
  background: transparent;
  color: #0f9d58;
  cursor: pointer;
  padding: 0;
  font-size: 12px;
}
.admin-link.is-danger {
  color: #dc2626;
}
.admin-message-content {
  max-width: 420px;
  color: #4b5563;
}
.admin-empty {
  text-align: center;
  color: #9ca3af;
  padding: 28px !important;
}
.admin-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 12px;
  font-size: 12px;
  color: #6b7280;
}
.admin-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.admin-member-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 24px;
}
.admin-member-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #eff6f2;
  color: #0b7a43;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11.5px;
}
.admin-member-chip button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0;
}
.admin-user-results {
  margin-top: 6px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  max-height: 180px;
  overflow-y: auto;
}
.admin-user-result {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: 0;
  border-bottom: 1px solid #f1f5f9;
  background: #fff;
  padding: 7px 10px;
  cursor: pointer;
  text-align: left;
  font-size: 12.5px;
}
.admin-user-result:hover,
.admin-user-result.is-selected {
  background: #f0fdf4;
}
@media (max-width: 720px) {
  .admin-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .admin-form-row {
    grid-template-columns: 1fr;
  }
}
</style>
