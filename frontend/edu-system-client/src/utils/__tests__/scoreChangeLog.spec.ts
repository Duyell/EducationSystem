import { describe, expect, it } from 'vitest'
import {
  changeSummaryText,
  formatPassed,
  formatScore,
  operationLabel,
  operationTagType,
  roleLabel,
  scoreDiffParts,
  sourceLabel,
  sourceTagType,
} from '@/utils/scoreChangeLog'
import type { ScoreChangeLog } from '@/types/models'

/**
 * 成绩变更日志展示逻辑的单测。
 *
 * 为什么要测这层"看起来只是格式化"的代码：这一页的全部价值就是回答
 * "**改前是多少、改后是多少**"（成绩申诉时唯一有用的问题）。
 * 如果差异推导错了——比如把没变的字段也标成"变了"、或者 UPDATE 时把 before/after 弄反——
 * 页面照样能渲染、类型检查照样通过，但结论是错的，而且**没人会发现**。
 *
 * 纯函数、无 DOM、无请求，所以不需要任何 mock。
 */

/** 造一条日志：before/after 只填需要的字段，其余留空（与后端 NULL 语义一致） */
function log(partial: Partial<ScoreChangeLog>): ScoreChangeLog {
  return {
    id: 1,
    operatorId: '10001',
    operatorRole: 'teacher',
    source: 'UI',
    operation: 'UPDATE',
    studentId: '2023001',
    createTime: '2026-09-28T10:00:00',
    ...partial,
  }
}

describe('枚举文案', () => {
  it('operation 走中文文案，未知值原样返回而不是变成 undefined', () => {
    expect(operationLabel('INSERT')).toBe('新增')
    expect(operationLabel('UPDATE')).toBe('修改')
    expect(operationLabel('DELETE')).toBe('删除')
    expect(operationLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(operationLabel(null)).toBe('—')
  })

  it('操作类型标签色：删除是危险色（申诉场景最需要被一眼看到）', () => {
    expect(operationTagType('INSERT')).toBe('success')
    expect(operationTagType('UPDATE')).toBe('warning')
    expect(operationTagType('DELETE')).toBe('danger')
    expect(operationTagType(null)).toBe('info')
  })

  it('来源区分"界面/接口"与"AI 助手"', () => {
    expect(sourceLabel('UI')).toBe('界面/接口')
    expect(sourceLabel('AI')).toBe('AI 助手')
    expect(sourceTagType('AI')).toBe('primary')
    expect(sourceTagType('UI')).toBe('info')
  })

  it('角色走中文文案', () => {
    expect(roleLabel('student')).toBe('学生')
    expect(roleLabel('teacher')).toBe('教师')
    expect(roleLabel('admin')).toBe('管理员')
    expect(roleLabel('')).toBe('—')
  })
})

describe('分数格式化', () => {
  it('去掉无意义的尾零，但保留 3 位小数的精度', () => {
    expect(formatScore(86.0)).toBe('86')
    expect(formatScore(87.365)).toBe('87.365')
    expect(formatScore(0)).toBe('0')
  })

  it('null / undefined 给占位符：0 分与"没有这个成绩项"是两件事', () => {
    expect(formatScore(null)).toBe('—')
    expect(formatScore(undefined)).toBe('—')
    expect(formatPassed(null)).toBe('—')
  })

  it('通过标记 1/0 转中文', () => {
    expect(formatPassed(1)).toBe('通过')
    expect(formatPassed(0)).toBe('未通过')
  })
})

describe('差异推导（这一页的核心）', () => {
  it('UPDATE 只列出真正变化的字段', () => {
    const parts = scoreDiffParts(
      log({
        operation: 'UPDATE',
        beforeUsual: 80,
        afterUsual: 80, // 没变
        beforeExam: 90,
        afterExam: 30,
        beforeTotal: 86,
        afterTotal: 50,
      }),
      true,
    )
    expect(parts.map((p) => p.key)).toEqual(['exam', 'total'])
    expect(parts[0]).toMatchObject({ label: '考试', before: '90', after: '30', changed: true })
    expect(parts[1]).toMatchObject({ label: '总评', before: '86', after: '50', changed: true })
  })

  it('before/after 不会被弄反', () => {
    const [part] = scoreDiffParts(log({ beforeTotal: 86, afterTotal: 50 }), true)
    expect(part.before).toBe('86')
    expect(part.after).toBe('50')
  })

  it('"通过"状态翻转也算变化', () => {
    const parts = scoreDiffParts(log({ beforePassed: 1, afterPassed: 0 }), true)
    expect(parts.some((p) => p.key === 'passed' && p.before === '通过' && p.after === '未通过')).toBe(true)
  })

  it('UPDATE 摘要把变化连成一句话，多个字段用分号分隔', () => {
    expect(
      changeSummaryText(log({ operation: 'UPDATE', beforeTotal: 86, afterTotal: 50, beforeExam: 90, afterExam: 30 })),
    ).toBe('考试 90 → 30；总评 86 → 50')
  })

  it('UPDATE 但字段值没变时给出明确文案，而不是空白', () => {
    expect(changeSummaryText(log({ operation: 'UPDATE', beforeTotal: 86, afterTotal: 86 }))).toBe('（字段值未变化）')
  })

  it('INSERT 给的是"改后"那份快照（改前本来就为空）', () => {
    const row = log({ operation: 'INSERT', afterUsual: 80, afterExam: 90, afterTotal: 86 })
    expect(changeSummaryText(row)).toBe('平时 80 / 考试 90 / 总评 86')
  })

  it('DELETE 给的是"改前"那份快照（改后已经没有这条记录了）', () => {
    const row = log({ operation: 'DELETE', beforeUsual: 80, beforeExam: 90, beforeTotal: 86 })
    expect(changeSummaryText(row)).toBe('平时 80 / 考试 90 / 总评 86')
  })

  it('快照里跳过两边都为空的字段（例如从来没有补考成绩）', () => {
    const row = log({ operation: 'INSERT', afterTotal: 86, afterMakeup: null })
    expect(scoreDiffParts(row, false).map((p) => p.key)).toEqual(['total'])
  })
})
