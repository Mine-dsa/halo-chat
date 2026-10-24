<script setup lang="ts">
import { useToast } from '../core/toast'

const toast = useToast()
</script>

<template>
  <Teleport to="body">
    <div class="hc-toasts">
      <div
        v-for="item in toast.items"
        :key="item.id"
        class="hc-toast"
        :class="{ 'is-error': item.type === 'error', 'is-success': item.type === 'success' }"
      >
        {{ item.message }}
      </div>
    </div>
  </Teleport>
</template>
