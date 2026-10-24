<script setup lang="ts">
withDefaults(
  defineProps<{
    open: boolean
    title?: string
    width?: string
  }>(),
  { title: '', width: '480px' },
)

const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="hc-modal-overlay" @click.self="emit('close')">
      <div class="hc-modal" :style="{ width }">
        <div class="hc-modal__title">{{ title }}</div>
        <slot />
        <div v-if="$slots.footer" class="hc-modal__footer">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>
