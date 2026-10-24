# Book_Agent

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

个人用户注册、创作与管理自己的知识，可向其他用户授予阅读或编辑权限；所有者决定发布。匿名访客阅读已发布 Book。系统管理员负责维护。

## Product Purpose

通过在线编写、文件上传或提纲→扩写→润色工作流创建 Book。作者决定是否发布；读者在目录、正文与对话组成的三栏空间理解内容。

## Stack

Vue 3、Vite、Element Plus、Pinia；Java 21 / Spring Boot 3.5 / MySQL / MinIO；Python FastAPI / LlamaIndex / OpenAI 兼容模型渠道；pgvector 持久化 Book 索引。

## Capabilities and Constraints

文章与 Book 是同一个内容概念，产品统一称 Book。Java 是唯一事实来源。AI 不自动发布，也不直接覆盖原文。匿名访问仅限 PUBLISHED Book，草稿和下线内容要求所有者、被授权用户或系统管理员。Markdown 必须净化。原文件保存在私有 MinIO，通过受控短期签名链接下载。保留现有数据库和容器，新增表采用非破坏性迁移。

## Operating Context

Windows 开发 Java 和前端；WSL 运行 Python 与现有数据库、对象存储容器。

## Evidence on Hand

docs/技术方案.md、docs/接口文档.md、现有代码与真实浏览器验收。原有 15 篇内容保留并映射为 Book；前台布局依据用户提供的三栏截图。模型接入使用用户环境变量 DASHSCOPE_API_KEY，密钥不写入文档。

## Product Principles

- 内容发布由作者确认。
- 所有页面连接真实接口，空库显示明确的引导。
- 阅读问答限定当前 Book；全库问答限定调用者有权的 Book 集合及就绪修订，引用可验证的原文；无证据时明确说明。
- 任务和检查点持久化，可在重启后继续。

## Autonomous Decisions

用户授权依据文档自主设计并完成。中文界面、阅读优先、轻量管理台为本次设计选择，不代表用户设定的永久视觉偏好。

## Unified Book workspace (2026-10-13)

管理员通过在线 Markdown 或上传一份 PDF/DOCX/TXT/MD 添加 Book，上传原文件就是内容来源，支持版本替换和下载。两种模式均自动解析、分块、向量化；发布状态独立于索引状态。所有写作任务最终确认后保存为新 Book 草稿并自动索引。旧 article 表作为档案保持不变，legacy_article_book 记录幂等迁移映射，旧链接继续解析到 Book。

前台左侧书库与章节目录，中间显示原始正文或文件解析原文，右侧为带历史上下文和原文引用的对话。左右独立收起，保留阅读位置和本次会话；手机转为互斥抽屉并支持 Escape 与键盘焦点约束。公开首页与 /books/:id 读取已发布内容，/books 为需登录的完整书库，/admin/books 为唯一内容管理入口。


## 阶段 1–3（2026-10-15）

个人空间具备历史版本恢复、Markdown/PDF导出与含原文件备份。WEB/REPO采集记录来源和快照，可重采集、去重及自动分类标签。全库页面支持关键词、全文、向量与混合检索，模型重排、多文档总结/对比和持久化对话。现有三栏阅读与出处定位继续作为原文入口。

## 阶段 4：笔记与 Agent 能力（2026-10-18）

已完成笔记整理助手、带来源的重复/冲突检查、相关知识推荐、合并预览与私有 Book 草稿、个人长期偏好，以及有范围限制且逐步手动执行的 Agent 计划。AI 改动先预览，由作者确认后才应用或另存；计划工具仅允许检索、总结、对比和起草。功能与验收证据见 [阶段 4 功能与验收](docs/阶段4功能与验收.md)。

