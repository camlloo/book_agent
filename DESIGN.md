---
name: Book_Agent
description: 统一 Book 的三栏阅读空间与由作者掌控的轻量写作台。
colors:
  ink: "#182f50"
  muted: "#59697e"
  accent: "#245bdd"
  accent-light-3: "#6589e8"
  accent-light-5: "#94adee"
  accent-light-7: "#c2d1f6"
  accent-light-9: "#e9effc"
  line: "#e2e8f0"
  surface: "#f4f7fc"
  white: "#fff"
  regular-text: "#425672"
  placeholder: "#66758b"
  control-border: "#d8e1ee"
  nav-hover: "#e7eefb"
  nav-selected: "#e2ebfb"
  focus: "#7b9ff2"
  selection: "#dbe8ff"
  draft-surface: "#edf1f6"
  success-surface: "#e6f5ee"
  success-text: "#21624b"
  review-surface: "#e9efff"
  danger: "#b33a43"
  error-surface: "#fbedef"
  error-text: "#a82e3b"
  editor-surface: "#f9fbff"
  code-surface: "#edf2f9"
  diff-added-surface: "#ddf3e6"
  diff-added-text: "#215a3c"
  diff-removed-surface: "#f9e1e5"
  diff-removed-text: "#932b37"
  reader-ink: "#252b34"
  reader-muted: "#666e79"
  reader-accent: "#365ec9"
  reader-line: "#e3e5e8"
  reader-panel: "#f6f7f8"
  reader-toolbar: "#fafafa"
  reader-selected: "#e8ecf5"
  reader-selected-text: "#294c9d"
  reader-hover: "#e9ebef"
  reader-selection: "#e2e9fc"
  reader-user-message: "#edf0f6"
  reader-composer-border: "#dce0e7"
typography:
  display:
    fontFamily: '"Noto Serif SC", SimSun, serif'
    fontSize: "30px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  reading-headline:
    fontFamily: '"Noto Serif SC", SimSun, serif'
    fontSize: "32px"
    fontWeight: 650
    lineHeight: 1.5
    letterSpacing: "-0.02em"
  headline:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "34px"
    fontWeight: 700
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  feed-title:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "18px"
    fontWeight: 500
    lineHeight: 1.6
  title:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.5
  body:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "15px"
    fontWeight: 400
    lineHeight: 1.7
  reading-body:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.95
  label:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.7
  metadata:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.7
  editor:
    fontFamily: '"Cascadia Code", Consolas, monospace'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.8
  reader-chrome:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.65
  reader-answer:
    fontFamily: '"Noto Sans SC", "Microsoft YaHei", sans-serif'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.85
rounded:
  badge: "4px"
  control: "6px"
  reader-control: "5px"
  reader-composer: "7px"
spacing:
  "6": "6px"
  "8": "8px"
  "10": "10px"
  "12": "12px"
  "14": "14px"
  "16": "16px"
  "18": "18px"
  "20": "20px"
  "24": "24px"
  "28": "28px"
  "32": "32px"
  "34": "34px"
  "36": "36px"
  "40": "40px"
  "46": "46px"
  "48": "48px"
  "56": "56px"
  "60": "60px"
  "68": "68px"
  "70": "70px"
  "80": "80px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.white}"
    rounded: "{rounded.control}"
    padding: "0 18px"
    height: "40px"
  button-primary-hover:
    backgroundColor: "{colors.accent-light-3}"
    textColor: "{colors.white}"
  button-secondary:
    backgroundColor: "{colors.white}"
    textColor: "{colors.regular-text}"
    rounded: "{rounded.control}"
    padding: "0 18px"
    height: "40px"
  nav-selected:
    backgroundColor: "{colors.nav-selected}"
    textColor: "{colors.accent}"
    rounded: "{rounded.control}"
    padding: "15px 18px"
  tag:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.muted}"
    rounded: "{rounded.badge}"
    padding: "5px 10px"
  status-draft:
    backgroundColor: "{colors.draft-surface}"
    rounded: "{rounded.badge}"
    padding: "3px 10px"
  status-published:
    backgroundColor: "{colors.success-surface}"
    textColor: "{colors.success-text}"
    rounded: "{rounded.badge}"
    padding: "3px 10px"
  status-waiting-review:
    backgroundColor: "{colors.review-surface}"
    textColor: "{colors.accent}"
    rounded: "{rounded.badge}"
    padding: "3px 10px"
  status-failed:
    backgroundColor: "{colors.error-surface}"
    textColor: "{colors.error-text}"
    rounded: "{rounded.badge}"
    padding: "3px 10px"
  checkpoint-input:
    backgroundColor: "{colors.editor-surface}"
    textColor: "{colors.ink}"
    typography: "{typography.editor}"
    rounded: "{rounded.control}"
    padding: "24px"
  reader-icon-button:
    backgroundColor: "transparent"
    textColor: "{colors.reader-ink}"
    rounded: "{rounded.reader-control}"
    width: "30px"
    height: "30px"
  reader-icon-button-hover:
    backgroundColor: "{colors.reader-hover}"
  reader-book-selected:
    backgroundColor: "{colors.reader-selected}"
    textColor: "{colors.reader-selected-text}"
    rounded: "{rounded.reader-control}"
    padding: "9px 10px 9px 25px"
  reader-composer:
    backgroundColor: "{colors.white}"
    textColor: "{colors.reader-ink}"
    rounded: "{rounded.reader-composer}"
    padding: "12px"
  reader-send:
    backgroundColor: "{colors.reader-accent}"
    textColor: "{colors.white}"
    rounded: "{rounded.reader-control}"
    width: "31px"
    height: "31px"
---

# Design System: Book_Agent

## Overview

**Creative North Star: "开放的中文写作台"**

白色页面承载内容，浅色面划分操作区，文字与细线建立层级。Book 阅读器以目录、原文和对话组成三栏阅读空间，使用石墨文字与灰白侧栏；管理台保留深蓝文字、淡蓝固定导航、开放式表格和紧凑表单。两者沿用中文字体与线条图标，阅读器的局部令牌不替换后台令牌。

此文件记录完成后的源码视觉事实。后台依据 `frontend/src/style.css`、`App.vue` 与现有管理视图；阅读器依据 `frontend/src/reader.css`、`views/Reader.vue` 和 `router.js`。管理台参考仍为 `.impeccable/mocks/admin.png`；阅读器方向与参考图见 `.impeccable/surfaces/public-reader.md`。前置产品约束见 `PRODUCT.md`。文章与 Book 是同一个内容概念，界面统一称 Book；保留旧标题与正文中的作者原始措辞，数量与内容均来自接口。

阅读器截图证据为 `.impeccable/review/desktop.png`（1440×1000）、`user-1280.png`（1280×900）、`mobile.png` 与 `mobile-chat.png`（390×844）。截图中的验收内容与计数只代表采样时的数据。旧后台机械比对的差异不构成本次阅读器的阻塞，也不代表后台视觉已重做；中文字体以源码选择的字体栈为准。

**Key Characteristics:**

- 阅读与操作共用字体与图标语言，分别保留阅读器与后台的局部配色和内容密度。
- 原文、目录、表格和引用列表以留白与细线分区。
- 中文衬线用于公开标题，中文无衬线用于正文与操作界面。
- 蓝色聚焦可执行操作；状态文本、确认文案与颜色共同表达后果。

## Colors

后台保留低饱和蓝白配色，阅读器采用灰白面与石墨文字，两者均以钴蓝强调操作。绿色与红色承载明确状态。上方 frontmatter 中的值与源码一致，后续修改先同步源码与规范。

### Primary

- **操作钴蓝**（`accent`）：主按钮、链接、活动导航、文字光标及生成进度。Element Plus 使用同一主色及现有浅色阶。
- **导航浅蓝**（`nav-hover` / `nav-selected`）：侧栏悬停与选中面；选中时同时使用主色文字与加重字重。
- **键盘焦点蓝**（`focus`）：全局可见焦点描边。**文字选择浅蓝**（`selection`）用于文本选择。
- **阅读钴蓝**（`reader-accent`）：阅读器的活动目录、正文链接、发送按钮及键盘焦点。选中 Book 使用 `reader-selected` / `reader-selected-text`，文字选择使用 `reader-selection`；这些值仅作用于阅读器。

### Neutral

- **正文深蓝**（`ink`）：后台正文、管理标题、品牌与普通公开壳层标题。
- **说明灰蓝**（`muted`）：摘要、时间、阅读数、辅助说明与未选中公开导航。
- **控件灰蓝**（`regular-text` / `placeholder`）：控件正文与占位提示。
- **白色纸面**（`white`）：主页面、阅读内容与控件底色。
- **淡蓝分区面**（`surface`）：侧栏、表头、标签与任务选择区。
- **细线灰蓝**（`line` / `control-border`）：内容分隔线、编辑容器边界与控件边界。
- **编辑浅纸面**（`editor-surface` / `code-surface`）：Markdown 输入与代码块，区分原始文本和阅读预览。
- **阅读石墨与说明灰**（`reader-ink` / `reader-muted`）：Book 原文、目录及对话的主要文字与辅助说明。
- **阅读灰白面与细线**（`reader-panel` / `reader-toolbar` / `reader-line`）：目录、工具栏、对话与阅读纸面的分区。问题气泡使用 `reader-user-message`；输入框使用 `reader-composer-border`，焦点时边界切换为阅读钴蓝。

状态色不是第二套品牌色。草稿与默认任务状态使用 `draft-surface`；已发布和完成使用 `success-surface` / `success-text`；待确认使用 `review-surface` / `accent`；失败使用 `error-surface` / `error-text`，删除动作使用 `danger`。润色对照使用 `diff-added-*` 和 `diff-removed-*`，删除内容同时有删除线。

**The Explicit State Rule.** 状态色必须伴随中文状态名称；禁止只靠颜色区分草稿、发布、失败或待确认。

## Typography

**Display Font:** Noto Serif SC，后备 SimSun、serif。

**Body Font:** Noto Sans SC，后备 Microsoft YaHei、sans-serif。

**Label/Mono Font:** 界面标签沿用正文栈；Markdown 输入与代码使用 Cascadia Code、Consolas、monospace。

中文衬线用于 Book 主标题与对话空态，无衬线用于原文正文、目录、表格和控件。Google Fonts 使用 `display=swap`，失败时使用既有本地中文后备；禁止为了匹配英文机械字体检查替换这些 CJK 字体。全局禁用字体合成。

### Hierarchy

- **Display**：阅读器未选择 Book 时的书库引言使用 `display`。登录说明标题是同一衬线家族的 42px / 600 / 1.65，手机隐藏说明区。
- **Reading headline**：Book 标题使用 `reading-headline`；手机降至 27px。正文内标题使用正文家族，h1 / h2 / h3 为 28px / 23px / 19px，手机 h2 为 21px。
- **Headline**：管理页采用 `headline`；760px 以下全局 h1 为 28px，管理页页头另设 26px。
- **Feed title**：未选择 Book 时的索引标题采用 `feed-title`，右侧保留箭头图标。
- **Title**：全局 h2 与检查点标题采用 `title`；手机检查点标题为 20px。h3 通常为 18px。
- **Body**：界面基本正文采用 `body`；公共引言和管理副标题为 17px，手机分别为 15px 和 14px。
- **Reading body**：Book 原文采用 `reading-body`，手机为 15px；纸面最大宽度 800px，包含内边距，不强设英文 ch 字符行宽。阅读器 chrome 使用 `reader-chrome`，回答使用 `reader-answer`。普通 Markdown 为 16px / 1.95，检查点预览为 15px。
- **Label / Metadata**：表单标签与行操作为 14px，辅助信息为 13px，标签、状态与任务元信息可为 12px。时间使用等宽数字排列。

**The Chinese First Rule.** 保留源码的中文字体家族及后备顺序；阅读标题的衬线角色与操作标题的无衬线角色不可混用。

## Layout

桌面管理台是 232px 侧栏加弹性内容列。侧栏贴顶、占一屏高；工作区默认留白为上 48px、左右 46px、下 24px，1500px 以上左右增至 60px。页头左侧标题与说明、右侧主动作，表格直接置于页面，不包多层卡片。Book 管理使用标题搜索与搜索按钮，再提供独立发布状态选择器；表头与行分别有 16px 与 20px 的垂直内边距。

阅读器占满 100dvh，桌面最小高度 380px，整体不产生页面级滚动。顶部工具栏 52px，下方为 260px 目录、弹性原文列与 350px 对话列。目录、原文和对话记录各自滚动；对话输入固定在面板底部。原文纸面最大宽度 800px，默认内边距 48px / 48px / 60px。左右面板独立收起至零宽，工具栏保留恢复入口，原文位置与本次会话不因收起而清空。

登录与未知路由保留普通公开壳层：页眉与页脚限宽 1200px、主体限宽 1120px，默认宽度为视口减 80px，主体上下留白 68px / 80px。登录限宽 900px，后台编辑表单限宽 1400px。阅读器路由不套用这套页眉页脚或旧单列文章布局。

编辑模式提供编辑、对照、预览三种布局；对照桌面各占一半，最小高度 480px。分类与标签桌面双列、间距 70px。任务页使用 240px 任务列表与结果列、间距 36px，阶段流程用四个文字项与底部分隔线表示。

### Responsive behavior

| 条件 | 已实现行为 |
| --- | --- |
| ≤1280px 且 ≥761px，阅读器 | 目录 / 对话为 230px / 320px，纸面内边距 40px / 32px。 |
| ≤980px 且 ≥761px，阅读器 | 目录 / 对话为 200px / 280px，纸面内边距 34px / 24px；隐藏面包屑的前置层级。 |
| ≤1100px，管理台 | 侧栏缩至 200px；工作区 36px / 28px；管理表格最小宽度 730px 并在自身容器滚动；任务列表 190px；任务创建按钮移至下一行。 |
| ≤760px | 管理台转为纵向；导航移到顶部横向滚动；工作区 28px / 20px；普通公开主体宽度减 40px，页眉宽度减 36px；页脚上下排列。 |
| ≤760px，阅读器 | 原文成为唯一内容列，纸面内边距 30px / 24px / 42px；隐藏面包屑。目录与对话改为互斥抽屉，顶部位于工具栏下，宽度 min(340px, 100vw − 36px)，覆盖遮罩与细阴影；分类、标签和搜索保留在目录抽屉。 |
| ≤760px，编辑与任务 | 对照的输入与预览上下排列、分隔线转为横线；输入最小高度 320px；分类标签管理改一列；任务结果置于任务列表下方，任务按钮两列；创建表单一列、步骤两列。 |

手机仍允许表格、Markdown 表格及代码块在各自容器横向滚动。正文使用断行，图片不超过容器宽度。编辑元数据字段仍是两列，间距收至 14px；不要把它误记为已实现的单列行为。

手机抽屉标记为 modal dialog，正文 inert。开启时聚焦第一个可见且启用的控件；Tab / Shift+Tab 含从抽屉外进入时均约束在抽屉内。Escape、遮罩或关闭按钮关闭抽屉并恢复对应工具栏按钮焦点。直接访问 `/ask` 使用同一开启与聚焦逻辑。

### Route modes

| 路由 | 模式 | 容器与任务 |
| --- | --- | --- |
| `/` | Read | 三栏阅读器；已发布 Book 目录，未筛选且有内容时打开首本 Book。 |
| `/books/:id` | Read | 同一三栏阅读器；先请求公开正文，已登录且公开请求返回 404 时请求受控管理员正文。 |
| `/books` | Read | 登录后的完整书库，包含草稿与下线 Book；同一个内容目录。 |
| `/articles/:id` | Read | 旧公开链接解析到 Book ID，替换为 `/books/:id`。 |
| `/ask` | Read | 同一阅读器与当前 Book 对话；手机直接访问开启对话抽屉，选择 Book 与索引就绪后可发送。 |
| `/login` | Operate | 普通公开布局；作者登录与返回目标路由。 |
| `/admin/books`、`/admin/books/new`、`/admin/books/:id` | Operate | 管理布局；唯一 Book 管理入口、在线编写或文件上传、保存与作者确认发布。 |
| `/admin/tasks`、`/admin/taxonomy` | Operate | 管理布局；检查点工作流与分类标签。 |
| `/admin/articles`、`/admin/articles/new`、`/admin/articles/:id` | Operate | 旧管理链接重定向或通过迁移映射到 Book 管理，不保留独立文章栏目。 |
| `/admin/ask` | Read | 重定向至登录后的 `/books` 阅读与对话。 |

模式表示交互密度；`reader` 与 `admin` 路由 meta 决定壳层。`/admin` 重定向到 Book 管理，未认证的管理路由与 `/books` 进入登录，未知路由显示返回入口。发布状态控制公开读取与提问：匿名仅能读取 PUBLISHED Book，包括已发布文件 Book；草稿与下线内容由 ADMIN 接口授权。页面标题跟随路由；Book 切换加载后原文滚至顶部，单纯收起面板保留原文位置。

上传原文件的 Book 必须在当前版本索引 READY 后才能发布；原文读取本身不要求索引 READY。阅读与索引状态分离，索引未就绪时仍展示有权限的原文，只禁用提问。

## Elevation & Depth

后台与桌面阅读器使用平面与色调分层，没有自定义卡片阴影。侧栏、表头、标签、代码区和选中任务通过浅色背景建立层次；原文与行数据通过细分隔线建立节奏。手机阅读器抽屉使用定向阴影（左 8px 0 24px，右 −8px 0 24px；rgba(20, 28, 40, 0.12)）与 rgba(20, 28, 40, 0.25) 遮罩，明确覆盖关系。Element Plus 浮层保留库原生表现，不将库内阴影误写为应用独立令牌。

全局键盘焦点使用 3px 描边并向外偏移 3px；阅读器局部焦点为 2px 描边与 2px 偏移，输入框通过 focus-within 改变边界。跳到主要内容链接在获得焦点时显示，层级高于页面。加载指示是 100px × 3px 细线，其中 45% 宽度的蓝段以 1.2 秒往返路径循环；减少动画偏好关闭动画、过渡及平滑滚动。没有自行增加的悬浮抬升动画。

**The Open Surface Rule.** 列表与正文默认直接放在页面上；仅为输入、预览边界和状态辨识使用既有浅色面与细线。

## Shapes

后台控件、导航项、编辑容器、图片和代码块使用轻微圆角（`control`）；标签与状态条使用更紧的小圆角（`badge`）。阅读器按钮、搜索、目录选中项与代码块使用 `reader-control`，问题气泡使用 `control`，输入框使用 `reader-composer`。纸面和表格保持开放边缘。品牌是 BookOpen 线条图标与 Book_Agent 文字，Lucide 图标维持同一套线条语言；后台导航通常 20px、品牌 25px，阅读器品牌 20px、工具栏按钮 19px、面板标题 17px。状态区的大图标用于空态或失败提示。

## Components

### Buttons

Element Plus 主按钮用操作蓝，次按钮用白底与边界，常规高度 40px、水平内边距 18px，小型后台按钮高度 32px。图标与文字通常间隔 8px。按钮组允许换行，间隔 12px。行内编辑、发布与删除是文字操作，悬停加下划线，删除使用危险色。加载时显示真实忙碌状态，禁用时不可交互；原生按钮默认禁用透明度为 0.6。悬停、焦点和浮层沿用 Element Plus 及全局可见焦点规则。

阅读器图标按钮为 30px 方形，悬停使用 `reader-hover`；发送按钮为 31px 方形与阅读钴蓝底。折叠入口始终位于工具栏，以 aria-expanded 与可变中文名称说明状态。对话标题栏提供新建与关闭按钮；新建保留原会话。最近列表中删除需二次确认，处理当前问题期间禁用切换与新建。

### Chips and status labels

Book 正文标签是 11px 辅助元信息，不伪装成可操作筛选；分类与标签筛选是目录中的原生 select，空值为全部分类或全部标签。后台状态条使用 12px / 500 与紧凑内边距，发布和完成为绿色，待确认为蓝色，失败为红色；未定义的状态保留默认中性色并显示中文名称。发布状态与索引状态分开显示，索引完成不代表已发布。

### Lists and containers

阅读器目录按真实分类分组，默认展开，显示标题与加载到的分组数量；目录底部显示接口总数，分页每次加载 50 条并提供加载更多。原文头部排列分类、时间、来源模式、标题、可选简介及标签；无简介就省略。Book 管理表格显示 Book、发布与索引状态、知识分块、更新时间和操作，来源模式与修订信息用辅助文字。引用列表和分类标签列表以底边线分隔；任务选中态用浅色面。不得把 Book 计数、题目或分类写死以填满构图。

### Inputs and fields

输入与选择器最小高度 42px，主要字体 14px，标签放在字段上方。Textarea 内边距 12px、行高 1.7；Markdown 编辑区使用单独的等宽字体和浅纸面。字段已有对应 label 或 aria-label；搜索和筛选允许清除。错误消息明确呈现并使用 alert，页面加载失败提供重试。新页面继续遵守现有 Element Plus 控件和中文 locale。

### Navigation

后台当前栏目采用选中背景、主色和 600 字重；Book 编辑归属于 Book 管理，另有写作助手、分类与标签及阅读与对话。底部保留阅读书库与退出登录。阅读器使用顶部作者登录 / 写作台入口；左侧“已发布 Book”与登录后的“我的 Book”切换同一内容目录的公开与完整视图。本文大纲从已呈现的 h1–h3 提取，按层级缩进，活动章节与选中 Book 均有浅色面。登录和未知路由的普通公开导航为书库、阅读与对话、作者登录 / 写作台。主内容可通过跳转链接到达。

### Markdown and checkpoints

编辑器显示原始 Markdown、净化预览或两者对照。Markdown 标题逐级排布，图片自适应，代码与表格独立横向滚动，引用使用浅色分区。检查点提供预览、修改内容，以及仅在润色阶段出现的改写对照；历史记录折叠展示确认过的内容。

未确认检查点的修改在切换任务、创建其他任务、离开路由和刷新/关闭页面时受保护。应用确认框说明“当前阶段的修改尚未确认”，按钮明确区分“丢弃修改”和“继续修改”；取消丢弃保留输入。成功确认后重置修改基线。刷新/关闭使用浏览器原生提示，不承诺浏览器允许自定义文案。

Book 编辑器同样保护未保存修改与已选文件。在线 Markdown 与单份 PDF / DOCX / TXT / MD 文件上传均创建 Book；文件选择明确支持非空且不超过 20MB，替换文件先说明原文件与旧索引将清理。保存自动更新索引，发布、下线、删除、取消任务和最终确认使用说明具体后果的确认提示。提纲与扩写确认后推进下一阶段，润色确认另存新 Book 草稿；完成态提供打开草稿，不能呈现为自动发布成功。失败态提供重试，排队和运行态显示生成说明，已取消态保留确认记录。

### Original Book reading and conversation

在线编写内容通过净化 Markdown 呈现。上传 Book 按接口返回的原始解析 sections 展示：Markdown 段落净化渲染，其余文本保留换行，PDF 有页码时显示章节标题；不把重叠的检索分块拼接成原文。原文件仍是私有 MinIO 对象，点击下载调用对应公开或管理员受控接口取得短期签名链接。

右侧对话默认当前 Book，也可选择多本 Book 或分类。登录后会话由后端持久化；“最近”在对话栏内展示按时间分组的历史、标题搜索和所选状态，点击后恢复消息、原知识范围及模式。标题采用首个问题，地址中的 conversation 参数用于刷新恢复。新建对话保留历史，同范围可有多段会话；删除只影响所选记录并需确认。匿名对话仅保留本次页面会话，历史入口引导登录。

默认当前 Book 只有当前修订索引 READY 且 indexedRevision 与 revision 相符时可提问；自选范围由服务端重新核验权限与索引。登录后的上下文由原会话恢复，匿名发送采用最近六条成功消息。用户消息使用浅色气泡，回答使用净化 Markdown。Enter 发送、Shift+Enter 换行，中文输入法组合期间不发送。请求失败保留问题以便修改重试；登录失效清除账户会话与历史元数据，迟到响应不能恢复已清理的内容。

阅读器引用直接放在回答对应句子后，以 12px 蓝色下划线超链接只显示 Book / 文件名与可用页码。悬停可查看完整句子；只显示回答实际使用的引用，不再追加来源折叠列表。点击或 Enter 定位原文并转移焦点，段落用选择色与 2px 操作蓝描边突出，长文件只标出对应句子。引用地址保存页段与原文定位信息，直接打开及刷新可以恢复位置；手机关闭对话抽屉后显示出处。无法精确匹配时退到所属页段，不虚构匹配结果。

问答正文固定由“结论”和“论据”两部分组成，使用现有正文样式与加粗标签划分，不增加卡片或尾部来源列表。结论直接回答；论据只显示“出处：”与来源链接，不解释、不复述或摘录。同一文件的同一页合并显示。没有相关资料时也保留两部分，明确本轮检索依据不足，不暗示全库缺少该主题。

### State and answer patterns

PageState 按加载、错误、空内容、正常内容的顺序显示。加载是细线与文字，失败有具体错误和重试，空态保留可执行的引导；无数据时不能用演示 Book 替代。后台请求失去认证时进入登录，模型失败显示真实错误。

后台 Book 问答输入空白时禁用提交，提交时清除前一次答案并展示忙碌状态；阅读器则追加多轮消息。引用包含编号、来源、摘录和原文入口；无引用时不制造引用块或来源。加载与忙碌使用 status，错误使用 alert；后台问答结果使用 aria-live。引用文案提示读者核对原文。

## Do's and Don'ts

### Do:

- **Do** 从源码读取真实视觉值，后台复用蓝白配色与 Element Plus，阅读器复用局部灰白配色，两者保留 CJK 字体栈。
- **Do** 保持阅读器的原文行距、三栏与独立滚动，以及后台的明确字段、状态、动作与结果关系。
- **Do** 在手机保留目录中的分类、标签与搜索能力，并检查互斥抽屉、焦点约束、Escape 和焦点恢复。
- **Do** 保留未确认和未保存内容的离开保护，明确确认动作实际会推进阶段、发布还是丢弃。
- **Do** 让统计、Book、任务和引用来自真实接口；缺省或失败使用对应状态和下一步动作。
- **Do** 用键盘检查跳转链接、导航、字段、筛选、表格操作和确认框，保留减少动画支持。

### Don't:

- **Don't** 用概念图的示例内容、虚构阅读数或营销指标填充真实界面。
- **Don't** 为通过 CJK 字体字符串机械比对而改用与源码角色不符的字体。
- **Don't** 给开放表格、Book 目录和阅读纸面添加重复卡片、重阴影或抢占阅读层级的装饰。
- **Don't** 在手机隐藏标签能力，或让表格与长代码撑宽整份页面。
- **Don't** 将 AI 完成态写成自动发布；新草稿与发布状态始终由作者确认区分。
- **Don't** 将文章与 Book 重新拆成两个产品概念，或让匿名用户访问草稿与下线内容。
- **Don't** 用索引分块替代原始正文，或把匿名页面内保留描述为登录账户的持久化历史。
