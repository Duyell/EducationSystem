<script setup lang="ts">
import { ref, watch } from 'vue'
import { roomTypeLabel, slotWithWeekText } from '@/utils/schedule'
import type { ClassTimeApply, Room } from '@/types/models'

/**
 * 排课申请「审批通过」对话框（管理员）。
 *
 * 单一职责：确认这次通过，并可选地改派一间教室。
 * 空闲教室列表由父级取好传进来（props down）——本组件不发请求，
 * 这样数据流是单向的，也避免同一个 composable 在父子里各存一份状态。
 *
 * 不改派教室时直接确认即可；后端在审批环节会**硬阻断冲突**，
 * 被阻断时父级不会关闭本弹窗，管理员可以改教室后重试。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  apply: ClassTimeApply | null
  /** 该时段可选教室（父级查询后传入） */
  roomOptions: Room[]
  freeLoading: boolean
  /** 无可用教室时的说明文案 */
  freeMessage: string
  /** 提交中（父级正在调审批接口） */
  submitting: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm', roomId: number | undefined): void
}>()

const roomId = ref<number | undefined>(undefined)

// 打开时默认沿用申请里填的教室，管理员可改
watch(visible, (open) => {
  if (open) roomId.value = props.apply?.roomId
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="审批通过排课申请"
    width="600px"
  >
    <div
      v-if="apply"
      class="dialog-body"
    >
      <el-descriptions
        :column="2"
        border
        size="small"
      >
        <el-descriptions-item label="课程">
          {{ apply.courseCode }} {{ apply.courseName }}
        </el-descriptions-item>
        <el-descriptions-item label="教师">
          {{ apply.teacherName || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="学期">
          {{ apply.term || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="上课时间">
          {{ slotWithWeekText(apply) }}
        </el-descriptions-item>
        <el-descriptions-item label="申请教室">
          {{ apply.roomName || '未指定' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="apply.conflictInfo"
        title="该申请提交时已存在冲突"
        type="warning"
        :closable="false"
        show-icon
        class="conflict-alert"
      >
        {{ apply.conflictInfo }}
      </el-alert>

      <el-form
        label-width="80px"
        class="room-form"
      >
        <el-form-item label="通过教室">
          <el-select
            v-model="roomId"
            placeholder="不指定（留空则为待分配）"
            clearable
            filterable
            :loading="freeLoading"
            style="width: 100%"
          >
            <el-option
              v-for="room in roomOptions"
              :key="room.id"
              :label="`${room.roomName}（${roomTypeLabel(room.roomType)} · ${room.capacity} 人）`"
              :value="room.id as number"
            />
          </el-select>
          <div class="field-hint">
            <template v-if="freeLoading">
              正在查询该时段空闲教室…
            </template>
            <template v-else-if="roomOptions.length">
              该时段有 {{ roomOptions.length }} 间可用教室，可改派以避开教室冲突
            </template>
            <template v-else>
              {{ freeMessage || '该时段没有可推荐的空闲教室' }}
            </template>
          </div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        @click="emit('confirm', roomId)"
      >
        确认通过
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.conflict-alert {
  align-items: flex-start;
}
.room-form {
  margin-top: 4px;
}
.field-hint {
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}
</style>
