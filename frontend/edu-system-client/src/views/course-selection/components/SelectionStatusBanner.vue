<script setup lang="ts">
import { computed } from 'vue'
import type { SelectionStatus } from '@/types/models'

/**
 * 选课状态横幅。
 *
 * 单一职责：把服务端给的 `SelectionStatus` 翻译成"现在能做什么"的一句话。
 *
 * ⚠️ 三态判定**完全以服务端为准**（`canSelect` / `canDrop`），前端不重算：
 * 判定要看开关 + 时间窗 + 适用范围 + 学分上限，前端再算一遍必然走偏。
 * `reason` 也是服务端给的可直接展示的中文说明。
 */
const props = defineProps<{
  status: SelectionStatus | null
  loading?: boolean
}>()

type Tone = 'success' | 'warning' | 'info'

interface Banner {
  tone: Tone
  title: string
  detail: string
}

const banner = computed<Banner>(() => {
  const s = props.status
  // 加载中且还没有数据：给"正在加载"而不是"暂无状态"，否则首屏会先闪一句像是坏了的话
  if (!s && props.loading) {
    return {
      tone: 'info',
      title: '正在加载选课状态…',
      detail: '',
    }
  }
  if (!s) {
    return {
      tone: 'info',
      title: '暂无选课状态',
      detail: '请先填写学期后点击「查询」。',
    }
  }
  if (s.canSelect) {
    return {
      tone: 'success',
      title: `选课开放中${s.roundName ? `：${s.roundName}` : ''}`,
      detail: s.canDrop
        ? '选课期间可以自由选课，也可以退掉已选课程。'
        : '现在可以选课；退课要等选课期或补退选期开放。',
    }
  }
  if (s.canDrop) {
    return {
      tone: 'warning',
      title: `补退选期间：只能退课，不能选课${s.roundName ? `（${s.roundName}）` : ''}`,
      detail: s.reason || '选课窗口已结束，补退选窗口开放，此时只能退掉已选课程。',
    }
  }
  return {
    tone: 'info',
    title: '当前不能选课，只能查看',
    detail: s.reason || '选课未开放或不在有效时间窗内，你仍然可以浏览课程，但无法选课或退课。',
  }
})

/** 本轮学分上限（可空 = 不限），模板里展示成角标 */
const maxCredits = computed(() => props.status?.maxCredits ?? null)
</script>

<template>
  <el-alert
    v-loading="loading"
    :type="banner.tone"
    :closable="false"
    show-icon
    class="status-banner"
  >
    <template #title>
      <span class="banner-title">{{ banner.title }}</span>
      <el-tag
        v-if="maxCredits !== null"
        type="info"
        size="small"
        effect="plain"
        class="banner-tag"
      >
        本轮学分上限 {{ maxCredits }}
      </el-tag>
    </template>
    <div
      v-if="banner.detail"
      class="banner-detail"
    >
      {{ banner.detail }}
    </div>
  </el-alert>
</template>

<style scoped>
.status-banner {
  border-radius: 8px;
  margin-bottom: 16px;
}
.banner-title {
  font-weight: 600;
}
.banner-tag {
  margin-left: 10px;
}
.banner-detail {
  font-size: 13px;
  line-height: 1.7;
}
</style>
