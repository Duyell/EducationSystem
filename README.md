# EducationSystem 教务系统

基于 Spring Boot 3.5 + Vue 3 的教务管理系统，支持管理员、教师、学生三种角色，内置 AI 对话助手（支持工具调用：选课、录成绩、查评价等）。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5、MyBatis、MySQL 8、Redis、Spring Security(BCrypt)、JWT (jjwt 0.13) |
| 前端 | Vue 3.5、Vite、TypeScript(strict)、Element Plus、Pinia |
| AI | OpenAI 兼容协议（默认 DeepSeek），SSE 流式 + 工具调用（Tool Calling） |

## 目录结构

```
├── backend/edu-system-server/   # 后端（Maven 多模块：edu-pojo / edu-common / edu-api）
├── frontend/edu-system-client/  # 前端（Vue 3 + Vite）
├── docs/                        # 文档（开发记录、AI 模块架构、SQL 迁移脚本）
├── edujwxt.sql                  # 数据库建表 + 基础数据
├── seed_data.sql                # 演示种子数据
└── docker-compose.yml           # 一键部署配置
```

## 本地开发

**前置**：JDK 21、Maven、Node 20+、MySQL 8、Redis

1. 初始化数据库：执行 `edujwxt.sql`（建表）和 `seed_data.sql`（种子数据）
2. 后端：`cd backend/edu-system-server && mvn spring-boot:run -pl edu-api -am`（端口 8080）
3. 前端：`cd frontend/edu-system-client && npm install && npm run dev`（端口 5173，已配置代理到 8080）
4. 可选：设置环境变量 `AI_API_KEY` 启用 AI 对话

**注意**：前端依赖请使用 npm 安装（项目以 package-lock.json 为准）。

## 测试账号（种子数据）

| 角色 | 账号 | 密码 |
|---|---|---|
| 管理员 | admin01 | 123456 |
| 教师 | 10001 / 10002 | 123456 |
| 学生 | 2023001 / 2023002 | 123456 |

## Docker 一键部署

```bash
docker compose up -d --build
```

- 前端：http://localhost
- 后端：http://localhost:8080
- 首次启动自动建表并导入种子数据

生产环境部署前务必修改 `docker-compose.yml` 中的：
- `MYSQL_ROOT_PASSWORD` / `SPRING_DATASOURCE_PASSWORD`（数据库密码）
- `JWT_SECRET`（>=32 字节强随机密钥）
- `AI_API_KEY`（环境变量注入，勿写入仓库）

## 安全说明

- 后端已实现基于 JWT 的登录校验 + 角色鉴权（拦截器）+ 数据归属校验（教师仅能操作自己课程的分数）
- 数据库迁移脚本：`docs/sql/2026-08-13-security-migration.sql`（唯一约束 + 索引，老库执行一次）
- 更多历史改动见 `docs/开发记录.md`
