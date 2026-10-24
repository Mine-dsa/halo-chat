<script setup lang="ts">
import { nextTick, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    disabled?: boolean
    uploading?: boolean
    canUpload?: boolean
    maxLength?: number
    placeholder?: string
  }>(),
  {
    disabled: false,
    uploading: false,
    canUpload: true,
    maxLength: 2000,
    placeholder: '输入消息，Enter 发送，Shift + Enter 换行',
  },
)

const emit = defineEmits<{
  send: [content: string]
  upload: [file: File]
}>()

const content = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const showEmoji = ref(false)

const EMOJIS = [
  '😀','😄','😁','😆','😅','😂','🤣','😊','😇','🙂','🙃','😉','😌','😍','🥰','😘','😗','😙','😚','😋',
  '😛','😝','😜','🤪','🤨','🧐','🤓','😎','🥸','🤩','🥳','😏','😒','😞','😔','😟','😕','🙁','☹️','😣',
  '😖','😫','😩','🥺','😢','😭','😤','😠','😡','🤬','🤯','😳','🥵','🥶','😱','😨','😰','😥','😓','🤗',
  '🤔','🤭','🤫','🤥','😶','😐','😑','😬','🙄','😯','😦','😧','😮','😲','🥱','😴','🤤','😪','😵','🤐',
  '🥴','🤢','🤮','🤧','😷','🤒','🤕','🤑','🤠','😈','👿','👹','👺','🤡','💩','👻','💀','☠️','👽','👾',
  '👍','👎','👌','✌️','🤞','🤟','🤘','🤙','👈','👉','👆','👇','☝️','✋','🤚','🖐️','🖖','👋','🤝','🙏',
  '❤️','🧡','💛','💚','💙','💜','🖤','🤍','🤎','💔','❣️','💕','💞','💓','💗','💖','💘','💝','🎉','🎊',
  '🔥','✨','⭐','🌟','💯','✅','❌','⚠️','❓','❗','💡','📌','🎵','☕','🍺','🍉','🍎','🌹','🌸','🌞',
]

function send() {
  if (props.disabled || props.uploading) {
    return
  }
  const value = content.value.trim()
  if (!value) {
    return
  }
  emit('send', value)
  content.value = ''
  showEmoji.value = false
  nextTick(() => autoResize())
}

function autoResize() {
  const element = textarea.value
  if (!element) {
    return
  }
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 148)}px`
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    send()
  }
}

function appendEmoji(emoji: string) {
  content.value += emoji
  showEmoji.value = false
  nextTick(() => {
    autoResize()
    textarea.value?.focus()
  })
}

function pickFile(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    emit('upload', file)
  }
  target.value = ''
}
</script>

<template>
  <div class="hc-composer">
    <div class="hc-composer__toolbar">
      <div class="hc-composer__wrap">
        <button class="hc-icon-btn" title="表情" :disabled="disabled" @click="showEmoji = !showEmoji">
          😊
        </button>
        <div v-if="showEmoji" class="hc-emoji-panel">
          <button v-for="emoji in EMOJIS" :key="emoji" type="button" @click="appendEmoji(emoji)">
            {{ emoji }}
          </button>
        </div>
      </div>
      <button
        class="hc-icon-btn"
        title="发送文件"
        :disabled="disabled || uploading || !canUpload"
        @click="fileInput?.click()"
      >
        📎
      </button>
      <input ref="fileInput" type="file" hidden @change="pickFile" />
      <span v-if="uploading" style="font-size: 11.5px; color: #6b7280">文件上传中…</span>
    </div>
    <div class="hc-composer__box">
      <textarea
        ref="textarea"
        v-model="content"
        :maxlength="maxLength"
        :disabled="disabled || uploading"
        :placeholder="placeholder"
        rows="1"
        @input="autoResize"
        @keydown="onKeydown"
      />
      <button class="hc-send-btn" :disabled="disabled || uploading || !content.trim()" @click="send">
        发送
      </button>
    </div>
    <div class="hc-composer__meta">
      <span>{{ content.length }} / {{ maxLength }}</span>
      <span>Enter 发送 · Shift + Enter 换行</span>
    </div>
  </div>
</template>
