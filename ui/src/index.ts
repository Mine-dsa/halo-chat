import { definePlugin } from '@halo-dev/ui-shared'
import { IconMessage, IconSettings } from '@halo-dev/components'
import { markRaw } from 'vue'
import ChatView from './views/ChatView.vue'
import ChatAdminView from './views/ChatAdminView.vue'
import './styles/chat.css'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/chat',
        name: 'Chat',
        component: ChatView,
        meta: {
          title: '聊天',
          searchable: true,
          hideFooter: true,
          menu: {
            name: '聊天',
            group: '聊天',
            icon: markRaw(IconMessage),
            priority: 0,
            mobile: true,
          },
        },
      },
    },
    {
      parentName: 'Root',
      route: {
        path: '/chat/admin',
        name: 'ChatAdmin',
        component: ChatAdminView,
        meta: {
          title: '聊天管理',
          searchable: true,
          hideFooter: true,
          permissions: ['plugin:chat:manage'],
          menu: {
            name: '聊天管理',
            group: '聊天',
            icon: markRaw(IconSettings),
            priority: 10,
            mobile: true,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
