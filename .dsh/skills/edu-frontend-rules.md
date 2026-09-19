---
name: edu-frontend-rules
description: EducationSystem 前端工作的技能路由与仲裁规范。在本仓库做任何前端/Vue/UI/组件/样式改动、或加载 vue-best-practices、design-taste-frontend、fe-ui-ts-linter 之前必须先读本技能。规定代码维度听 vue-best-practices、视觉维度如何裁剪 design-taste-frontend、以及冲突时的裁决顺序与排除清单。
whenToUse: 在本仓库修改 frontend/edu-system-client 下任何文件、拆分或新增 Vue 组件、调整样式或布局、做前端代码审核，或在同一任务中准备加载多个前端技能时。
---

# EducationSystem 前端技能路由与仲裁

本仓库同时存在**栈无关技能**与**带技术栈假设的技能**。后者会把另一套技术栈的规则投影到本项目上。
完整版（含逐条行号证据与维护约定）：`docs/技能使用规范.md`。本技能是其可执行摘要。

## 1. 先做加载决策（最容易被忽视的风险）

`design-taste-frontend` 的触发条件是「任何前端/UI 工作」，在本项目做**每一次**前端改动它都会生效。
**未被察觉的隐性触发比显式冲突更危险。** 按任务类型决定加载：

| 任务类型 | 加载 |
|---|---|
| 纯后端（Java / MyBatis / SQL / agent 循环） | **不加载任何前端技能** |
| Vue 纯逻辑、类型、数据流、组件拆分 | 仅 `vue-best-practices` |
| 明确的视觉/美化/新界面 | `vue-best-practices` + `design-taste-frontend`（按下节仲裁） |
| 前端代码审核 | `fe-ui-ts-linter`（不要用上两者重复做同一件事） |

## 2. 职责切分

**代码维度 → `vue-best-practices` 独家权威**（`design-taste-frontend` 无权干预）：
组件拆分触发条件、入口/路由视图保持为组合面、props down / events up、`v-model` 边界、
`provide/inject` 仅用于深层共享、composable 抽取时机、SFC 节序（`<script>`→`<template>`→`<style>`）、
响应式最小化（`ref`/`reactive` 存源状态，其余 `computed` 派生）、`defineProps`/`defineEmits` 类型契约、
内置组件的选用时机、性能优化只在功能正确之后做。

**视觉维度 → `design-taste-frontend` 仅裁取可用部分**：

- ✅ 可迁移采用：§5 硬件加速（只动 `transform`/`opacity`）与 z-index 克制；§3 Rule 5 交互四态（loading/empty/error/触觉反馈）；§3 Rule 6 表单与数据模式（label 在上、错误在下、统一 `gap-2`）；§3 Rule 1/2 的排版与色彩校准**思路**；§7 避免 AI 套路化（假数据、套话文案）；§6 移动端强制单列塌陷。
- ❌ 明确排除：§2 全部栈约束（React/Next.js/RSC/`'use client'`、Tailwind 覆盖 90% 样式、图标必须用 `@phosphor-icons/react`）；§4 与 §9 的 Framer Motion 全套与「每张卡片必须有无限循环动画」；§3 Rule 2「紫色/蓝色审美禁用」（与主色 `#165DFF` 冲突）；§7「禁 3 列等宽卡片布局」。
- ⚠️ `ANTI-EMOJI POLICY`（§2，CRITICAL 级）与现有实现冲突：后端 `AiChatService` 的 SSE 状态文案用 `🔄`/`⏸`/`⚠️`。**若要执行，必须作为一次独立重构同时改前后端所有用户可见文案，严禁在任何局部任务中顺手执行。**

## 3. 冲突裁决顺序

1. **项目现状优先**：`frontend/edu-system-client/package.json` 与既有代码风格是事实来源。任何技能都不得据其自身假设引入新依赖或替换技术栈。
2. 代码问题 → 听 `vue-best-practices`。
3. 视觉问题 → `design-taste-frontend` 降级为「可选审美建议」，且不改变技术栈；与既有视觉体系冲突时维持既有体系。
4. 两者正交时 → 同时采纳。
5. 无法裁决时 → 在回复中显式说明分歧点与取舍理由交用户决定，**不得静默选一边**。

## 4. 本项目栈事实（技能不得违反）

Vue 3.5 + Vite 8 + TypeScript strict（`vue-tsc --build`）；`<script setup lang="ts">` + Composition API；
Element Plus 2.13 + `@element-plus/icons-vue`；Pinia 3；`<style scoped>`；主色 `#165DFF`；
依赖管理用 **npm**（以 `package-lock.json` 为准，**勿用 pnpm**）；测试用 Vitest 4 + Playwright 1.58。

## 5. 维护约定

不要直接编辑 `~\.dsh\skills\` 下第三方技能的 `SKILL.md`——它们是 vendored 产物（带 `.skillhub/metadata.json` 与逐文件 sha256，`vue-best-practices` 另有 `SYNC.md` 记录上游 git SHA），`skillhub upgrade` 会覆盖本地修改。
项目级约束一律写进 `docs/技能使用规范.md` 与本文件；技能升级后回看第 2 节的排除清单是否仍成立。
