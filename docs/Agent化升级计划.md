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

**阶段 0（安全底座）已全部完成：8/8。** 后端单测 **41 项** + 端到端安全断言 16 项，全绿。
并已用本地 Ollama `qwen2.5:7b` 完成**真实 LLM 端到端验证**（含业务数据变更与审计落库的双向核实）。

**验证缺口已闭合（2026-09-20）**：已用本地 Ollama `qwen2.5:7b` 跑通真实 LLM 端到端闭环
（模型发起工具调用 → 服务端挂起 → 确认 → 执行 → 审计落库 → 自然语言汇报），
业务数据与审计记录均已双向核实。验证脚本：`.dsh/verify-real-llm.cjs`。
**仍未验证**：`DUPLICATE_SKIPPED`（幂等命中）分支需构造并发才能命中。

> **M5「评测」的一部分已被 P5 提前落地**：`.dsh/eval-p5-tools.cjs` 现在能给出
> **工具选择准确率**（`ROUTING: n/10`）与逐条失败诊断，并在 `mvn test` 里有 6 项工具面治理断言。
> M5 剩下的部分是把评测扩到多轮场景与 LLM-as-Judge，届时直接在这套脚本上长，不要另起一套。

**已知既有缺陷（未修）**：`AiChatService.sendSse()` 只捕获 `IOException`，
客户端中途断开时 `SseEmitter.send()` 抛的 `IllegalStateException` 会冒泡成误导性的
`AI chat processing error` 日志。已核对非本次引入，修法简单（catch 一并捕获）。
（P5 顺带修掉了**同类**的另一处：`LoginInterceptor.reject()` 在响应已提交时硬写 JSON，
会把一次干净的拒绝变成 500 + 一屏堆栈；现在只记日志。见 `docs/开发记录.md` (十)。）

---

## 接续开发指南（每次继续本项目先读这一节）

> 本节为「下次开工」而写：环境怎么起、怎么验证、哪些事在等用户确认。
> 更新到 2026-09-22：Agent 主线 **M1 阶段 0 ✅** ＋**教务业务扩展 P1/P2/P3/P4/P5 ✅** ＋
> **M2 的 1.2/1.5/1.6 ✅**（Spring AI 接入、多轮记忆、会话 API），并已产出
> **M3 的 RAG 语料（`docs/policies/` 10 份制度文件）**，全部推送 `origin/main`。
> 下一步：**① 前端会话侧栏（1.7）→ ② `@Tool` 声明式迁移对照（1.3/1.4）**。

> ### ⏸ 暂停状态（2026-09-22 第七轮 = M2 多轮会话落地，下次直接从这里开始）
>
> - **主线位置**：**M2 进行中**。1.2（接入 Spring AI）✅、**1.5 多轮记忆 ✅、1.6 会话 API ✅、1.7 前端会话侧栏 ✅**、
>   **1.3 工具声明式迁移 🟡 已迁 3 个**、**1.4 Agent 循环 🟡 编排抽离完成（方案 B）**；
>   **剩 1.1（拆 `edu-agent` 模块）、1.3 的批量迁移、1.9（断线重连）**。
> - **代码状态**：会话/消息落 MySQL（`ai_conversation` + `ai_message`，迁移已执行）；
>   `POST /ai/chat` 支持可选 `conversationId`（无 id 时服务端兜底新建，SSE 回传 `conversation` 事件）；
>   AI 页已按 `vue-best-practices` 拆分（`index.vue` **752 → 324 行** + 3 个展示组件 + 2 个 composable），
>   侧栏支持列表/新建/切换/删除、`?conversationId=` 回填历史、越权会话提示后回落；
>   旧手写 `OpenAiClient`/`AiChatService` 仍是主链路（计划 1.4：保留一个版本周期作对照）。
> - **本轮修掉两个"测试全绿、真机在错"的 bug（务必知道，都是断言缺口造成的）**：
>   1. **同名工具跨角色互相覆盖**：学生与教师都有 `get_my_courses`，定义却存在一张全局 Map 里，
>      后注册者覆盖前者 → 学生白名单**通过**、执行的却是**教师实现**（拿学号当工号查课 → 空列表），
>      模型看到的描述也是教师版。真机表现："学生问'我选了什么课'，助手答'你没选任何课'"。
>      修法：定义改为 `Map<role, Map<name, ToolDefinition>>`，`getTool(role, name)` 必须带角色。
>      **教训：'工具被选中' ≠ '工具做对了事'**——工具面测试只看"在不在/角色对不对"，
>      评测只看 `ROUTING`，没有一处看过工具返回的数据。新增 `StudentCourseListToolTest` 补上这一层。
>   2. **逐字符流式下漏出落单 `</tool_call>`**：上一轮只把"半个**开**标签"扣住，
>      于是 `</`、`t`、`o`… 被逐段当普通文本发出去（整段喂入的用例测不出来）。
>      是"**落库正文**不得含工具调用残渣"这条新断言抓到的。修法：`partialTagIndex` 同时匹配开/闭标签前缀。
> - **M2 基线（迁移前对照，已存档）**：评测 `PASS=75 FAIL=0`、**`ROUTING 10/10`**（迁移前）；
>   ⚠️ 7B 模型选路**本身有波动**（同套用例出现过 10/10 与 8/10），迁移后对照要**多次跑**再下结论。
> - **本轮交付**：`MybatisChatMemory`（只追加完整记录 + 取最近 N 条作窗口）、`ConversationService(+Impl)`、
>   `AgentConversationController`（`/ai/conversations`）、`AiChatService` 的历史窗口与落库、
>   `.dsh/verify-m2-conversations.cjs`（**PASS=31 FAIL=0**，跑完不留数据）。
>   ⚠️ **刻意不用框架的 `MessageWindowChatMemory`**：它的 `saveAll` 语义是"替换整个会话的消息"，
>   落到 MySQL 要么丢历史、要么消息翻倍（字节码核对过，详见开发记录 **(十九)** 与架构文档第八节）。
> - **本轮两个环境坑（已解决，动手前先看）**：
>   1. **离线构建认领不了本地 Spring AI 制品**：本机 m2 里这批制品来源 id 是 `aliyun-maven`（镜像），
>      Maven `-o` 模式要求来源 id 在当前仓库列表里，否则报
>      `spring-ai-bom:pom:1.0.9 (present, but unavailable)`。
>      已在父 pom 显式声明同 id 仓库解决（**不要**去改 Maven 全局配置或本地仓库文件）。
>   2. **Spring AI 的传递依赖本地不全**（spring-retry / spring-webflux / micrometer-core…），
>      联网拉一次；本机沙箱不许写 `~/.m2` → **一次性提权**跑同一条构建命令即可，之后 `mvn -o` 正常。
> - **下一步（按顺序，可直接开工）**：
>   1. **批量迁移剩余工具到声明式**（1.3 后半）：建议顺序 —— 管理员挑 1 个（覆盖"审批"这类
>      带业务前置校验的语义）→ 教师 `enter_score`（覆盖"非本人课程拒绝"）→ 其余按角色批量。
>      新增一个 `DeclarativeToolGroup` Bean 即可，注册器不用改；
>      每迁一批跑 `node .dsh/eval-p5-tools.cjs --only=<迁移的工具>` 对照选路，
>      并核对 `/ai/tools` 里该工具的参数 **Schema 形状**与手写版一致
>      （见开发记录 (二十一) 坑 ②：描述可以写得更好，形状不能变）；
>   2. ~~1.4 事件契约与编排抽离~~（已完成，见开发记录 (二十二)）；
>   3. 可选：拆分 `edu-agent` 模块（1.1）；管理端"成绩变更日志"页面（接口已就绪）；
>      1.9 断线重连；把 `MessageBubble`/`ToolTrace`/`ConfirmCard` 从 `ChatMessageList` 里细分。
> - **动手前检查（本仓库踩过的，逐条照做）**：
>   1. 先起 Redis + 后端；**用受管后台任务起服务**（`Start-Process` 起的会随命令结束被杀）；
>      `Get-NetTCPConnection` 在本机沙箱里查不到监听端口 → 用 **`netstat -ano | findstr LISTENING`**；
>   2. `mvn -o -B test -pl edu-api -am` 应 **220 项**全绿（188 + M2 新增 32）；
>   3. **改了接口/工具/Mapper 就要先 `mvn -o -B package -DskipTests` 再起后端**（只跑 test 不重打包 =
>      拿旧 jar 测）→ 再 `node .dsh/verify-m2-conversations.cjs --no-llm`（会话链路，快）与
>      `node .dsh/eval-p5-tools.cjs --inventory-only`（39 项）；
>   4. **浏览器验证需要两次一次性提权**：`npm run dev`（Vite 的 `windowsSafeRealPathSync` 会
>      `exec('net use')`）与 `node .dsh/verify-m2-ui.cjs`（Playwright 用**命名管道**做 CDP），
>      在本机沙箱里都会 `spawn EPERM`——这是**沙箱边界，不是代码问题**，按规则一次性提权重跑同一条命令即可；
>      前端 `npm run type-check`、`oxlint` 不需要提权；
>   5. **脚本自己登录会顶掉 token**：界面登录会把 Redis 里 `token:<username>` 换成新值，
>      之前用 API 拿的 token 立刻失效（症状：后续接口返回 `code=401`、`data=null`）。
>      界面登录后要用 `page.evaluate(() => sessionStorage.getItem('token'))` 取**页面里那个** token；
>   6. **`.ps1` 改完必须数非 ASCII 字节**（必须为 0）；
>      多行/带引号的 `git commit -m` 会被 PowerShell 拆坏 → 用 `git commit -F <文件>`；
>   7. 跑评测核对审计表前先 `.\.dsh\verify-p5-audit.ps1 -Mark`；审计水位线已推进到 **716**。
> - **本次工作记录**：`docs/开发记录.md` 第 **(二十二)**（AgentRuntime + 事件契约）、**(二十一)**（工具声明式迁移）、**(二十)**（前端会话侧栏 +
>   浏览器验证）、**(十九)**（M2 多轮会话 + 两个 bug）、**(十八)**（M2 起步）等；简历口径见 `docs/简历项目描述.md`。
> - **待作者确认的事项**：`docs/policies/README.md` 第三节——**8 项已全部收口，当前无待决事项**。
> - **一个已知的、不影响使用的设计取舍**：`drop_course`（退课）目前是普通写操作、**不弹确认卡片**，
>   而选课/录成绩/开课申请/审批都弹（已在 JW-02 5.5 如实写明）。若认为退课也该确认，一行就能改。

### 0. 快速定位

| 想了解 | 看哪里 |
|---|---|
| 计划本身、里程碑与验收标准 | 本文档第三～七节 |
| **教务业务扩展（培养计划/绩点→排课→选课→考试）该做什么** | `docs/教务业务扩展设计.md`（**权威**：业务规则、表设计、接口、分阶段计划、测试设计、【假设】汇总） |
| **现在做到哪一步了** | 同上「进度追踪」表：**P1 ✅、P2 ✅、P3 ✅、P4 ✅、P5 ✅**；教务扩展这条线已收官，下一步回到 Agent 主线 **M2 框架化迁移** |
| 已经做了什么、踩过什么坑 | `docs/开发记录.md`（追加式，**倒序**，最新在最上面） |
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

外部依赖现状：MySQL **8.0.40**（库 `edujwxt`，**现有 21 张表**：原有 11 张（含 `ai_tool_audit`）
+ P1 的 `training_plan`/`plan_course`/`gpa_rule` + P2 的 `room`(800 行)/`class_time`/`course_apply`/`class_time_apply`
+ P3 的 `selection_round`/`selection_round_scope` + P4 的 `exam_schedule`）、Redis 5.0.14.1。
> ⚠️ **版本以服务器为准**：安装目录名是 `mysql-8.4.7-winx64`，但 `select version()` 返回 **8.0.40**——
> 文档此前按目录名写成 8.4.7，已于 2026-09-20 更正。**该版本没有原生 `VECTOR` 列类型**（实测
> `create temporary table (v vector(3))` 报 `ERROR 1064`），做向量检索需升 9.0 或换 pgvector。
**全新建库**：依次导入 `edujwxt.sql` → `seed_data.sql`（顺序不能反，课表种子依赖两边都建好的课程）。
⚠️ 新装的坑：课表种子放在 `seed_data.sql` 而不是 `edujwxt.sql`，因为后者只有 2 门课，
按 `course_code` 反查会得到 NULL 主键而使导入失败。
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

### 2. 当前验证方式（改完代码按需跑，**改哪层跑哪层**）

先起 Redis + 后端（见上节），业务类校验还需要 MySQL 在线。

```powershell
# ---------- 后端单测：150 项（安全底座 + 绩点/学分 + 排课 + 选课 + 考试 + 工具面 + 拦截器）----------
cd backend\edu-system-server; mvn -o -B test -pl edu-api -am

# ---------- 业务扩展接口层（需 Redis + 后端已启动）----------
.\.dsh\verify-p1-api.ps1      # 31 项：绩点/培养计划的读接口与越权
.\.dsh\verify-p1-write.ps1    # 63 项：培养计划写接口（自带 fixture，可重复跑）
.\.dsh\verify-p2-api.ps1      # 136 项：教室/冲突检测/两条审批流完整链路
.\.dsh\verify-p3-api.ps1      # 106 项：选课轮次三种状态 + 六道校验链 + 补退选规则
.\.dsh\verify-p4-api.ps1      # 91 项：考试安排 + 半开区间冲突边界

# ---------- Agent 工具与评测（需 Redis + 后端 + Ollama）----------
node .dsh\eval-p5-tools.cjs --inventory-only                    # 39 项：工具面（秒级，改工具先跑这个）
node .dsh\eval-p5-tools.cjs --only=get_my_gpa,recommend_courses  # 只跑某几条 golden set 用例
.\.dsh\verify-p5-audit.ps1 -Mark   # ① 评测前记下审计 id 水位线
node .dsh\eval-p5-tools.cjs        # ② 整轮 golden set（CPU 上约 10 分钟）
.\.dsh\verify-p5-audit.ps1         # ③ 只核对本次运行写下的审计行（23 项）
# ⚠️ 顺序不能反：单元测试会往 ai_tool_audit 写真实行（随机 audit-<uuid> 用户），
#    所以水位线必须在 mvn test 之后、评测之前打。

# ---------- 浏览器层（需前端 5173 也在跑）----------
node .dsh\verify-p1-ui.cjs    # 67 项：绩点/方案/计划维护三页
node .dsh\verify-p2-ui.cjs    # 60 项：开课申请→审批→排课→冲突提示→课表
node .dsh\verify-p3-ui.cjs    # 43 项：轮次三态渲染 + 学生选/退往返 + 菜单不越权
node .dsh\verify-p4-ui.cjs    # 44 项：考试两页 + 前端算的待考/已考分段 + 半开区间说明

# ---------- 新装库（建临时库导入，不影响现有库）----------
.\.dsh\validate-edujwxt-sql.ps1

# ---------- 前端 ----------
cd frontend\edu-system-client; npm run type-check
npm run build-only            # 沙箱下需提权：Vite 配置加载会 child_process.exec
```

**四条纪律**（都是踩过坑换来的，见开发记录）：
- 只要测试会写真实持久化存储（MySQL/Redis），**必须连续跑两次确认结果一致**——只跑一次绿过不算数。
- 测试失败**先确认外部依赖在线**（Redis 停止曾被误判为限流逻辑 bug）。
- 断言"某处有 N 条数据"时先想清楚**这个断言能不能失败**：本仓库出过"断言只检查列宽、
  所以漏洞照样通过"的事故，也出过 PowerShell 把 1 条数据读成空、使断言看起来像"数据为空"的误报。
- **含中文的文件（尤其是 `.ps1`）绝不用 PowerShell 的 `Get-Content`/`Set-Content` 往返改写**：
  PS 按 ANSI 读，中文会全变乱码。要批量改就用 write/edit 工具；校验脚本尽量写成 ASCII-only。
- 🔥 **`.ps1` 必须 ASCII-only，并且改完要数一遍非 ASCII 字节**（这是 2026-09-20 踩的大坑）：
  本项目的命令**实际由 Windows PowerShell 5.1 执行**（不是 pwsh 7），
  **无 BOM 的 .ps1 会被按 ANSI/GBK 解码**；中文注释一旦跨行错位，
  **下一行代码会被并进注释静默吃掉**——脚本照跑，只是少执行一行，极难排查。
  自查（每个脚本都应为 0）：
  ```powershell
  foreach ($f in Get-ChildItem .dsh\*.ps1) {
    $b = [System.IO.File]::ReadAllBytes($f.FullName)
    "{0}: {1}" -f $f.Name, (@($b | Where-Object { $_ -gt 127 }).Count)
  }
  ```
  必须在脚本里写中文时，用 `[char]0xXXXX` 码点拼接（现有脚本的 `$S_*` / `$CN_*` 常量就是这么做的）。
- 🔥 **请求体里有中文时，`ContentType` 必须带 `charset=utf-8`**：
  `Invoke-WebRequest -Body <字符串>` 不带 charset 时按默认代码页发送，中文变成 `?`
  （实测 `"D区01-20"` 到服务端是 `"D??1-20"`）。**不要**改成传 `byte[]`——实测整批 400。

> 🖥️ **浏览器验证需要一次性提权**：Playwright 启动 Chromium 走 `--remote-debugging-pipe`（命名管道），
> 会被 workspace-write 沙箱以 `spawn EPERM` 拒绝。`npm run dev` / `build-only` 同理
> （Vite 配置加载内部会 `child_process.exec`）。这不是代码问题，提权重试即可。
> 另外本机 Playwright 浏览器版本与 `playwright-core` 钉的版本不一致，
> 脚本里已用 `resolveChromium()` 自动指向已安装的最新 `chromium-*`，**不要**去下载。

### 3. 阶段性成果（供讲解用，非路线图）

这些是已经**做出来并且验证过**的能力，不是计划：

- **工具调用安全边界的完整闭环**：21 个工具按角色白名单隔离 → 参数 Schema 校验 → 风险分级 →
  危险操作挂起等人工确认 → 确认令牌服务端持有 → 全程审计留痕 → 幂等保护 → 频次与调用量限流。
  这条链路上每一环都有测试，其中 6 条断言是通过真实 HTTP 打真实后端验证的。
- **审计表 `ai_tool_audit`**：四类以上状态、可变宽截断防御、失败不外抛但不静默。
- **可复用的验证脚本** `.dsh/verify-m1.ps1`：16 项断言，含跨用户确认被拒、限流边界精确命中。
- **仓库卫生**：`node_modules` 已移出版本库（已跟踪文件 12,143 → 170）。
- **教务业务扩展 P1**（培养计划 + 绩点/排名 + 毕业学分审核，含前端三页）：
  绩点公式 `score/10 - 5`（经用户逐例纠正两次才对，**不要**再改成"整数部分拼接"写法）；
  权限规则顺序敏感，已实测教师无绩点权限、学生只能看本人数据。
- **教务业务扩展 P2**（教室 800 间 + 排课 + 冲突检测 + 两条审批流，含前端四页）：
  冲突判据写在 SQL 里且**全项目只此一处**（闭区间，相接不算冲突）；
  教室推荐按"容量/楼栋/楼层/房间号"排序而非自增 id（否则推荐结果会随安装而变）。
- **教务业务扩展 P3**（选课轮次 + 选课/补退选，含前端两页）：
  **六道校验链只有一处实现**（`selectionBlocker`），选课提交与"可选课程列表"共用它，
  否则必然出现"列表说能选、提交却说不能"；
  状态与原因也只有一处（`evaluate`），判定顺序是"先开关、后范围、最后时间窗"。
- **教务业务扩展 P4**（考试安排，含前端两页）：
  **半开区间**判冲突（连续时钟）——与 P2 节次的**闭区间**刻意不同，
  两处判据各自成文、都有边界测试，**不要"统一"**。
- **教务业务扩展 P5**（Agent 工具补齐 + 评测，教务扩展收官）：
  工具面 21 → **30**（学生 16 / 教师 7 / 管理员 9），11 个新工具与设计文档 §4.5 逐条对应；
  新增 `GET /ai/tools`（按角色，不含 prompt）与 SSE 事件里的 `tool` 字段；
  **评测分"链路不变量"（必须全绿）与"工具选择准确率"（计分，默认阈值 0.8）**，见设计文档 §6.3/§6.4。
  三条由评测倒逼出来的修复，都比"改代码"更值钱（细节见 `docs/开发记录.md` (十)）：
  - **异步派发不该重复鉴权**：`LoginInterceptor` 会在 SSE 流的 ASYNC 派发阶段再查一次 Redis，
    用户换个标签页登录就会掐断正在输出的回答（还抛 500）。现已跳过 ASYNC 并保护已提交响应。
  - **不要要求模型先查一次 ID**：`check_time_conflict` 原本只收 `courseId`，
    模型于是**编了一个 id**；改成 `courseId`/`courseCode`/`courseName` 任选其一。
    —— 工具参数要迁就用户实际会说的话，提示词叮嘱是软的、接口设计是硬的。
  - **"描述里写了省略行为"不够**：模型仍会为可选参数反问用户，
    最终靠 system prompt 的硬规则（"不要为可选参数反问"）才稳定。
- **自动化验证总盘子**：后端单测 150 + 接口 31/63/136/106/91 + 浏览器 67/60/43/44
  + Agent 评测 65（含选路 10/10）+ 审计核对 25 = **881 项**
  —— 其中浏览器那两层是唯一能证明"数据真的画到屏幕上了"的，
  已各抓到过接口层与类型检查都发现不了的缺陷。
- ⚠️ **已知问题（未修）**：未知路径返回 `code:500 "系统繁忙"` 而不是 404。
  后果不只是不语义化——**它让"路径写错"看起来像"服务器崩了"**（P4 时确实误导过一次排查）。
  修法：把 `NoHandlerFoundException` / `NoResourceFoundException` 映射成 404。
- **跨环境排查纪律**：断言"服务挂了"之前，先用一个**已知必然成功**的对比项校准
  （路径前缀有没有 `/api`？端口是 8080 还是 5173？代理有没有生效？）。
  P4 时"所有已认证请求都 500"的误报，根因就是直连 8080 却带了只存在于代理层的 `/api` 前缀。
- **七个反复出现的缺陷类别**（见 `docs/开发记录.md` 各节，写代码时逐条自查）：
  ① Promise reject 未接住（`validate()` / `ElMessageBox.confirm` 取消时都是 reject）
  ② 派生方法不进报文（**record** 的派生方法不会被 Jackson 序列化；POJO 的 `isXxx()` 会）
  ③ 权限规则顺序（具体路径必须排在 `/**` 之前）
  ④ 断言不会失败（写得太弱，漏洞照样通过——写断言时先问"它怎么才能红"）
  ⑤ 断言打在错误的守卫上（拿"已选过的课"去测"轮次未开启"，先命中更早的重复校验）
  ⑥ 种子数据测不到边界（用例看着写了，其实那条分支永远进不去）
  ⑦ **校验脚本自己读不到数据 / 断言了设计没承诺的东西**（P5 连中三次：
     mysql 退出码 + 沙箱管道让判据恒为空因而"全绿"；取消路径断言了根本不存在的自然语言；
     时间窗把单元测试夹具算进"本次运行"）—— 详见开发记录 (十)。自查两问：
     **"我这个判据能证明自己在看数据吗？""我断言的是设计承诺的行为，还是我以为它该有的行为？"**

### 4. 待用户确认的事项（不要在未确认时擅自处理）

| 事项 | 现状 | 需要用户决定什么 |
|---|---|---|
| **流式输出护栏**（P5 评测暴露） | 7B 模型偶发把工具调用**写成正文**流给用户（`{"name":...,"arguments":...}` 连 `</tool_call>`），操作没发生但屏幕上出现原始 JSON。评测已能识别该分类 | 是否作为 M2 前置小课题做掉：流式过程中识别并抑制"文本形态的工具调用"，同时把它当真正的调用继续走（仍过白名单/参数校验/确认卡片） |
| `sendSse()` 既有缺陷 | 只捕获 `IOException`，客户端中途断开时 `IllegalStateException` 冒泡成误导日志。已核对非本次引入 | 是否现在修（修法简单：catch 一并捕获） |
| 前端 `frontend/.vscode/` | 未跟踪（仓库根 `.gitignore` 只忽略了根目录的 `.vscode/*`） | 是否需要提交（通常不需要；也可补一条忽略规则） |
| M2 拆分 `views/ai/index.vue` | **✅ 已做（2026-09-22）：752 → 324 行**；拆出 `ConversationSidebar`/`ChatMessageList`/`ChatInput` + `useAgentChat`/`useConversations`（见第五节末的实际落地清单） | 已完成，无需拍板 |
| 真实 LLM 验证 | 本地 Ollama `qwen2.5:7b` 已跑通 golden set 10/10；**云端大模型未测**（无 `AI_API_KEY`） | 是否提供云 Key 做一次对比（7B 的偶发失误正好可以用大模型对照） |
| 培养计划示例数据 | `training_plan` 里那份是**示例**，且现有课程库只有 2 个学期 12 门课 | 是否需要我按真实教学计划补一份完整 4 年数据 |

### 5. 下一步（两条线，用户指定哪条就走哪条）

**A. Agent 主线：M2 框架化迁移**（详见第三节阶段 1）——**教务扩展收官后，这条就是主线**
1. **先做最小的 Spring AI 接入验证**：新建 `edu-agent` 模块 → 接 `spring-ai-bom` → 迁移一个工具
   （如 `get_my_courses`）到 `@Tool` 形态 → 确认能跑通，**再**批量迁移其余 29 个。
   > 迁移前先跑一遍 `node .dsh/eval-p5-tools.cjs` 存档当前 `ROUTING` 分数：
   > 换框架最容易悄悄改掉工具描述与参数形状，有基线才说得清"是不是退化了"。
2. `ChatMemory` + Redis 多轮记忆：当前每轮只构造 `[system, user]` 两条消息、没有 sessionId，
   这是与"真 Agent"差距最大的一项。
3. 会话管理 API + 前端会话侧栏，同时按 `vue-best-practices` 拆分 `views/ai/index.vue`。
4. **注意**：引入 Spring AI 后 `AiChatService` 的手写循环与 `OpenAiClient` 会逐步被替代，
   迁移期间应保留旧实现一个版本周期作为回归对照。

**B. 输出护栏 —— ✅ 已完成（2026-09-22，M2 的前置小课题）**
- 现象（P5 评测实测，见 `docs/开发记录.md` (十) 末节）：7B 模型偶尔把工具调用**写成正文**
  （`{"name":...,"arguments":...}` + `</tool_call>`）流给用户 → 用户看到原始 JSON，而**操作根本没发生**。
- 已实现：`duyell.ai.guard.ToolCallTextGuard`（流式识别 + 扣留）接进 `AiChatService`，
  把恢复出的调用**当成真实工具调用继续走**——角色白名单、参数 Schema 校验、危险操作确认卡片、
  审计**全部照旧**，因此护栏不新增任何权限（提示注入让它复述 JSON 也一样要过这些闸门）。
- 取舍：宁可漏认不可吞字（解析不出来就原样展示）；缓冲上限 8000 字符避免卡住回答；
  原始 JSON 不写回模型历史（否则模型会反复复述）。
- 验证：`ToolCallTextGuardTest` 9 项（含**实测抓包原文逐字符喂入**）+
  `OutputGuardrailIntegrationTest`（继承 `OpenAiClient` 的桩，必现"坏模型"，断言恢复出的调用**落审计且 SUCCESS**）
  + 评测对**每个用例**断言"回答正文不出现工具调用 JSON"。详见开发记录 **(十五)**。

> 已完成（不必重做）：教务业务扩展 P1–P5 全部交付并验证（培养计划/绩点 → 排课 →
> 选课 → 考试 → Agent 工具与评测）。设计文档 §4.5 规划的 11 个工具已全部上线；
> 加上学业预警工具，工具面从 21 个扩到 **31 个**（学生 17 / 教师 7 / 管理员 9）。
> 另有 **M3 的 RAG 语料**：`docs/policies/` 10 份制度文件（作者正在逐章审计，JW-01、JW-06 已审完并修订）。
- 验收：新增一条评测断言——**回答正文里不允许出现工具调用 JSON**，
  并让"文本形态工具调用"从"偶发失败"变成"被正确接住"。

**C. 学业预警通知 —— ✅ 已完成（2026-09-20，作者选定口径后当天落地）**
- 需求（用户原话口径）：**只做学业预警，不做留级/退学等自动化处理**。
  学生满足条件后，**登录教务系统时弹出一条通知**；具体事宜由**辅导员约谈**；
  即使达到退学等严重程度，也只是**由管理员修改学生状态**（人工决定）。
- **已拍板的两个选择**：口径 = **未通过课程学分累计 ≥ 8 学分**（配置项，可覆盖）；
  通知 = **只弹一次，学生可标记已读**（情况变严重才再次提示）。
- **实现**：`AcademicWarningServiceImpl`（判定 + 水位线）、`GET /academic-warning/my`（只读）、
  `POST /academic-warning/my/read`（确认，幂等）、表 `academic_warning`、
  前端 `composables/useAcademicWarning.ts`（登录后弹一次）、助手工具 `get_my_academic_warning`。
- **验证**：`AcademicWarningServiceTest` 8 项 + `.dsh/verify-warning.ps1` 28 项，全绿。
- 细节与三个坑见 `docs/开发记录.md`（十三）；制度口径见 `docs/policies/01-学籍管理规定.md` §5（**v1.2**）。

> 已完成（不必重做）：教务业务扩展 P1–P5 全部交付并验证（培养计划/绩点 → 排课 →
> 选课 → 考试 → Agent 工具与评测）。设计文档 §4.5 规划的 11 个工具已全部上线；
> 加上学业预警工具，工具面从 21 个扩到 **31 个**（学生 17 / 教师 7 / 管理员 9）。
> 另有 **M3 的 RAG 语料**：`docs/policies/` 10 份制度文件（作者正在逐章审计，JW-01 已审完两轮）。

**动手前先读**：`docs/技能使用规范.md`（若涉及前端）、本节第 2 节的验证方式。

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
| 1.3 工具改造 | **🟡 试点+第二批完成（2026-09-22）**：`get_my_courses`（学生 READ_ONLY / 教师 READ_ONLY，**同名跨角色**）、`select_course`（DANGEROUS，弹确认卡片）已迁为声明式（`@Tool` + 项目侧 `@ToolMeta` 补展示名/风险等级），手写实现保留作对照（`registerOverride` 接管）；批量只需新增一个 `DeclarativeToolGroup` Bean。**其余 28 个待批量迁移**。⚠️ 迁移踩到的两个坑见开发记录 (二十一)：`MethodToolCallback.Builder` 不会从注解推导定义；单个对象参数会被框架**再套一层参数名**（静默改变模型要填的形状） | `tool/declarative/` |
| 1.4 Agent 循环 | **🟡 编排抽离完成（2026-09-22，用户拍板方案 B）**：`AgentRuntime`（模型↔工具往复 + 四道闸门 + 输出护栏）+ `AgentEventPublisher`/`AgentEventType`（事件契约固定成枚举，生产走 SSE、测试用记录实现）；`AiChatService` 756 → 372 行，只剩 token/限流/会话/传输；助手正文落库从三处收敛为一处。**未换成框架 tool-calling 循环**：确认卡片要挂起线程、护栏要过滤输出、审计要留痕，重写成 Advisor 风险高于收益。**剩**：`AgentEventPublisher` 抽象出 `AgentEvent` 联合类型文档、断线重连（1.9） | `runtime/` |
| 1.5 多轮记忆 | **✅ 已完成（2026-09-22）**：`ChatMemory` + **MySQL** 持久化（`ai_conversation`/`ai_message`）；窗口＝"取最近 N 条"（`ai.memory.max-messages`，默认 20）。⚠️ **未用**框架的 `MessageWindowChatMemory`：其 `saveAll` 是"替换整个会话"语义，落 MySQL 要么丢历史、要么消息翻倍（理由见开发记录 (十九)）；摘要压缩**暂不做**（7B 下性价比低，待上下文真正吃紧再加） | `MybatisChatMemory`、`ConversationService` |
| 1.6 会话管理 API | **✅ 已完成（2026-09-22）**：`POST/GET /ai/conversations`、`GET /ai/conversations/{id}/messages`、`DELETE /ai/conversations/{id}`；`POST /ai/chat` 支持可选 `conversationId` + SSE 回传 `conversation` 事件；归属校验单一入口 `requireOwned`（改 id 只会得到"不存在或无权访问"）；role 只认 token。**重命名接口暂未做**（标题由首条消息自动生成，够用） | `AgentConversationController` |
| 1.7 前端会话侧栏 | 左侧会话列表 + 新建 + 删除；`?conversationId=` 路由参数；历史消息回填（后端已就绪，见 1.6） | `views/ai/` 拆分组件 |
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

> **2026-09-22 实际落地的拆分**（1.7 已完成，与上面的草案略有出入，以实际为准）：
> ```
> views/ai/
> ├── index.vue                        # 324 行（原 752）：只做编排——会话 id ↔ URL、流结束刷列表
> └── components/
>     ├── ConversationSidebar.vue      # 会话侧栏（草案里的 ConversationList）
>     ├── ChatMessageList.vue          # 消息流 + 流式渲染 + 确认卡片 + 空态示例问题
>     └── ChatInput.vue                # 输入区（Enter 发送 / Shift+Enter 换行）
> composables/
> ├── useAgentChat.ts                  # SSE 解析 + 事件分发 + 确认卡片生命周期
> └── useConversations.ts              # 会话 CRUD + 历史消息
> types/models.ts                      # 追加 AiConversation/AiMessage/AgentSseEvent 等
> ```
> 未按草案拆出的部分：`MessageBubble`/`ToolTrace`/`ConfirmCard` 目前并入 `ChatMessageList`；
> `useAgentStream` 的断线重连归 1.9（流式健壮性）。

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

