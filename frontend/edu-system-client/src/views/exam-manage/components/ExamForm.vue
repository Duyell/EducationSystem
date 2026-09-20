<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import {
  EXAM_TYPE_OPTIONS,
  examConflictCount,
  examConflictItemText,
  examEndTime,
  formatDateTime,
} from '@/utils/exam'
import type { Course, ExamConflictCheck, ExamForm, ExamSchedule, ExamType, Room } from '@/types/models'

/**
 * 考试新建/编辑对话框。
 *
 * 单一职责：维护一条考试记录的表单 + **实时冲突预检**。
 *
 * 冲突预检（`POST /exam/check`）在课程/时间/时长/考场任一变化后防抖触发，结果分三种：
 * 无冲突（可以保存）/ 有冲突（列出两个维度）/ 检测失败（未知态，仍可尝试保存）。
 *
 * ⚠️ **判据是半开区间**：`existing.start < newEnd AND existing.end > newStart`。
 * 所以 09:00-11:00 与 11:00-13:00 **不是**冲突，只是首尾相接；
 * 这与排课（P2）的节次判据（3-4 与 4-5 共用第 4 节算冲突）**故意不同**。
 * 前端**不重写**这套判据——判据只有一个权威实现（服务端），这里只做展示。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 传入表示编辑，null 表示新建 */
  exam: ExamSchedule | null
  courseOptions: Course[]
  roomOptions: Room[]
}>()

const emit = defineEmits<{
  (e: 'success'): void
}>()

const formRef = ref()
const saving = ref(false)

const form = reactive({
  courseId: undefined as number | undefined,
  examType: 'FINAL' as ExamType,
  examTime: '',
  durationMinutes: 120,
  roomId: undefined as number | undefined,
  seatRange: '',
  invigilator: '',
  status: 1,
  remark: '',
})

const rules = {
  courseId: [{ required: true, message: '请选择课程', trigger: 'change' }],
  examTime: [{ required: true, message: '请选择考试开始时间', trigger: 'change' }],
  durationMinutes: [{ required: true, message: '请填写考试时长', trigger: 'blur' }],
}

const isEdit = computed(() => !!props.exam?.id)
const title = computed(() => (isEdit.value ? '编辑考试安排' : '新建考试安排'))

/**
 * 结束时间的预览。
 *
 * 报文里没有 `endTime`（普通 Java 方法不会被序列化），所以只能在前端算。
 * 顺手展示出来：管理员填完"09:00 + 120 分钟"最想确认的就是"到 11:00 结束"。
 */
const endText = computed(() => {
  if (!form.examTime || !form.durationMinutes) return ''
  const end = examEndTime({ examTime: form.examTime, durationMinutes: form.durationMinutes })
  return end ? formatDateTime(end) : ''
})

// ===================== 冲突预检 =====================

const checking = ref(false)
const checkResult = ref<ExamConflictCheck | null>(null)
const checkFailed = ref(false)

/** 三个必填项齐了才有必要检测（缺一项服务端也算不出来） */
const canCheck = computed(
  () => !!form.courseId && !!form.examTime && Number(form.durationMinutes) > 0,
)

const conflictTotal = computed(() => examConflictCount(checkResult.value))
const roomConflicts = computed(() => checkResult.value?.roomConflicts ?? [])
const studentConflicts = computed(() => checkResult.value?.studentConflicts ?? [])

/** 确实检出冲突 → 保存必然被服务端拒绝，干脆把按钮禁掉，别让人白点一次 */
const blockedByConflict = computed(() => checkResult.value?.conflict === true)

const runCheck = async () => {
  if (!canCheck.value) {
    checkResult.value = null
    checkFailed.value = false
    return
  }
  checking.value = true
  checkFailed.value = false
  try {
    const res = await axios.post('/api/exam/check', {
      courseId: form.courseId,
      examTime: form.examTime,
      durationMinutes: form.durationMinutes,
      // 清空 el-select 可能得到空串，空串会被当成"一个值"发给后端；
      // 用 `|| undefined` 让它变成"不传"，语义与 UI 上的"考场待定"一致。
      roomId: form.roomId || undefined,
      // 编辑时必须排除自己，否则会报"和自己冲突"
      excludeExamId: props.exam?.id,
    })
    checkResult.value = (res.data ?? null) as ExamConflictCheck | null
  } catch {
    // 拦截器已统一提示；检测失败不该阻断填单，退回"未知"态
    checkResult.value = null
    checkFailed.value = true
  } finally {
    checking.value = false
  }
}

const resetForm = () => {
  const exam = props.exam
  Object.assign(form, {
    courseId: exam?.courseId,
    examType: exam?.examType ?? 'FINAL',
    examTime: exam?.examTime ?? '',
    durationMinutes: exam?.durationMinutes ?? 120,
    roomId: exam?.roomId,
    seatRange: exam?.seatRange ?? '',
    invigilator: exam?.invigilator ?? '',
    status: exam?.status ?? 1,
    remark: exam?.remark ?? '',
  })
  checkResult.value = null
  checkFailed.value = false
  formRef.value?.clearValidate()
}

// 打开时载入待编辑的数据并立刻检测一次
watch(visible, (open) => {
  if (!open) return
  resetForm()
  runCheck()
})

// 任一影响冲突判定的字段变化 → 防抖后重新检测
let debounceTimer: ReturnType<typeof setTimeout> | undefined
watch(
  () => [form.courseId, form.examTime, form.durationMinutes, form.roomId].join('|'),
  () => {
    if (!visible.value) return
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(runCheck, 300)
  },
)

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

// ===================== 提交 =====================

const submit = async () => {
  try {
    await formRef.value.validate()
  } catch {
    return // 校验失败由 el-form 自己渲染提示（validate() 失败时是 reject，必须接住）
  }

  const payload: ExamForm = {
    // 新建时不带 id（undefined 会被 JSON.stringify 丢掉）
    id: props.exam?.id,
    courseId: form.courseId,
    examType: form.examType,
    examTime: form.examTime,
    durationMinutes: Number(form.durationMinutes),
    // 同上：空串会让后端 Integer 绑定失败，空值一律转成 undefined（= 考场待定）
    roomId: form.roomId || undefined,
    seatRange: form.seatRange.trim() || undefined,
    invigilator: form.invigilator.trim() || undefined,
    status: form.status,
    remark: form.remark.trim() || undefined,
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await axios.put('/api/exam', payload)
      ElMessage.success('更新成功')
    } else {
      await axios.post('/api/exam', payload)
      ElMessage.success('创建成功')
    }
    visible.value = false
    emit('success')
  } catch {
    // 服务端在冲突时会硬拒绝并给出原因，拦截器已提示；
    // 保持对话框打开，管理员可以直接改时间/考场再试。
  } finally {
    saving.value = false
  }
}

const close = () => {
  formRef.value?.clearValidate()
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="760px"
    top="6vh"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="110px"
    >
      <el-form-item
        label="课程"
        prop="courseId"
      >
        <el-select
          v-model="form.courseId"
          placeholder="请选择课程"
          filterable
          style="width: 100%"
        >
          <el-option
            v-for="c in courseOptions"
            :key="c.id"
            :label="`${c.courseCode ?? ''} ${c.courseName}`.trim()"
            :value="c.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="考试类型">
        <el-select
          v-model="form.examType"
          style="width: 160px"
        >
          <el-option
            v-for="opt in EXAM_TYPE_OPTIONS"
            :key="opt.value"
            :label="opt.label"
            :value="opt.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item
        label="开始时间"
        prop="examTime"
      >
        <!--
          value-format 用 [T] 把 T 转义成字面量：后端要的是不带时区的
          ISO 本地时间串 "2026-09-27T09:00:00"，裸写 T 有被当成格式记号的风险。
        -->
        <el-date-picker
          v-model="form.examTime"
          type="datetime"
          placeholder="选择考试开始时间"
          format="YYYY-MM-DD HH:mm"
          value-format="YYYY-MM-DD[T]HH:mm:ss"
          style="width: 220px"
        />
      </el-form-item>

      <el-form-item
        label="考试时长"
        prop="durationMinutes"
      >
        <el-input-number
          v-model="form.durationMinutes"
          :min="1"
          :max="600"
          :step="10"
          controls-position="right"
          style="width: 150px"
        />
        <span class="field-hint">分钟</span>
        <span
          v-if="endText"
          class="end-hint"
        >
          结束时间：{{ endText }}
        </span>
      </el-form-item>

      <el-form-item label="考场">
        <el-select
          v-model="form.roomId"
          placeholder="可留空 = 考场待定"
          clearable
          filterable
          style="width: 320px"
        >
          <el-option
            v-for="room in roomOptions"
            :key="room.id"
            :label="`${room.roomName}（${room.capacity} 人）`"
            :value="room.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="座位号段">
        <el-input
          v-model="form.seatRange"
          placeholder="如 A101-A130，可留空"
          style="width: 320px"
        />
      </el-form-item>

      <el-form-item label="监考教师">
        <el-input
          v-model="form.invigilator"
          placeholder="可多人，逗号分隔，可留空"
        />
      </el-form-item>

      <el-form-item label="状态">
        <el-switch
          v-model="form.status"
          :active-value="1"
          :inactive-value="0"
          active-text="有效"
          inactive-text="作废"
        />
      </el-form-item>

      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          :rows="2"
          placeholder="可选"
        />
      </el-form-item>
    </el-form>

    <!-- 冲突预检结果：这是本页的重点，故放在表单正下方、保存按钮正上方 -->
    <div class="check-block">
      <el-alert
        v-if="!canCheck"
        title="选择课程、开始时间与时长后会自动检测冲突"
        type="info"
        :closable="false"
        show-icon
      />

      <el-alert
        v-else-if="checking"
        title="正在检测冲突…"
        type="info"
        :closable="false"
        show-icon
      />

      <el-alert
        v-else-if="checkFailed"
        title="冲突检测未能完成（保存时服务端仍会硬校验）"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-alert
        v-else-if="conflictTotal > 0"
        :title="`检测到 ${conflictTotal} 处冲突，保存会被拒绝`"
        type="error"
        :closable="false"
        show-icon
      >
        <div
          v-if="roomConflicts.length"
          class="conflict-group"
        >
          <div class="conflict-group-title">
            考场占用冲突（{{ roomConflicts.length }}）—— 同一考场在同一时段已被占用
          </div>
          <ul class="conflict-list">
            <li
              v-for="(item, index) in roomConflicts"
              :key="`room-${index}-${item.courseCode ?? ''}-${item.examTime ?? ''}`"
            >
              {{ examConflictItemText(item) }}
            </li>
          </ul>
        </div>
        <div
          v-if="studentConflicts.length"
          class="conflict-group"
        >
          <div class="conflict-group-title">
            学生时间冲突（{{ studentConflicts.length }}）—— 同时选了这两门课的学生会撞考
          </div>
          <ul class="conflict-list">
            <li
              v-for="(item, index) in studentConflicts"
              :key="`stu-${index}-${item.courseCode ?? ''}-${item.examTime ?? ''}`"
            >
              {{ examConflictItemText(item) }}
            </li>
          </ul>
        </div>
      </el-alert>

      <el-alert
        v-else
        title="无冲突，可以保存"
        type="success"
        :closable="false"
        show-icon
      />

      <div class="rule-hint">
        判定规则：两场考试的时间区间<strong>半开重叠</strong>才算冲突
        （已有考试的开始 &lt; 本次结束 <strong>且</strong> 已有考试的结束 &gt; 本次开始）。
        所以 09:00-11:00 与 11:00-13:00 只是首尾相接，<strong>不算</strong>冲突；
        这与排课的节次判据不同（那边第 3-4 节与第 4-5 节共用第 4 节，算冲突）。
      </div>
    </div>

    <template #footer>
      <el-button @click="close">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="blockedByConflict"
        @click="submit"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  margin-left: 8px;
  font-size: 12px;
  color: #999;
}
.end-hint {
  margin-left: 16px;
  font-size: 12px;
  color: #165dff;
}
.check-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
}
.conflict-group {
  margin-top: 6px;
}
.conflict-group-title {
  font-size: 12px;
  font-weight: 600;
}
.conflict-list {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.7;
}
.rule-hint {
  font-size: 12px;
  line-height: 1.7;
  color: #999;
}
</style>
