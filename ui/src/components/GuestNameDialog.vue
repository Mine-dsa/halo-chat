<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    initial?: string
    maxLength?: number
    busy?: boolean
    title?: string
  }>(),
  { initial: '', maxLength: 20, busy: false, title: '输入昵称' },
)

const emit = defineEmits<{
  close: []
  submit: [nickname: string]
}>()

const nickname = ref(props.initial)

watch(
  () => props.open,
  (open) => {
    if (open) {
      nickname.value = props.initial || localStorage.getItem('halo-chat-nickname') || ''
    }
  },
)

function submit() {
  const value = nickname.value.trim()
  if (!value) {
    return
  }
  localStorage.setItem('halo-chat-nickname', value)
  emit('submit', value)
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="hc-modal-overlay" @click.self="emit('close')">
      <div class="hc-modal" style="width: 420px">
        <div class="hc-modal__title">{{ title }}</div>
        <p style="font-size: 12.5px; color: #6b7280; margin: 0 0 14px; line-height: 1.6">
          无需注册即可参与公共频道聊天。昵称仅用于本次聊天展示，登录后会自动使用 Halo 账号昵称与头像。
        </p>
        <div class="hc-field">
          <label>你的昵称</label>
          <input
            v-model="nickname"
            :maxlength="maxLength"
            type="text"
            placeholder="例如：小明"
            autofocus
            @keydown.enter.prevent="submit"
          />
          <span class="hc-field__help">最多 {{ maxLength }} 个字符，不能使用已注册用户的用户名。</span>
        </div>
        <div class="hc-modal__footer">
          <button class="hc-btn" :disabled="busy" @click="emit('close')">取消</button>
          <button class="hc-btn is-primary" :disabled="busy || !nickname.trim()" @click="submit">
            {{ busy ? '处理中…' : '开始聊天' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
