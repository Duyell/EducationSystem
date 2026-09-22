<script setup lang="ts">
import { Delete, Plus } from '@element-plus/icons-vue'
import type { AiConversation } from '@/types/models'

/**
 * 会话侧栏。
 *
 * 单一职责：把"我的会话列表 + 新建/删除入口"画出来，并把用户动作抛给父组件。
 * 它**不发请求、不判断归属、不生成标题**——那些在 `useConversations` 与服务端；
 * 组件只负责渲染与交互（props down / events up）。
 */
const props = defineProps<{
  conversations: AiConversation[]
  /** 当前选中的会话 id；空串表示"新对话" */
  activeId: string
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'create'): void
  (e: 'remove', conversation: AiConversation): void
}>()

/** 相对时间：列表里"3 分钟前"比完整时间戳更好扫读；只做展示，不参与逻辑 */
const relativeTime = (value: string): string => {
  if (!value) return ''
  const time = new Date(value.replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return value
  const diffMinutes = Math.floor((Date.now() - time) / 60000)
  if (diffMinutes < 1) return '刚刚'
  if (diffMinutes < 60) return `${diffMinutes} 分钟前`
  const diffHours = Math.floor(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours} 小时前`
  const diffDays = Math.floor(diffHours / 24)
  if (diffDays < 30) return `${diffDays} 天前`
  return value.slice(0, 10)
}
</script>

<template>
  <aside class="conversation-sidebar">
    <div class="sidebar-head">
      <span class="sidebar-title">我的会话</span>
      <el-button
        type="primary"
        size="small"
        :icon="Plus"
        @click="emit('create')"
      >
        新对话
      </el-button>
    </div>

    <div
      v-loading="props.loading"
      class="sidebar-list"
    >
      <!-- 空态：给出下一步动作，而不是只留一片空白 -->
      <p
        v-if="!props.loading && props.conversations.length === 0"
        class="sidebar-empty"
      >
        还没有会话，直接提问就会自动创建
      </p>

      <div
        v-for="item in props.conversations"
        :key="item.id"
        class="conversation-item"
        :class="{ active: item.id === props.activeId }"
        @click="emit('select', item.id)"
      >
        <div class="item-main">
          <div class="item-title">{{ item.title || '新对话' }}</div>
          <div class="item-meta">
            {{ item.messageCount }} 条 · {{ relativeTime(item.updateTime) }}
          </div>
        </div>
        <el-button
          class="item-delete"
          size="small"
          text
          :icon="Delete"
          title="删除该会话"
          @click.stop="emit('remove', item)"
        />
      </div>
    </div>
  </aside>
</template>

<style scoped>
.conversation-sidebar {
  display: flex;
  flex-direction: column;
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid #eee;
  background: #fafafa;
}

.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 12px 10px;
  border-bottom: 1px solid #eee;
}
.sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.sidebar-empty {
  margin: 16px 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #999;
}

.conversation-item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 8px 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
}
.conversation-item:hover {
  background: #eef2ff;
}
.conversation-item.active {
  background: #e6efff;
}
.item-main {
  flex: 1;
  min-width: 0;
}
.item-title {
  font-size: 13px;
  color: #333;
  line-height: 1.4;
  /* 标题可能很长（由首条消息生成），截断而不是撑破侧栏 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conversation-item.active .item-title {
  color: #165dff;
  font-weight: 600;
}
.item-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #999;
}
.item-delete {
  padding: 2px 4px;
  color: #bbb;
}
.item-delete:hover {
  color: #ff4d4f;
}
</style>
