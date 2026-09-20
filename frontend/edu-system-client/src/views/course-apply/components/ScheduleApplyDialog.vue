<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import axios from '@/utils/request'
import {
  PERIOD_MAX,
  PERIOD_MIN,
  WEEKDAY_OPTIONS,
  WEEK_MAX,
  WEEK_MIN,
  conflictCount,
  conflictItemText,
  hasConflictInfo,
  roomTypeLabel,
  slotWithWeekText,
} from '@/utils/schedule'
import type {
  ClassTimeApply,
  ConflictCheck,
  CourseApply,
  FreeRoomResult,
} from '@/types/models'

/**
 * 排课申请对话框（教师）。
 *
 * 单一职责：为一个**已审批通过**的开课申请挑选上课时间与教室并提交。
 *
 * 两个实时数据源：
 *  1. `POST /class-time/check` —— 该时段会与谁相撞（教师冲突 / 教室冲突分开显示）
 *  2. `GET /room/free`        —— 该时段可用教室，容量下限取本课程的选课容量
 *
 * 时间任一字段变化都重新检测（300ms 防抖，避免连点数字框时打爆接口），
 * 组件卸载时清掉定时器。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 待排课的开课申请；课程 id 取它的 createdCourseId */
  apply: CourseApply | null
}>()

const emit = defineEmits<{
  /** 提交成功（conflictInfo 非空表示该时段有冲突，仅作提醒，仍会进入待审批） */
  (e: 'submitted', conflictInfo: string): void
}>()

/** 已审批申请对应的课程 id —— 排课申请与冲突检测都以它为入口 */
const courseId = computed(() => props.apply?.createdCourseId)

const slot = reactive({
  weekday: 1,
  startPeriod: 1,
  endPeriod: 2,
  startWeek: 1,
  endWeek: 16,
})

const roomId = ref<number | undefined>(undefined)

const checking = ref(false)
const checkResult = ref<ConflictCheck | null>(null)
const checkFailed = ref(false)

const freeLoading = ref(false)
const freeResult = ref<FreeRoomResult | null>(null)

const submitting = ref(false)

/** 冲突总条数：必须由两个数组长度自行相加（后端 total() 是 record 派生方法，不序列化） */
const conflictTotal = computed(() => conflictCount(checkResult.value))
const teacherConflicts = computed(() => checkResult.value?.teacherConflicts ?? [])
const roomConflicts = computed(() => checkResult.value?.roomConflicts ?? [])
const roomOptions = computed(() => freeResult.value?.candidates ?? [])

/** 校验区间的起止顺序（el-input-number 的 min/max 表达不了"结束不能早于开始"） */
const slotInvalid = computed(
  () => slot.endPeriod < slot.startPeriod || slot.endWeek < slot.startWeek,
)

const resetSlot = () => {
  const apply = props.apply
  // 审批通过时用教师当初填的期望时间作为默认值；没填就回到"周一 1-2 节 / 1-16 周"
  slot.weekday = apply?.expectedWeekday ?? 1
  slot.startPeriod = apply?.expectedStartPeriod ?? 1
  slot.endPeriod = apply?.expectedEndPeriod ?? 2
  slot.startWeek = apply?.expectedStartWeek ?? 1
  slot.endWeek = apply?.expectedEndWeek ?? 16
  roomId.value = apply?.preferRoomId
  checkResult.value = null
  checkFailed.value = false
  freeResult.value = null
}

const runCheck = async () => {
  if (!courseId.value) {
    checkResult.value = null
    return
  }
  checking.value = true
  checkFailed.value = false
  try {
    // 传了 courseId，学期由后端从课程推导，无需前端再传 term
    const res = await axios.post('/api/class-time/check', {
      courseId: courseId.value,
      weekday: slot.weekday,
      startPeriod: slot.startPeriod,
      endPeriod: slot.endPeriod,
      startWeek: slot.startWeek,
      endWeek: slot.endWeek,
      roomId: roomId.value,
    })
    checkResult.value = (res.data ?? null) as ConflictCheck | null
  } catch {
    // 拦截器已统一提示；检测失败不该阻断填单，退回"未知"态
    checkResult.value = null
    checkFailed.value = true
  } finally {
    checking.value = false
  }
}

const runFreeRooms = async () => {
  const apply = props.apply
  if (!apply?.term) {
    freeResult.value = null
    return
  }
  freeLoading.value = true
  try {
    const res = await axios.get('/api/room/free', {
      params: {
        term: apply.term,
        weekday: slot.weekday,
        startPeriod: slot.startPeriod,
        endPeriod: slot.endPeriod,
        startWeek: slot.startWeek,
        endWeek: slot.endWeek,
        // 容量下限 = 本课程的选课容量，避免推荐出坐不下的教室
        minCapacity: apply.maxStudent,
        limit: 20,
      },
    })
    freeResult.value = (res.data ?? null) as FreeRoomResult | null
  } catch {
    freeResult.value = null
  } finally {
    freeLoading.value = false
  }
}

const refresh = () => {
  if (slotInvalid.value) return
  runCheck()
  runFreeRooms()
}

/** 打开时用期望时间初始化并立即检测一次 */
watch(visible, (open) => {
  if (!open) return
  resetSlot()
  refresh()
})

// 时间任一字段变化 → 防抖后重新检测（含空闲教室，它与时段强相关）
let debounceTimer: ReturnType<typeof setTimeout> | undefined
watch(
  () => [slot.weekday, slot.startPeriod, slot.endPeriod, slot.startWeek, slot.endWeek].join('-'),
  () => {
    if (!visible.value) return
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(refresh, 300)
  },
)

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

/**
 * 换教室后只需重检冲突。
 *
 * 刻意**不用** `watch(roomId)`：打开弹窗时 resetSlot() 会回填申请里的教室，
 * 那样会连带触发一次多余的检测（一次开窗最多打三次 check）。改成在下拉框
 * 上显式 @change，只有用户真的改了教室才重检。
 */
const onRoomChange = () => {
  if (!slotInvalid.value) runCheck()
}

/** 采用后端推荐的最合适教室，随后重检冲突 */
const useRecommendedRoom = () => {
  const recommended = freeResult.value
  if (!recommended?.found || !recommended.roomId) {
    ElMessage.warning('当前时段没有可用教室可推荐，请调整时间')
    return
  }
  roomId.value = recommended.roomId
  ElMessage.success(`已选用推荐教室：${recommended.roomName ?? ''}`)
  onRoomChange()
}

const submit = async () => {
  if (!courseId.value) {
    ElMessage.warning('该申请尚未生成课程，无法排课')
    return
  }
  if (slotInvalid.value) {
    ElMessage.warning('结束节次/周次不能早于起始值')
    return
  }
  submitting.value = true
  try {
    const res = await axios.post('/api/class-time/apply', {
      courseId: courseId.value,
      weekday: slot.weekday,
      startPeriod: slot.startPeriod,
      endPeriod: slot.endPeriod,
      startWeek: slot.startWeek,
      endWeek: slot.endWeek,
      roomId: roomId.value,
    })
    const created = (res.data ?? null) as ClassTimeApply | null
    const conflictInfo = created?.conflictInfo ?? ''
    if (hasConflictInfo(conflictInfo)) {
      // 有冲突不是失败：后端只作警告并落库，由管理员审批时决定
      ElMessage.warning('排课申请已提交，但该时段存在冲突，将由管理员审批时处理')
    } else {
      ElMessage.success('排课申请已提交，等待管理员审批')
    }
    visible.value = false
    emit('submitted', conflictInfo)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="申请排课"
    width="760px"
    top="6vh"
  >
    <div
      v-if="apply"
      class="dialog-body"
    >
      <el-descriptions
        :column="3"
        border
        size="small"
        class="apply-info"
      >
        <el-descriptions-item label="课程">
          {{ apply.courseCode }} {{ apply.courseName }}
        </el-descriptions-item>
        <el-descriptions-item label="学期">
          {{ apply.term }}
        </el-descriptions-item>
        <el-descriptions-item label="选课容量">
          {{ apply.maxStudent }} 人
        </el-descriptions-item>
      </el-descriptions>

      <el-form label-width="72px">
        <el-form-item label="星期">
          <el-select
            v-model="slot.weekday"
            style="width: 150px"
          >
            <el-option
              v-for="day in WEEKDAY_OPTIONS"
              :key="day.value"
              :label="day.label"
              :value="day.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="节次">
          <el-input-number
            v-model="slot.startPeriod"
            :min="PERIOD_MIN"
            :max="PERIOD_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="range-sep">至</span>
          <el-input-number
            v-model="slot.endPeriod"
            :min="PERIOD_MIN"
            :max="PERIOD_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="field-hint">一天 {{ PERIOD_MAX }} 节（{{ PERIOD_MIN }}-{{ PERIOD_MAX }}）</span>
        </el-form-item>

        <el-form-item label="周次">
          <el-input-number
            v-model="slot.startWeek"
            :min="WEEK_MIN"
            :max="WEEK_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="range-sep">至</span>
          <el-input-number
            v-model="slot.endWeek"
            :min="WEEK_MIN"
            :max="WEEK_MAX"
            controls-position="right"
            style="width: 130px"
          />
          <span class="field-hint">课程可以从中间周次开始，如第 9-16 周</span>
        </el-form-item>

        <el-form-item label="教室">
          <el-select
            v-model="roomId"
            placeholder="可留空由管理员分配"
            clearable
            filterable
            :loading="freeLoading"
            style="width: 320px"
            @change="onRoomChange"
          >
            <el-option
              v-for="room in roomOptions"
              :key="room.id"
              :label="`${room.roomName}（${roomTypeLabel(room.roomType)} · ${room.capacity} 人）`"
              :value="room.id as number"
            />
          </el-select>
          <el-button
            class="recommend-btn"
            :disabled="!freeResult?.found"
            @click="useRecommendedRoom"
          >
            用推荐教室
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="slotInvalid"
        title="结束节次/周次不能早于起始值，请先修正时间"
        type="error"
        :closable="false"
        show-icon
        class="conflict-alert"
      />

      <template v-else>
        <el-alert
          v-if="checking"
          title="正在检测时间冲突…"
          type="info"
          :closable="false"
          show-icon
          class="conflict-alert"
        />

        <el-alert
          v-else-if="checkFailed"
          title="冲突检测未能完成（可继续提交，最终以管理员审批时的检测为准）"
          type="warning"
          :closable="false"
          show-icon
          class="conflict-alert"
        />

        <el-alert
          v-else-if="conflictTotal > 0"
          :title="`检测到 ${conflictTotal} 处冲突，提交后会由管理员审批时处理`"
          type="error"
          :closable="false"
          show-icon
          class="conflict-alert"
        >
          <div
            v-if="teacherConflicts.length"
            class="conflict-group"
          >
            <div class="conflict-group-title">
              教师时间冲突（{{ teacherConflicts.length }}）
            </div>
            <ul class="conflict-list">
              <li
                v-for="(item, index) in teacherConflicts"
                :key="`t-${item.classTimeId ?? index}`"
              >
                {{ conflictItemText(item) }}
              </li>
            </ul>
          </div>
          <div
            v-if="roomConflicts.length"
            class="conflict-group"
          >
            <div class="conflict-group-title">
              教室占用冲突（{{ roomConflicts.length }}）
            </div>
            <ul class="conflict-list">
              <li
                v-for="(item, index) in roomConflicts"
                :key="`r-${item.classTimeId ?? index}`"
              >
                {{ conflictItemText(item) }}
              </li>
            </ul>
          </div>
        </el-alert>

        <el-alert
          v-else
          :title="`${slotWithWeekText(slot)} 无冲突，可以提交`"
          type="success"
          :closable="false"
          show-icon
          class="conflict-alert"
        />
      </template>

      <div class="free-room">
        <div class="free-room-head">
          <span class="free-room-title">该时段空闲教室</span>
          <el-button
            link
            type="primary"
            size="small"
            :loading="freeLoading"
            @click="refresh"
          >
            重新检测
          </el-button>
        </div>
        <div
          v-if="freeResult?.found"
          class="free-room-body"
        >
          <div class="free-room-recommend">
            推荐：<strong>{{ freeResult.roomName }}</strong>
            <span
              v-if="freeResult.capacity"
              class="muted-text"
            >（{{ freeResult.capacity }} 人）</span>
          </div>
          <div
            v-if="roomOptions.length"
            class="muted-text"
          >
            另有 {{ roomOptions.length }} 间候选可选
          </div>
        </div>
        <div
          v-else
          class="muted-text"
        >
          {{ freeResult?.message || (freeLoading ? '查询中…' : '该时段没有满足容量的空闲教室') }}
        </div>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="slotInvalid"
        @click="submit"
      >
        提交排课申请
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.apply-info {
  margin-bottom: 16px;
}
.range-sep {
  margin: 0 8px;
  color: #999;
}
.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #999;
}
.recommend-btn {
  margin-left: 8px;
}
.conflict-alert {
  margin: 4px 0 14px;
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
.free-room {
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
}
.free-room-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.free-room-title {
  font-size: 13px;
  font-weight: 600;
  color: #333;
}
.free-room-body {
  margin-top: 6px;
  font-size: 13px;
}
.free-room-recommend {
  color: #333;
}
.muted-text {
  font-size: 12px;
  color: #999;
}
</style>
