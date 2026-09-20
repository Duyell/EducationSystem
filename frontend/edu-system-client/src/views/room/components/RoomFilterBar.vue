<script setup lang="ts">
import { ROOM_TYPE_OPTIONS } from '@/utils/schedule'
import type { RoomQuery } from '@/types/models'

/**
 * 教室筛选栏（管理员）。
 *
 * 筛选条件整体用 `defineModel` 双向绑定——这是真正的双向契约：子组件就地改条件。
 * 但"何时真正发请求"由父级决定，故查询/重置只上报事件，不自己调接口。
 */
const query = defineModel<RoomQuery>({ required: true })

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
}>()
</script>

<template>
  <div class="filter-bar">
    <el-input
      v-model="query.building"
      placeholder="楼栋（如 教1）"
      clearable
      style="width: 160px"
      @keyup.enter="emit('search')"
    />
    <el-select
      v-model="query.roomType"
      placeholder="教室类型"
      clearable
      style="width: 150px"
    >
      <el-option
        v-for="item in ROOM_TYPE_OPTIONS"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
    <el-input-number
      v-model="query.minCapacity"
      :min="0"
      :max="1000"
      :step="20"
      controls-position="right"
      placeholder="最小容量"
      style="width: 150px"
    />
    <el-select
      v-model="query.status"
      placeholder="状态"
      clearable
      style="width: 120px"
    >
      <el-option
        label="可用"
        :value="1"
      />
      <el-option
        label="停用"
        :value="0"
      />
    </el-select>
    <el-button
      type="primary"
      @click="emit('search')"
    >
      查询
    </el-button>
    <el-button @click="emit('reset')">
      重置
    </el-button>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
</style>
