# 教务系统 AI 助手模块 —— 架构与实现详解

## 一、模块概述

本模块实现了一个 **智能教务助手（AI Agent）**，用户可以用自然语言与 AI 对话，让 AI 帮他们查询课表、选课退课、录入成绩等。三个角色（学生、教师、管理员）各有不同的功能。

这不是一个简单的一问一答的聊天机器人，而是一个 **能调用工具执行实际操作** 的 AI 代理（Agent）——它不仅能用自然语言回答，还能操作数据库。

### 核心概念：工具调用（Tool Calling）

传统聊天 AI 只会"说话"，但不会"做事"。**工具调用**解决了这个问题：

1. 系统告诉 AI 有哪些"工具"可以用（如 `select_course` 工具，参数是 `courseId`）
2. AI 判断需要用什么工具——用户说"帮我选课 ID 为 5"，AI 知道要调用 `select_course(courseId=5)`
3. AI 返回"工具调用请求"——不是直接回答，而是说"我需要调用 select_course，参数 courseId=5"
4. 程序真正执行这个工具——去数据库里添加选课记录
5. 把执行结果告诉 AI——AI 拿到结果后，用自然语言回复用户

---

## 二、模块文件清单（11 个后端文件 + 1 个前端文件）

### 后端 - 配置层（edu-common 模块）

| 文件 | 路径 | 职责 |
|------|------|------|
| `AiProperties.java` | `edu-common/.../config/AiProperties.java` | 读取 `ai.apiKey`、`ai.model`、`ai.baseUrl` 配置 |
| `AiConfig.java` | `edu-common/.../config/AiConfig.java` | 创建 `HttpClient` Bean |

### 后端 - 应用层（edu-api 模块）

| 文件 | 路径 | 职责 |
|------|------|------|
| `ChatMessage.java` | `edu-api/.../dto/ChatMessage.java` | 消息数据结构（DTO） |
| `ToolDefinition.java` | `edu-api/.../tool/ToolDefinition.java` | 工具定义（Record） |
| `ToolRegistry.java` | `edu-api/.../tool/ToolRegistry.java` | 工具注册中心 |
| `StudentToolRegistrar.java` | `edu-api/.../tool/StudentToolRegistrar.java` | 注册学生工具（8 个） |
| `TeacherToolRegistrar.java` | `edu-api/.../tool/TeacherToolRegistrar.java` | 注册教师工具（5 个） |
| `AdminToolRegistrar.java` | `edu-api/.../tool/AdminToolRegistrar.java` | 注册管理员工具（8 个） |
| `OpenAiClient.java` | `edu-api/.../service/OpenAiClient.java` | 底层 HTTP/SSE 客户端 |
| `AiChatService.java` | `edu-api/.../service/AiChatService.java` | **核心**：系统提示词 + 工具调用循环 |
| `AiController.java` | `edu-api/.../controller/AiController.java` | REST 控制器（2 个接口） |

### 前端（Vue 3）

| 文件 | 路径 | 职责 |
|------|------|------|
| `index.vue` | `frontend/.../views/ai/index.vue` | AI 聊天界面 + SSE 流接收 + Markdown 渲染 |

---

## 三、整体架构图

```
frontend (Vue 3)
    │
    │  POST /api/ai/chat (SSE 流式传输)
    │  GET  /api/ai/config
    ▼
AiController.java          ← 接收 HTTP 请求，返回 SSE 流
    │
    ▼
AiChatService.java         ← 核心编排：系统提示词 + 工具调用循环
    │          │
    │          ├── ToolRegistry.java        ← 工具注册中心（按角色分组）
    │          │       ├── StudentToolRegistrar.java  (8 个工具)
    │          │       ├── TeacherToolRegistrar.java  (5 个工具)
    │          │       └── AdminToolRegistrar.java    (8 个工具)
    │          │
    │          └── OpenAiClient.java        ← 与 AI API 通信（HTTP + SSE 解析）
    │
    ▼
AiConfig.java / AiProperties.java  ← 读取配置（API 地址、密钥、模型名）
```

---

## 四、逐层详解

### 第 1 层：配置层

**`AiProperties.java`**
```java
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String baseUrl = "https://api.openai.com/v1";  // AI 服务地址，默认 OpenAI
    private String apiKey = "";                             // API 密钥
    private String model = "gpt-4o-mini";                   // 模型名称
}
```

项目支持**任意兼容 OpenAI 接口的服务**——只要改配置，就能从 OpenAI 切换到国内的大模型服务（如 DeepSeek、通义千问等），不需要改任何代码。

**`AiConfig.java`**
```java
@Bean
public HttpClient aiHttpClient() {
    return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
}
```

创建 Java HTTP 客户端 Bean，整个 AI 模块共用这一个客户端，连接超时 30 秒。

---

### 第 2 层：数据结构 —— `ChatMessage.java`

遵循 OpenAI API 标准格式：

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | String | 消息角色：`system`（系统提示词）、`user`（用户）、`assistant`（AI 回复）、`tool`（工具返回值） |
| `content` | String | 文本内容 |
| `reasoningContent` | String | AI 的思考过程（推理模型如 DeepSeek-R1 会输出思考链） |
| `toolCalls` | List\<ToolCall\> | 工具调用列表（AI 说"我需要调用这个工具"） |
| `toolCallId` | String | 工具调用 ID，用于关联"调用请求"和"执行结果" |
| `name` | String | 工具名称（仅 tool 角色消息使用） |

**`ToolCall`** 嵌套类：
- `id`：工具调用唯一 ID
- `type`：类型（固定为 `"function"`）
- `function`：包含 `name`（工具名）和 `arguments`（JSON 格式的参数）

当 AI 要调用工具时，消息格式为 `role=assistant` + `toolCalls=[{id, function:{name, arguments}}]`；程序把执行结果发回去时，消息格式为 `role=tool` + `toolCallId` + `content=执行结果`。

---

### 第 3 层：工具系统

#### 3.1 `ToolDefinition.java` —— 工具定义

```java
public record ToolDefinition(
    String name,                      // 工具名称，如 "select_course"
    String description,               // 工具描述，AI 靠这个理解工具的用途
    Map<String, Object> parameters,   // 参数定义（JSON Schema 格式）
    ToolExecutor executor             // 真正的执行逻辑（Lambda 表达式）
) {
    @FunctionalInterface
    public interface ToolExecutor {
        String execute(Map<String, Object> args, String userId, String role) throws Exception;
    }
}
```

- **`name`** 和 **`description`**：AI 通过这两项理解工具的用途
- **`parameters`**：JSON Schema 格式，描述工具需要什么参数。AI 据此从用户自然语言中提取正确的参数值
- **`executor`**：Lambda 表达式，真正执行数据库操作。接收 AI 给出的参数 + 当前用户 ID + 角色，返回 JSON 字符串

#### 3.2 `ToolRegistry.java` —— 工具注册中心

内存中的工具仓库，线程安全（`ConcurrentHashMap` + `CopyOnWriteArraySet`）：

| 方法 | 功能 |
|------|------|
| `register(role, tool)` | 注册工具并关联角色 |
| `getToolsByRole(role)` | 按角色获取工具列表（学生 8 个、教师 5 个、管理员 8 个） |
| `getTool(name)` | 按名称获取单个工具 |
| `toToolsPayload(toolDefs)` | 把工具定义转换成 OpenAI API 要求的 JSON 格式 |

`toToolsPayload` 生成的 JSON 格式：
```json
{
  "type": "function",
  "function": {
    "name": "select_course",
    "description": "学生选课，添加课程到已选列表",
    "parameters": {
      "type": "object",
      "properties": {
        "courseId": { "type": "integer", "description": "课程ID" }
      },
      "required": ["courseId"]
    }
  }
}
```

#### 3.3 角色工具注册器

三个类均实现 `InitializingBean`，在 Spring 启动时自动运行 `afterPropertiesSet()` 注册工具。

##### 学生工具（`StudentToolRegistrar.java`）—— 8 个

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `get_my_courses` | 查看已选课程列表 | 无 |
| `get_my_scores` | 查看成绩 | 无 |
| `select_course` | 选课 | courseId (Integer, 必填) |
| `drop_course` | 退课 | courseId (Integer, 必填) |
| `get_course_list` | 查看可选课程列表 | courseName (String, 可选) |
| `evaluate_teacher` | 评价教师 | courseId, teacherId, score, content |
| `check_evaluation` | 检查是否已评价 | courseId (Integer, 必填) |
| `get_my_evaluations` | 查看我的评价 | 无 |

##### 教师工具（`TeacherToolRegistrar.java`）—— 5 个

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `get_my_courses` | 查看我教授的课程 | 无 |
| `get_course_students` | 查看选课学生 | courseId (Integer, 必填) |
| `enter_score` | 录入成绩 | courseId, studentId, usualScore, examScore |
| `update_score` | 修改成绩 | id, usualScore, examScore |
| `get_my_evaluations` | 查看学生评价 | 无 |

##### 管理员工具（`AdminToolRegistrar.java`）—— 8 个（全部只读）

| 工具名 | 功能 | 参数 |
|--------|------|------|
| `get_statistics` | 系统统计数据 | 无 |
| `list_users` | 查询用户列表 | role, username（均可选） |
| `list_students` | 查询学生列表 | studentName, studentId（均可选） |
| `list_teachers` | 查询教师列表 | teacherName, teacherId（均可选） |
| `list_courses` | 查询课程列表 | courseName（可选） |
| `list_colleges` | 查询学院列表 | 无 |
| `list_majors` | 查询专业列表 | 无 |
| `list_classes` | 查询班级列表 | 无 |

**新增工具的方式**：在对应 Registrar 的 `afterPropertiesSet()` 中添加一个 `registry.register()` 调用即可，无需改动其他任何代码。

---

### 第 4 层：AI 客户端 —— `OpenAiClient.java`

与 AI 服务直接通信的底层组件。

#### SSE（Server-Sent Events）原理

正常 HTTP 请求是"一问一答"——等服务端完全处理好后一次性返回。但 AI 生成回复需要时间，SSE 实现了**边生成边发送**，前端收到一个字就显示一个字（打字效果）。

#### `streamChat()` 方法签名

```java
public void streamChat(
    List<ChatMessage> messages,           // 对话历史
    List<Map<String, Object>> tools,      // 可用工具列表
    Consumer<String> onToken,             // 收到文本 token 时回调 → 转发给前端
    Consumer<List<ChatMessage.ToolCall>> onToolCalls,  // 收到工具调用时回调
    Consumer<String> onReasoningContent,  // 收到推理内容时回调
    Runnable onDone,                      // 流结束时回调
    Consumer<String> onError              // 出错时回调
)
```

#### 请求格式

```json
{
  "model": "gpt-4o-mini",
  "stream": true,
  "messages": [ ... ],
  "tools": [ ... ]
}
```

#### 流式响应解析

AI 服务返回的 SSE 流格式：
```
data: {"choices":[{"delta":{"content":"好"}}]}
data: {"choices":[{"delta":{"content":"的"}}]}
data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"select_course","arguments":"{\"courseId\":5}"}}]}}]}
data: [DONE]
```

解析逻辑：

1. **文本 Token**（`delta.content`）→ 调用 `onToken` 回调
2. **工具调用**（`delta.tool_calls`）→ 同一 `index` 的多个片段用 `DeltaToolCall` 内部类拼接（因为参数 JSON 可能分多次返回）
3. **结束信号**（`finish_reason` = `tool_calls` 或 `stop`）→ 调用对应回调

---

### 第 5 层：核心编排 —— `AiChatService.java`

整个 AI 模块的"大脑"。

#### 5.1 系统提示词（System Prompt）

三个角色各有独立的系统提示词，用于设定 AI 的"人设"和行为规范：

- **学生**：可以选课、退课、查看成绩、评价教师。规则：选课退课前须确认、成绩只读
- **教师**：可以查看课程和评价、录入/修改成绩。规则：成绩公式 = 平时成绩 × 0.4 + 考试成绩 × 0.6、操作前须确认
- **管理员**：只能查询（查看统计、用户/学生/教师/课程/学院/专业/班级列表）。规则：最多返回 50 条

#### 5.2 `chat()` 方法流程

```
POST /ai/chat 请求进来
    │
    ▼
1. 解析 JWT Token → 提取 userId 和 role
   │ 如果 Token 无效 → 返回错误 SSE 事件
   ▼
2. 创建 SseEmitter（SSE 连接，超时 5 分钟）
   │ 注册 onTimeout / onError / onCompletion 回调
   ▼
3. 在后台线程中执行 processChat()
```

#### 5.3 `processChat()` —— 工具调用循环（核心算法）

```
最多循环 10 次：

  ┌─→ 调用 OpenAiClient.streamChat()（带消息 + 工具列表）
  │       │
  │       ├── AI 返回文本 → 通过 SSE 发送给前端 → 结束
  │       │
  │       └── AI 返回工具调用 → 逐一执行每个工具
  │               │
  │               ├── 发送状态提示给前端："🔄 正在执行: xxx"
  │               ├── 从 ToolRegistry 找到工具定义
  │               ├── 解析参数（JSON → Map）
  │               ├── 调用 executor.execute(args, userId, role)
  │               ├── 将 assistant 消息（带 toolCalls）和 tool 消息（执行结果）加入消息列表
  │               └── 循环回去，让 AI 基于结果生成最终回复
  │
  └── 超过 10 次 → 发送警告 + 强制结束
```

**为什么需要循环？** 一个对话可能需要多轮工具调用。例如用户同时问"我有什么课？成绩怎样？"，AI 第一轮同时调用 `get_my_courses` 和 `get_my_scores`，拿到结果后第二轮用自然语言整理回复。

**安全保护**：
- 最多循环 10 次，防止无限循环
- SSE 连接超时 5 分钟

#### 5.4 消息构建过程示例

```
第 1 轮:
  messages = [
    {role: "system", content: "你是一个智能的教务系统助手..."},
    {role: "user", content: "帮我选课 ID 为 5"}
  ]
  → AI 返回 toolCalls: [select_course(courseId=5)]

第 1 轮结束（添加 assistant + tool 消息）:
  messages = [
    {role: "system", content: "..."},
    {role: "user", content: "帮我选课 ID 为 5"},
    {role: "assistant", toolCalls: [{id:"call_abc", function:{name:"select_course", arguments:"{courseId:5}"}}]},
    {role: "tool", toolCallId: "call_abc", content: '{"message":"选课成功"}'}
  ]

第 2 轮:
  → AI 看到执行结果，用自然语言回复："已经帮你成功选了课程..."
  → 通过 SSE 逐字发送给前端 → 结束
```

---

### 第 6 层：控制器 —— `AiController.java`

#### 接口 1：POST `/api/ai/chat`

- **入参**：`{"message": "用户输入的自然语言"}` + Header `token`（JWT）
- **返回**：`SseEmitter`（SSE 流式响应，不是普通 JSON）

SSE 事件类型：

| type | 说明 |
|------|------|
| `token` | AI 正在逐字输出文本（打字效果） |
| `status` | 状态提示（如"🔄 正在执行: select_course"） |
| `error` | 错误信息 |
| `done` | 本轮对话结束 |

#### 接口 2：GET `/api/ai/config`

- **返回**：`{"configured": true/false, "model": "...", "baseUrl": "..."}`
- **用途**：前端判断 AI 是否已配置，决定是否显示"已配置/未配置"标签

---

### 第 7 层：前端 —— `index.vue`

Vue 3 单文件组件，Element Plus UI 框架。

#### 功能要点

1. **根据角色显示不同示例提示词**：
   - 管理员：查看系统统计数据、列出所有学生、查询教师列表、查看课程列表
   - 教师：查看我的课程、查看我的评价、怎么录入成绩？
   - 学生：查看我的课程、查看我的成绩、有哪些课程可以选？

2. **SSE 流接收**：使用 `fetch` + `ReadableStream` 逐行解析 `data:` 开头的 SSE 事件

3. **Markdown 渲染**：使用 `marked` 库将 AI 返回的 Markdown 转换为 HTML，支持表格、列表、代码块

4. **打字动画**：收到 `token` 事件时，将内容追加到 `currentAssistantMsg.content`，实时渲染

5. **状态显示**：Loading 动画（三个跳动的小圆点）、错误提示、状态提示

#### 核心代码流程

```javascript
// 1. 发起 SSE 请求
const response = await fetch('/api/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'token': token },
    body: JSON.stringify({ message: text }),
});

// 2. 逐行读取 SSE 流
const reader = response.body.getReader();
const decoder = new TextDecoder();
while (true) {
    const { done, value } = await reader.read();
    // 按行分割，解析 "data: {...}" 格式
}

// 3. 处理 SSE 事件
// type=token   → 追加文本，Markdown 渲染
// type=status  → 显示工具执行状态
// type=error   → 显示错误
// type=done    → 结束
```

---

## 五、完整数据流示例

以"学生说『帮我选课 ID 为 5 的课程』"为例：

```
1. 前端 → POST /api/ai/chat
   Body: {"message": "帮我选课 ID 为 5 的课程"}
   Header: token=eyJhbG...

2. AiController.chat()
   → 提取 message 和 token

3. AiChatService.chat()
   → 解析 JWT: userId="2021001", role="student"
   → 创建 SseEmitter（5 分钟超时）
   → 后台线程执行 processChat()

4. AiChatService.processChat()
   → 系统提示词: SYSTEM_PROMPT_STUDENT
   → 初始消息: [{role:"system", content:"..."}, {role:"user", content:"帮我选课..."}]
   → 获取 student 角色的 8 个工具，转成 OpenAI 格式

5. OpenAiClient.streamChat()（第 1 轮）
   → HTTP POST → AI 服务 /chat/completions
   → AI 返回: toolCalls=[{id:"call_abc", function:{name:"select_course", arguments:"{courseId:5}"}}]

6. AiChatService 处理工具调用
   → SSE 给前端: {"type":"status","content":"🔄 正在执行: select_course"}
   → 从 ToolRegistry 找到 "select_course" 工具
   → 解析参数: {courseId: 5}
   → 执行 Lambda:
       - courseSelectionMapper.selectByStudentId("2021001") → 检查重复（无）
       - courseMapper.selectCourseById(5) → 检查名额（有）
       - courseSelectionMapper.add(5, "2021001") → 写入数据库
       - 返回 '{"message":"选课成功"}'
   → 消息列表追加:
       [2] role=assistant, toolCalls=[{id:"call_abc", ...}]
       [3] role=tool, toolCallId="call_abc", content='{"message":"选课成功"}'

7. OpenAiClient.streamChat()（第 2 轮）
   → AI 看到执行结果，用自然语言回复
   → 收到 token: "已" "经" "帮" "你" "成" "功" "选" "课" ...
   → 每个 token 通过 onToken 回调 → SSE 发给前端

8. AiChatService 发送结束信号
   → SSE: {"type":"done"}
   → emitter.complete()

9. 前端 index.vue
   → token 事件：逐字渲染 Markdown
   → done 事件：完成
```

---

## 六、设计亮点

1. **工具系统可插拔**：新增工具只需在 Registrar 中添加一个 `registry.register()` 调用，不改其他代码

2. **与具体 AI 服务解耦**：基于 OpenAI 兼容接口，切换到 DeepSeek / 通义千问 / GPT-4 只需改配置文件

3. **角色权限隔离**：每个角色看到不同的系统提示词、可调用不同的工具。后端根据 JWT 中的真实角色提供工具列表，保证安全性

4. **流式传输体验**：SSE 实现打字效果，工具执行时有状态提示

5. **安全保护**：
   - 工具调用最多循环 10 次，防止死循环
   - 选课前检查名额和重复
   - 成绩录入前检查是否已存在
   - 管理员工具全部只读

6. **并发安全**：`ToolRegistry` 使用 `ConcurrentHashMap` 和 `CopyOnWriteArraySet`

---

## 七、如何新增一个工具

以给教师角色新增一个"查看学生某门课的成绩"工具为例：

在 `TeacherToolRegistrar.java` 的 `afterPropertiesSet()` 中添加：

```java
registry.register("teacher", new ToolDefinition(
    "get_student_scores", "查看某门课程中某位学生的成绩",
    Map.of(
        "type", "object",
        "properties", Map.of(
            "courseId", Map.of("type", "integer", "description", "课程ID"),
            "studentId", Map.of("type", "string", "description", "学生学号")
        ),
        "required", List.of("courseId", "studentId")
    ),
    (args, userId, role) -> {
        Integer courseId = Integer.valueOf(args.get("courseId").toString());
        String studentId = (String) args.get("studentId");
        Score score = scoreMapper.select(courseId, Integer.valueOf(studentId));
        return objectMapper.writeValueAsString(score);
    }
));
```

仅此而已——不需要改 `ToolRegistry`、`ToolDefinition`、`AiChatService` 等任何其他文件。
