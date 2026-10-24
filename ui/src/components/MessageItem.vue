<script setup lang="ts">
import { computed } from 'vue'
import type { Me, Message } from '../core/types'
import { formatSize, formatTime } from '../core/format'
import { sanitizeHtml } from '../core/sanitize'
import UserAvatar from './UserAvatar.vue'

const props = defineProps<{
  message: Message
  me: Me | null
}>()

const emit = defineEmits<{
  delete: [message: Message]
  preview: [url: string]
}>()

const own = computed(() => {
  if (!props.me || props.me.type === 'ANONYMOUS') {
    return false
  }
  if (props.message.senderType !== 'USER' && props.message.senderType !== 'GUEST') {
    return false
  }
  return props.me.name === props.message.senderName
})

const canDelete = computed(
  () =>
    own.value &&
    props.me?.type === 'USER' &&
    props.message.source !== 'COMMENT' &&
    props.message.senderType !== 'SYSTEM',
)

const htmlContent = computed(() =>
  props.message.contentType === 'HTML' ? sanitizeHtml(props.message.content) : '',
)

const attachments = computed(() => props.message.attachments || [])
</script>

<template>
  <div class="hc-message" :class="{ 'is-own': own }">
    <UserAvatar
      :name="message.senderName"
      :display-name="message.senderDisplayName"
      :src="message.senderAvatar"
      :size="36"
    />
    <div class="hc-message__body">
      <div class="hc-message__head">
        <span class="hc-message__name">
          {{ message.senderDisplayName || message.senderName || '匿名' }}
        </span>
        <span>{{ formatTime(message.createdAt) }}</span>
        <span v-if="message.editedAt" style="color: #9ca3af">已编辑</span>
      </div>
      <div
        v-if="message.content"
        class="hc-message__bubble"
        :class="{ 'is-html': message.contentType === 'HTML' }"
      >
        <template v-if="message.contentType === 'HTML'">
          <div v-html="htmlContent" />
        </template>
        <template v-else>{{ message.content }}</template>
      </div>
      <div v-if="attachments.length" class="hc-attachments">
        <template v-for="attachment in attachments" :key="attachment.fileRef">
          <img
            v-if="attachment.image && !attachment.expired"
            class="hc-attachment__image"
            :src="attachment.url"
            :alt="attachment.name"
            loading="lazy"
            @click="emit('preview', attachment.url)"
          />
          <a
            v-else-if="!attachment.expired"
            class="hc-attachment"
            :href="attachment.url"
            target="_blank"
            rel="noopener"
          >
            <span class="hc-attachment__icon">📄</span>
            <span class="hc-attachment__body">
              <span class="hc-attachment__name">{{ attachment.name }}</span>
              <span class="hc-attachment__meta">{{ formatSize(attachment.size) }}</span>
            </span>
          </a>
          <div v-else class="hc-attachment">
            <span class="hc-attachment__icon">🗑️</span>
            <span class="hc-attachment__body">
              <span class="hc-attachment__name">{{ attachment.name }}</span>
              <span class="hc-attachment__meta hc-attachment__expired">文件已过期</span>
            </span>
          </div>
        </template>
      </div>
      <div class="hc-message__foot">
        <span class="hc-message__badges">
          <span v-if="message.source === 'COMMENT'" class="hc-chip">来自评论</span>
          <span v-if="message.onBehalf" class="hc-chip hc-chip--warn">管理员代发</span>
        </span>
        <button v-if="canDelete" class="hc-message__delete" @click="emit('delete', message)">
          删除
        </button>
      </div>
    </div>
  </div>
</template>
