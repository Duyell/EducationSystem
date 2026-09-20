<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import { scopeText } from '@/utils/selection'
import type { College, Major, ScopeForm, SelectionRound, SelectionRoundScope } from '@/types/models'

/**
 * 轮次适用范围抽屉。
 *
 * 自包含：打开时自己拉范围与参考数据（专业/学院），增删也自己发。
 * 父级只传"给哪个轮次配范围"，并在 `changed` 后刷新列表里的范围摘要
 * （同 P2 的 ScheduleApplyDialog：只在被打开时才取自己的数据）。
 *
 * 业务规则（后端强制，前端同步提示）：
 * - **没有任何范围记录 = 不限**，这正是后端拒绝"三个条件全空的范围记录"的原因；
 * - 一条范围里填了的条件必须匹配，没填的不参与判断（NULL = 不限）。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  round: SelectionRound | null
}>()

const emit = defineEmits<{
  (e: 'changed'): void
}>()

const scopes = ref<SelectionRoundScope[]>([])
const loading = ref(false)
const loadError = ref('')

const majors = ref<Major[]>([])
const colleges = ref<College[]>([])
const adding = ref(false)

const draft = reactive({
  grade: '',
  majorId: undefined as number | undefined,
  collegeId: undefined as number | undefined,
})

/** 三个条件是否全空 —— 全空等同"不限"，后端会拒绝，这里先拦一次并说明原因 */
const draftEmpty = computed(
  () => !draft.grade.trim() && draft.majorId === undefined && draft.collegeId === undefined,
)

const resetDraft = () => {
  draft.grade = ''
  draft.majorId = undefined
  draft.collegeId = undefined
}

const loadScopes = async () => {
  if (!props.round?.id) {
    scopes.value = []
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const res = await axios.get(`/api/selection-round/${props.round.id}/scope`)
    scopes.value = (res.data ?? []) as SelectionRoundScope[]
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
    scopes.value = []
  } finally {
    loading.value = false
  }
}

const loadReferenceData = async () => {
  try {
    const [majorRes, collegeRes] = await Promise.all([
      axios.get('/api/major', { params: { pageNum: 1, pageSize: 200 } }),
      axios.get('/api/college'),
    ])
    majors.value = (majorRes.data?.list ?? []) as Major[]
    colleges.value = (collegeRes.data?.list ?? []) as College[]
  } catch {
    // 参考数据拉不到不该挡住"看范围"这件事：下拉为空，用户仍可只按年级配范围
    majors.value = []
    colleges.value = []
  }
}

watch(visible, (open) => {
  if (!open) return
  resetDraft()
  loadScopes()
  loadReferenceData()
})

const addScope = async () => {
  if (!props.round?.id) return
  if (draftEmpty.value) {
    ElMessage.warning('三个条件至少要填一个：全空的范围等同于"不限"，请直接不配范围')
    return
  }
  adding.value = true
  try {
    const payload: ScopeForm = {
      roundId: props.round.id,
      grade: draft.grade.trim() || undefined,
      majorId: draft.majorId,
      collegeId: draft.collegeId,
    }
    await axios.post('/api/selection-round/scope', payload)
    ElMessage.success('已添加')
    resetDraft()
    await loadScopes()
    emit('changed')
  } catch {
    // 后端会拒绝重名/全空范围；拦截器已统一提示，这里只中止，不重复提示
  } finally {
    adding.value = false
  }
}

const removeScope = async (scope: SelectionRoundScope) => {
  try {
    await ElMessageBox.confirm(`确定删除范围「${scopeText(scope)}」？`, '提示', { type: 'warning' })
  } catch {
    return // 用户取消：ElMessageBox 取消时会 reject，不接住就是未处理的 Promise 异常
  }
  try {
    await axios.delete(`/api/selection-round/scope/${scope.id}`)
  } catch {
    return // 拦截器已提示
  }
  ElMessage.success('已删除')
  await loadScopes()
  emit('changed')
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="`适用范围 — ${round?.roundName ?? ''}`"
    size="760px"
  >
    <div class="scope-manager">
      <el-alert
        title="没有任何范围记录 = 不限（全年级全专业都可选）"
        type="info"
        :closable="false"
        show-icon
        class="scope-alert"
      >
        一条范围里<strong>填了的条件必须匹配</strong>，没填的不参与判断。
        例如只填「2023」就表示"仅 2023 级可选，专业学院不限"。
      </el-alert>

      <el-card
        shadow="never"
        class="add-card"
      >
        <template #header>
          <span class="card-title">添加范围</span>
        </template>
        <div class="add-form">
          <el-input
            v-model="draft.grade"
            placeholder="年级（如 2023）"
            clearable
            style="width: 150px"
          />
          <el-select
            v-model="draft.majorId"
            placeholder="专业（不限留空）"
            clearable
            filterable
            style="width: 220px"
          >
            <el-option
              v-for="m in majors"
              :key="m.id"
              :label="m.majorName"
              :value="m.id as number"
            />
          </el-select>
          <el-select
            v-model="draft.collegeId"
            placeholder="学院（不限留空）"
            clearable
            style="width: 200px"
          >
            <el-option
              v-for="c in colleges"
              :key="c.id"
              :label="c.collegeName"
              :value="c.id as number"
            />
          </el-select>
          <el-button
            type="primary"
            :loading="adding"
            @click="addScope"
          >
            添加
          </el-button>
        </div>
        <div
          v-if="draftEmpty"
          class="add-hint"
        >
          三个条件都为空时不能添加——那等于"不限"，直接用"不配任何范围"表达即可。
        </div>
      </el-card>

      <el-alert
        v-if="loadError"
        :title="'加载失败：' + loadError"
        type="error"
        :closable="false"
        show-icon
        class="scope-alert"
      />

      <el-table
        v-loading="loading"
        :data="scopes"
        stripe
        empty-text="未配置范围：本轮的适用对象是「不限」"
      >
        <el-table-column
          label="范围"
          min-width="280"
        >
          <template #default="{ row }">
            {{ scopeText(row) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="90"
          align="center"
        >
          <template #default="{ row }">
            <el-button
              type="danger"
              link
              size="small"
              @click="removeScope(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-drawer>
</template>

<style scoped>
.scope-manager {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.scope-alert {
  border-radius: 8px;
}
.add-card {
  border-radius: 8px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}
.add-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.add-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.6;
}
</style>
