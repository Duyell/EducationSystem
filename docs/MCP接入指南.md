# MCP 接入指南（把教务工具接进 Cursor / Claude Desktop）

> 面向"我有一台跑着这个项目的机器，想让别的 AI 客户端用上它的工具面"。
> 设计与安全边界见 `docs/AI模块架构文档.md` 第九节；实现代码在 `backend/.../duyell/ai/mcp/`。

---

## 一、它到底给了你什么

内置 Agent（页面里的 AI 助手）用的是**我们自己的**模型；MCP 让**别人的**模型也能用同一批工具：

```
Cursor / Claude Desktop（它们自己的模型）
    │  MCP（SSE）
    ▼
本项目 MCP server ──→ 你的工具面（默认：学生 14 个只读工具）
                      「我选了什么课」「我的绩点」「我的考试」「培养方案」「学业预警」…
```

于是你可以直接在编辑器里问："我这学期几门课、绩点多少、下一场考试什么时候"，
模型调的是**真实教务数据**，而不是它猜的。

---

## 二、三步接上

### 1. 打开 MCP（默认是关的）

```bash
# 后端必须带上这个环境变量；其余按平常启动
AI_MCP_ENABLED=true java -jar edu-api/target/edu-api-0.0.1-SNAPSHOT.jar
```

启动日志里会明确列出**开放了哪些工具、排除了哪些、以及排除原因**，例如：

```
MCP 工具面已装配: role=student, 开放 14 个, 排除 3 个, allowWrites=false
  [MCP 开放] get_my_courses (READ_ONLY) - 我的已选课程
  ...
  [MCP 排除] select_course (DANGEROUS) - DANGEROUS 工具永不开放：它的安全性依赖人工确认（HITL），而 MCP 没有这条通道
  [MCP 排除] drop_course (WRITE) - WRITE 工具默认不开放给外部客户端（需要 ai.mcp.allow-writes=true）
```

### 2. 拿一个登录令牌

MCP 端点与其它接口**共用同一套鉴权**（JWT + Redis 双校验），所以需要一个 token：

```bash
curl -s -X POST http://localhost:8080/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"2023001","password":"123456"}'
# -> {"token":"eyJhbGciOiJIUzM4NCJ9....","role":"student", ...}
```

要点：

- **token 决定你是谁**：工具以持令牌那个人的身份执行（学号取自 token，不接受参数指定），
  所以"学生只能看自己的数据"这条在 MCP 上同样成立；
- **令牌有有效期**（`jwt.expiration-minutes`，默认 30 分钟）且**重新登录会让旧 token 立即失效**，
  过期后客户端会收到 401，重新取一个即可；
- 本服务默认开放**学生（student）**工具面，因此请用学生账号的 token；
  用教师令牌连上后调工具会被拒（拒绝原因会写进结果文本，审计记为 `ROLE_FORBIDDEN`）。
  想让教师/管理员用自己的工具面：启动时设 `AI_MCP_ROLE=teacher`（或 `admin`）。

### 3. 配置客户端

**Cursor**（`~/.cursor/mcp.json`，Windows 是 `%USERPROFILE%\.cursor\mcp.json`）：

```json
{
  "mcpServers": {
    "edu-system": {
      "type": "sse",
      "url": "http://127.0.0.1:8080/mcp/sse",
      "headers": { "token": "把上一步的 token 粘在这里" }
    }
  }
}
```

> `"type": "sse"` 是**实测加上去的**（2026-09-28）：不写它，Cursor 会把 URL 当 streamable HTTP 去 POST，
> 而我们这个端点是 SSE 传输（只收 GET）。用 `127.0.0.1` 而不是 `localhost` 只是为了少一个解析变量
> （本机 `localhost` 会同时解析出 `::1` 与 `127.0.0.1`）。

**Claude Desktop**（它只认 stdio，用官方桥接器 `mcp-remote` 转发到我们的 SSE 端点）：

```json
{
  "mcpServers": {
    "edu-system": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "http://localhost:8080/mcp/sse",
        "--header", "token:把上一步的 token 粘在这里"
      ]
    }
  }
}
```

> 各客户端的配置字段偶尔会变，以它自己的文档为准；关键只有两点：
> **URL 是 `http://<host>:8080/mcp/sse`**，**请求头里带 `token`**。

---

## 三、期望看到什么

接上之后，客户端会列出工具（学生面 14 个）：

```
get_my_courses          我的已选课程
get_my_scores           我的成绩
get_my_gpa              我的绩点与排名
get_my_exams            我的考试安排
get_my_training_plan    我的培养方案
audit_my_graduation     毕业学分审核
list_my_class_times     我的课表
get_my_academic_warning 我的学业预警
get_course_list         可选课程列表
recommend_courses       推荐可选课程
get_selection_status    选课开放状态
check_time_conflict     检查上课时间冲突
check_evaluation        评价状态检查
get_my_evaluations      我的评价
```

试试这些问法（都是只读的，随便问）：

- "我这学期选了几门课？都是什么？"
- "我的平均学分绩点是多少、专业排名第几？"
- "我下一场考试是哪门、什么时候、在哪个教室？"
- "我还差多少学分能毕业？"

---

## 四、安全边界（先说清楚，免得踩坑觉得"功能不全"）

| 工具类型 | 是否外放 | 为什么 |
|---|---|---|
| 只读查询 | ✅ 开放 | 不改任何数据 |
| `WRITE`（例：退课） | ⛔ 默认关闭 | 外部客户端能改我们的数据，需要显式同意：`AI_MCP_ALLOW_WRITES=true` |
| `DANGEROUS`（选课 / 录成绩 / 改成绩 / 评教） | ⛔ **永不开放** | 它们的安全性来自**人工确认**（服务端挂起、你在页面上点确认），而 MCP 没有这条通道。放出去就等于"外部客户端一次调用即可选课/录成绩"，而确认卡片永远不会出现 |

其它两条约束：

- **MCP 端点必须带 token**：不带就是 401，与其它接口完全一致（同一个 `LoginInterceptor`）；
- **每次外部调用都留痕**：`ai_tool_audit` 里记一条，`session_id = mcp:<会话号>`，
  因此"界面改的 / 内置 Agent 改的 / 外部客户端改的"三者在审计里可以区分。

---

## 五、自检与排错

```bash
# ① 自写脚本：协议级（握手 / tools/list / tools/call / 越权 / 审计 / 回退契约），无需模型
node .dsh/verify-mcp.cjs
#   RESULT: PASS=30 FAIL=0 SKIP=2      <- 本机沙箱下读库的两条会 SKIP（CI 里会真跑）
#   其中两条专门钉"回退契约"：客户端先 POST 到 /mcp/sse 时必须拿到 4xx（而不是 500），见下方排错表

# ② 官方 MCP Inspector（真实第三方客户端，不需要装 GUI）——本项目已实测通过
TOKEN=$(curl -s -X POST http://localhost:8080/login -H 'Content-Type: application/json' \
  -d '{"username":"2023001","password":"123456"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

# 列出工具（应看到 14 个只读工具）
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp/sse \
  --header "token: $TOKEN" --method tools/list

# 调用一个只读工具（应返回该生的真实课程数据，isError=false）
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp/sse \
  --header "token: $TOKEN" --method tools/call --tool-name get_my_courses

# 调一个未开放的危险工具（应报 tool_not_found —— 这是设计，不是 bug）
npx -y @modelcontextprotocol/inspector --cli http://localhost:8080/mcp/sse \
  --header "token: $TOKEN" --method tools/call --tool-name select_course --tool-arg courseId=7
```

> 上面第 ② 组命令**在本机实测通过**（Inspector 能列出全部工具、调用返回真实数据、
> 危险工具报 `tool_not_found`）。GUI 版直接用 `npx @modelcontextprotocol/inspector` 打开界面填同样两项。

| 现象 | 原因 / 处理 |
|---|---|
| 客户端显示 `connection:connect_failure`，日志里是 `Transient error connecting to streamableHttp server` + `Unrecognized keys: "code", "msg", "data"` | **已经修过的坑（2026-09-28）**：MCP 客户端普遍**先往 `/mcp/sse` 试 streamable HTTP**（POST 一条 JSON-RPC），拿到 **4xx** 才按协议回退到 SSE。此前我们这里没有匹配的处理器，异常落进全局兜底 → 回的是 `HTTP 200 + {"code":"500","msg":"服务器内部错误"}`，客户端把它当"JSON-RPC 响应解析失败"，于是**既不回退也说不清原因**。现在 POST 到 SSE 端点会正确回 **404**（`GlobalExceptionHandler` 新增 `NoResourceFoundException`/`HttpRequestMethodNotSupportedException` 处理）。**自检命令**：`curl -s -o - -w "%{http_code}" -X POST -H "token: $TOKEN" http://127.0.0.1:8080/mcp/sse` → 应为 **404**，绝不能是 500 |
| 客户端把 URL 当成 streamable HTTP（不试 SSE） | 在配置里显式声明传输类型：Cursor 的 `~/.cursor/mcp.json` 里给该 server 加 `"type": "sse"`；Claude Desktop 走 `mcp-remote` 桥接（见第二步） |
| 客户端连不上，日志 401 | token 没带、写错、或已过期；重新登录取一个。注意本项目用的是 **`token`** 头，不是 `Authorization` |
| 能连上但调工具被拒，提示"角色不符" | 服务端 `AI_MCP_ROLE` 与 token 的角色不一致：换对应角色的账号，或改 `AI_MCP_ROLE` 重启 |
| 工具列表里没有"选课/退课/评教" | **这是设计**，不是 bug，见上面的安全边界 |
| 想让教师/管理员也能用 | 一台实例一个角色面（`AI_MCP_ROLE`）。要同时支持多角色得按会话动态注册工具面，尚未实现 |
| 改了工具却在客户端看不到 | 客户端会缓存工具列表，重启客户端或重连 |
| 调用报 `tool_not_found` | 该工具没有被这个实例开放（按角色/风险等级过滤）；见启动日志里的 `[MCP 排除]` 行 |
| 「工具数 14」但聊天里一个工具都不出现 | 配置改完要**在客户端里把该 server toggle 一次**（或重启客户端）才会重连；Cursor 的排查入口是 `Ctrl+Shift+U` → Output → **MCP Logs** |

---

## 六、还没做的（如实列出）

- **stdio 传输**：Claude Desktop 需要靠 `mcp-remote` 桥接。原生 stdio 需要应用以 stdio 模式启动
  （日志要改走 stderr），尚未实现；
- **按会话动态工具面**：现在是"配置一个角色面 + 校验令牌角色"，同一实例只服务一个角色的工具面；
- **resources / prompts 能力**：只声明了 tools（声明了却给不出内容的协议能力不如不声明）；
- ~~**GUI 客户端实机验证**~~ **✅ 已做（2026-09-28，Cursor）**：协议层先前已用官方 Inspector CLI 跑通；
  这一轮补上了界面这一半，并因此揪出上面排错表第一条的 4xx 缺陷。**证据**（Cursor 自己的日志
  `%APPDATA%\Cursor\logs\<会话>\window*\exthost\anysphere.cursor-mcp\MCP user-edu-system.log`）：

  ```
  21:06:18.575 [info] [V2] Handling CreateClient action
  21:06:18.851 [warning] Error connecting to streamableHttp server, falling back to SSE:
                      Streamable HTTP error: Error POSTing to endpoint: {"code":"404","msg":"接口不存在: mcp/sse","data":null}
  21:06:19.038 [info] Successfully connected to sse server
  21:06:19.219 [info] [V2 FSM] connection:connect_success: conn=connecting -> conn=connected
  21:06:19.219 [info] CreateClient completed, connected: true, statusType: connected
  ```

  这两行放在一起就是那条 4xx 契约的全部意义：**"falling back to SSE" 是被我们的 404 触发的**
  （同一个客户端、同一份配置，此前回 500 时它直接 `connect_failure`、连回退都不试）。
  Claude Desktop 仍未实机（要用 `mcp-remote` 桥接，且本机未安装）。
