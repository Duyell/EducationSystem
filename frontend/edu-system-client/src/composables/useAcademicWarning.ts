import { h, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import axios from '@/utils/request'
import type { AcademicWarningStatus } from '@/types/models'

/**
 * 学业预警提示（制度依据 `docs/policies/01-学籍管理规定.md` §5）。
 *
 * 规则：只做**提示**，不做任何自动处理；**只弹一次**，学生点"我知道了"后记为已读水位线，
 * 之后只有情况**变严重**（未通过学分比上次确认时更多）才会再次提示。
 *
 * 放在 composable 而不是 `Layout.vue` 里：这是一段"取数 + 副作用（弹窗 + 标记已读）"的逻辑，
 * 视图只应负责组合（`vue-best-practices`：把状态与副作用移入 composable）。
 *
 * 两个容易踩的坑，已按本仓库既有教训处理：
 * 1. `ElMessageBox` 被取消/关闭时返回的是 **reject**——必须接住，否则是未处理的 Promise；
 * 2. 每条路由都会挂载 `Layout`，所以用**按 token 记忆**的方式保证一次登录只查一次
 *    （按 token 而不是全局 boolean：退出再登录另一个账号时要重新检查）。
 */

/** 已检查过的 token：同一次登录只查一次 */
let checkedToken = ''

/** 数字展示：去掉无意义的小数零（后端 BigDecimal 会序列化成 8.0 / 8.00） */
const num = (v: number | null | undefined): string =>
  v === null || v === undefined ? '0' : String(Number(v))

/** 当前登录用户信息（登录时写入 sessionStorage） */
function currentUser(): { role?: string; name?: string } {
  try {
    return JSON.parse(sessionStorage.getItem('user') || '{}')
  } catch {
    return {}
  }
}

export function useAcademicWarning() {
  const status = ref<AcademicWarningStatus | null>(null)
  const checking = ref(false)

  /** 组装弹窗内容：用 VNode 而不是 HTML 字符串，避免把库里的课程名当 HTML 解析 */
  const buildMessage = (data: AcademicWarningStatus) => {
    const shown = data.courses.slice(0, 5)
    const rest = data.courses.length - shown.length
    return h('div', { style: 'line-height: 1.8;' }, [
      h('p', { style: 'margin: 0 0 8px;' }, [
        `你目前有 ${data.failedCourseCount} 门课程未通过，累计 ${num(data.failedCredits)} 学分，`,
        h('strong', `已达到学业预警条件（阈值 ${num(data.threshold)} 学分）。`),
      ]),
      h(
        'ul',
        { style: 'margin: 0 0 8px; padding-left: 18px;' },
        shown.map((c) => h('li', `《${c.courseName}》 ${num(c.credit)} 学分`)),
      ),
      rest > 0 ? h('p', { style: 'margin: 0 0 8px;' }, `……另有 ${rest} 门，详见「我的绩点」。`) : null,
      h(
        'p',
        { style: 'margin: 0; color: #909399; font-size: 12px;' },
        '请尽快与辅导员沟通重修安排。本提示仅作提醒，不会自动产生任何处理。',
      ),
    ])
  }

  /** 标记已读（幂等）：失败也不打扰学生，下次登录会再提示 */
  const markRead = async () => {
    try {
      await axios.post('/api/academic-warning/my/read')
    } catch {
      // 拦截器已提示错误；预警是附加能力，失败不应影响学生当前操作
    }
  }

  /** 学生本人确认预警后调用；非学生角色直接跳过（教师没有该功能） */
  const checkAndNotify = async () => {
    const token = sessionStorage.getItem('token') || ''
    const user = currentUser()
    if (user.role !== 'student' || !token || checkedToken === token) return
    checkedToken = token
    checking.value = true
    try {
      const res = await axios.get('/api/academic-warning/my')
      const data = res.data as AcademicWarningStatus
      status.value = data
      if (!data?.shouldNotify) return
      try {
        await ElMessageBox.alert(buildMessage(data), '学业预警', {
          confirmButtonText: '我知道了',
          type: 'warning',
        })
        await markRead()
        status.value = { ...data, shouldNotify: false }
      } catch {
        // 学生直接关闭弹窗：不视为已读，下次登录仍会提示；这里只需吞掉 reject
      }
    } catch {
      // 本次检查失败 → 允许下次挂载重试
      checkedToken = ''
    } finally {
      checking.value = false
    }
  }

  return { status, checking, checkAndNotify }
}
