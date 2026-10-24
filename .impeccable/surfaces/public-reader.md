# Book reading workspace — Read mode

## Direction contract

THESIS: A Book reading desk: real directory, original text, grounded conversation in one viewport. Articles and Books are ONE content concept named Book.
OWN-WORLD: Reference-pinned light-gray panels, white paper, graphite text, cobalt active states, fine rules. Existing CJK families and Lucide icons. The existing admin identity remains intact.
STORY: Select a Book, read its structure, ask a question and inspect the cited passage. Publishing controls public access.
FIRST VIEWPORT: A 52px toolbar above 260px directory, flexible paper and 350px conversation; composer anchors the bottom. The implemented paper maximum width is 800px including padding.
SIGNATURE: Independently collapse either sidebar without losing central scroll or current in-memory conversation. Mobile uses mutually exclusive focus-contained drawers.
QUALITY BAR: Faithful three-pane hierarchy, readable center at 1280px, 390px usability, real multi-turn model calls, no private-content leak, legacy migration without content loss.

## Visual authority and implementation

User-pinned reference: C:/Users/12507/AppData/Local/Temp/codex-clipboard-4266e527-97b0-4480-b82d-797a429f9044.png. Implement its three-pane topology directly, code-led. The authorized redesign concerns the reader surface; preserve the admin palette, layout and components documented in root DESIGN.md.

Reader source: frontend/src/views/Reader.vue and frontend/src/reader.css; shell and routing: frontend/src/App.vue and frontend/src/router.js. Use light-neutral directory #f6f7f8, toolbar and conversation #fafafa, white paper, graphite #252b34, muted #666e79, active/focus cobalt #365ec9 and 1px separators #e3e5e8. Noto Sans SC supplies chrome and original body, Noto Serif SC supplies the Book title, Cascadia Code / Consolas supplies code. No oversized hero, decorative cards or metrics. These colors are scoped to the reader, not replacements for admin tokens.

Desktop uses a 100dvh shell with a 52px toolbar and independently scrolling directory, original content and transcript. Default side widths are 260px / 350px; at ≤1280px and ≥761px they are 230px / 320px, at ≤980px and ≥761px 200px / 280px. Paper padding is respectively 48px 48px 60px, 40px 32px, and 34px 24px. Persistent toolbar controls independently close and reopen either side. Desktop preferences are stored locally; current original-content scroll and per-Book in-memory messages survive sidebar toggles, not page reloads.

## One Book truth and access

The canonical book table backs creation, writing, publication and reading. Existing 15 article rows remain unchanged as archives with idempotent legacy_article_book mappings; published status, category and tags are preserved. Public directory lists published Books. Logged-in “我的 Book” is the same catalog including drafts, not a second content type. All new directory links, headings, original reading and scoped conversations use Book IDs. Keep authored titles and body text unchanged even if they contain the old word “文章”.

`/` uses the published catalog and opens its first Book when unfiltered and nonempty. `/books` is the authenticated full catalog. `/books/:id` is the unified reader: anonymous access is restricted to PUBLISHED Books, including published file Books; draft and offline content requires ADMIN. `/articles/:id` resolves the legacy ID and replaces the route with `/books/:id`. `/ask` uses the same reader and opens the conversation drawer on mobile; select a Book before sending. `/admin/books`, `/admin/books/new`, and `/admin/books/:id` are the canonical management routes. `/admin` and former article-management links resolve to Book management; `/admin/ask` redirects to `/books`. No separate article-management navigation remains. Login and unknown routes retain the ordinary public shell with Book/书库 language.

Left directory groups real Books by category, searches by title, filters category/tags and loads 50 items per page. It contains the current rendered h1–h3 outline. Center presents saved online Markdown or original parsed file sections, with page labels where supplied; never concatenate overlapping vector chunks as reading text. Original files remain private MinIO objects; download actions obtain short-lived links through publication-controlled public or ADMIN endpoints. Online writing and PDF/DOCX/TXT/MD upload are two creation modes for the same Book.

Right panel includes current-Book scope, per-Book transcript, inline source links and a bottom composer. User refinement: do not show trailing source accordion lists. Replace used citation markers with short links containing Book/file name and optional page only; evidence is a concise origin line without explanation or visible excerpts; activate to locate and highlight the passage, close mobile chat and transfer focus to original content. Links carry a reloadable original-location hash; missing citations never manufacture sources. Ask only when indexStatus is READY and indexedRevision matches revision. Send recent successful history to real scoped model calls; failed requests retain the editable question. Publication state remains separate from indexing state: file Books require the current index READY before publication, but original reading itself is not gated on index readiness. Creation and writing tasks save drafts; only author-confirmed publication enables public access.

## Mobile and evidence

At ≤760px original content remains primary; paper padding becomes 30px 24px 42px, title 27px and body 15px. Directory and conversation become mutually exclusive dialogs below the 52px toolbar, width min(340px, 100vw − 36px), with overlay and close controls. Original content is inert while open. Opening uses the first visible enabled focus target; Tab / Shift+Tab remain inside, including entry from outside. Escape, mask and close restore the corresponding toolbar control. Direct `/ask` uses the same opening/focus path. Keep long code and tables within their own scrolling containers.

Screenshot evidence: .impeccable/review/desktop.png (1440×1000), user-1280.png (1280×900), mobile.png and mobile-chat.png (390×844). These show sampled data, not fixed content or permanent counts. Additional direct-ask focus evidence is in .impeccable/review/direct-ask-focus.json and direct-ask-mobile.png; login-header.png covers the ordinary public shell. Root DESIGN.md captures shared and surface-scoped design facts; .impeccable/design.json extends its tokens with reusable snippets, elevation, motion and breakpoints. Earlier admin mechanical comparison warnings are outside the reader change and are not reader blockers.
