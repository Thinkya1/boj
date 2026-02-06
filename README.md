# boj-backend

在线判题（OJ）后端服务，包含题目管理、提交判题、帖子搜索、文件上传等能力，并支持[远程代码沙箱执行](https://github.com/Thinkya1/boj-sandbox)。

## 目录结构
- `src/main/java`: 后端主业务代码
- `src/main/resources`: 配置与 Mapper
- `sandbox/`: 代码沙箱服务（独立 Spring Boot）
- `sql/`: 数据库初始化脚本
- `doc/`: 项目相关文档

## 技术栈
- Spring Boot 2.7.x
- MyBatis-Plus 3.5.x
- MySQL 8.x
- Redis
- Docker（沙箱执行）

## 环境要求
- JDK 8
- Maven 3.6+
- MySQL 8.x
- Docker（使用远程沙箱时需要）

## 快速开始

1. 初始化数据库
```sql
-- 执行 sql/create_table.sql
```

2. 配置环境变量（推荐使用 `.env` / Docker Compose）
```env
# MySQL
DB_URL=jdbc:mysql://localhost:3306/boj
DB_USERNAME=root
DB_PASSWORD=change_me

# 代码沙箱（example / remote）
CODESANDBOX_TYPE=remote
```

2.1 Docker Compose 部署（推荐）
```bash
# 1) 编辑 .env（不要提交到 git）
# 2) 编辑 src/main/resources/application-prod.yml（不要提交到 git）
docker compose up -d --build
```

3. 启动后端
```bash
mvn -q -DskipTests spring-boot:run
```

4. 启动沙箱（`codesandbox.type=remote` 时需要）
```bash
cd sandbox
mvn -q -DskipTests spring-boot:run
```

## 判题结果说明
- `status`: 判题流程状态（WAITING/RUNNING/SUCCEED/FAILED）
- `result`: 判题结果（AC/WA/TLE/MLE/RE/CE 等）
- `judgeInfo`: 判题详情（含 `caseResults`）

前端展示建议优先使用 `result`，样例级状态使用 `judgeInfo.caseResults[].status`。

## AI 助手
在题目编辑页提供 AI 对话助手，基于 LangChain4j 接入 DeepSeek（OpenAI 兼容接口），支持多轮对话与流式输出，返回修正代码的 unified diff。

- 配置 `src/main/resources/application.yml` 中的 `ai.chat` 参数
- 接口：`POST /ai/chat/stream`（SSE）
  - 请求体示例：
    ```json
    {
      "questionId": 1,
      "language": "java",
      "code": "public class Main { ... }",
      "prompt": "请帮我修正超时问题",
      "history": [
        {"role": "user", "content": "之前的提问"},
        {"role": "assistant", "content": "之前的回复"}
      ]
    }
    ```

## 代码沙箱说明
沙箱服务提供 `/executeCode` 接口，支持 Docker 隔离执行，限制 CPU/内存/网络，并返回输出与资源占用。
