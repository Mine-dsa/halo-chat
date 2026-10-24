import { createApp, h } from 'vue'
import ChatShell from '../components/ChatShell.vue'
import '../styles/chat.css'

createApp({
  render: () => h(ChatShell, { mode: 'web' }),
}).mount('#app')
