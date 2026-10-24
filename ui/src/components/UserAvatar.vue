<script setup lang="ts">
import { computed } from 'vue'
import { colorOf, initialOf } from '../core/format'

const props = withDefaults(
  defineProps<{
    name?: string | null
    displayName?: string | null
    src?: string | null
    size?: number
  }>(),
  { size: 38, name: '', displayName: '', src: null },
)

const letter = computed(() => initialOf(props.displayName || props.name))
const color = computed(() => colorOf(props.name || props.displayName))
const fontSize = computed(() => `${Math.max(12, Math.round(props.size * 0.4))}px`)
</script>

<template>
  <span
    class="hc-avatar"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      background: src ? 'transparent' : color,
      fontSize,
    }"
    :title="displayName || name || ''"
  >
    <img v-if="src" :src="src" alt="" loading="lazy" />
    <template v-else>{{ letter }}</template>
  </span>
</template>
