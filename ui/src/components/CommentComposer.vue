<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  channel: string
}>()

const emit = defineEmits<{
  submitted: []
  fallback: []
}>()

const container = ref<HTMLElement | null>(null)
const status = ref<'loading' | 'ready' | 'failed'>('loading')
let widgetElement: (HTMLElement & { shadowRoot: ShadowRoot | null }) | null = null

const WIDGET_URL = '/plugins/PluginCommentWidget/assets/static/comment-widget.js'

function onCreated() {
  emit('submitted')
}

function cleanup() {
  window.removeEventListener('halo:comment:created', onCreated)
  if (widgetElement) {
    widgetElement.remove()
    widgetElement = null
  }
  if (container.value) {
    container.value.innerHTML = ''
  }
}

function hideCommentList(element: HTMLElement & { shadowRoot: ShadowRoot | null }) {
  const root = element.shadowRoot
  if (!root || root.querySelector('#halo-chat-hide-list')) {
    return Boolean(root)
  }
  const style = document.createElement('style')
  style.id = 'halo-chat-hide-list'
  style.textContent = `
    comment-list, comment-detail, comment-pagination { display: none !important; }
    .comment-widget { padding: 0 !important; }
  `
  root.appendChild(style)
  return true
}

async function mountWidget() {
  cleanup()
  status.value = 'loading'
  try {
    // Loaded at runtime from the official comment widget plugin, never bundled.
    const url = WIDGET_URL
    await import(/* @vite-ignore */ url)
    if (!container.value) {
      return
    }
    const element = document.createElement('comment-widget') as HTMLElement & {
      shadowRoot: ShadowRoot | null
      group: string
      kind: string
      version: string
      name: string
    }
    element.group = 'chat.halo.run'
    element.kind = 'ChatChannel'
    element.version = 'v1alpha1'
    element.name = props.channel
    container.value.appendChild(element)
    widgetElement = element
    container.value.style.setProperty('--halo-cw-primary-1-color', '#0f9d58')
    container.value.style.setProperty('--halo-cw-primary-2-color', '#34b475')
    container.value.style.setProperty('--halo-cw-primary-3-color', '#b7e4c7')
    container.value.style.setProperty('--halo-cw-primary-contrast-color', '#ffffff')
    container.value.style.setProperty('--halo-cw-base-rounded', '10px')
    let attempts = 0
    const timer = window.setInterval(() => {
      attempts++
      const found = widgetElement ? hideCommentList(widgetElement) : false
      if (found || attempts > 30) {
        window.clearInterval(timer)
      }
    }, 150)
    window.addEventListener('halo:comment:created', onCreated)
    status.value = 'ready'
  } catch (error) {
    console.warn('Halo comment widget is not available, falling back to built-in composer', error)
    status.value = 'failed'
    emit('fallback')
  }
}

onMounted(mountWidget)
watch(() => props.channel, mountWidget)
onBeforeUnmount(cleanup)
</script>

<template>
  <div class="hc-comment-composer">
    <div class="hc-comment-composer__head">
      <span>使用 Halo 评论组件输入 · 消息会同步到聊天记录</span>
      <button class="hc-comment-composer__toggle" @click="emit('fallback')">
        改用聊天输入框
      </button>
    </div>
    <div v-if="status === 'loading'" class="hc-comment-composer__loading">正在加载评论组件…</div>
    <div ref="container" />
  </div>
</template>
