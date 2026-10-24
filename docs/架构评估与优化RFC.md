# RFC：Book 阅读、会话与 RAG 边界优化

日期：2026-10-14。状态：主要改动已落地，运行验收见文末。

本次按 `code-arch-optimizer` 的探索、候选聚类、多方案对比和边界测试流程评估。项目目录没有 Git 仓库或 GitHub 目标，RFC 保存在本地，未创建远程 Issue。实现前已在工作区外备份源代码与文档。

## 问题

系统采用 Vue 3 → Spring Boot → FastAPI 的分工，MySQL 保存业务事实，MinIO 保存原文件，pgvector 保存可重建的向量。这个边界适合当前单管理员内容站。主要风险集中在一个操作穿过多个模块时，调用方必须自行记住权限、标识和版本规则。

| 候选聚类 | 架构摩擦及可观察风险 | 依赖类别 | 测试影响与处理 |
| --- | --- | --- | --- |
| BookReaderController、BlogController、QuestionController、BookService | 多入口重复组合“已发布→远程读取/问答→再校验”；漏掉完成检查会返回处理期间已下线的内容；远程解析不应被控制器事务包围 | 本地可替代：MySQL；自有远程：Python；外部边界：MinIO SDK | 通过 HTTP 边界模拟 AI/签名期间下线或修订，检查所有公开入口的最终响应 |
| ArticleController、BookService 的旧 ID 解析、BookMigration | articleId 与 bookId 是独立自增域。旧列表返回规范 ID、详情却先查旧映射，可能读写另一作品 | 进程内标识转换；本地可替代：映射表 | 构造实际数值碰撞，验证列表→详情→编辑→删除往返及旧 URL |
| Reader.vue、api.js、auth.js | 读取途中 route.params 改变；公开失败后回退私有与后续读取交错；会话失效后迟到响应恢复私有状态 | 进程内状态；自有远程：Java API | 以可控延迟请求测试正文、目录、提问、下载及凭证失效，检查可见状态，不绑定组件内部布局 |
| provider.py、book_rag.py、retrieval.py | 默认配置和显式环境变量的身份计算不一致；嵌入模型等待占用事务；状态、语义候选与关键词候选可能来自不同提交 | 进程内配置；自有存储：pgvector；外部：DashScope | 配置边界测试 + 真实 pgvector 并发更新/删除测试；模型用确定性替代品 |
| models.py、main.py、writing.py | 知识写作允许只传 Book ID 或修订之一，检索冲突缺少明确 HTTP 语义 | 进程内校验；自有远程 HTTP | 入口测试成对字段、409 冲突、未就绪不调用模型及成功响应 |

本次优先处理这些已能复现的接缝问题。任务调度的多实例租约、聊天持久化、全库检索等属于独立产品和部署能力，后续应按需求设计。

## 约束

- Book 是唯一主内容模型，支持 ONLINE 与 UPLOAD；旧文章档案和映射仍可读取。
- ADMIN 校验由 Java 接口层执行；公开内容仅限当前已发布 Book。AI 不获得发布权限。
- MySQL 的 revision 是业务内容版本；pgvector 按 Book、修订及嵌入空间限定候选。
- 保持现有 `{book, sections}`、`{answer, sources, mode}` JSON 和简短出处链接，不改变三栏布局。
- 不为此次优化新增表、消息队列或依赖框架；既有 Book 向量命名空间继续复用。

## 接口方案对比

以下是设计阶段的三种候选接口；示意签名用于说明取舍，并非都已实现。

### A. 最小阅读门面

```java
BookRead read(BookId id, ReadScope scope);
Answer ask(BookId id, Question question, ReadScope scope);
Download download(BookId id, FileId fileId, ReadScope scope);
```

调用方只声明操作与访问范围，例如 `reader.ask(id, question, PUBLISHED)`。门面隐藏状态快照、修订核验、并发限制和远程调用。MySQL 用 H2 集成测试替代，AI 与对象存储在传输边界替代。

优点是默认调用简单，容易覆盖所有入口；代价是门面仍拥有多种远程操作。适合当前规模。

### B. 可扩展访问上下文

```java
ReadSession session = reader.open(new ReadRequest(id, audience, expectedRevision));
Answer answer = session.ask(question);
session.verifyCompletion();
```

上下文可承载多租户、访问策略、取消和多个连续操作。内部隐藏访问快照与身份解析，外部依赖通过可替换接口提供。

优点是扩展空间充足；代价是调用方可能忘记完成校验，生命周期更复杂。若改成自动托管回调又会引入更多泛型与控制流。当前单管理员应用收益不足。

### C. 端口与适配器

```java
interface BookSnapshots { Snapshot load(BookId id, Audience audience); }
interface OriginalReader { Sections read(Manifest manifest); }
interface BookAnswers { Answer ask(BookRevision revision, Question question); }
```

应用服务组合业务快照端口、Python HTTP 适配器和对象签名适配器，例如 `new ReadingService(snapshots, originalReader, answers, signer)`。业务规则可用内存适配器独立验证。

优点是跨部署边界清晰，适合未来替换存储或 AI 服务；代价是当前每种依赖只有一个实现，全面铺开会增加文件与跳转。当前保留 AiClient、Provider 等已有替换边界，只在确有第二实现时继续抽取。

### 选择

采用 A 的简单调用形式，结合 C 的外部边界替代策略；前端单独建立阅读会话边界。没有引入 B 的长期服务端会话。核心标准是让调用方只描述业务意图，把权限、时序和版本规则放在拥有完整操作的模块内。

## 已落地的接口

### Java 阅读与标识

```java
BookReadVO read(long id);
BookReadVO readPublished(long id);
JsonNode ask(long id, String question, int topK, List<ChatMessageDTO> history);
JsonNode askPublished(long id, String question, int topK, List<ChatMessageDTO> history);
String download(long id, long fileId);
String downloadPublished(long id, long fileId);
```

`BookService` 内部以 `ReadScope` 复用实现，公开入口明确调用 Published 方法。`AccessSnapshot` 只取修订、索引状态及发布状态；问答无需为前后校验重复加载整篇正文、标签和文件。`verifyCompletion` 统一检查处理完成时的可见性、修订及必要的索引就绪状态。远程阅读不再由 BlogController 开启数据库事务。

`BookReadVO(BookVO book, JsonNode sections)` 代替不透明 Map 与强制转换，保持 JSON 字段相同；文件阅读要求 sections 为数组，异常上游返回 503。规范列表使用 `BookQuery`，`ArticleQuery` 作为兼容别名保留。

```java
long LegacyBookIds.resolve(long handle);
ObjectNode LegacyBookIds.present(BookVO book);
Map<String, Object> LegacyBookIds.presentPage(Map<String, Object> page);
```

旧接口投影规则：有历史映射时 `id` 为原 articleId；没有映射时为 `2^52 + bookId`。返回值增加规范 `bookId` 与 `readerUrl`。这是保留 JavaScript 安全整数精度的独立句柄区间，要求 bookId 小于 `2^52`；超出时明确要求使用规范 Book API。列表逆向映射批量查询，避免逐条查库。

旧客户端应将 `id` 视为不透明句柄，在旧接口间原样传递；新客户端使用 `/api/books/{bookId}`。历史原始 ID 保持映射优先，未映射的普通数字仍兼容规范 ID 回退。仅调整旧接口输出和解析，不重编号已有数据。

### 前端阅读会话

```js
const session = createReaderSession({ request, hasToken, state, conversations });
const work = session.beginRead(bookId, { legacy, publicOnly });
const result = await work.promise;
if (!work.current()) return;
```

会话捕获请求开始时的 ID，使用读取代次与凭证失效代次判断结果是否有效；正文、章节和访问模式一起提交。公开失败后的私有回退使用同一 ID，旧读取、旧目录及旧操作不能覆盖当前会话。

`invalidate()` 清空私有正文、私有目录、私有对话与未发送问题，并使迟到操作失效；已展示的公开正文和公开对话保留。每本 Book 的公开对话可在页面内切换后继续。`api.js` 只让当前 token 的 401 注销当前登录，旧凭证的迟到 401 无权清除新凭证。

### Python RAG 与写作

```python
space = provider.embedding_space()  # EmbeddingSpace(base_url, model, dimensions)
await BookVectorStore(provider).search(request)
```

`EmbeddingSpace` 以实际解析的 URL、模型和维度生成统一身份，不含密钥。哈希公式保持现有 Book 表名兼容；遗留快照检索也使用相同配置解析，避免默认值或本地模型被误判为未配置、或混用不同模型缓存。

Book 检索先在短事务内检查就绪状态，再关闭连接等待嵌入，最后在一个只读 REPEATABLE READ 快照内重新检查版本并取得两类候选。等待模型期间发生更新或删除，后续读取会拒绝过期请求。上传索引已有的原子版本切换与共享删除墓碑继续生效。

`WriteRequest` 要求 `book_id` / `book_revision` 同时提供或同时省略；不完整请求返回 422。知识写作遇到 `IndexNotReady` 返回 409，模型不可用仍为 503，成功字段不变。

## 依赖策略

| 类别 | 本项目做法 |
| --- | --- |
| 进程内 | ID 投影、会话代次、嵌入身份与字段校验归属于明确模块；用可观测行为断言 |
| 本地可替代 | Java 集成测试使用 H2 验证业务状态；不以 H2 代替 PostgreSQL 的向量与隔离语义 |
| 远程但自有 | Java→Python 仍走 AiClient；前端→Java 由注入 request 的会话模块消费。测试在请求边界控制返回与延迟 |
| 真正外部 | DashScope 模型调用使用确定性替代品；MinIO 签名在 SDK 边界替代。另做真实服务链路冒烟 |

## 测试策略

新增测试针对之前缺失的跨模块行为：旧 ID 数值碰撞、所有公开问答入口的完成校验、解析/签名期间下线、前端读取乱序和凭证失效、实际嵌入配置、知识写作错误语义、嵌入等待期间索引更新/删除。

原有兼容接口测试改为使用句柄与 bookId 的新契约，没有保留冲突的旧断言。现有解析、Markdown 安全、出处和工作流测试仍验证独立行为，因此保留。没有为简单包装器叠加镜像单元测试。

真实 pgvector 测试使用随机命名空间和专用高位 Book ID，使用确定性嵌入，不调用收费模型；finally 中删除本轮测试表及状态。不会清空业务向量。

## 实施与后续指导

1. 控制器负责 HTTP、参数与角色；阅读服务负责完整操作的版本与可见性，新增公开入口直接调用 Published 方法。
2. 历史标识只在兼容边界解析。规范业务代码与新页面只传 Book ID，不重复判断 articleId 优先级。
3. 页面负责显示和交互；会话模块负责异步结果是否仍属于当前权限与文档。新增后台操作应持有会话凭据并在完成前检查。
4. 向量空间由已生效配置决定。新的嵌入渠道沿用 Provider 的解析，不能各自重写环境变量默认规则。
5. 网络等待与数据库提交分开。查询所需的状态和候选必须来自一致快照，响应前还要经过业务服务当前状态校验。

后续按实际需求推进：多实例任务领取租约与崩溃恢复、连接池与迁移阶段 DDL、持久化聊天、全库检索权限集合。当前不添加仅有单实现的通用 Repository 层，也不删除仍可能被旧客户端使用的兼容代码。

## 保证范围

公开操作保证在最终校验时 Book 仍为 PUBLISHED 且 revision 未变；当前发布表没有单独的发布代次，所以同修订在处理中“下线→重新发布”不会被识别为撤回历史。最后一次检查后仍存在普通并发响应窗口，这不是永久授权快照。

已经签发的 MinIO 链接有效 120 秒，当前设计无法在下线时立即撤销。删除先禁止业务访问，再异步清理对象和向量。会话缓存仅存在浏览器当前页面内存，刷新不恢复历史。

## 验收记录

- Java：15 项集成测试通过，`mvn package -DskipTests` 成功。
- Python：42 项通过，包含 5 项真实 pgvector 用例；无跳过，存在一条 Starlette/httpx 第三方弃用提示。
- 前端：会话边界测试与生产构建已通过；最终计数与浏览器记录在完成运行验收后更新。

