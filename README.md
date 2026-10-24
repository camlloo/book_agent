# Book_Agent

Book 阅读与协作写作助手。文章与 Book 统一为同一内容模型；Vue 负责三栏阅读和写作界面，Java 是 Book 与任务的唯一事实来源，Python 负责解析、向量化与模型调用。

## 阶段 1–3（2026-10-15）

新增个人账号与知识权限、版本恢复、Markdown/PDF 导出、完整备份还原、网页/代码仓库采集，以及跨 Book 的全文/语义搜索、重排、多文档问答与持久化对话。入口与验收步骤见 [阶段 1–3 执行与验收](docs/阶段1-3执行与验收.md)，接口与边界见 [阶段 1–3 接口与设计](docs/阶段1-3接口与设计.md)。

| 入口 | 地址 |
| --- | --- |
| 创建个人账号 | http://127.0.0.1:5173/register |
| 我的 Book | http://127.0.0.1:5173/admin/books |
| 全库搜索、总结与对比 | http://127.0.0.1:5173/admin/knowledge |
| 网页与仓库采集 | http://127.0.0.1:5173/admin/imports |
| 资料、备份与恢复 | http://127.0.0.1:5173/admin/settings |

私有仓库可配置服务端 GITHUB_TOKEN / GITLAB_TOKEN；Windows 用户变量由启动脚本读取并传入 WSL。默认使用 gte-rerank-v2 重排，可用 RERANK_ENABLED=false 关闭。数据同步指在线访问同一服务端内容和对话，尚无离线合并或实时共同编辑。

## 阶段 4：笔记与 Agent（2026-10-18）

新增 AI 笔记整理/润色预览、带出处的重复与冲突检查、关联知识推荐、多 Book 合并预览、个人长期偏好，以及逐步手动执行的受限 Agent 计划。所有内容修改由作者确认后才应用或另存为私有 Book 草稿。

| 入口 | 地址 |
|---|---|
| 笔记与 Agent 工作台 | http://127.0.0.1:5173/admin/assistant |
| Book 笔记助手 | `/admin/books/{id}` 的“AI 笔记助手”区域 |
| Agent 长期偏好 | `/admin/settings` |

步骤只可检索、总结、对比和起草；必须按顺序执行，后续步骤会接收前序生成内容和原文证据。结果保存后可回看，执行时复核 Book 权限与版本。功能边界、浏览器验收结果及截图见[阶段 4 功能与验收](docs/阶段4功能与验收.md)。

## 阶段 6：学习辅助 Agent（2026-10-21）

学习中心 `/admin/learning` 可用所选 Book 生成按周安排的学习路线。路线可设定 1–8 周、每周学习次数、单次时长和开始日期；每次学习可打卡、记录掌握度与反思，查看进度和薄弱点，并生成带 Book 出处的阶段报告。水平评估依据学习者自评和学习记录，不替代正式考试。详细说明见[阶段 6 学习辅助与验收](docs/阶段6学习辅助与验收.md)。

## 已实现

- 个人用户注册登录、所有权和阅读/编辑分享；公开端匿名访问已发布内容。
- Book CRUD、分页筛选、发布/下线、原子浏览量、分类树和多对多标签。
- MinIO 图片上传：大小、扩展名和文件头检查，`images/` 匿名只读直链。
- 持久化写作任务：提纲 → 扩写 → 润色，每阶段由作者修改和确认，最终另存为新草稿。旧文润色不覆盖原文。
- 执行版本、过期回调防护、取消/重试、检查点历史、终态任务记录删除。
- FastAPI + LlamaIndex 工作流，DeepSeek / Qwen / GLM / Ollama 渠道与重试降级。
- 当前 Book 问答：pgvector HNSW 与中文关键词混合召回、历史上下文，回答由结论与简短出处组成，出处链接可定位原文。
- 三栏阅读前台、Book 管理、Markdown 编辑器/净化预览、配图、分类标签、写作对照和问答页面。
- Book 添加支持在线编写与文件上传两种模式：在线正文或原文件就是 Book 内容，自动索引并支持引用问答。
- 上传 PDF / DOCX / TXT / Markdown 直接创建文件型 Book，原文件存入私有 MinIO；在线正文和文件内容都自动分块、向量化并持久化到 pgvector。
- 使用 Book 知识进行提纲 → 扩写 → 润色，人工确认后另存为新 Book，自动向量化，不覆盖原始资料。
- 知识范围统一支持多选 Book、分类文件夹（含子分类）和混合选择，覆盖 Agent、笔记检查、合并、全库检索、阅读与后台问答、学习资料。阅读页可切换知识范围并恢复仅当前 Book，不同范围的对话独立。操作与验收见[知识范围选择与验收](docs/知识范围选择与验收.md)。
- 前台问答提供“最近”历史：按时间分组、搜索标题、恢复原消息与知识范围、继续追问及确认删除。新建对话保留旧记录；登录后的会话持久保存，刷新可恢复当前会话，匿名用户可登录后使用历史。
- 上下文管理：统一估算 Token 预算、持久化滚动意图摘要、近期消息与相关旧问题召回、各 Agent 步骤记忆、证据去重和预算控制、长文分段润色 / 校对，以及脱敏 usage 日志。摘要只帮助理解意图，事实仍由当前检索来源支撑。设计与验收见[上下文管理与验收](docs/上下文管理与验收.md)。

完整的已实现功能、项目结构、入口、验证记录与当前边界见 [功能清单](docs/功能清单.md)。

2026-10-21 整体自验与优化已完成：见[完整功能验收清单](docs/完整功能验收清单.md)（74 项需求对照、100 条操作验收）和[整体测试与优化报告](docs/整体测试与优化报告.md)（修复、测试结果、证据和边界）。

## 本机启动

环境：Windows Java 21、Maven 3.9+、Node 22；WSL Ubuntu Python 3.12、uv。复用运行中的 MySQL 8、MinIO 和 pgvector 容器，无需新建 compose 服务。

首次安装前端依赖：

```powershell
cd D:\Book_Agent\frontend
npm ci
```

在三个终端分别执行：

```powershell
cd D:\Book_Agent
powershell -ExecutionPolicy Bypass -File scripts/Start-Backend.ps1
```

```powershell
cd D:\Book_Agent
powershell -ExecutionPolicy Bypass -File scripts/Start-AI.ps1
```

```powershell
cd D:\Book_Agent
powershell -ExecutionPolicy Bypass -File scripts/Start-Frontend.ps1
```

启动脚本会在缺少 `.env` 时生成随机内部服务令牌；Java 与 Python 共享该文件。Python 虚拟环境位于 WSL 的 `~/book-agent-venv`，避免在 Windows 挂载目录安装环境。

如 WSL 尚未安装 uv：

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
```

访问：

| 入口 | 地址 |
|---|---|
| 公开书库 | http://127.0.0.1:5173 |
| 管理台 | http://127.0.0.1:5173/admin/books |
| 完整书库（需登录） | http://127.0.0.1:5173/books |
| Java 接口文档 | http://127.0.0.1:8080/doc.html |
| AI 健康检查 | http://127.0.0.1:8100/health |

开发环境默认管理员 `admin / admin123`。已有管理员不会被重新创建或重置密码。部署时覆盖数据库、MinIO 和管理员的开发默认值。

## 模型配置

默认主渠道是 `https://dashscope.aliyuncs.com/compatible-mode/v1` 的 `qwen3.8-flash`。在 Windows **用户环境变量**设置 `DASHSCOPE_API_KEY`，重启 AI 服务。启动脚本会读取最新用户变量，并通过 `WSLENV` 传递给 Python，不把密钥写入项目文件或命令参数。Linux 部署时直接向服务注入同名变量。此配置已使用真实请求验证。

需要其他备用渠道时，可在项目根 `.env` 设置对应密钥，随后重启 AI 服务：

```dotenv
DEEPSEEK_API_KEY=你的本地密钥
```

也支持 `QWEN_API_KEY`、`GLM_API_KEY`。不需要把密钥提交到代码库或粘贴到聊天。修改模型、渠道顺序或按阶段分档时，复制 `ai/config.yaml` 为 `ai/config.local.yaml`，在 `.env` 设置 `AI_CONFIG=/mnt/d/Book_Agent/ai/config.local.yaml`（WSL 路径）。每个渠道的 `tasks` 决定支持哪些阶段；使用第一个可用渠道，每渠道最多两次尝试。

本地模型：先启动 Ollama 并拉取 `qwen3:8b`，再设置 `OLLAMA_ENABLED=true`。Ollama 的默认 URL 为 Python 运行环境中的 `127.0.0.1:11434`；Windows Ollama 在部分 WSL 网络配置下需要改为可访问的宿主地址。

没有可用模型时，任务明确进入失败态，可配置后重试；不会返回伪造的生成结果。没有当前 Book 的相关原文时，问答直接提示依据不足。

## Book 添加与 RAG

新建页面提供两种模式：**在线编写**直接保存 Markdown 正文；**文件上传**选择原文件并直接创建 Book，无需先建空白 Book。文件型 Book 展示原文件，可下载、修改标题简介或按版本替换；文件解析原文用于阅读、问答和知识写作，不作为其他 Book 的参考附件。已有旧版附件保留兼容访问。

首次运行 `powershell -ExecutionPolicy Bypass -File scripts/Setup-RAG.ps1`。脚本复用已有 `pgvector` 容器，在其中创建专用数据库、应用账号与 vector 扩展，将 `PGVECTOR_DSN` 保存到忽略提交的根 `.env`。不会清空现有数据库。

默认 `EMBEDDING_PROVIDER=remote`，使用 DashScope `text-embedding-v4`、1024 维和相同的 `DASHSCOPE_API_KEY`。聊天模型与嵌入模型分开配置。可用 `EMBEDDING_BASE_URL/API_KEY/MODEL/DIMENSIONS` 覆盖；可选 `EMBEDDING_PROVIDER=local` 使用 FastEmbed `BAAI/bge-small-zh-v1.5`（512 维，首次运行需下载模型）。

保存在线正文、上传文件创建 Book、替换 Book 原文件或重新索引，都会在 MySQL 中创建索引任务。Python 从私有 MinIO 签名链接下载，验证大小与 SHA-256，解析文字，按 400 token / 64 token 重叠分块，再批量向量化。pgvector 按 Book、修订版本与模型空间保存，查询使用向量和中文关键词混合召回；只在当前版本索引就绪后开放问答。回答固定为结论与论据，论据只显示真实出处链接，同一文件的同一页去重，不另列解释或摘录清单；点击可以定位原文。索引失败有可读原因，可在界面重试。

一份文件创建一本 Book，文件 ≤20MB；正文 ≤100 万字符，解析文字单文档 ≤200 万字符，Book ≤10000 分块。支持文本型 PDF、DOCX 段落/表格、UTF-8/GB18030 文本。扫描 PDF 需先 OCR，加密或损坏文件会明确失败。草稿/下线 Book 的原文件仅所有者、被授权用户或系统管理员可访问；发布后的原文件由公开接口核验状态再签名，下载链接有效两分钟；历史版本需要的原文件保留，删除整个 Book 后向量与对象由后台清理，不再参与后续检索。

## 统一 Book 与阅读界面

旧 article 数据保留为档案，启动时通过 legacy_article_book 幂等复制到 Book，保留正文、状态、分类、标签、浏览量和时间。LegacyBookIds 统一管理旧接口的 ID 空间：有历史映射的 Book 返回原 article ID，没有映射的新 Book 返回 `2^52 + bookId` 的不透明数值句柄，同时返回规范 `bookId` 和 `readerUrl`。句柄在 JavaScript 安全整数范围内，旧客户端应原样传回；原始历史 ID 的映射优先于同值 Book ID，不增加别名表。规范 `/api/books` 与 `/books/:id` 始终使用 Book ID。后台只保留 Book 管理，所有写作最终确认都保存为新 Book 草稿并自动索引。

发布状态为 DRAFT → PUBLISHED ⇄ OFFLINE。匿名用户只能访问已发布 Book；草稿/下线内容需所有者、被授权用户或系统管理员。BookService 统一原文读取、问答与签名下载的访问范围，在耗时操作前后核验修订及公开发布状态；读取返回固定 BookReadVO（book、sections）。公开语义是完成校验时仍为 PUBLISHED；同一修订先下线再发布不会留下可识别的撤回历史。发布与索引独立，当前修订未就绪时可阅读原文，但禁止问答。文件对象始终保持 MinIO 私有，已签发的 120 秒链接不能即时撤销。

前台左侧为分类、标签、搜索与章节目录，中间为 Markdown 或原文件解析原文，右侧默认为当前 Book 对话，也可按多本 Book 或分类切换问答范围。左右可独立收起，保留滚动和本次会话；手机使用互斥抽屉。readerSession 捕获请求目标 ID，使用读取/目录代次及会话代次阻止迟到结果覆盖新页面。认证失效清理私有原文、目录与会话，保留公开内容；旧 token 的迟到 401 不注销新登录。首页自动打开最近发布的 Book，/books 为管理员完整书库，/books/:id 为阅读地址，/ask 打开对话入口并提示先选择 Book。

Book RAG 必须配置持久化 pgvector，不在问答时临时重建知识。EMBEDDING_DIMENSIONS 与模型一致（1–2000）；EmbeddingSpace 按实际生效的 URL、模型和维度确定共享空间身份，保持既有 Book 哈希命名。Book 查询的嵌入模型等待不占数据库事务，之后在同一 REPEATABLE READ 快照核验状态并读取向量与关键词候选。内部写作的 book_id/book_revision 必须成对传入，版本冲突返回 409。

Java 旧 `/api/blog/ask` 与 `/api/ask` 由 QuestionController 按 bookId 或兼容 articleId 限定当前已发布 Book；未选 Book 时返回选择提示。它们不是 article 快照全站问答。仅 Python 内部 `/internal/ask` 保留调用方显式传入文章快照的兼容能力。架构分析、接口方案与本次取舍见 [架构评估与优化 RFC](docs/架构评估与优化RFC.md)。

## 验证与构建

2026-10-21 范围功能全量回归：Java 36 项、Python 67 项、前端 36 项通过，共 139 项，无跳过；包含 6 项真实 pgvector 测试。JAR 和前端生产构建成功，该轮 10 组真实接口 / Chrome 场景通过，见[知识范围选择与验收](docs/知识范围选择与验收.md)。仅保留 1 条第三方 Starlette 弃用警告。此前整体验收另有 36 组真实接口与 12 组 Chrome 桌面 / 手机场景，见[整体测试与优化报告](docs/整体测试与优化报告.md)。

随后前台“最近”对话功能完成前端 45 项测试与生产构建，新增 9 项会话恢复 / 范围 / 异步状态回归，并验证 11 组真实 Chrome 接口与桌面 / 手机交互。此次复用既有对话 API，Java 和 Python 未改动。

最新上下文管理增量：Java 40 / Python 82 / 前端 45，共 167 项通过，JAR 与前端构建成功；真实验证 9 轮问答的摘要生成 / 复用 / 重启后推进、四步 Agent 和前台历史恢复。详见[上下文管理与验收](docs/上下文管理与验收.md)。

已安装依赖后，在项目根目录可一次执行测试与构建：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/Test-Project.ps1 -WithVectorDatabase
```

脚本不重启业务服务、不修改应用配置，日志保存在临时目录。去掉 `-WithVectorDatabase` 时，不主动读取项目向量库配置；若环境也未提供测试 DSN，6 项可选向量库用例会跳过。以下是分层执行方式：

```powershell
cd D:\Book_Agent\backend
mvn test
mvn package -DskipTests
cd ..\frontend
npm test
npm run build
```

```bash
cd /mnt/d/Book_Agent/ai
UV_PROJECT_ENVIRONMENT=~/book-agent-venv ~/.local/bin/uv sync --locked
~/book-agent-venv/bin/pytest -q
```

可选实际向量库测试：设置 `TEST_PGVECTOR_DSN` 后执行 `pytest tests/test_vector_integration.py tests/test_book_vector_integration.py -q`。测试仅创建、删除自己的唯一测试表。

Java 构建产物：`backend/target/backend-0.0.1-SNAPSHOT.jar`；前端构建产物：`frontend/dist`。部署步骤见 [部署说明](docs/部署说明.md)，接口约定见 [接口文档](docs/接口文档.md)，设计与取舍见 [实现说明](docs/实现说明.md)。
