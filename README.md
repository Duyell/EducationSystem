# EducationSystem 教务系统 · Agent 智能助手版

> 一个**真实的教务业务系统**（学籍/成绩/选课/排课/考试/评教，25 张表、21 个前端页面），
> 上面装了一套**完整可验证的 Agent 能力**：33 个领域工具、四道安全闸门、多轮会话记忆、
> 以及带引用出处的制度问答（RAG）。
>
> 它不是"接了个大模型聊天框"：危险操作会**弹确认卡片**、越权调用会被**拦截并留痕**、
> 每一次工具调用都进**审计表**、制度问答必须**给出处**。

---

## 一、这个项目能讲什么（可量化）

| 维度 | 数字 / 事实 |
|---|---|
| 后端测试 | **312 项全绿**（0 失败，6 项依赖外部服务的用例默认跳过） |
| 验证脚本 | **26 个**（`.dsh/`）：工具面 39、隐私与审计 41、会话链路 31（含强断言）、MCP 协议级、会话/来源卡片 UI 实机（真实 Chromium）、RAG 评测（检索指标 + 端到端忠实度） |
| Agent 工具 | **33 个**（学生 17 / 教师 7 / 管理员 9），全部为 Spring AI **声明式 `@Tool`**；RAG 开启时增加 1 个跨角色 `search_policy` |
| 对外能力（MCP） | **MCP server**（官方 Java SDK 0.18.3 + SSE）：默认开放学生 **14 个只读工具**给外部客户端；`DANGEROUS` 永不外放，`WRITE` 需显式开启 |
| 安全闸门 | 角色白名单 → 参数 Schema 校验 → 危险操作人工确认（HITL）→ 审计留痕；另有输出护栏、限流、幂等键 |
| 多轮记忆 | 会话与消息落 MySQL；模型上下文取最近 N 条；前端会话侧栏（新建/切换/删除、`?conversationId=` 可分享可刷新） |
| RAG 制度问答 | 11 份制度 / 85 个分块；**Hit@5 = 100%（12/12）、MRR = 0.944、关键词命中率 100%** |
| 代码体量 | 后端 204 个 Java 文件 / 20,907 行；前端 88 个 Vue/TS 文件 / 13,375 行 |

---

## 二、功能矩阵

### 业务功能（三种角色）

| 模块 | 学生 | 教师 | 管理员 |
|---|---|---|---|
| 学籍 | 我的培养方案 | — | 用户/学生/教师/学院/专业/班级 |
| 成绩 | 我的成绩 | 录入/修改成绩、课程名单 | 课程维护、**成绩变更日志**接口（谁改的、改前改后、界面还是 AI 改的）——⚠️ 接口已就绪并 41 项断言验过，但**管理端还没有对应页面**（见"已知限制"） |
| 绩点与毕业 | 绩点与专业排名、毕业学分审核 | — | 按学号查询 |
| 选课 | **选课轮次**（管理员开关+时间窗+范围）、选课/退课、时间冲突、可选课程、推荐课程 | — | 轮次与范围配置 |
| 排课 | 我的课表 | 申请排课、开课申请 | 审批、教室、全量课表 |
| 考试 | 我的考试（待考/已考） | — | 考试安排（含冲突判定） |
| 评教 | 评教、评价状态（**匿名**：教师看得到内容、看不到是谁） | 查看本人课程评价 | 查询 |
| 预警 | 学业预警（未通过学分累计 ≥ 阈值，弹一次 + 水位线） | — | 查询 |

### Agent 能力

```
                    ┌──────────────── 四道安全闸门 ────────────────┐
用户提问 → 角色/会话 → │ ① 角色白名单 ② 参数Schema校验 ③ HITL确认 ④ 审计 │ → 工具执行 → 回答
                    └──────────────────────────────────────────────┘
```

- **危险操作挂起等人点确认**：选课、录成绩、改成绩、开课申请、审批 —— 服务端挂起 SSE 流，
  用户点"确认"后才执行（带幂等键，重复确认不会重复写库）
- **输出护栏**：小模型常把工具调用"写成正文"（`{"name":...,"arguments":...}` 连 `</tool_call>`），
  护栏会扣住原始 JSON、**恢复成真实调用**继续走同一套闸门，并清理协议残渣
- **多轮对话**：会话与消息落库、窗口取最近 N 条、历史可在界面回看且 URL 可分享
- **制度问答（RAG）**：问"补考通过后绩点怎么算"会检索出条款并注明《重修与补考办法》3. 补考 等出处
- **来源卡片**：回答下方列出本轮依据的条款（`JW-编号` + 文档名 + 章节）。出处走独立的
  `type=sources` 事件、只来自**服务端真实检索结果**，**不从模型正文里正则抠**（那正是会被编造的字段）；
  工具检索与"服务端强制注入"两条路径都会推出来源，且**出处随消息落库**
  （`ai_message.sources_json`）——刷新页面或切回历史会话时卡片仍在
- **MCP server（M4）**：把工具面按 **Model Context Protocol** 开放给外部客户端（Claude Desktop / Cursor / Cline）。
  官方 MCP Java SDK + SSE 传输，默认关闭（`AI_MCP_ENABLED=true` 才开）。三条硬约束：
  ① 只开放 `READ_ONLY`（`WRITE` 需显式开启，**`DANGEROUS` 永不开放**——它的安全性依赖人工确认，
  而 MCP 没有这条通道）；② 身份取自登录令牌，工具**以持令牌那个人的身份**执行，角色不符即拒；
  ③ 外部调用仍走同一套闸门（角色白名单 + 变更上下文），并在 `ai_tool_audit` 留下
  `session_id = mcp:<会话号>` 的审计（与内置 Agent 的调用可区分）

---

## 三、架构

```
浏览器 (Vue 3 + Vite, :5173)
   │  /api/* 代理
   ▼
后端 Spring Boot (:8080)
   ├── LoginInterceptor（JWT + Redis 比对 + 按路径的角色规则）
   ├── 业务 Controller → Service → MyBatis → MySQL 8 (:3306)
   └── AI 接入层 AiController / AgentConversationController
          │
          ├── AiChatService      token / 限流 / 会话 / SSE 传输
          │      │
          │      ▼
          ├── AgentRuntime       模型↔工具往复 + 四道闸门 + 输出护栏（只做编排）
          │      ├── ToolRegistry（33 个声明式工具，按角色隔离）
          │      ├── ConfirmationGate / PendingActionStore（HITL）
          │      └── AiAuditService → ai_tool_audit（审计）
          │
          ├── ChatMemory → ai_conversation / ai_message（多轮记忆）
          │
          └── RAG（可选，默认关闭）
                 PolicySearchService → PgVectorStore → PostgreSQL 16 + pgvector
                                        ↑ bge-m3 嵌入（本地 Ollama）
                 语料：docs/policies/*.md → 构建期复制进 jar → 按章节切分 → 幂等索引
```

设计要点（也是可深挖的技术点）：

| 主题 | 做法与原因 |
|---|---|
| **声明式工具** | 33 个工具全部 `@Tool` + 项目侧 `@ToolMeta`（补展示名与风险等级）+ `@ParamConstraint`（补框架表达不了的 enum/min/max），由扫描器接进既有闸门。迁移踩的坑见 `docs/开发记录.md` (二十一)(二十三) |
| **为什么保留自写循环** | 框架的 tool-calling 循环把"执行工具"当内部细节，而本项目要在执行**前**挂起等人确认、输出**后**过滤畸形工具调用、每次调用落审计 —— 换成框架循环就得把这些重写成 Advisor，风险高于收益（详见 `AgentRuntime` 类注释） |
| **事件契约** | `AgentEventType` 枚举 + `AgentEventPublisher` 抽象：生产走 SSE，测试用记录实现直接断言事件序列；`type` 用枚举名小写，与历史线协议逐字兼容 |
| **多轮记忆** | 刻意不用框架的 `MessageWindowChatMemory`（其 `saveAll` 语义是"替换整个会话"，落 MySQL 要么丢历史、要么消息翻倍），改为"只追加完整记录 + 取最近 N 条" |
| **RAG 切分** | 按**章节**切（条款是最小语义单元，按字数切会把规则劈成半句）；分块文本带文档标题提升召回；id 确定性可读，**先按 docId 删旧块再写入**保证幂等 |

---

## 四、技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue 3.5、Vite、TypeScript（strict）、Element Plus、Pinia |
| 后端 | Java 21、Spring Boot 3.5.16、MyBatis、PageHelper、JWT（jjwt 0.13）、Lombok |
| 存储 | MySQL 8（业务）、Redis（登录态/限流）、PostgreSQL 16 + pgvector（向量） |
| AI | **Spring AI 1.0.9** + 本地 **Ollama**（`qwen2.5:7b` 对话、`bge-m3` 嵌入）；也支持任何 OpenAI 兼容端点 |
| 对外协议 | **MCP（Model Context Protocol）Java SDK 0.18.3** + SSE 传输：把工具面开放给 Claude Desktop / Cursor 等外部客户端 |
| 测试与验证 | JUnit 5、Playwright（会话 UI 与来源卡片实机验证）、26 个 `.dsh` 脚本 + **CI 用的确定性假模型** `.dsh/fake-model.cjs`（OpenAI 兼容 SSE 桩：文本 / 工具调用 / HITL 三种模式） |
| CI | GitHub Actions：导入建表与种子数据 → 312 项后端测试 → 前端 `vue-tsc` → 起**假模型**+后端（带 `AI_MCP_ENABLED=true`）→ 四套断言（工具面 39 / 隐私审计 41 / 会话 31 / MCP 协议级）。需要真实模型的质量评测不进 CI |

---

## 五、快速开始

### 1. 依赖

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | |
| Maven | 3.9+ | 支持 `-o` 离线构建 |
| Node.js | 18+ | 只用 npm |
| MySQL | 8.0 | 业务库 `edujwxt` |
| Redis | 5+ | 登录态与限流 |
| PostgreSQL + pgvector | 16 / 0.8+ | **仅 RAG 需要**（不启用 RAG 可不装） |
| Ollama | 最新 | `ollama pull qwen2.5:7b`；RAG 另需 `ollama pull bge-m3` |

### 2. 初始化数据库

```bash
mysql -uroot -p < edujwxt.sql        # 建表
mysql -uroot -p < seed_data.sql      # 演示数据
# 另需按序执行 docs/sql/*.sql（幂等）：AI 审计、多轮会话、学业预警、成绩变更日志等
```

RAG 额外一步：建库 `edurag` 并启用扩展 ——
`psql -U postgres -c "create database edurag"` → `psql -U postgres -d edurag -c "create extension vector"`
（向量表由应用启动时自动创建）。

### 3. 起后端

```powershell
cd backend\edu-system-server
mvn -o -B package -DskipTests -pl edu-api -am

$env:AI_BASE_URL='http://localhost:11434/v1'   # 手写客户端指向 Ollama 的 OpenAI 兼容端点
$env:AI_MODEL='qwen2.5:7b'
$env:AI_API_KEY='ollama'
$env:AI_RAG_ENABLED='true'                     # 可选：开启制度问答（需 PostgreSQL+pgvector）

java -jar edu-api\target\edu-api-0.0.1-SNAPSHOT.jar
```

常用环境变量：

| 变量 | 默认 | 作用 |
|---|---|---|
| `AI_BASE_URL` / `AI_MODEL` / `AI_API_KEY` | DeepSeek 占位 | 手写客户端的模型端点 |
| `AI_OLLAMA_BASE_URL` | `http://localhost:11434` | Spring AI 的 Ollama 端点 |
| `AI_RAG_ENABLED` | `false` | 是否启用 RAG（关闭时完全不碰 PostgreSQL） |
| `AI_RAG_JDBC_URL` / `AI_RAG_USERNAME` / `AI_RAG_PASSWORD` | `jdbc:postgresql://localhost:5432/edurag` / `postgres` / `123456` | 向量库连接 |
| `AI_MEMORY_MAX_MESSAGES` | `20` | 进上下文的最近消息条数 |
| `ACADEMIC_WARNING_THRESHOLD` | `8` | 学业预警的未通过学分阈值 |
| `JWT_SECRET` | 开发默认值 | **生产必须设置（>=32 字节）** |

### 4. 起前端

```bash
cd frontend/edu-system-client
npm install
npm run dev          # http://localhost:5173
```

### 5. 演示账号

| 角色 | 账号 | 密码 |
|---|---|---|
| 学生 | `2023001` / `2023002` | `123456` |
| 教师 | `10001` / `10002` | `123456` |
| 管理员 | `admin01` | `123456` |

### 6. 自检（脚本都可重复跑）

```bash
node .dsh/eval-p5-tools.cjs --inventory-only   # 工具面不变量      期望 39/39
.\.dsh\verify-privacy.ps1                      # 隐私与审计        期望 41/41
node .dsh/verify-m2-conversations.cjs --no-llm # 会话链路          期望 25/25
node .dsh/eval-rag.cjs                         # RAG（需 AI_RAG_ENABLED=true）
mvn -o -B test -pl edu-api -am                 # 后端全量测试      期望 312 项
```

> 用容器编排也可以：`docker compose up -d --build`。**注意**该 compose 按 Docker 起 MySQL，
> 与本文档描述的"本机原生三库 + 本地 Ollama"路径不同，详见"已知限制"。

---

## 六、目录结构

```
├── backend/edu-system-server/     # Maven 多模块：edu-pojo / edu-common / edu-api
│   └── edu-api/…/duyell/
│       ├── ai/                    # Agent 层：tool（声明式工具）/ runtime（编排）/ rag /
│       │                          #   memory / confirm / audit / guard / limit
│       ├── controller/ service/ mapper/   # 业务层
│       └── resources/             # application.yml、MyBatis XML（构建期还会注入 policies/）
├── frontend/edu-system-client/    # Vue 3 + Vite（views/ 21 个页面、composables/、api/）
├── docs/
│   ├── Agent化升级计划.md          # 里程碑、验收标准、接续指南（**开工先读**）
│   ├── 开发记录.md                 # 追加式开发日志（34 条，倒序，含所有踩坑）
│   ├── AI模块架构文档.md           # AI 模块逐层详解 + M2/M3 设计
│   ├── 教务业务扩展设计.md          # P1–P5 业务规则权威文档
│   ├── MCP接入指南.md             # 把工具面接进 Cursor / Claude Desktop 的步骤与排错
│   ├── policies/                  # 11 份制度文档（RAG 语料，也是业务规则的制度依据）
│   └── sql/                       # 10 个幂等迁移脚本
├── .dsh/                          # 26 个验证/评测脚本（含 CI 假模型）+ redis 配置 + 项目级技能
├── edujwxt.sql / seed_data.sql    # 建表与演示数据
└── docker-compose.yml             # 容器化编排（与本地原生开发路径不同，见已知限制）
```

---

## 七、已知限制（如实列出，不是装饰）

| 限制 | 说明 | 状态 |
|---|---|---|
| **模型偶发不检索就作答** | 7B 在制度类问题上有时不调用 `search_policy`，甚至复述工具的空结果文案假装查过、再编造文件名。**检索本身没问题**（同一问题直接检索 top1 命中正确条款），是模型指令遵循问题 | ✅ 已修：**服务端路由级强制检索**（判定制度意图 → 自动检索 → 注入真实条款，严格评测 9/9） |
| 模型偶发空响应 | 工具轮之后偶尔不产出正文，答案被截断 | ✅ 已修：`AgentRuntime` 对空响应重试一次（含确定性用例） |
| 忠实度评测默认是诊断性 | `eval-rag.cjs` 的端到端部分不计入退出码（7B 措辞不稳定）；里程碑验收用 `--strict-faithfulness` | 有意为之，避免"碰运气式全绿" |
| 载荷组装代码有两份 | 手写工具注册器与声明式类各拼一份"给模型看的 JSON"（对照期取舍，删手写实现即消失） | 计划内 |
| 无 LICENSE | 测试与脚本可跑，仓库暂未声明开源协议 | 待补 |
| CI 覆盖有限 | GitHub Actions 跑"导入数据 → 全量测试 → 前端类型检查 → 假模型 + 后端 → 三套断言"，**已全绿**。但仍有两块只在本地跑：**需要真实模型的质量评测**（工具路由准确率、RAG 忠实度）与**需要 PostgreSQL+pgvector 的检索评测**（CI 里没有 pgvector service）；Playwright 实机验证同理 | 待补（计划：pgvector service + 假嵌入） |
| 本机模型冷启动慢 | 后端重启后第一次提问要等 Ollama 装载对话与嵌入两个模型（实测单轮可达 260 秒）；预热一轮即可降到 ~20 秒 | 已知，非缺陷 |
| `docker-compose.yml` 仍未实跑 | 已补齐 pgvector 服务与模型环境变量，但本机到 Docker Hub 不可达，**实测路径是"原生三库 + 本地 Ollama"** | 待核对 |
| 示例演示数据会随时间老化 | `seed_data.sql` 里考试时间是导入那刻固化的绝对时间，跑考试相关演示前先执行 `docs/sql/2026-09-27-reanchor-demo-exam-dates.sql` | 已知，脚本已提供 |
| 成绩变更日志**只有接口、没有页面** | `/score/change-log`（仅管理员）已实现并被 `verify-privacy.ps1` 的断言覆盖，但前端没有任何页面引用它 —— "谁改过成绩"这条链目前只能靠接口/脚本查看 | 已知缺口（补页面属于加功能，留待决定） |
| 模型相关的评测不能并发跑 | 本机 Ollama 串行处理请求：同时跑两个依赖模型的脚本（如 UI 实机 + RAG 评测）会让嵌入调用排队超时，`eval-rag.cjs` 的检索指标会**假性掉到 25%**。评测必须独占运行 | 已知，见开发记录（三十五） |

---

## 八、路线图

| 里程碑 | 状态 |
|---|---|
| **M1** Agent 安全底座（工具/白名单/校验/HITL/审计/护栏/限流） | ✅ |
| **P1–P5** 教务业务扩展（培养计划·绩点·排课·选课轮次·考试·成绩变更日志） | ✅ |
| **M2** 框架化迁移（Spring AI 接入、33 工具声明式、多轮记忆与会话 API、前端侧栏、编排抽离） | ✅ 基本收口 |
| **M3** RAG 制度问答（pgvector、章节切分与幂等索引、`search_policy` + 引用回填、评测） | ✅ 四项达标 |
| M3 收尾（路由级强制检索、前端来源卡片） | ✅ 已完成（严格评测 9/9；卡片覆盖"模型检索"与"服务端注入"两条路径） |
| **M4** 对外能力：MCP server（官方 SDK + SSE，只读工具面，默认关闭） | ✅ 第一步 |
| **M4 续** stdio 传输 / 按会话动态工具面 / 接入指南 | ⬜ 未开始 |
| **M5** 评测门禁整合进 CI（RAG 检索接线 + 假嵌入） | 🚧 部分（工具面/隐私/会话/MCP 已进 CI） |

---

## 九、安全说明

- JWT 登录校验 + Redis 比对（重新登录即失效旧 token）+ 按路径的角色规则（`LoginInterceptor`，顺序敏感）
- 数据归属校验：学生只能看本人数据；教师只能操作本人所授课程（服务端强制，不靠前端隐藏）
- 危险操作一律经**人工确认**；越权/未知工具调用会被拦截并记入 `ai_tool_audit`
- 生产部署前务必修改：数据库密码、`JWT_SECRET`、模型 API Key（用环境变量注入，勿写入仓库）

---

## 十、文档索引

| 想了解 | 看哪里 |
|---|---|
| **拿这个项目当教材学 Agent（学习地图 + 面试要点清单）** | `docs/Agent学习地图与面试要点.md` |
| **想让 Cursor / Claude Desktop 用上这个项目的工具面（MCP）** | `docs/MCP接入指南.md` |
| **下次开工怎么接** | `docs/Agent化升级计划.md` 顶部的"接续指南" |
| 做过什么、踩过哪些坑（信息量最大） | `docs/开发记录.md`（倒序，34 条） |
| AI 模块怎么运作、为什么这么设计 | `docs/AI模块架构文档.md` |
| 教务业务规则（学分/绩点/冲突判定…） | `docs/教务业务扩展设计.md` + `docs/policies/` |
| 前端规范 | `docs/技能使用规范.md` |

> **`.dsh/skills/`**：项目级 agent 技能目录（由 DSH 自动扫描）。其中 `edu-frontend-rules.md`
> 定义了本仓库前端工作的技能路由与仲裁规则，做前端改动前请先阅读。

