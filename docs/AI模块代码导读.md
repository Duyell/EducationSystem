# AI 模块代码导读（49 个文件怎么读）

> 目的：给你一条**最短的理解路径**。核心原则——**别按目录顺序读，按"入口 → 主链 → 分支"读**。
> 配套：`docs/AI模块架构文档.md`（分层讲解）、`docs/Agent学习地图与面试要点.md`（知识点与面试要点）。

---

## 一、先看总量：48 个文件，但只要精读 6 个

`backend/.../duyell/ai/` 下共 49 个 Java 文件。**约 1600 行覆盖 80% 的理解**：

| 顺序 | 文件 | 行数 | 读它回答什么 |
|---|---|---|---|
| 1 | `controller/AiController.java` | 116 | 入口有哪几个：`/ai/chat`、`/ai/confirm`、`/ai/tools`、`/ai/config` |
| 2 | `service/AiChatService.java` | 388 | 传输层：SSE 怎么建、限流、会话归属校验、把活交给 runtime —— **只读它的几个 public 方法与 `processChat`，其余略读** |
| 3 | `runtime/AgentRuntime.java` | 467 | **全文精读**：循环、迭代上限、四道闸门接入点、事件发布、空响应重试、来源转发 |
| 4 | `service/OpenAiClient.java` | 258 | 协议层：请求体里 `tools` 怎么拼、SSE 增量怎么解析成 token / tool_call |
| 5 | `tool/ToolRegistry.java` | 154 | 工具注册（按角色存）、`getToolsByRole`、`executeForRole`（白名单 + 变更来源标记） |
| 6 | `tool/JsonSchemaToolArgumentValidator.java` | 214 | 参数校验：拿同一份 Schema 在服务端再校验一次 |

**按需读**（用到再看）：`tool/declarative/`（**只挑 1~2 个工具方法看**，别读那 1124 行）、
`confirm/`（HITL）、`audit/`（审计与幂等）、`guard/ToolCallTextGuard`（输出护栏）、
`memory/MybatisChatMemory`、`runtime/AgentEvent*`（事件契约）、`rag/*`（RAG 全链）。

**可以完全不看**：`mcp/*`（对外协议，除非要讲这一块）、前端 21 个页面里除 `views/ai/` 之外的部分、
20 多个业务 Service/Mapper（它们只是工具内部的实现细节）、`.dsh/` 30 个脚本的内部实现。

---

## 二、三遍读法（一遍读懂是幻觉）

**第 1 遍：跑起来看现象（半天）** —— 先有现象，再看代码，顺序反过来最省时间。

```bash
# 假模型（确定性，推荐先用它）
node .dsh/fake-model.cjs                                  # 另开一个窗口
AI_BASE_URL=http://127.0.0.1:11435/v1 AI_API_KEY=stub AI_MODEL=fake-model \
  java -jar backend/edu-system-server/edu-api/target/edu-api-0.0.1-SNAPSHOT.jar
# 拿 token 后，直接看 SSE 原始事件流
curl -N -X POST http://localhost:8080/ai/chat -H 'Content-Type: application/json' \
  -H "token: <登录拿到的 JWT>" -d '{"message":"我绩点多少"}'
```

把屏幕上出现的**事件顺序抄下来**（`status → token… → done`）。事件顺序就是代码结构。

**第 2 遍：只跟一条主链**（1~2 天）——见第三节，画出时序图。

**第 3 遍：挑一个问题深挖**（每个半天）——见第五节的自测题。

---

## 三、两条主链（照着这个在代码里走一遍）

### 主链 A：只读请求（"我绩点多少"）

```
POST /ai/chat
 └─ AiController.chat()
     └─ AiChatService.chat(message, token, conversationId)     // 建 SseEmitter；限流
         └─ CompletableFuture.runAsync(...)                    // 后台线程跑，别占 MVC 线程
             └─ processChat(...)
                 ├─ resolveConversation(...)                   // 归属校验：不是我的会话就拒
                 ├─ historyMessages(...)                       // 取最近 N 条历史
                 └─ AgentRuntime.run(request, events)          // ★ 核心
                     ├─ withAugmentedContext(...)              // 制度类问题：先强制检索并注入
                     ├─ for (iteration < maxIterations)        // ★ 循环在这
                     │   ├─ openAiClient.streamChat(messages, tools, ...)   // 带上工具清单
                     │   │   ├─ guard.feed(token) → events.publish(token)   // 逐 token 过护栏并推 SSE
                     │   │   └─ 收齐本轮 tool_calls
                     │   ├─ 模型没要工具 → publish(done) + return Outcome(completed)
                     │   └─ 模型要工具 → 逐条处理（★ 四道闸门都在这里）
                     │       ├─ 名字不在注册表 → audit(UNKNOWN) + 回灌"已拦截"
                     │       ├─ toolArgumentValidator.validate(...) 不通过 → audit + 回灌原因
                     │       ├─ risk == DANGEROUS → awaitConfirmation(...)  // 挂起
                     │       └─ 否则 → toolRegistry.executeForRole(...) → audit(...)
                     │              └─ 结果作为 tool 消息追加进 messages → 下一轮
                     └─ publishSourcesIfAny(...)               // 工具返回里带 citation 就转发来源事件
         └─ 落库助手正文 + 来源 → emitter.complete()
```

### 主链 B：写请求（"帮我选课"）——只有"挂起/恢复"这一段不同

```
… 走到 risk == DANGEROUS
 ├─ events.publish(CONFIRM, confirmId, 展示名, 参数摘要)   // 前端弹确认卡片
 ├─ confirmationGate.suspend(...)                          // 待确认动作存 Redis（TTL = 确认超时，默认 180 秒），线程等待
 └─ 本轮结束，**数据库零变化**

用户点「确认」→ POST /ai/confirm
 └─ AiChatService.resolveConfirmation(confirmId, userId, approved)
     └─ ConfirmationGate.confirm(...)  // 唤醒等待中的线程
         └─ AgentRuntime 恢复：
             ├─ 用 confirmId 作幂等键查审计 → 已成功执行过就复用结果（防连点两次）
             └─ 否则执行工具 → 结果回灌 → 继续循环 → 最终回答
```

> 看懂这两条链，就等于看懂了这个 Agent。剩下的（RAG、MCP、业务工具内部实现）都是挂在这条链上的插件。

---

## 四、把测试当调试器（最快的"看懂"办法）

测试是**可执行的需求说明**，比读实现快：

| 想看什么 | 跑哪个测试 | 为什么快 |
|---|---|---|
| 事件序列协议 | `AgentRuntimeTest` | 它用"记录事件"的实现替代 SSE，直接断言事件顺序，读它比读 runtime 快 |
| 审计与幂等 | `AiAuditServiceTest` | 含"重复请求只执行一次"、"幂等查询失败不阻塞业务" |
| 确认挂起/唤醒 | `ConfirmationGateTest` | 超时、重复确认、取消 |
| 工具面不变量 | `DeclarativeMigrationCoverageTest` | 断言"注册集合 == 声明式集合" |
| 端到端真机 | `.dsh/verify-real-llm.cjs` | 真模型走一遍并把事件打出来 |

```bash
cd backend/edu-system-server
mvn -o test -pl edu-api -Dtest=AgentRuntimeTest        # 单类跑，几秒钟
```

---

## 五、五个"顺着问题读"的自测题

| 自测题 | 怎么找答案 | 你应该能说出来 |
|---|---|---|
| 模型编了一个不存在的工具名会怎样？ | 搜 `UNKNOWN_TOOL` | 服务端拒绝 + 记审计 + 把原因当工具结果回灌，让模型自我纠正，**不抛异常** |
| 用户连点两次"确认"会不会选两次课？ | 搜 `confirmId` → `findExecuted` → 审计表唯一约束 | 确认令牌就是幂等键，第二次命中直接复用结果 |
| 学生能不能调用教师工具？ | 搜 `executeForRole` → `isAllowedForRole` | 工具定义按「角色 + 名字」存，白名单查不到就拒绝 |
| 模型死循环怎么办？ | `AgentRuntime` 的 `maxIterations` 与调用次数上限 | 双重上限：轮数 + 单轮工具调用总数 |
| 模型不检索就编制度怎么办？ | 搜 `ContextAugmenter` / `PolicyContextAugmenter` | 识别到制度意图就**强制检索并注入**，不依赖模型自觉 |

---

## 六、五天安排（每天 2~3 小时）

| 天 | 做什么 | 验收（做不到就重来） |
|---|---|---|
| 1 | 用假模型跑一次，把 SSE 事件顺序抄下来；再读 `AiController` + `AgentEventType` | 能说出 8 种事件各自什么时候发 |
| 2 | 跟主链 A，在代码里逐方法走一遍，画时序图 | 合上代码能画出来，并说清循环退出条件 |
| 3 | 跟主链 B（确认 → 恢复 → 幂等）；读 `ConfirmationGate` + `AiAuditService` | 能讲清"确认前为什么数据库零变化" |
| 4 | 工具层：读 `ToolRegistry` + 1 个声明式工具，然后**自己加一个工具**（照着写） | 新工具能被模型调用，工具面断言仍全绿 |
| 5 | RAG：`PolicyChunker → PolicyIndexer → PolicySearchService → PolicyContextAugmenter` | 能解释切分粒度 / 幂等重建 / 阈值 / 强制检索的取舍 |

---

## 七、三个别掉进去的坑

1. **别从 `StudentDeclarativeTools.java`（1124 行）开始**——它是"业务实现的集合"，
   读其中 1~2 个方法知道"一个工具长什么样"就够，其余全是重复模式。
2. **别一开始读前端**——先把后端事件流看懂（前端只是把 8 种事件渲染成气泡/卡片）。
3. **别背类名**——记住**两条主链 + 五个接入点**（白名单 / 参数校验 / 人工确认 / 审计 / 输出护栏）即可，
   需要时再回去查具体类。
