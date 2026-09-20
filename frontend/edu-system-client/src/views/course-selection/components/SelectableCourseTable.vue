<script setup lang="ts">
import { computed } from 'vue'
import type { SelectableCourse, SelectionStatus } from '@/types/models'
import type { TagType } from '@/utils/schedule'

/**
 * 可选课程列表。
 *
 * 单一职责：渲染 + 抛意图。选课/退课由父级执行（要刷新两个列表）。
 *
 * 「能不能选/退」完全取自服务端：
 * - 行级 `selectable` + `reason` 说明**这门课**为什么不能选（时间冲突、容量满、已修过…）；
 * - 全局 `status.canSelect` / `canDrop` 说明**现在这个时间点**允不允许操作。
 * 两者都满足按钮才可点；被挡住时按钮禁用并把原因显示出来，而不是让用户点了才知道。
 */
const props = defineProps<{
  rows: SelectableCourse[]
  status: SelectionStatus | null
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'select', courseId: number): void
  (e: 'drop', courseId: number): void
}>()

/** 全局开关：服务端说现在能不能选/退 */
const canSelectNow = computed(() => props.status?.canSelect === true)
const canDropNow = computed(() => props.status?.canDrop === true)

interface RowView {
  id: number
  courseCode: string
  courseName: string
  teacherName: string
  credit: number
  maxStudent: number | string
  selected: boolean
  /** 现在能否对该行执行"选课"（行级允许 + 全局开启） */
  canPick: boolean
  /** 现在能否对该行执行"退课" */
  canDrop: boolean
  tagLabel: string
  tagType: TagType
  /** 展示给用户的说明；空串表示没有任何阻碍 */
  note: string
}

/**
 * 逐行把"三态 + 原因"算好。
 *
 * 放在 computed 而不是模板里：模板里每列各调一次会重复计算，
 * 且这种分支属于派生逻辑，不属于渲染。
 */
const rowViews = computed<RowView[]>(() =>
  props.rows.map((row) => {
    const course = row.course
    const blockedReason = (row.reason ?? '').trim()

    let tagLabel: string
    let tagType: TagType
    if (row.selected) {
      tagLabel = '已选'
      tagType = 'success'
    } else if (row.selectable) {
      tagLabel = '可选'
      tagType = 'primary'
    } else {
      tagLabel = '不可选'
      tagType = 'danger'
    }

    // 说明的优先级：行级阻碍 > 全局未开放 > 无
    let note = ''
    if (!row.selected && blockedReason) {
      note = blockedReason
    }
    if (!row.selected && !blockedReason && !canSelectNow.value) {
      note = props.status?.reason || '当前不在选课开放时间内'
    }
    if (row.selected && !canDropNow.value) {
      note = props.status?.reason || '当前不在可退课时间内（补退选期间才能退）'
    }
    // 已选且能退：没有阻碍，note 保持空串（不显示多余文案）

    return {
      id: course.id,
      courseCode: course.courseCode || '—',
      courseName: course.courseName,
      teacherName: course.teacherName || '—',
      credit: Number(course.credit ?? 0),
      maxStudent: course.maxStudent ?? '—',
      selected: row.selected,
      canPick: !row.selected && row.selectable && canSelectNow.value,
      canDrop: row.selected && canDropNow.value,
      tagLabel,
      tagType,
      note,
    }
  }),
)

/** 已选门数，给表头/标题用（父级也可自行统计，这里只做展示） */
const selectedCount = computed(() => rowViews.value.filter((r) => r.selected).length)
</script>

<template>
  <div class="selectable-wrap">
    <div class="list-summary">
      共 {{ rowViews.length }} 门课程，其中已选 {{ selectedCount }} 门。
      <span
        v-if="!canSelectNow"
        class="muted-text"
      >当前不可选课，按钮已禁用。</span>
    </div>

    <el-table
      v-loading="loading"
      :data="rowViews"
      stripe
      empty-text="该学期暂无可选课程"
    >
      <el-table-column
        prop="courseCode"
        label="课程代码"
        width="110"
      />
      <el-table-column
        prop="courseName"
        label="课程名称"
        min-width="170"
        show-overflow-tooltip
      />
      <el-table-column
        prop="teacherName"
        label="授课教师"
        width="110"
      />
      <el-table-column
        prop="credit"
        label="学分"
        width="80"
        align="center"
      />
      <el-table-column
        prop="maxStudent"
        label="容量上限"
        width="100"
        align="center"
      />
      <el-table-column
        label="状态"
        width="90"
        align="center"
      >
        <template #default="{ row }">
          <el-tag
            :type="row.tagType"
            size="small"
            effect="plain"
          >
            {{ row.tagLabel }}
          </el-tag>
        </template>
      </el-table-column>

      <!--
        说明列用 show-overflow-tooltip 而不是自绘 tooltip：
        时间冲突的原因很长（会点名冲突课程），溢出时悬浮即可看全，且不占宽度。
      -->
      <el-table-column
        label="说明"
        min-width="200"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <span
            v-if="row.note"
            class="note-text"
          >{{ row.note }}</span>
          <span
            v-else
            class="muted-text"
          >—</span>
        </template>
      </el-table-column>

      <el-table-column
        label="操作"
        width="100"
        fixed="right"
        align="center"
      >
        <template #default="{ row }">
          <el-button
            v-if="row.selected"
            type="danger"
            link
            size="small"
            :disabled="!row.canDrop"
            @click="emit('drop', row.id)"
          >
            退课
          </el-button>
          <el-button
            v-else
            type="primary"
            link
            size="small"
            :disabled="!row.canPick"
            @click="emit('select', row.id)"
          >
            选课
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.selectable-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.list-summary {
  font-size: 13px;
  color: #666;
}
.note-text {
  font-size: 12px;
  color: #e6a23c;
}
.muted-text {
  font-size: 12px;
  color: #999;
}
</style>
