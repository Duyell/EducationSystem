# Agent 学习地图与面试要点（以本项目为教材）

> **怎么用这份文档**：每个知识点都给了「概念 → 本项目对应文件 → 你要能回答的问题」。
> 建议**边读代码边对照**：先跑起来看现象，再看实现，最后用自己的话讲一遍。
> 项目里的开发记录（`docs/开发记录.md`）记录了每个决定背后的**真实踩坑**，
> 那是最能体现深度、也是面试官最容易被吸引的部分。

---

## 一、知识地图（八块）

### 1. Agent 循环与工具调用（最核心）

| | |
|---|---|
| **概念** | 模型不直接"干活"，而是输出**工具调用**（function calling）；应用执行后把结果回灌，模型再决定下一步，直到不再调用工具。要看懂：消息角色（system/user/assistant/tool）、`tool_call_id`、并行调用、迭代上限、终止条件 |
| **本项目** | `duyell/ai/runtime/AgentRuntime.java`（循环 + 终止 + 工具预算）、`dto/ChatMessage.java`、`service/OpenAiClient.java`（手写 SSE 客户端） |
| **要能回答** | 为什么需要循环？一轮里模型能调几次工具？怎么防死循环？工具报错怎么回灌？多个工具并行调用时结果怎么对齐？ |
| **延伸** | ReAct（推理+行动交替）、Plan-and-Execute、Reflexion（自我反思） |

### 2. 工具设计（"写得对不对"直接决定 Agent 好不好用）

| | |
|---|---|
| **概念** | **工具描述是给模型的指令**，不是给人看的文档；参数 schema 的形状（平铺 vs 嵌套）、必填与取值约束、返回值结构化、幂等性 |
| **本项目** | `ai/tool/declarative/*`（33 个声明式工具）、`ToolMeta`（展示名+风险等级）、`ParamConstraint`（enum/min/max）、`ToolRegistry`（角色隔离）、`JsonSchemaToolArgumentValidator` |
| **要能回答** | 为什么同名工具在不同角色下必须是两份定义？schema 里为什么不能出现 `userId`？为什么"可选参数"必须显式声明？描述里写"系统会弹确认卡片"为什么会改变模型行为？ |
| **真实教训** | ① 单对象参数被框架再套一层 `request`，静默改变模型要填的形状；② `@ToolParam.required` 默认 true，可选参数变必填；③ 描述里写"用户确认后才执行"→ 模型改成用文字反问而不调工具 |

### 3. 上下文工程（Context Engineering）

| | |
|---|---|
| **概念** | 系统提示词分层（角色/规则/可用能力）、历史窗口、记忆的写入与读取、**主动注入**（不指望模型自己去查）、token 预算 |
| **本项目** | 三套 system prompt（`AiChatService`）、`MybatisChatMemory`（窗口=取最近 N 条）、`ContextAugmenter` + `PolicyContextAugmenter`（制度问题**强制检索注入**） |
| **要能回答** | 窗口该按条数还是轮数？历史该存全量还是只存窗口？为什么"让模型自己决定是否检索"不可靠？注入该放在调用模型之前还是之后？摘要压缩什么时候做？ |
| **延伸** | Lost in the Middle（长上下文中间信息易被忽略）、MemGPT、12-Factor Agents 的 Factor 3（own your context window） |

### 4. 安全与治理（企业级 Agent 的及格线）

| | |
|---|---|
| **概念** | 权限最小化、**人在环**（HITL）、审计留痕、输出护栏、速率与预算限制、幂等 |
| **本项目** | 四道闸门（`ToolRegistry` 白名单 / 参数校验 / `ConfirmationGate`+`PendingActionStore` / `AiAuditService`）、`ToolCallTextGuard`（输出护栏）、`AgentRateLimiter`、`ai_tool_audit` 表、确认令牌作幂等键 |
| **要能回答** | 为什么不能只靠提示词约束危险操作？确认卡片怎么实现（SSE 挂起+恢复）？越权调用怎么处理（拒绝+审计+回灌模型）？重试/重复点击怎么不重复写库？ |
| **延伸** | OWASP LLM Top 10（提示注入、不安全输出）、最小权限原则 |

### 5. RAG（检索增强）

| | |
|---|---|
| **概念** | 切分策略、嵌入模型选型、向量库与索引（HNSW/IVFFlat）、相似度阈值、元数据过滤、**引用回填**、混合检索（向量+关键词+RRF） |
| **本项目** | `ai/rag/PolicyChunker`（**按章节切**）、`PolicyIndexer`（幂等重建：先按 docId 删旧块）、`PolicySearchService`（唯一检索实现）、`PgVectorStore` + PostgreSQL+pgvector、`bge-m3` 嵌入 |
| **要能回答** | 为什么按章节切而不是按字数？为什么分块要带文档标题？为什么重建索引要先删旧块（"幽灵条款"）？相似度阈值调高/调低各有什么后果？引用回填为什么是 RAG 的刚需？ |
| **延伸** | Self-RAG、HyDE、RAG 综述（Gao et al.）、BM25/混合检索 |

### 6. 评测（区分"能跑"和"可信"）

| | |
|---|---|
| **概念** | 黄金集、分层指标（工具路由 / 检索质量 / 端到端忠实度）、LLM-as-judge 与其局限、**把约束变成断言** |
| **本项目** | `.dsh/eval-p5-tools.cjs`（工具路由 + 输出不变量）、`.dsh/eval-rag.cjs`（**Hit@5 / MRR / 关键词命中率 / 忠实度代理**）、`DeclarativeMigrationCoverageTest`（覆盖闸门）、40 项隐私与审计断言 |
| **要能回答** | 为什么只看 Hit@1 会误判？忠实度为什么难自动评？"跑一遍全绿"为什么可能骗人（本项目的自动化指标默认是诊断性的）？ |
| **真实教训** | 评测把"多条不同条款都提到 60 分"误判成"重复入库"；把 top1 钉死在某份文档上（其实 FAQ 更贴切）——**断言业务事实，不要钉死实现细节** |

### 7. 工程化（把 Demo 变成系统的部分）

| | |
|---|---|
| **概念** | 事件协议设计（SSE 事件类型化）、可测性（把传输抽象掉）、可观测（状态事件/日志/审计）、迁移策略（灰度、对照、回滚） |
| **本项目** | `AgentEventType` + `AgentEvent` + `AgentEventPublisher`（生产走 SSE、测试用记录实现）、`SseAgentEventPublisher`、`AgentConversationController`（会话 API）、CI workflow |
| **要能回答** | 为什么把事件出口抽象出来（不只是"解耦"——它让"不启 HTTP 就能断言事件序列"成为可能）？线协议为什么要逐字兼容？框架迁移怎么保证四道闸门不失效？ |
| **延伸** | 12-Factor Agents（own your control flow / own your prompts / small, focused agents） |

### 8. 框架 vs 自研的边界（最能体现判断力的一题）

| | |
|---|---|
| **概念** | 框架给什么（声明、schema 生成、参数绑定、循环）、你必须自己拿什么（授权、确认、审计、上下文策略） |
| **本项目** | 用 Spring AI 的 `@Tool`/`MethodToolCallback`/`JsonSchemaGenerator`；**不用**框架的 tool-calling 循环（要 HITL 挂起、输出过滤、审计），也**不用**框架的 `MessageWindowChatMemory`（其 `saveAll` 语义会丢历史或消息翻倍）——两个决定都写在类注释里 |
| **要能回答** | 什么时候该用框架循环？换成框架会丢什么？迁移中"契约漂移"怎么发现（schema 形状断言）？ |
| **延伸** | LangGraph / LlamaIndex Workflows 的状态机思路、MCP（把工具变成跨应用协议） |

---

## 二、面试高频问题清单（用本项目答）

**基础层**
1. 什么是 function calling？和 RAG 有什么区别？（一个是"做事"，一个是"查资料"）
2. Agent 和"聊天机器人 + 工具"的区别在哪？（自主决策链 + 工具结果驱动下一步）
3. 一轮对话里模型的输出结构是什么？`tool_call_id` 有什么用？
4. 怎么防 Agent 死循环？（迭代上限 + 工具调用预算 + 终止条件）

**设计层**
5. 工具描述怎么写才好用？（何时用/何时不用 + "因此你该怎么办"）
6. 参数太多怎么办？可选参数怎么表达？（平铺 schema + 显式 required=false + ParamConstraint）
7. 多轮记忆怎么存怎么取？（只追加完整记录 + 窗口取最近 N 条；不要用"替换整窗口"的语义）
8. 上下文太长怎么办？（窗口、摘要压缩、按需注入、元数据过滤）
9. 提示词该放多少东西？（角色 + 规则 + 能力清单；模型不稳的行为要用代码兜，不要堆提示词）

**安全层**
10. 危险操作怎么保证不出事？（白名单 + 参数校验 + HITL + 审计 + 幂等键）
11. 提示注入怎么防？（输入与工具输出都不可信；工具结果只作为数据不当指令；权限最小化）
12. 用户点"确认"之前进程在干什么？连接断了怎么办？（SSE 挂起 + 连接级取消回调）
13. 怎么发现模型在"编"？（引用回填 + 忠实度评测 + 服务端强制检索）

**RAG 层**
14. 文档怎么切？（按语义单元；本项目按章节）
15. 向量库怎么选？（规模决定选型；本项目用 pgvector，早期用内存版对照）
16. 召回不准怎么排查？（先看检索指标，再看模型：本项目发现"检索 100% 命中但模型不调工具"）
17. 怎么保证答案有据？（引用回填 + 断言出处）

**评测与工程层**
18. 怎么证明你的 Agent 比上个版本好？（黄金集 + 分层指标 + 存档基线）
19. 评测怎么会骗你？（指标默认诊断性、模型措辞不稳定、只测路由不测数据）
20. 怎么迁移框架而不出事？（对照实现、契约形状断言、覆盖闸门、可回滚）
21. 你怎么保证质量？（297 项测试 + 24 个脚本 + CI 门禁；并说明哪些是"真机验证"）

**深度题（能讲出这些就超过大多数人）**
22. 为什么"评测全绿"但真机在错？（本项目两个真实 bug：同名工具跨角色覆盖、`</tool_call>` 泄漏）
23. 框架的 `@ToolParam` 表达不了 enum/min/max 时你怎么办？（补一层约束注解，并让对不上属性名时**启动失败**）
24. 什么时候**不该**用框架的循环？（需要在执行前挂起等人、输出后过滤、全过程审计）
25. 模型指令遵循不可靠时怎么办？（**把可靠性从提示词搬到代码**：路由级强制注入）

---

## 三、两周学习路径（按这个项目的文件读）

| 天 | 读什么 | 目标 |
|---|---|---|
| 1 | `docs/AI模块架构文档.md` + README 架构图 | 建立全局图：请求怎么从浏览器走到模型和数据库 |
| 2–3 | `AgentRuntime`（循环/闸门/终止）+ `ChatMessage` + `OpenAiClient` | 能自己画出一次"提问→工具→回答"的时序图 |
| 4–5 | `ai/tool/declarative/*` 与 `ToolRegistry` | 能自己新增一个工具（并知道描述/schema/风险等级怎么写） |
| 6 | `ConfirmationGate` + `PendingActionStore` + `AuditService` | 讲清 HITL 与审计的实现细节 |
| 7 | `MybatisChatMemory` + `AgentConversationController` + 前端会话侧栏 | 讲清多轮记忆的存储与窗口 |
| 8–9 | `ai/rag/*`（切分/索引/检索/注入） | 能独立复现一个最小 RAG，并解释每个设计选择 |
| 10 | `.dsh/eval-rag.cjs` + `eval-p5-tools.cjs` + 各断言脚本 | 会设计评测集与指标，会读评测结果 |
| 11–12 | `docs/开发记录.md`（重点 (十九)(二十一)(二十三)(二十六)(二十九)） | 把每个坑讲成自己的故事 |
| 13–14 | 自己动手：加一个工具 / 改一处提示词 / 加一条评测 | 从"读懂"到"能改" |

---

## 四、资源推荐

> ⚠️ 下面是我按记忆推荐的**一手资料**，地址可能有变动，建议直接用标题搜索。
> 中文社区里"Agent 八股"整理帖质量参差，**优先读一手文档 + 论文**，再用中文帖查漏。

**必读（英文一手，篇幅都不长）**
- Anthropic — *Building Effective Agents*：`anthropic.com/engineering/building-effective-agents`（工作流 vs Agent 的区分、什么时候别用 Agent）
- OpenAI — *A Practical Guide to Building Agents*（PDF，官方 cookbook 里也有 function calling 最佳实践）
- HumanLayer — *12-Factor Agents*：`github.com/humanlayer/12-factor-agents`（**这个项目几乎逐条命中**：own your prompts / own your context / own your control flow / small focused agents / human in the loop）
- Lilian Weng — *LLM Powered Autonomous Agents*：`lilianweng.github.io/posts/2023-06-23-agent/`（规划/记忆/工具三件套的经典综述）
- Model Context Protocol：`modelcontextprotocol.io`（工具跨应用标准化的方向）
- LangChain / LlamaIndex 官方 blog 的 *context engineering*、*agent evaluation* 系列

**论文（挑 5 篇够用）**
- ReAct（推理+行动）、Reflexion（自我反思）、Toolformer（工具学习）
- Self-RAG / RAG 综述（Gao et al.）、Lost in the Middle（长上下文）
- MemGPT（长期记忆）——想深挖记忆机制时看

**中文（辅助）**
- 吴恩达 DeepLearning.AI 的 Agentic AI / LangGraph 短课（有中文字幕，动手向）
- 各家云厂商（阿里云、字节、腾讯）的技术博客里 RAG/Agent 工程实践文章
- 掘金/知乎可搜"Agent 面试"作查漏，但**别背结论**——用本项目的真实例子去答

---

## 五、这个项目里"最值钱"的 8 个坑（面试讲这些）

1. **同名工具跨角色互相覆盖**：白名单通过、却执行了另一个角色的实现（学生问"我选了什么课"答"你没选任何课"）——根因是工具定义只按名字索引
2. **`</tool_call>` 逐字符流式泄漏**：整段喂入的用例测不出来，是"落库正文不得含工具调用残渣"这条新断言抓到的
3. **单对象参数被框架套一层 `request`**：静默改变模型要填的形状，编译期与类型检查都不报错
4. **`@ToolParam.required` 默认 true**：可选参数变必填，模型省略就被校验器拒掉
5. **第二个数据源把主数据源顶掉**：`@ConditionalOnMissingBean(DataSource)` 的连带后果——连登录都失败
6. **工具描述反过来改变模型行为**：写了"用户确认后才执行"，模型改成用文字反问
7. **"评测全绿但真机在错"**：断言只覆盖了路由与状态码，没覆盖业务数据
8. **夹具随时间老化**：相对时间在导入那刻被固化成绝对时间，几天后测试自己红了

> 这 8 条都在 `docs/开发记录.md` 里有完整的前因后果。能把它们讲清楚，
> 比背 20 个术语更能证明你真的做过 Agent 系统。
