<script setup lang="ts">
import { ref, watch } from 'vue'
import { adminApi } from '../core/api'
import type { AdminChannel, UserSummary } from '../core/types'
import { useToast } from '../core/toast'
import ModalDialog from './ModalDialog.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  close: []
  created: [channel: AdminChannel]
}>()

const toast = useToast()
const saving = ref(false)
const memberKeyword = ref('')
const memberSearching = ref(false)
const memberResults = ref<UserSummary[]>([])
const members = ref<UserSummary[]>([])
const form = ref({
  type: 'GROUP',
  displayName: '',
  description: '',
  visibility: 'AUTHENTICATED',
  postPolicy: 'AUTHENTICATED',
  composerMode: 'INHERIT',
})

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    form.value = {
      type: 'GROUP',
      displayName: '',
      description: '',
      visibility: 'AUTHENTICATED',
      postPolicy: 'AUTHENTICATED',
      composerMode: 'INHERIT',
    }
    members.value = []
    memberKeyword.value = ''
    memberResults.value = []
  },
)

async function searchMembers() {
  const keyword = memberKeyword.value.trim()
  if (!keyword) {
    return
  }
  memberSearching.value = true
  try {
    const response = await adminApi.users(keyword)
    memberResults.value = (response.items || []).filter(
      (user) => !members.value.some((member) => member.name === user.name),
    )
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '搜索用户失败')
  } finally {
    memberSearching.value = false
  }
}

function addMember(user: UserSummary) {
  members.value.push(user)
  memberResults.value = memberResults.value.filter((item) => item.name !== user.name)
}

function removeMember(name: string) {
  members.value = members.value.filter((member) => member.name !== name)
}

async function submit() {
  if (!form.value.displayName.trim()) {
    toast.error('请输入频道名称')
    return
  }
  saving.value = true
  try {
    const channel = await adminApi.createChannel({
      type: form.value.type,
      displayName: form.value.displayName.trim(),
      description: form.value.description,
      visibility: form.value.visibility,
      postPolicy: form.value.postPolicy,
      composerMode: form.value.composerMode,
      members: members.value.map((member) => member.name),
    })
    toast.success(`「${channel.displayName}」已创建`)
    emit('created', channel)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '创建频道失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <ModalDialog :open="open" title="新建群组 / 话题" width="560px" @close="emit('close')">
    <div class="hc-field">
      <label>频道名称 *</label>
      <input v-model="form.displayName" maxlength="60" placeholder="例如：前端交流群、周末活动话题" />
    </div>
    <div class="admin-form-row">
      <div class="hc-field">
        <label>类型</label>
        <select v-model="form.type">
          <option value="GROUP">群组</option>
          <option value="TOPIC">话题</option>
        </select>
      </div>
      <div class="hc-field">
        <label>消息输入方式</label>
        <select v-model="form.composerMode">
          <option value="INHERIT">跟随插件设置</option>
          <option value="BUILTIN">插件自带输入框</option>
          <option value="HALO_COMMENT">Halo 评论组件</option>
        </select>
      </div>
    </div>
    <div class="admin-form-row">
      <div class="hc-field">
        <label>谁可以查看</label>
        <select v-model="form.visibility">
          <option value="EVERYONE">所有人（含游客）</option>
          <option value="AUTHENTICATED">仅登录用户</option>
          <option value="MEMBERS">仅指定成员</option>
        </select>
      </div>
      <div class="hc-field">
        <label>谁可以发言</label>
        <select v-model="form.postPolicy">
          <option value="EVERYONE">所有人（含游客）</option>
          <option value="AUTHENTICATED">仅登录用户</option>
          <option value="MEMBERS">仅指定成员</option>
        </select>
      </div>
    </div>
    <div class="hc-field">
      <label>频道简介</label>
      <textarea v-model="form.description" rows="2" maxlength="500" placeholder="可选，用于频道列表展示" />
    </div>
    <div v-if="form.visibility === 'MEMBERS' || form.postPolicy === 'MEMBERS'" class="hc-field">
      <label>指定成员</label>
      <div class="admin-member-chips">
        <span v-for="member in members" :key="member.name" class="admin-member-chip">
          {{ member.displayName || member.name }}
          <button type="button" @click="removeMember(member.name)">×</button>
        </span>
        <span v-if="!members.length" class="hint">尚未添加成员</span>
      </div>
      <div style="display: flex; gap: 6px; margin-top: 6px">
        <input
          v-model="memberKeyword"
          placeholder="搜索用户名 / 昵称"
          @keydown.enter.prevent="searchMembers"
        />
        <button type="button" class="hc-btn" @click="searchMembers">
          {{ memberSearching ? '搜索中…' : '搜索' }}
        </button>
      </div>
      <div v-if="memberResults.length" class="admin-user-results">
        <button
          v-for="user in memberResults"
          :key="user.name"
          type="button"
          class="admin-user-result"
          @click="addMember(user)"
        >
          <UserAvatar :name="user.name" :display-name="user.displayName" :src="user.avatar" :size="24" />
          <span>{{ user.displayName || user.name }}</span>
          <span class="hint">{{ user.name }}</span>
        </button>
      </div>
    </div>
    <div class="hc-modal__footer">
      <button type="button" class="hc-btn" :disabled="saving" @click="emit('close')">取消</button>
      <button type="button" class="hc-btn is-primary" :disabled="saving" @click="submit">
        {{ saving ? '创建中…' : '创建' }}
      </button>
    </div>
  </ModalDialog>
</template>

<style scoped>
.admin-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.admin-member-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 22px;
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
  border: 1px solid var(--hc-border);
  border-radius: 8px;
  max-height: 170px;
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
.admin-user-result:hover {
  background: #f0fdf4;
}
.hint {
  font-size: 11px;
  color: var(--hc-text-muted);
}
@media (max-width: 720px) {
  .admin-form-row {
    grid-template-columns: 1fr;
  }
}
</style>
