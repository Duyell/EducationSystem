<script setup lang="ts">
/**
 * 输入区。
 *
 * 单一职责：受控输入框 + 发送按钮 + 快捷键提示。
 * 用标准 `v-model` 契约（props `modelValue` / emit `update:modelValue`）：
 * 这是真正的双向绑定语义，而不是"父组件传值、子组件偷偷改"。
 * 回车发送的判定放在父组件（`send()`），因为它要知道"当前是否在流式输出"。
 */
const props = defineProps<{
  modelValue: string
  /** 流式输出中：禁用输入，避免并发发送 */
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'send'): void
}>()

/**
 * Enter 发送、Shift+Enter 换行（与下方提示一致）。
 * 只写 `.enter.prevent` 会把换行一并挡掉，于是提示里的 Shift+Enter 其实失效——这里显式区分。
 */
const onEnter = (event: KeyboardEvent) => {
  if (event.shiftKey) return
  event.preventDefault()
  emit('send')
}
</script>

<template>
  <div class="chat-input-area">
    <el-input
      :model-value="props.modelValue"
      type="textarea"
      :rows="2"
      :disabled="props.loading"
      :autosize="{ minRows: 2, maxRows: 4 }"
      placeholder="输入你的问题，按 Enter 发送..."
      @update:model-value="(value: string) => emit('update:modelValue', value)"
      @keydown.enter="onEnter"
    />
    <div class="input-actions">
      <span class="hint">Enter 发送，Shift+Enter 换行</span>
      <el-button
        type="primary"
        :disabled="!props.modelValue.trim() || props.loading"
        :loading="props.loading"
        @click="emit('send')"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-input-area {
  padding: 12px 20px 16px;
  border-top: 1px solid #eee;
  background: #fafafa;
}
.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
.hint {
  font-size: 12px;
  color: #999;
}
</style>
