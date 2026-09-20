# EducationSystem → Agent 项目升级计划

> 目标：把「教务管理系统 + 一个会调工具的聊天助手」，升级成一个**可讲、可测、可观测、有记忆、有知识、有安全边界的 Production-grade Agent 项目**，作为 Java 后端 → Agent 开发转型的主力作品。
>
> 文档定位：实施计划 + 学习路线（每个阶段都写清"做什么 / 验收标准 / 学到什么 / 面试怎么讲"）。
> 关联文档：`docs/AI模块架构文档.md`（现状设计）、`docs/开发记录.md`（改动日志，升级过程按追加式继续记录）。

---

## 进度追踪

> 本节随实施更新，作为「计划 vs 实际」的对照入口。逐项细节见 `docs/开发记录.md`。

| 阶段 | 任务 | 状态 | 验证方式 |
|---|---|---|---|
| 0.1 | 工具白名单双校验（按角色拒绝越权/未知工具） | ✅ 完成 | 单元测试 + 真实 HTTP |
| 0.2 | 参数 JSON Schema 校验（自研轻量子集校验器） | ✅ 完成 | 单元测试 13 项（含"21 个工具不被误伤"） |
| 0.3 | 危险操作 HITL 确认（挂起→确认→恢复） | ✅ 完成 | 单元测试 + 真实 HTTP |
| 0.4 | 确认令牌服务端持有（前端只回传 confirmId） | ✅ 完成 | 跨用户确认实测被拒（410） |
| 0.5 | `ai_tool_audit` 审计表 + 四类状态落库 | ✅ 完成 | 真实 MySQL 集成测试 |
| 0.6 | 幂等保护（以 confirmId 为幂等键） | ✅ 完成 | 真实 MySQL：成功记录可复用、失败不拦重试 |
| 0.7 | 错误脱敏（工具异常不再回灌模型） | ✅ 完成 | 单元测试断言不含内部细节 |
| 0.8 | 限流与预算（对话频次 + 工具调用上限，`maxIterations` 可配） | ✅ 完成 | 真实 HTTP：12 次 → 10 通过 2 拒绝 |
| 1 | 框架化迁移（Spring AI + 多轮记忆） | ⬜ 未开始 | —— |
| 2–5 | RAG / 能力进阶 / 评测 / 收尾 | ⬜ 未开始 | —— |

**阶段 0（安全底座）已全部完成：8/8。** 后端单测 39 项 + 端到端安全断言 16 项，全绿。

**当前验证缺口**：云 `AI_API_KEY` 未配置，「agent 循环 → 工具执行 → 审计落库」的完整链路
尚未经真实 LLM 端到端验证；已完成的证据是单元/集成测试（真实 MySQL + 真实 Redis）与真实 HTTP 安全断言。
其中参数校验与限流两条链路不需要模型即可验证，已充分覆盖。
**待办**：用本地 Ollama（`qwen2.5:7b`，见第 1.1 节）补一次真实对话验证 —— 已具备条件，无需云 Key。

**已知既有缺陷（未修）**：`AiChatService.sendSse()` 只捕获 `IOException`，
客户端中途断开时 `SseEmitter.send()` 抛的 `IllegalStateException` 会冒泡成误导性的
`AI chat processing error` 日志。已核对非本次引入，修法简单（catch 一并捕获）。

---

## 接续开发指南（每次继续本项目先读这一节）

> 本节为「下次开工」而写：环境怎么起、怎么验证、哪些事在等用户确认。
> 更新到 2026-09-19，阶段 0 完成并已推送 `origin/main`。

### 0. 快速定位

| 想了解 | 看哪里 |
|---|---|
| 计划本身、里程碑与验收标准 | 本文档第三～七节 |
| 已经做了什么、踩过什么坑 | `docs/开发记录.md`（追加式，倒序） |
| 前端该按什么规范写 | `docs/技能使用规范.md` + 项目级技能 `.dsh/skills/edu-frontend-rules.md` |
| 现有 AI 模块怎么运作 | `docs/AI模块架构文档.md`（注意：**尚未更新到阶段 0 之后的状态**） |

### 1. 环境启动（本机 Windows）

本项目 Redis 是**硬依赖**（登录 token 校验也走 Redis），不启动则登录即失败。

```powershell
# ① Redis（无则从 D:\Redis\5.0.14.1 启动）
D:\Redis\5.0.14.1\redis-server.exe .dsh\redis-dev.conf

# ② 后端：离线环境注意——`mvn install` 不可用（缺 maven-install-plugin 依赖），
#    必须先 package 出 fat jar 再用 java -jar 启动
cd backend\edu-system-server
mvn -o -B package -DskipTests
java -jar edu-api\target\edu-api-0.0.1-SNAPSHOT.jar
# ⚠️ 重建前必须先停掉正在运行的后端：Windows 文件锁会让 repackage 失败

# ③ 前端
cd frontend\edu-system-client
npm run dev        # 开发（端口 5173，代理到 8080）
npm run type-check # 类型检查
```

外部依赖现状：MySQL 8.4.7（库 `edujwxt`，10 张表 + 种子数据 + 安全约束齐全）、Redis 5.0.14.1。
**云 `AI_API_KEY` 未配置**，因此 `/ai/chat` 只能验证到"明确报错"这一层 —— 但**可用本地 Ollama 补上**，见下节。

### 1.1 用本地 Ollama 做真实 LLM 验证（已具备条件）

本机已装 Ollama（`C:\Users\53473\AppData\Local\Programs\Ollama\ollama.exe`，服务在 11434），现有模型：

| 模型 | 用途 |
|---|---|
| `qwen2.5:7b`（4.7GB） | **Agent 对话与工具调用验证**（补上"未过真实 LLM"的缺口） |
| `bge-m3`（1.2GB） | 嵌入模型，**M3 做 RAG 时可直接用**，无需再引入云 embedding |
| `nomic-embed-text`（274MB） | 嵌入模型备选 |

Ollama 提供 OpenAI 兼容端点，因此**不改代码**即可接入：

```powershell
$env:AI_BASE_URL = 'http://localhost:11434/v1'
$env:AI_MODEL    = 'qwen2.5:7b'
$env:AI_API_KEY  = 'ollama'     # 占位值，Ollama 不校验
cd backend\edu-system-server
java -jar edu-api\target\edu-api-0.0.1-SNAPSHOT.jar
```

**⚠️ 先确认再动手**：`OpenAiClient` 有一道前置校验——`apiKey` 为空/空白时**直接返回错误、根本不发请求**。
所以走 Ollama 必须给非空占位 key。若要让"本地模型无需 key"真正成立，需改 `OpenAiClient` 的校验逻辑
（按 base-url 判断，或加 `ai.allow-empty-key` 开关）。**这属于代码改动，未确认前不要做。**

> 用本地模型的额外好处：**零 API 成本**，可反复跑（M5 的 golden set 回归尤其需要）。
> 但 7B 模型的工具调用能力弱于云端大模型，适合验证**链路是否通**
> （事件协议、确认卡片、审计落库、幂等、限流），**不适合**作为工具选择准确率的基准。

### 2. 当前验证方式（改完代码跑这三样）

```powershell
# 后端单测（39 项；含真实 MySQL 审计落库与真实 Redis 限流）
cd backend\edu-system-server; mvn -o -B test -pl edu-api -am

# 端到端安全断言（16 项；需 Redis + 后端已启动）
.\.dsh\verify-m1.ps1

# 前端：类型检查 + linter 全量
cd frontend\edu-system-client; npm run type-check
node .dsh\lint-ai-view.cjs <某个.vue>     # 或遍历 22 个 .vue
```

**两条纪律**（都是踩过坑换来的，见开发记录）：
- 只要测试会写真实持久化存储（MySQL/Redis），**必须连续跑两次确认结果一致**——只跑一次绿过不算数。
- 测试失败**先确认外部依赖在线**（Redis 停止曾被误判为限流逻辑 bug）。

### 3. 阶段性成果（供讲解用，非路线图）

这些是已经**做出来并且验证过**的能力，不是计划：

- **工具调用安全边界的完整闭环**：21 个工具按角色白名单隔离 → 参数 Schema 校验 → 风险分级 →
  危险操作挂起等人工确认 → 确认令牌服务端持有 → 全程审计留痕 → 幂等保护 → 频次与调用量限流。
  这条链路上每一环都有测试，其中 6 条断言是通过真实 HTTP 打真实后端验证的。
- **审计表 `ai_tool_audit`**：四类以上状态、可变宽截断防御、失败不外抛但不静默。
- **可复用的验证脚本** `.dsh/verify-m1.ps1`：16 项断言，含跨用户确认被拒、限流边界精确命中。
- **仓库卫生**：`node_modules` 已移出版本库（已跟踪文件 12,143 → 170）。

### 4. 待用户确认的事项（不要在未确认时擅自处理）

| 事项 | 现状 | 需要用户决定什么 |
|---|---|---|
| `sendSse()` 既有缺陷 | 只捕获 `IOException`，客户端中途断开时 `IllegalStateException` 冒泡成误导日志。已核对非本次引入 | 是否现在修（修法简单：catch 一并捕获） |
| 前端 `frontend/.vscode/` | 未跟踪，`frontend/.gitignore` 已含 `.vscode/*` | 是否需要提交（通常不需要） |
| M2 拆分 `views/ai/index.vue` | 已达 752 行，按 `vue-best-practices` 的客观标准已构成 mega component | 是否在 M2 一并拆组件 |
| 真实 LLM 验证 | 缺 `AI_API_KEY` | 是否提供 Key 补一次真实对话验证 |

### 5. 下一步：M2 框架化迁移（计划下一阶段）

M2 目标（详见第三节阶段 1）与**开工入口建议**：
1. **先做最小的 Spring AI 接入验证**：新建 `edu-agent` 模块 → 接 `spring-ai-bom` → 迁移一个工具（如 `get_my_courses`）
   到 `@Tool` 形态 → 确认能跑通，**再**批量迁移其余 20 个。不要一次性改完 21 个工具。
2. **`ChatMemory` + Redis 多轮记忆**：当前每轮只构造 `[system, user]` 两条消息、没有 sessionId，
   这是与"真 Agent"差距最大的一项（见第一节差距表）。
3. **会话管理 API + 前端会话侧栏**，同时按 `vue-best-practices` 拆分 `views/ai/index.vue`。
4. **注意**：引入 Spring AI 后 `AiChatService` 的手写循环与 `OpenAiClient` 会逐步被替代，
   迁移期间应保留旧实现一个版本周期作为回归对照（阶段 1 计划里已写明）。

**动手前先读**：`docs/技能使用规范.md`（若涉及前端）、本节第 2 节的三条验证方式。

---

## 一、现状盘点

### 1.1 已有资产（不要推倒重来）

| 资产 | 位置 | 价值 |
|---|---|---|
| 完整业务域 | `edu-api/.../controller`、`service`、`mapper`（10 张表：学院/专业/班级/学生/教师/课程/选课/成绩/评价/用户） | Agent 的"手脚"齐备，不用造业务 |
| 手写 Agent 循环 | `AiChatService.processChat()` | 已经理解 ReAct/Tool-Calling 循环本质，迁移只是换实现 |
| 工具注册体系 | `ToolRegistry` + 3 个 `*ToolRegistrar`（学生 8 / 教师 5 / 管理员 8 = 21 个工具） | 工具层的雏形，可平滑转成 Spring AI 的 `@Tool` |
| 系统提示词分角色 | `AiChatService` 三个 `SYSTEM_PROMPT_*` | 已有"角色人设 + 行为规则"的意识 |
| SSE 流式链路 | `OpenAiClient` → `AiController` → `views/ai/index.vue` | 流式 + 打字机 + Markdown 渲染 + DOMPurify |
| 安全底座 | JWT 鉴权、角色拦截器、IDOR 归属校验、选课并发锁 | Agent 工具的越权防线已有一半 |
| 部署 | Dockerfile ×2、nginx（SSE 关缓冲）、docker-compose | 演示环境现成 |

代码量：后端 ~4000 行 Java，前端 ~3600 行（不含 node_modules）。

### 1.2 与"真 Agent"的差距（本计划的靶子）

按严重程度排序：

| # | 差距 | 现状证据 | 后果 |
|---|---|---|---|
| 1 | **没有多轮记忆** | `processChat()` 每次只构造 `[system, user]` 两条消息，无 sessionId | 无法追问"那第二门呢？"，无法做会话列表，体验断崖 |
| 2 | **破坏性操作无人工确认（HITL）** | 提示词写了"选课前务必确认"，但 `select_course` 一旦被调用就直接写库 | 提示词不是安全边界，模型一次幻觉 = 一次越权落库 |
| 3 | **无写操作审计 / 幂等** | 工具执行结果只进消息列表，无落库记录 | 出事无法追溯；重试即重复选课 |
| 4 | **无评测体系** | 0 个 AI 相关测试（`src/test` 只有一个 `testBcrypt`） | 改提示词/换模型全靠手点，无法证明"没退化"——这是简历最大短板 |
| 5 | **无可观测性** | MyBatis `StdOutImpl` 打印 SQL；无 trace / token 统计 | 无法回答"哪一步慢、花了多少钱、失败率多少" |
| 6 | **无 RAG** | 教务政策（学分要求、重修规则、成绩管理办法）全靠模型常识瞎编 | 事实性幻觉；也没有"引用来源"这个加分项 |
| 7 | **无 MCP** | 工具只能被自家前端调用 | 错过 2026 年最热的 Agent 协议话题，扩展性差 |
| 8 | **无输入/输出护栏** | 工具名不校验白名单；`result = "执行失败: " + e.getMessage()` 把内部异常喂回模型并可能透出 | 提示注入 / 信息泄露面 |
| 9 | **无成本与限流控制** | 无 token 计数、无速率限制、`MAX_ITERATIONS=10` 是唯一闸门 | 一个循环刷爆 API 额度 |
| 10 | **手写 HTTP 客户端维护成本高** | `OpenAiClient` 260 行手工解析 SSE delta、拼装 tool_calls | 换模型/换厂商/加多模态要自己改，重复造轮子 |

**一句话结论**：现在的形态是「**一次性的 Tool-Calling Demo**」——能演示，但缺少 Agent 系统工程的全部四个支柱：**记忆、安全、评测、可观测**。本计划就是把这四个支柱补上，并把底层换成框架（不再手写协议解析）。

---

## 二、目标架构（To-Be）

```
┌──────────────────────────────────────────────────────────────────────┐
│ 前端 Vue 3  (views/ai/)                                              │
│  会话列表 · 流式消息 · 工具调用轨迹(Trace) · 危险操作确认卡片 · 引用来源 │
└───────────────────────────┬──────────────────────────────────────────┘
                            │ SSE（事件协议扩展：token/status/confirm/trace/citation/done）
┌───────────────────────────▼──────────────────────────────────────────┐
│ edu-agent 模块（新）                                                  │
│                                                                      │
│  ┌── REST 层 ─────────────────────────────────────────────────────┐  │
│  │ AgentChatController  会话 CRUD、SSE 对话、确认/取消、Trace 查询 │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌── Agent 核心 ──────────────────────────────────────────────────┐  │
│  │ AgentRuntime     ReAct 循环 + 迭代上限 + 超时 + 取消            │  │
│  │ Planner          复杂任务先出计划再执行（Plan-and-Execute）      │  │
│  │ Router           按意图路由到子 Agent（多智能体协作）            │  │
│  │ ConfirmationGate 危险工具挂起 → 等待用户确认 → 恢复执行          │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  ┌── 记忆 Memory ─────┐ ┌── 知识 RAG ────────┐ ┌── 工具 Tools ────┐  │
│  │ ChatMemory(Redis)  │ │ ETL → VectorStore  │ │ @Tool 21+ 个     │  │
│  │ 摘要压缩 + 窗口     │ │ 检索 + 元数据过滤   │ │ ToolCallback     │  │
│  │ 长期记忆(偏好/画像) │ │ 引用来源回填        │ │ MCP Server/Client│  │
│  └────────────────────┘ └────────────────────┘ └──────────────────┘  │
│                                                                      │
│  ┌── 护栏 Guardrail ──┐ ┌── 可观测 O11y ─────┐ ┌── 评测 Eval ─────┐  │
│  │ 工具白名单(按角色)  │ │ Micrometer 指标     │ │ Golden Set 回归  │  │
│  │ 参数 Schema 校验    │ │ OTel 链路追踪       │ │ LLM-as-Judge     │  │
│  │ 注入防护 + 输出脱敏 │ │ Token/成本 计量     │ │ 工具选择准确率   │  │
│  └────────────────────┘ └────────────────────┘ └──────────────────┘  │
│                                                                      │
│  基础设施：Spring AI ChatClient/ChatModel · Advisor 链 · Redis · MySQL │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ 复用现有 Service / Mapper（业务不动）
                    ┌──────▼───────┐
                    │ MySQL + Redis │
                    └───────────────┘
```

### 2.1 关键技术选型

| 决策点 | 选择 | 理由 | 备选 |
|---|---|---|---|
| Agent 框架 | **Spring AI**（`spring-ai-bom` 统管版本，优先 1.1.x 稳定线，2.0 已 GA 可评估） | 与现有 Spring Boot 3.5 / 依赖注入 / MyBatis 无缝；中文简历上"Spring AI"信号最强；自带 `@Tool`、`ChatMemory`、`Advisor`、`VectorStore`、MCP、可观测 | LangChain4j 1.19.x（`@Tool` + `AiServices` 声明式接口更简洁，`langchain4j-mcp` 亦可）——可作为**对照实现**或第二阶段替换项 |
| 模型接入 | 继续走 **OpenAI 兼容协议**（`spring-ai-starter-model-openai` 指向 DeepSeek `base-url`），配 Ollama 本地模型兜底 | 现有 `AI_BASE_URL/AI_API_KEY/AI_MODEL` 配置与 Docker 环境变量零改动 | 阿里 `spring-ai-alibaba`（DashScope） |
| 向量库 | 起步 **Redis Stack / pgvector**，规模化再上 Milvus | Redis 已在 compose 里，零新增中间件 | Milvus / Qdrant |
| 前端协议 | **保持 SSE 不变，只扩展事件类型** | 现有 `index.vue` 的解析骨架可复用，改动集中在事件分支 | WebSocket（暂不需要双向） |

> 说明：框架版本号以 `spring-ai-bom` 为准，不手写死版本；引入前用 `mvn dependency:tree` 核对 starter 名称（Spring AI 1.0 GA 后 starter 命名做过一次调整）。

---

## 三、分阶段实施计划

每阶段都是**独立可交付 + 可验收**，做完就能写进简历；即使中途停下，项目也是完整可跑的。

### 阶段 0：安全底座 + 审计（1～2 周）——先扎篱笆再上智能

> 为什么最先做：Agent 的破坏力 = 工具能力 × 自主性。工具会写库，就先要能刹车、能追溯。这部分也最容易在面试里讲出"工程判断力"。

| 任务 | 实现要点 | 产出文件 |
|---|---|---|
| 0.1 工具白名单双校验 ✅ | 执行前校验 `toolDef` 存在且**属于当前角色**（现有 `getTool(name)` 是全局查，学生理论上能触发教师工具名），未知工具直接拒绝并记审计 | `ToolRegistry.executeForRole(...)` |
| 0.2 参数 Schema 校验 ✅ | 自研轻量校验器（离线环境无法引入 `networknt/json-schema-validator`）：覆盖 object/string/integer/number/boolean/array + required/enum/min-max/minLength/maxLength/minItems/maxItems/items；失败回给模型可读错误让它重试 | `ToolArgumentValidator`、`JsonSchemaToolArgumentValidator` |
| 0.3 危险操作 HITL 确认 ✅ | `ToolDefinition` 增加 `RiskLevel { READ_ONLY, WRITE, DANGEROUS }`；DANGEROUS（选课/退课/录成绩/改成绩/评价）**不直接执行**，挂起为待确认请求，SSE 推 `confirm` 事件带 `confirmId + 工具名 + 参数摘要`；前端渲染确认卡片 → 用户点确认 → 携带 `confirmId` 继续执行 | `ConfirmationGate`、`PendingActionStore`(Redis, TTL 5min)、前端确认卡片组件 |
| 0.4 确认令牌服务端持有 ✅ | 前端只回传 `confirmId`，**绝不回传工具名/参数**（否则等于给了前端任意工具调用权）；服务端取出暂存参数执行 | 同上 |
| 0.5 审计日志 ✅ | 新表 `ai_tool_audit`：`id, user_id, role, session_id, tool_name, risk_level, args_json, result_json, status(SUCCESS/FAILED/DENIED/REJECTED_BY_USER), error_msg, confirm_id, request_id, duration_ms, created_at` | `docs/sql/2026-09-19-ai-audit-migration.sql`、`AiAuditService` |
| 0.6 幂等保护 ✅ | 以 confirmId 为幂等键：执行前查 `findSuccessfulByRequestId`，命中则复用既有结果不重复写库；只认 SUCCESS（失败/拒绝不拦重试）。范围限于经人工确认的写操作，直连写工具由业务层唯一约束兜底 | `ai_tool_audit.request_id` + `uk_ai_audit_request` |
| 0.7 错误脱敏 ✅ | 工具异常只回 `{"error":"操作失败，请稍后重试"}`，细节进日志与审计表；不再 `e.getMessage()` 回灌模型 | `ToolExecutionResult.failed(...)` |
| 0.8 限流与预算 ✅ | Redis 固定窗口：单用户 10 次对话/分钟 + 单次对话 20 次工具调用；`maxIterations` 由硬编码 10 改为配置（默认 5） | `AgentRateLimiter`、`ai.limits.*` 配置项 |

**验收标准**
1. 学生账号尝试触发 `enter_score`（构造越权参数/注入指令）→ 被拒 + 审计表出现 `DENIED` 记录。
2. 说"帮我选课 5"→ 前端出现确认卡片 → 点取消 → **数据库无写入**；点确认 → 写入且审计表有记录。
3. 同一 `confirmId` 重复提交两次 → 只落库一次。
4. 工具抛异常时返回给模型的 JSON 中不含 SQL / 堆栈 / 表名。

**学到什么 / 面试怎么讲**
- 「Agent 的安全边界必须落在**工具执行层**，不能依赖 System Prompt」——这是 2026 年 Agent 面试高频题，可结合本项目 0.3/0.4 讲。
- Human-in-the-Loop 的设计要点：挂起/恢复、令牌不可伪造、幂等、超时清理。

---

### 阶段 1：框架化迁移（2～3 周）——从"手写协议"到"标准 Agent 骨架"

| 任务 | 实现要点 | 产出 |
|---|---|---|
| 1.1 新建 `edu-agent` 模块 | Maven 加 `edu-agent`（依赖 `edu-api` 的 Service/Mapper），AI 相关代码全部迁入；`edu-api` 只留业务 | `edu-agent/pom.xml` |
| 1.2 接入 Spring AI | `spring-ai-bom` + `spring-ai-starter-model-openai` 指向 DeepSeek；`ChatClient` Bean 统一构建；保留 `OpenAiClient` 一个版本周期作为回归对照，之后删除 | `AgentConfig` |
| 1.3 工具改造 | 21 个工具由 Lambda 改为**声明式 `@Tool` 方法**（`description` 写清"何时用/何时不用"），按角色拆成工具类：`StudentTools/TeacherTools/AdminTools`；入参用 record/DTO 让框架自动生成 JSON Schema | `tool/*.java` |
| 1.4 Agent 循环 | 用框架的 tool-calling 循环（或自写 `AgentRuntime` 只保留编排：迭代上限、超时、取消、事件回调）；`SseEmitter` 抽象为 `AgentEventPublisher`，事件类型枚举化 | `AgentRuntime`、`AgentEvent` |
| 1.5 多轮记忆 | `ChatMemory` + Redis 持久化（`MessageWindowChatMemory(maxMessages=20)`，超出走**摘要压缩**）；`sessionId = userId + conversationId`；敏感上下文（如成绩明细）不进长期记忆 | `MemoryConfig`、`conversation`/`message` 表 |
| 1.6 会话管理 API | `GET/POST/DELETE /ai/conversations`、`GET /ai/conversations/{id}/messages`；支持"新建会话/切换/重命名/删除" | `AgentChatController` |
| 1.7 前端会话侧栏 | 左侧会话列表 + 新建 + 删除；`?conversationId=` 路由参数；历史消息回填 | `views/ai/` 拆分组件 |
| 1.8 结构化系统提示 | 提示词外置到 `src/main/resources/prompts/*.st`（可配、可测、可按版本 diff），`{角色} + {当前日期} + {可用工具摘要} + {行为规则}` 模板化 | `prompts/` |
| 1.9 流式健壮性 | 断线重连（前端带 `lastEventId` 重试）、超时可配、`onError` 不再静默吞异常 | `AgentEventPublisher`、前端 |

**验收标准**
1. 功能回归：21 个工具全部可用，行为与升级前一致（用阶段 5 的回归集反向验证）。
2. 多轮：`我有哪些课？` → `第2门课有多少学分？` → `退掉它`（确认后）能正确指代。
3. 会话隔离：A 用户拿 B 的 `conversationId` 请求 → 403。
4. 死代码零残留：`OpenAiClient` 删除后项目仍可 `mvn package`。

**学到什么 / 面试怎么讲**
- 「我手写过 OpenAI 兼容 SSE 与 tool_calls 增量拼接，也用过 Spring AI 的 Advisor/ChatMemory」——**先手写再框架**是很有说服力的叙事，说明理解底层而不只是调包。
- `@Tool` 与传统 `ToolDefinition` 的取舍：声明式换可维护性，代价是动态注册（按角色/租户开关工具）需要额外手段。

---

### 阶段 2：知识与长期记忆（2～3 周）——从"模型常识"到"有据可依"

| 任务 | 实现要点 |
|---|---|
| 2.1 知识语料 | 编写/收集 8～15 篇 Markdown：学籍管理规定、选课与退课规则、重修/补考办法、毕业学分要求（按专业）、成绩构成与绩点换算、评教规则、教师成绩录入规范、常见问题 FAQ |
| 2.2 ETL + 向量化 | `DocumentReader → TokenTextSplitter(按标题切分 + overlap) → 元数据{文档类型, 学院, 专业, 年级} → VectorStore`；提供 `/admin/ai/knowledge/reindex` 重建索引接口 |
| 2.3 检索增强 | `QuestionAnswerAdvisor`（或自写 RAG Advisor）：向量检索 + `topK` + `similarityThreshold` + 元数据过滤（学生只召回自己专业相关文档） |
| 2.4 引用来源回填 | 把命中的 chunk 元数据（文档名 + 段落）通过 SSE `citation` 事件回给前端，前端在回答下方渲染「来源」 |
| 2.5 长期记忆/画像 | 结构化提取用户偏好与事实（`学生 2023001 上学期挂过《数据结构》`、`偏好表格输出`）存 MySQL，每轮按需注入；提供"忘记我"接口（合规意识） |
| 2.6 混合检索（进阶） | 向量 + 关键词（MySQL 全文/BM25）混合 + RRF 融合，解决"专有名词精确匹配" |
| 2.7 幻觉抑制 | 工具结果 > 文档 > 模型常识 的优先级写进提示词；无依据时明确说"文档中没有相关规定" |

**验收标准**
1. `毕业需要多少个学分？` 回答引用《XX专业培养方案》且答案与文档一致（不是模型编的）。
2. 问一个文档里没有的规则 → 回答"未查到相关规定"，**不编造**。
3. 跨专业提问 → 不召回无关专业文档（元数据过滤生效）。

**学到什么 / 面试怎么讲**
- RAG 全链路：切分策略、embedding 选型、召回率 vs 精确率、chunk 元数据设计、引用回填。
- 「业务系统的 Agent 不只是聊天，而是**有企业私有知识的问答 + 可执行动作**」——这是 To-B Agent 的标准形态。

---

### 阶段 3：Agent 能力进阶（3～4 周）——规划、协作、MCP

按投入产出排序，**至少完成 3.1 + 3.3**。

| 任务 | 实现要点 |
|---|---|
| 3.1 Plan-and-Execute（做） | 复杂请求（"帮我看看学分够不够毕业，不够的话推荐几门能选的课"）先由 Planner 产出结构化步骤（JSON Schema 约束），再逐步执行；每步结果回填；失败可重规划。对比 ReAct 讲清楚延迟/成本/可控性权衡 |
| 3.2 多智能体（做） | `Supervisor` 按意图路由到子 Agent：`教务Agent`（选课/课表）、`成绩Agent`（查询/统计/趋势）、`学籍Agent`（学籍/毕业审核）、`咨询Agent`（RAG 问答）；子 Agent 各持最小工具集（**权限最小化**天然更安全）；用事件流展示"谁在处理" |
| 3.3 MCP（做，性价比最高） | ① **MCP Server**：把 21 个工具经 `spring-ai-starter-mcp-server` 暴露，让 Claude Desktop / Cursor 等外部宿主直接操作教务系统（演示效果极强）；② **MCP Client**：接 1～2 个外部 MCP Server（如日历、文件系统）做扩展 |
| 3.4 反思与自纠（选做） | 工具报错后自动修正参数重试（最多 2 次）；最终回答前自检"是否回答了用户的问题、是否与工具结果矛盾" |
| 3.5 长任务与流式思考（选做） | 展示模型的 reasoning_content（推理过程折叠面板）；长任务异步化 + 进度推送 |
| 3.6 多模态（选做） | 上传成绩单图片/PDF → 模型识别 → 批量录入成绩（教师场景），演示效果好但要控成本 |

**验收标准**
1. 一个复合请求能产出可见的执行计划，并按计划完成（Trace 面板可看到每一步）。
2. 外部 MCP 宿主（Claude Desktop 或 MCP Inspector）能列出并调用教务工具，且**越权调用被 MCP Server 侧拒绝**。
3. 至少 2 个子 Agent 生效，路由准确率 ≥ 90%（用阶段 5 的数据集度量）。

**学到什么 / 面试怎么讲**
- ReAct vs Plan-and-Execute vs 多智能体的**适用边界**（别为了炫技上多智能体）。
- MCP 的协议本质（Server/Client/Transport/Resources/Tools/Prompts）、为什么它把"工具集成"标准化了。

---

### 阶段 4：评测与可观测（2～3 周）——让"效果好"变成数字

> 这一阶段是**简历含金量最高**的部分：绝大多数候选人的 Agent 项目没有评测。

| 任务 | 实现要点 |
|---|---|
| 4.1 黄金数据集 | `src/test/resources/eval/golden-set.jsonl`，50～100 条：`{用户输入, 期望工具, 期望参数, 期望结果要点, 是否多轮}`，覆盖三角色 + 正常/边界/恶意输入 |
| 4.2 工具调用评测 | 断言"是否选中正确工具 / 参数是否正确"，输出**准确率**报告（不调真实模型时用录制回放） |
| 4.3 端到端评测 | 真实模型跑数据集：任务完成率、平均轮数、幻觉率（用 LLM-as-Judge 打分，judge 用另一个模型 + 固定 rubric） |
| 4.4 RAG 评测 | 命中率 / MRR / 忠实度（faithfulness，回答是否被检索内容支撑） |
| 4.5 单元测试 | `@MockBean ChatModel` 断言工具循环（无工具调用→直接结束；有→执行→回灌；超限→中断）；`ConfirmationGate` 挂起-恢复；越权拒绝 |
| 4.6 集成测试 | Testcontainers（MySQL + Redis）跑真实链路；WireMock 回放 LLM 响应保证 CI 稳定（**不真调模型**） |
| 4.7 回归门禁 | `mvn verify` 触发评测，工具选择准确率 < 阈值（如 95%）则失败；提示词/模型变更必须过门禁 |
| 4.8 可观测 | Micrometer 指标：每轮 token 数、工具调用次数/失败率、各工具 P95 耗时、LLM 首字延迟(TTFT)；OTel 导出 trace（`agent.turn / llm.call / tool.execute` 三层 span）；成本看板（按用户/会话聚合 token × 单价） |
| 4.9 CI | GitHub Actions：`compile → test → eval(WireMock) → package` |

**验收标准**
1. `mvn verify` 输出评测报告（准确率 / 完成率 / 平均轮数 / 耗时 / 成本）。
2. 故意把某个工具 description 改坏 → 门禁**失败**（证明评测真的有效）。
3. 能回答"上周工具调用失败率多少、平均每次对话多少钱、P95 延迟多少"。

**学到什么 / 面试怎么讲**
- 「我做了 Agent 评测体系：golden set + 工具选择准确率 + LLM-as-Judge + CI 门禁，准确率从 x% 提到 y%」——直接可复用的 STAR 素材。
- 能区分**确定性测试**（mock 模型）与**非确定性评测**（真实模型打分）的边界。

---

### 阶段 5：工程收尾与对外展示（1～2 周）

| 任务 | 实现要点 |
|---|---|
| 5.1 文档升级 | 重写 `docs/AI模块架构文档.md` → `docs/Agent架构文档.md`（含时序图、事件协议、记忆/RAG/评测设计）；`README.md` 增补 Agent 能力矩阵与架构图 |
| 5.2 开发记录 | 按追加式在 `docs/开发记录.md` 顶部补本次升级各批次的改动 + 验证结果 |
| 5.3 演示脚本 | 3 分钟演示动线：多轮对话 → 危险操作确认 → MCP 外部调用 → RAG 引用 → Trace 面板 → 评测报告 |
| 5.4 性能与稳定性 | 并发压测（多个 SSE 会话）、Redis 断连降级、模型超时降级到"稍后再试"、长会话内存占用 |
| 5.5 简历与面试包 | 一句话项目定位 + 架构图 + 5 个可深挖的技术点（HITL 安全、记忆压缩、RAG 元数据、MCP、评测门禁）+ 常见追问答案 |

**验收标准**：陌生人照着 README 能在 10 分钟内跑起来，并复现演示动线。

---

## 四、里程碑与工作量

| 里程碑 | 阶段 | 预估 | 标志性产出 | 简历可写的一句话 |
|---|---|---|---|---|
| M1 安全版 | 0 | 1–2 周 | HITL 确认 + 审计表 + 限流 | 为 Agent 设计工具级安全边界与 HITL 确认机制，写操作 100% 可审计 |
| M2 框架版 | 1 | 2–3 周 | Spring AI + 多轮记忆 + 会话管理 | 基于 Spring AI 实现角色化 Agent，Redis 记忆窗口 + 摘要压缩 |
| M3 知识版 | 2 | 2–3 周 | RAG + 引用 + 长期记忆 | 构建教务知识库 RAG（元数据过滤 + 引用回填），事实性问答准确率 X% |
| M4 能力版 | 3 | 3–4 周 | 规划 + 多智能体 + MCP | 实现 Supervisor 多智能体协作，并以 MCP Server 对外暴露 21 个教务工具 |
| M5 评测版 | 4 | 2–3 周 | 数据集 + 门禁 + 可观测 | 建立 80 条 golden set 评测体系与 CI 门禁，工具选择准确率 ≥95% |
| M6 展示版 | 5 | 1–2 周 | 文档 + 演示 + 简历包 | —— |

总计约 **3～4 个月**（业余时间按每周 10～15 小时估；若全职可压缩到 6～8 周）。

**建议节奏**：M1→M2→M5 先跑通一轮（约 6 周），拿到"有安全、有记忆、有评测"的最小可讲版本，再补 M3/M4。**不要**先做大而全的 RAG 再回头补测试。

---

## 五、前端改造要点

现有 `views/ai/index.vue` 571 行、单文件承载全部逻辑，建议拆分为：

```
views/ai/
├── index.vue                 # 布局 + 状态编排
├── components/
│   ├── ConversationList.vue  # 会话侧栏（新建/切换/重命名/删除）
│   ├── MessageList.vue       # 消息流 + 流式渲染
│   ├── MessageBubble.vue     # Markdown + DOMPurify + 引用来源
│   ├── ToolTrace.vue         # 工具调用轨迹（名称/参数/耗时/状态，可折叠）
│   ├── ConfirmCard.vue       # 危险操作确认卡片（确认/取消，倒计时）
│   └── Composer.vue          # 输入区（Shift+Enter、深度模式开关）
├── composables/
│   ├── useAgentStream.ts     # SSE 解析 + 事件分发 + 断线重连
│   └── useConversations.ts   # 会话 CRUD
└── types.ts                  # AgentEvent 联合类型（token|status|trace|confirm|citation|error|done）
```

关键约定：
- **事件协议集中定义**（`types.ts`），前后端同一份语义，避免 `if (type === ...)` 散落。
- SSE 令牌过期（`error`）时**不静默失败**，弹登录失效引导。
- 确认卡片必须能"取消"，且取消后模型能收到"用户拒绝"并给出替代方案（体验闭环）。

---

## 六、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 框架版本变动快（Spring AI 1.x/2.0、starter 命名调整） | 编译不过、返工 | 用 BOM 管版本；迁移前先跑通一个最小 demo（单工具 + 单轮），再批量改 21 个工具 |
| LLM 非确定性破坏测试 | CI 常年红 | 分两层：CI 用 WireMock/录制回放（确定性）；真实模型评测手动/夜间跑，产报告不卡门禁 |
| 提示注入（用户输入里藏指令，或 RAG 文档被投毒） | 越权写库 | 安全边界在工具层（阶段 0 白名单 + Schema + 确认）；检索内容标记为"不可信数据"；写操作永远要确认 |
| RAG 效果不达预期 | 演示翻车 | 先小语料（10 篇）跑通，做 bad case 分析（切分粒度/embedding/元数据）；准备"混合检索"备选 |
| Token 成本失控 | 钱包受伤 | 阶段 0 的预算闸门 + 阶段 4 的成本看板；开发期用本地 Ollama 或小模型 |
| 一人开发战线过长 | 半途而废 | 严格按里程碑切分，每个里程碑都是"可交付可演示"的完整状态；每完成一个里程碑立刻更新 README/架构文档 |
| 已有安全底座被破坏 | 回归风险 | 阶段 0 起就把"越权拒绝"写成自动化测试，后续每阶段跑一遍 |

---

## 七、转型能力映射（Java 后端 → Agent 工程师）

| 你已有的能力 | 在 Agent 领域的新用法 | 本计划中的落点 |
|---|---|---|
| Spring 生态 / 依赖注入 / 分布式工程 | Agent 编排、Advisor 链、多模块架构 | 阶段 1 |
| 事务、并发（`SELECT FOR UPDATE`）、唯一约束 | 工具执行的幂等与并发安全 | 阶段 0.5/0.6 |
| 鉴权、越权防护（IDOR）、拦截器 | 工具级权限最小化、HITL 确认 | 阶段 0、阶段 3.2 |
| 接口设计 / 契约思维 | SSE 事件协议、MCP 协议、结构化输出 Schema | 阶段 1.4、3.3 |
| 单元测试 / CI | Agent 评测体系、回归门禁 | 阶段 4 |
| 数据库与索引 | 记忆存储、会话表、审计表、向量元数据过滤 | 阶段 1.5、2.2 |
| SQL / MyBatis 调优经验 | 工具结果精简（省 token）、上下文裁剪 | 阶段 1.5、2.7 |

**需要补的新知识**（建议边做边学，别先啃书）
1. Prompt Engineering 工程化：结构化提示、few-shot、输出约束（JSON Schema）、CoT/ReAct
2. Embedding 与向量检索基础：相似度度量、chunk 策略、召回评估
3. Agent 范式：ReAct、Plan-and-Execute、Reflection、Multi-Agent、HITL
4. 协议与生态：MCP、OpenAI 兼容 API、A2A（了解即可）
5. 评测方法：golden set、LLM-as-Judge、RAGAS 指标
6. 可观测：OpenTelemetry GenAI 语义约定、token 计量

**参考资源**
- [Spring AI 版本发布说明（1.0.8 / 1.1.7 / 2.0.0-M7）](https://spring.io/blog/2026/05/23/spring-ai-1-0-8-1-1-7-2-0-0-M7-available-now)
- [Spring AI 2.0 GA 报道（Java AI Stack）](https://byteiota.com/spring-ai-2-0-ships-may-28-java-finally-has-a-real-ai-stack/)
- [LangChain4j 版本（1.19.x）与 `@Tool` 文档](https://newreleases.io/project/github/langchain4j/langchain4j/release/1.19.0)
- [Java AI 工程师成长路线图（Spring AI · RAG · Agent · MCP · LangGraph4j）](https://github.com/xiaomozhang/java-ai-engineer-roadmap)
- [Spring AI 与 LangChain4j 选型对比](https://cloud.tencent.cn/developer/article/2657478)

---

## 八、下一步（立刻可执行的三件事）

1. **确认路线**：是否按「M1 安全版 → M2 框架版 → M5 评测版」的主线推进（推荐）；以及框架选 **Spring AI**（推荐）还是 **LangChain4j**。
2. **执行阶段 0.1 + 0.3**：工具白名单双校验 + 危险操作确认（改动集中、当天可见效果，且立刻把项目从"Demo"抬到"有安全设计"）。
3. **建评测数据的骨架**：先把现有 21 个工具各写 2～3 条 golden 用例（哪怕先只有 40 条），后面每阶段都用它做回归。

> 落地时按现有惯例：改动同步写入 `docs/开发记录.md` 顶部；新增数据库变更放 `docs/sql/` 并在 `edujwxt.sql` 同步。
