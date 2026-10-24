<script setup lang="ts">
import ChatShell from '../components/ChatShell.vue'
</script>

<template>
  <div class="chat-route">
    <ChatShell mode="console" />
  </div>
</template>

<style scoped>
.chat-route {
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
}
</style>
