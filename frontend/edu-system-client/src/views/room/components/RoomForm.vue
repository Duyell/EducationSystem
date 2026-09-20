<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import { ROOM_TYPE_OPTIONS } from '@/utils/schedule'
import type { Room, RoomType } from '@/types/models'

/**
 * 教室新增/编辑对话框（管理员）。
 *
 * 数据流：`v-model` 控制显隐；待编辑行由 props 传入（null 表示新增）；
 *        保存成功用事件通知父级刷新列表。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 传入表示编辑，null 表示新增 */
  row: Room | null
}>()

const emit = defineEmits<{
  (e: 'success'): void
}>()

const formRef = ref()
const saving = ref(false)

const form = reactive({
  id: undefined as number | undefined,
  building: '',
  floorNo: undefined as number | undefined,
  roomNo: '',
  roomName: '',
  capacity: 60,
  roomType: 'NORMAL' as RoomType,
  status: 1,
})

const rules = {
  building: [{ required: true, message: '楼栋不能为空', trigger: 'blur' }],
  roomName: [{ required: true, message: '教室名称不能为空', trigger: 'blur' }],
}

const resetForm = () => {
  Object.assign(form, {
    id: undefined,
    building: '',
    floorNo: undefined,
    roomNo: '',
    roomName: '',
    capacity: 60,
    roomType: 'NORMAL' as RoomType,
    status: 1,
  })
  formRef.value?.clearValidate()
}

/** 打开时重置并用待编辑行填充（编辑时覆盖默认值） */
watch(visible, (open) => {
  if (!open) return
  resetForm()
  const row = props.row
  if (row) {
    Object.assign(form, {
      id: row.id,
      building: row.building,
      floorNo: row.floorNo,
      roomNo: row.roomNo ?? '',
      roomName: row.roomName,
      capacity: Number(row.capacity),
      roomType: row.roomType,
      status: row.status,
    })
  }
})

const submit = async () => {
  // el-form 的 validate() 校验失败时是 reject，不接住会产生未处理的 Promise 异常
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    if (form.id !== undefined) {
      // 更新接口要求 body 里带 id
      await axios.put('/api/room', form)
      ElMessage.success('更新成功')
    } else {
      await axios.post('/api/room', form)
      ElMessage.success('添加成功')
    }
    visible.value = false
    emit('success')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="form.id !== undefined ? '编辑教室' : '新增教室'"
    width="560px"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="90px"
    >
      <el-form-item
        label="楼栋"
        prop="building"
      >
        <el-input
          v-model="form.building"
          placeholder="如 教1"
        />
      </el-form-item>

      <el-form-item
        label="教室名称"
        prop="roomName"
      >
        <el-input
          v-model="form.roomName"
          placeholder="如 教1-301（展示用，排课时看到的就是它）"
        />
      </el-form-item>

      <el-form-item label="楼层">
        <el-input-number
          v-model="form.floorNo"
          :min="1"
          :max="30"
          controls-position="right"
          placeholder="可选"
        />
      </el-form-item>

      <el-form-item label="房间号">
        <el-input
          v-model="form.roomNo"
          placeholder="如 01"
        />
      </el-form-item>

      <el-form-item label="容量">
        <el-input-number
          v-model="form.capacity"
          :min="1"
          :max="1000"
          :step="10"
          controls-position="right"
        />
        <span class="field-hint">排课推荐教室时按此容量与选课容量匹配</span>
      </el-form-item>

      <el-form-item label="教室类型">
        <el-select
          v-model="form.roomType"
          style="width: 100%"
        >
          <el-option
            v-for="item in ROOM_TYPE_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="状态">
        <el-switch
          v-model="form.status"
          :active-value="1"
          :inactive-value="0"
          active-text="可用"
          inactive-text="停用"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #999;
}
</style>
