<script setup>
import {
  ref,
  reactive,
  computed,
  watch,
  nextTick,
  onMounted,
  onUnmounted,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  BookOpen,
  FileText,
  Folder,
  Search,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  MessageSquare,
  Send,
  X,
  ChevronRight,
  RefreshCw,
  Download,
  ArrowUpRight,
  List,
  History,
  SquarePen,
} from "lucide-vue-next";
import { api, query, date, flatten } from "../api";
import { useAuth } from "../stores/auth";
import Markdown from "../components/Markdown.vue";
import CitedAnswer from "../components/CitedAnswer.vue";
import { locateSource, markPlainQuote, sourceHref } from "../citations";
import { createReaderSession } from "../readerSession";
import PageState from "../components/PageState.vue";
import KnowledgeScopePicker from "../components/KnowledgeScopePicker.vue";
import ReaderRecentConversations from "../components/ReaderRecentConversations.vue";
import { readerScopeKey, sameKnowledgeScope } from "../knowledgeScope";
import "../reader.css";
const route = useRoute(),
  router = useRouter(),
  auth = useAuth();
const mobile = ref(window.matchMedia("(max-width: 760px)").matches);
function preference(key) {
  try {
    return localStorage.getItem(key) !== "0";
  } catch {
    return true;
  }
}
const leftOpen = ref(!mobile.value && preference("reader-left")),
  rightOpen = ref(!mobile.value && preference("reader-right"));
const leftPane = ref(null),
  rightPane = ref(null),
  leftToggle = ref(null),
  rightToggle = ref(null),
  scroller = ref(null),
  body = ref(null),
  transcript = ref(null);
const readerState = reactive({ document: null, directory: [], total: 0 });
const library = ref(route.meta.privateReader ? "books" : "articles"),
  directory = computed(() => readerState.directory),
  total = computed(() => readerState.total),
  page = ref(1),
  directoryLoading = ref(false),
  directoryError = ref("");
const filters = reactive({ keyword: "", categoryId: "", tagId: "" }),
  categories = ref([]),
  tags = ref([]);
const document = computed(() => readerState.document?.book || null),
  sections = computed(() => readerState.document?.sections || []),
  loading = ref(false),
  error = ref(""),
  headings = ref([]),
  activeHeading = ref("");
const conversations = reactive({});
const readerSession = createReaderSession({
  request: api,
  hasToken: () => !!sessionStorage.getItem("book-agent-token"),
  state: readerState,
  conversations,
});
let observer,
  statusTimer,
  media,
  alive = true;
const isBook = computed(() => true);
const privateAccess = computed(() => !!readerState.document?.privateAccess);
const selectedBookIds = ref([]), selectedPublicOnly = ref(true);
const activeSessionId = ref(null), restoredScope = ref(null), recentOpen = ref(false), historyOpening = ref(false), historyError = ref("");
let historySelection = 0;
const currentKey = computed(() =>
  activeSessionId.value ? `session:${activeSessionId.value}` : restoredScope.value && !selectedBookIds.value.length
    ? `scope:${selectedPublicOnly.value ? 'public' : 'personal'}:all`
    : readerScopeKey(route.params.id, selectedBookIds.value, selectedPublicOnly.value),
);
const conversation = computed(
  () => readerSession.conversation(currentKey.value,
    !!auth.user || (selectedBookIds.value.length ? !selectedPublicOnly.value :
      privateAccess.value && String(document.value?.id) === String(route.params.id))),
);
const scope = computed(() => restoredScope.value && !selectedBookIds.value.length
  ? `${selectedPublicOnly.value ? "公开书库" : "我的知识"} · 全库`
  : selectedBookIds.value.length
  ? `${selectedPublicOnly.value ? "公开书库" : "我的知识"} · ${selectedBookIds.value.length} 本 Book`
  : document.value?.title || "先选择 Book 或文件夹");
function chatSelection() {
  if (restoredScope.value) return { ...restoredScope.value, bookIds: [...selectedBookIds.value], publicOnly: selectedPublicOnly.value };
  return selectedBookIds.value.length
    ? { bookIds: [...selectedBookIds.value], publicOnly: selectedPublicOnly.value }
    : { bookIds: [Number(route.params.id)], publicOnly: !privateAccess.value };
}
function forgetSelectedHistory() {
  historySelection++; historyOpening.value = false; historyError.value = ""; activeSessionId.value = null; restoredScope.value = null;
  if (route.query.conversation) { const { conversation: _id, ...rest } = route.query; router.replace({ query: rest }); }
}
function changeChatScope() { forgetSelectedHistory(); }
function resetScope() { forgetSelectedHistory(); selectedBookIds.value = []; selectedPublicOnly.value = !privateAccess.value; }
watch(privateAccess, (value) => { if (!selectedBookIds.value.length && !restoredScope.value) selectedPublicOnly.value = !value; });
const groups = computed(() => {
  const result = new Map();
  for (const item of directory.value) {
    const label = item.categoryName || "未分类";
    if (!result.has(label)) result.set(label, []);
    result.get(label).push(item);
  }
  return [...result].map(([name, items]) => ({ name, items }));
});
const canAsk = computed(
  () =>
    !historyOpening.value && !conversation.value.serverBusy && (!!restoredScope.value || !!selectedBookIds.value.length || (!loading.value &&
    !error.value &&
    !!document.value &&
    document.value?.indexStatus === "READY" &&
    document.value.indexedRevision === document.value.revision)),
);
function focusable(pane) {
  return [...(pane?.querySelectorAll('a,button,input,textarea,select,[tabindex="0"]') || [])]
    .filter((el) => !el.disabled && el.getClientRects().length);
}
async function openDrawer(side) {
  (side === "left" ? leftOpen : rightOpen).value = true;
  if (!mobile.value) return;
  (side === "left" ? rightOpen : leftOpen).value = false;
  await nextTick();
  focusable((side === "left" ? leftPane : rightPane).value)[0]?.focus();
}
async function toggle(side) {
  const target = side === "left" ? leftOpen : rightOpen;
  target.value = !target.value;
  if (mobile.value) {
    if (target.value) await openDrawer(side);
  } else
    try {
      localStorage.setItem("reader-" + side, target.value ? "1" : "0");
    } catch {}
}
function closeDrawer() {
  const wasLeft = leftOpen.value;
  leftOpen.value = false;
  rightOpen.value = false;
  nextTick(() => (wasLeft ? leftToggle : rightToggle).value?.focus());
}
function keyboard(event) {
  if (event.target.closest?.(".knowledge-scope-dialog")) return;
  if (!mobile.value || (!leftOpen.value && !rightOpen.value)) return;
  if (event.key === "Escape") {
    event.preventDefault();
    closeDrawer();
  }
  if (event.key !== "Tab") return;
  const pane = (leftOpen.value ? leftPane : rightPane).value;
  const elements = focusable(pane);
  const first = elements[0],
    last = elements.at(-1);
  if (
    event.shiftKey &&
    (window.document.activeElement === first ||
      !pane.contains(window.document.activeElement))
  ) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && (window.document.activeElement === last ||
    !pane.contains(window.document.activeElement))) {
    event.preventDefault();
    first?.focus();
  }
}
async function loadDirectory(append = false) {
  const work = readerSession.beginDirectory(
    query({ ...filters, pageNum: page.value, pageSize: 50 }),
    { privateAccess: library.value === "books", append },
  );
  directoryLoading.value = true;
  directoryError.value = "";
  try {
    const data = await work.promise;
    if (!work.current() || !data) return;
    if (
      route.path === "/" &&
      !filters.keyword &&
      !filters.categoryId &&
      !filters.tagId &&
      data.list.length
    )
      router.replace("/books/" + data.list[0].id);
  } catch (e) {
    if (work.current()) directoryError.value = e.message;
  } finally {
    if (work.current()) directoryLoading.value = false;
  }
}
async function search() {
  page.value = 1;
  await loadDirectory();
}
async function more() {
  page.value++;
  await loadDirectory(true);
}
function linkFor(item) {
  return `/books/${item.id}`;
}
async function switchLibrary(value) {
  await router.push(value === "books" ? "/books" : "/");
  if (mobile.value) closeDrawer();
}
async function navigate(item) {
  await router.push(linkFor(item));
  if (mobile.value) closeDrawer();
}
async function collectHeadings(work) {
  await nextTick();
  if (work && !work.current()) return;
  observer?.disconnect();
  const nodes = [...(body.value?.querySelectorAll("h1,h2,h3") || [])];
  headings.value = nodes.map((node, index) => {
    node.id = "reader-heading-" + index;
    return {
      id: node.id,
      text: node.textContent,
      level: Number(node.tagName.slice(1)),
    };
  });
  observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries)
        if (entry.isIntersecting) activeHeading.value = entry.target.id;
    },
    { root: scroller.value, rootMargin: "-5% 0px -65% 0px", threshold: 0 },
  );
  nodes.forEach((node) => observer.observe(node));
  if (restoreCitation()) return;
  if (route.hash) {
    const target = nodes.find((n) => "#" + n.id === route.hash);
    target?.scrollIntoView({ block: "start" });
  }
}
async function loadDocument({ publicOnly = false, loginOnMissing = false } = {}) {
  const id = route.params.id;
  const work = readerSession.beginRead(id, {
    legacy: route.path.startsWith("/articles/"),
    publicOnly,
  });
  headings.value = [];
  error.value = "";
  observer?.disconnect();
  if (!id) {
    loading.value = false;
    if (route.path === "/ask") await openDrawer("right");
    return;
  }
  loading.value = true;
  try {
    const result = await work.promise;
    if (!work.current() || !result) return;
    if (result.redirectBookId != null) {
      await router.replace("/books/" + result.redirectBookId);
      return;
    }
    if (auth.user && !route.query.conversation) {
      const chat=readerSession.conversation(`book:${id}`,true);
      if(!chat.historyLoaded) chat.historyLoading=loadHistory(work,id,chat,{bookIds:[Number(id)],publicOnly:!privateAccess.value});
    }
    if (privateAccess.value && library.value !== "books") {
      library.value = "books";
      Object.assign(filters, { keyword: "", categoryId: "", tagId: "" });
      page.value = 1;
      loadDirectory();
    }
  } catch (e) {
    if (work.current()) {
      error.value = e.message;
      if (loginOnMissing && e.code === 404)
        router.replace({ path: "/login", query: { redirect: route.fullPath } });
    }
  } finally {
    if (work.current()) {
      loading.value = false;
      await nextTick();
      if (!work.current()) return;
      scroller.value?.scrollTo({ top: 0 });
      await collectHeadings(work);
    }
  }
}
async function jump(id) {
  activeHeading.value = id;
  body.value?.querySelector("#" + id)?.scrollIntoView({ block: "start" });
  if (mobile.value) closeDrawer();
}
async function download(fileId) {
  const work = readerSession.operation({ document: true });
  const id = document.value?.id || route.params.id;
  const privateScope = privateAccess.value, chat = conversation.value;
  try {
    const result = await api(
      `${privateScope ? "/api/books" : "/api/blog/books"}/${id}/files/${fileId}/download`,
    );
    if (!work.current()) return;
    const a = window.document.createElement("a");
    a.href = result.url;
    a.rel = "noopener";
    a.click();
  } catch (e) {
    if (work.current()) chat.error = e.message;
  }
}
function restoreCitation() {
  if (!route.hash.startsWith("#cite=")) return false;
  let source;
  try { source = JSON.parse(decodeURIComponent(route.hash.slice(6))); } catch { return false; }
  if (!source || typeof source.quote !== "string" || source.quote.length > 180) return false;
  body.value?.querySelectorAll(".reader-source-target").forEach((el) => {
    if (el.tagName === "MARK") el.replaceWith(...el.childNodes);
    else el.classList.remove("reader-source-target");
  });
  const target = markPlainQuote(locateSource(body.value, source, source.quote), source.quote);
  if (!target) return;
  target.classList.add("reader-source-target");
  target.scrollIntoView({ block: "center" });
  target.setAttribute("tabindex", "-1");
  target.focus({ preventScroll: true });
  return true;
}
async function cite(source, quote) {
  if (mobile.value) closeDrawer();
  await router.push(sourceHref(source, quote));
  await nextTick();
  restoreCitation();
}
async function scrollChat() {
  await nextTick();
  transcript.value?.scrollTo({ top: transcript.value.scrollHeight });
}
async function loadHistory(work,id,chat,selection = chatSelection()) {
  const version = chat.historyVersion || 0;
  const current = () => work.current() && version === (chat.historyVersion || 0);
  try {
    const sessions=await api(id ? `/api/conversations?bookId=${id}` : '/api/conversations');
    if(!current())return;
    const matching=sessions.find((item) => item.mode === 'qa' && sameKnowledgeScope(item.scope,selection.bookIds,selection.publicOnly));
    if(matching){
      const data=await api(`/api/conversations/${matching.id}`);
      if(!current())return;
      chat.sessionId=data.id; chat.title=data.title; chat.mode=data.mode; chat.serverBusy=data.busy;
      if(!chat.messages.length)chat.messages=data.messages.map(m=>({...m,failed:m.status==='FAILED'}));
    }
    chat.historyLoaded=true;
  } catch(e){if(current())chat.error=e.message;}
  finally { if(current())chat.historyLoading=null; }
}
function syncChatCopies(chat) {
  for (const cached of Object.values(conversations)) {
    if (cached !== chat && chat.sessionId && cached.sessionId === chat.sessionId && !cached.busy) {
      cached.messages = chat.messages.map(message => ({ ...message }));
      cached.title = chat.title; cached.mode = chat.mode; cached.serverBusy = chat.serverBusy;
    }
  }
}
async function openHistory(item) {
  const id = Number(typeof item === "object" ? item.id : item);
  if (!auth.user || !Number.isSafeInteger(id) || id <= 0 || conversation.value.busy) return;
  const run = ++historySelection, work = readerSession.operation();
  historyOpening.value = true; historyError.value = "";
  try {
    const data = await api(`/api/conversations/${id}`);
    if (!work.current() || run !== historySelection) return;
    const chat = readerSession.conversation(`session:${id}`, true);
    Object.assign(chat, { sessionId: id, title: data.title, mode: data.mode, serverBusy: data.busy,
      messages: data.messages.map(message => ({ ...message, failed: message.status === "FAILED" })),
      historyLoaded: true, question: "", error: "" });
    syncChatCopies(chat);
    restoredScope.value = data.scope;
    selectedBookIds.value = [...data.scope.bookIds]; selectedPublicOnly.value = data.scope.publicOnly;
    activeSessionId.value = id; recentOpen.value = false;
    if (String(route.query.conversation) !== String(id)) await router.replace({ query: { ...route.query, conversation: id } });
    scrollChat();
  } catch (e) { if(work.current() && run === historySelection) historyError.value = e.message; }
  finally { if(work.current() && run === historySelection) historyOpening.value = false; }
}
function newChat() {
  if (conversation.value.busy) return;
  historySelection++; historyOpening.value = false; historyError.value = "";
  activeSessionId.value = null;
  const chat = conversation.value;
  Object.assign(chat, { sessionId: null, title: "", mode: "qa", serverBusy: false, messages: [], question: "", error: "",
    historyLoaded: true, historyLoading: null, historyVersion: (chat.historyVersion || 0) + 1 });
  if (route.query.conversation) { const { conversation: _id, ...rest } = route.query; router.replace({ query: rest }); }
  recentOpen.value = false; nextTick(() => window.document.getElementById("reader-question")?.focus());
}
function deletedHistory(id) {
  const current = conversation.value.sessionId === id;
  for (const [key, chat] of Object.entries(conversations)) {
    if (chat.sessionId === id) {
      chat.historyVersion = (chat.historyVersion || 0) + 1;
      delete conversations[key];
    }
  }
  if (current) { newChat(); recentOpen.value = true; }
}
async function send(value) {
  const chat=conversation.value,question=(typeof value==='string'?value:chat.question).trim();
  if(!question||chat.busy||!canAsk.value)return;
  const work=readerSession.operation(),id=route.params.id,custom=!!restoredScope.value||!!selectedBookIds.value.length,
    title=custom?scope.value:document.value?.title||'',privateScope=privateAccess.value,selection=chatSelection();
  chat.busy=true;chat.error='';
  let userMessage;
  try {
    if(chat.historyLoading)await chat.historyLoading;
    if(!work.current())return;
    const history=chat.messages.filter(m=>!m.failed).slice(-6).map(m=>({role:m.role,content:m.content.slice(0,4000)}));
    chat.question='';userMessage={role:'user',content:question};chat.messages.push(userMessage);scrollChat();
    const retrievalQuestion=!custom&&title&&question.length+title.length<475?`《${title}》：${question}`:question;
    let result;
    if(auth.user){
      if(!chat.sessionId){
        const label=question.slice(0,80),created=await api('/api/conversations',{method:'POST',body:{...selection,title:label,mode:'qa'}});
        if(!work.current())return;
        chat.sessionId=created.id;chat.title=label;chat.mode='qa';
        if(conversation.value===chat)await router.replace({query:{...route.query,conversation:created.id}});
        if(!work.current())return;
      }
      result=await api(`/api/conversations/${chat.sessionId}/messages`,{method:'POST',body:{question:retrievalQuestion}});
    } else if(custom) result=await api('/api/blog/knowledge/ask',{method:'POST',body:{question:retrievalQuestion,bookIds:selection.bookIds,history,topK:5,searchMode:'hybrid',rerank:true,mode:'qa'}});
    else result=await api(`${privateScope?'/api/books':'/api/blog/books'}/${id}/ask`,{method:'POST',body:{question:retrievalQuestion,history,topK:5}});
    if(!work.current())return;
    chat.messages.push({role:'assistant',content:result.answer,sources:result.sources||[],bookTitle:title});
    syncChatCopies(chat);
  }catch(e){if(work.current()){if(userMessage)userMessage.failed=true;chat.error=e.message;chat.question=question;}}
  finally{if(work.current()){chat.busy=false;if(conversation.value===chat)scrollChat();}}
}
function enter(event) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    send();
  }
}
function sessionEnded() {
  recentOpen.value = false;
  resetScope();
  const pendingRead = loading.value, pendingDirectory = directoryLoading.value;
  const privateLibrary = library.value === "books";
  const { privateDocument } = readerSession.invalidate();
  loading.value = false;
  directoryLoading.value = false;
  if (privateDocument) {
    headings.value = [];
    activeHeading.value = "";
    observer?.disconnect();
    error.value = "";
  }
  if (privateLibrary) {
    readerState.directory = [];
    readerState.total = 0;
    library.value = "articles";
    Object.assign(filters, { keyword: "", categoryId: "", tagId: "" });
    page.value = 1;
  }
  // The private directory route is redirected by App; mixed-access detail routes
  // must reload through the public endpoint before anything can be shown again.
  if (route.meta.privateReader) return;
  if (privateDocument || pendingRead)
    loadDocument({ publicOnly: true, loginOnMissing: privateDocument });
  if (privateLibrary || pendingDirectory) loadDirectory();
}
watch([()=>auth.user?.id,currentKey],()=>{
  if(!auth.user || activeSessionId.value || route.query.conversation)return;
  const custom=!!selectedBookIds.value.length;
  if(!custom&&(!document.value||String(document.value.id)!==String(route.params.id)))return;
  const work=readerSession.operation({document:!custom}),chat=conversation.value;
  if(!chat.historyLoaded&&!chat.historyLoading)chat.historyLoading=loadHistory(work,custom?null:route.params.id,chat,chatSelection());
});
watch(() => route.query.conversation, id => {
  if (id && Number(id) !== conversation.value.sessionId) openHistory(id);
  else {
    historySelection++; historyOpening.value=false;
    if (!id && activeSessionId.value) { activeSessionId.value=null; restoredScope.value=null; selectedBookIds.value=[]; }
  }
});
watch(() => auth.user?.id, id => { if(id && route.query.conversation)openHistory(route.query.conversation); });
watch(library,async value=>{const work=readerSession.operation();try{const [c,t]=await Promise.all([api(value==='books'?'/api/categories':'/api/blog/categories'),api(value==='books'?'/api/tags':'/api/blog/tags')]);if(work.current()){categories.value=flatten(c);tags.value=t;}}catch{}});
watch(() => route.hash, async () => { await nextTick(); restoreCitation(); });
watch(
  () => route.path,
  async () => {
    const desired = route.meta.privateReader ? "books" : "articles";
    if (!route.params.id && desired !== library.value) {
      library.value = desired;
      Object.assign(filters, { keyword: "", categoryId: "", tagId: "" });
      page.value = 1;
      loadDirectory();
    }
    await loadDocument();
  },
);
onMounted(async () => {
  window.document.addEventListener("keydown", keyboard);
  window.addEventListener("book-agent-session-expired", sessionEnded);
  media = window.matchMedia("(max-width: 760px)");
  media.onchange = (e) => {
    mobile.value = e.matches;
    leftOpen.value = !e.matches && preference("reader-left");
    rightOpen.value = !e.matches && preference("reader-right");
  };
  loadDirectory();
  loadDocument();
  if (auth.user && route.query.conversation) openHistory(route.query.conversation);
  try {
    const [c, t] = await Promise.all([
      api(library.value === "books"?"/api/categories":"/api/blog/categories"),
      api(library.value === "books"?"/api/tags":"/api/blog/tags"),
    ]);
    if (!alive) return;
    categories.value = flatten(c);
    tags.value = t;
  } catch {}
  if (!alive) return;
  statusTimer = setInterval(async () => {
    if (
      isBook.value &&
      document.value &&
      ["QUEUED", "RUNNING"].includes(document.value.indexStatus)
    ) {
      const id = document.value.id;
      const work = readerSession.operation({ document: true });
      try {
        const data = await api(
          (privateAccess.value ? "/api/books/" : "/api/blog/books/") + id,
        );
        if (work.current() && document.value?.id === id) {
          if (data.revision === document.value.revision)
            Object.assign(document.value, {
              indexStatus: data.indexStatus,
              indexedRevision: data.indexedRevision,
            });
          else loadDocument();
        }
      } catch {}
    }
  }, 4000);
});
onUnmounted(() => {
  alive = false;
  readerSession.invalidate();
  observer?.disconnect();
  clearInterval(statusTimer);
  window.document.removeEventListener("keydown", keyboard);
  window.removeEventListener("book-agent-session-expired", sessionEnded);
  if (media) media.onchange = null;
});
</script>

<template>
  <div
    class="reader-shell"
    :class="{ 'left-closed': !leftOpen, 'right-closed': !rightOpen }"
  >
    <header class="reader-toolbar">
      <div class="reader-toolbar-left">
        <button
          ref="leftToggle"
          class="reader-icon-button"
          :aria-label="leftOpen ? '收起目录' : '展开目录'"
          :aria-expanded="leftOpen"
          aria-controls="reader-directory"
          @click="toggle('left')"
        >
          <component
            :is="leftOpen ? PanelLeftClose : PanelLeftOpen"
            :size="19"
          /></button
        ><RouterLink to="/" class="reader-brand"
          ><BookOpen :size="20" /><span>Book_Agent</span></RouterLink
        >
      </div>
      <div class="reader-breadcrumb">
        <span>{{ isBook ? "Book" : "阅读空间" }}</span
        ><ChevronRight :size="13" /><strong>{{
          document?.title ||
          (route.meta.privateReader ? "我的书库" : "Book 目录")
        }}</strong>
      </div>
      <div class="reader-toolbar-right">
        <RouterLink v-if="auth.user" class="reader-admin-link" to="/admin/knowledge">全库搜索</RouterLink>
        <RouterLink
          class="reader-admin-link"
          :to="auth.user ? '/admin/books' : '/login'"
          >{{ auth.user ? "写作台" : "作者登录"
          }}<ArrowUpRight :size="14" /></RouterLink
        ><button
          ref="rightToggle"
          class="reader-icon-button"
          :aria-label="rightOpen ? '收起对话' : '展开对话'"
          :aria-expanded="rightOpen"
          aria-controls="reader-conversation"
          @click="toggle('right')"
        >
          <component
            :is="rightOpen ? PanelRightClose : PanelRightOpen"
            :size="19"
          />
        </button>
      </div>
    </header>
    <button
      v-if="mobile && (leftOpen || rightOpen)"
      class="reader-drawer-mask"
      tabindex="-1"
      aria-label="关闭侧栏"
      @click="closeDrawer"
    ></button>
    <aside
      v-if="leftOpen"
      id="reader-directory"
      ref="leftPane"
      class="reader-directory"
      :role="mobile ? 'dialog' : undefined"
      :aria-modal="mobile || undefined"
      aria-labelledby="reader-directory-title"
    >
      <div class="reader-panel-title">
        <h2 id="reader-directory-title"><Folder :size="17" />目录</h2>
        <button
          class="reader-icon-button"
          aria-label="关闭目录"
          @click="mobile ? closeDrawer() : toggle('left')"
        >
          <X :size="16" />
        </button>
      </div>
      <div class="reader-library-switch">
        <button
          :class="{ active: library === 'articles' }"
          @click="switchLibrary('articles')"
        >
          已发布 Book</button
        ><button
          v-if="auth.user"
          :class="{ active: library === 'books' }"
          @click="switchLibrary('books')"
        >
          我的 Book
        </button>
      </div>
      <form class="reader-directory-search" @submit.prevent="search">
        <Search :size="15" /><input
          v-model="filters.keyword"
          aria-label="搜索目录"
          placeholder="搜索标题…"
        /><button class="reader-icon-button" type="submit" aria-label="搜索">
          <ChevronRight :size="16" />
        </button>
      </form>
      <div v-if="categories.length || tags.length" class="reader-filters">
        <select
          v-model="filters.categoryId"
          aria-label="按分类筛选"
          @change="search"
        >
          <option value="">全部分类</option>
          <option v-for="c in categories" :key="c.id" :value="c.id">
            {{ c.label }}
          </option></select
        ><select
          v-if="tags.length"
          v-model="filters.tagId"
          aria-label="按标签筛选"
          @change="search"
        >
          <option value="">全部标签</option>
          <option v-for="t in tags" :key="t.id" :value="t.id">
            {{ t.name }}
          </option>
        </select>
      </div>
      <div class="reader-directory-scroll">
        <p
          v-if="directoryLoading && !directory.length"
          class="reader-small-state"
          role="status"
        >
          正在加载目录…
        </p>
        <div v-if="directoryError" class="reader-small-state" role="alert">
          <p>{{ directoryError }}</p>
          <button @click="search">重试加载目录</button>
        </div>
        <p
          v-else-if="!directoryLoading && !directory.length"
          class="reader-small-state"
        >
          {{
            filters.keyword || filters.categoryId || filters.tagId
              ? "没有匹配的内容，试试其他筛选条件。"
              : "这里还没有可阅读的内容。"
          }}
        </p>
        <nav aria-label="Book 目录">
          <details
            v-for="group in groups"
            :key="group.name"
            class="reader-folder"
            open
          >
            <summary>
              <Folder :size="15" />{{ group.name
              }}<small>{{ group.items.length }}</small>
            </summary>
            <a
              v-for="item in group.items"
              :key="item.id"
              :href="linkFor(item)"
              :class="{ selected: route.path === linkFor(item) }"
              :aria-current="route.path === linkFor(item) ? 'page' : undefined"
              @click.prevent="navigate(item)"
              ><FileText :size="15" /><span>{{ item.title }}</span></a
            >
          </details>
        </nav>
        <button
          v-if="directory.length < total && !directoryError"
          class="reader-load-more"
          :disabled="directoryLoading"
          @click="more"
        >
          {{ directoryLoading ? "正在加载…" : "加载更多" }}
        </button>
        <nav
          v-if="headings.length"
          class="reader-outline"
          aria-label="本文大纲"
        >
          <h3><List :size="15" />本文大纲</h3>
          <button
            v-for="h in headings"
            :key="h.id"
            :class="{ active: activeHeading === h.id }"
            :style="{ '--heading-depth': Math.min(h.level - 1, 2) }"
            @click="jump(h.id)"
          >
            {{ h.text }}
          </button>
        </nav>
      </div>
      <div class="reader-directory-footer">
        <BookOpen :size="15" /><span>{{
          library === "books" ? "我的书库" : "公开书库"
        }}</span
        ><small>{{ total }} 篇</small>
      </div>
    </aside>
    <main
      id="main"
      ref="scroller"
      class="reader-content"
      tabindex="-1"
      :inert="mobile && (leftOpen || rightOpen)"
    >
      <div class="reader-paper">
        <PageState :loading="loading" :error="error" @retry="loadDocument">
          <article v-if="document" class="reader-document">
            <header class="reader-document-header">
              <div class="reader-document-meta">
                <span>{{ document.categoryName || "Book" }}</span
                ><span>{{
                  date(document.publishedAt || document.updatedAt).slice(0, 10)
                }}</span
                ><span v-if="isBook">{{
                  document.sourceMode === "UPLOAD" ? "文件上传" : "在线编写"
                }}</span>
              </div>
              <h1>{{ document.title }}</h1>
              <p v-if="document.summary" class="reader-summary">
                {{ document.summary }}
              </p>
              <div v-if="document.tags?.length" class="reader-tags">
                <span v-for="tag in document.tags" :key="tag.id"
                  >#{{ tag.name }}</span
                >
              </div>
            </header>
            <div ref="body" class="reader-document-body">
              <template v-if="isBook && document.sourceMode === 'UPLOAD'"
                ><section
                  v-for="(s, i) in sections"
                  :key="i"
                  :data-file="s.fileId"
                  :data-page="s.page || 0"
                  class="reader-file-section"
                >
                  <h2 v-if="s.page">第 {{ s.page }} 页</h2>
                  <Markdown v-if="s.format === 'md'" :content="s.text" />
                  <div v-else class="reader-plain-text">{{ s.text }}</div>
                </section>
                <button
                  v-if="document.files?.length"
                  class="reader-source-download"
                  @click="download(document.files[0].id)"
                >
                  <Download :size="16" />下载 Book 原文件
                </button></template
              ><Markdown v-else :content="document.content" />
            </div>
            <footer class="reader-document-footer">
              <span>{{
                document.status === "PUBLISHED"
                  ? "已发布 Book"
                  : "Book 尚未发布，仅所有者和被授权用户可访问"
              }}</span
              ><button @click="rightOpen || toggle('right')">
                <MessageSquare :size="15" />聊聊这{{
                  isBook ? "本 Book" : "篇文章"
                }}
              </button>
            </footer>
          </article>
          <section v-else class="reader-home">
            <div class="reader-home-title">
              <BookOpen :size="30" />
              <h1>
                {{ route.meta.privateReader ? "我的书库" : "阅读与思考" }}
              </h1>
              <p>
                {{
                  isBook
                    ? "选择一本 Book，继续阅读并与内容对话。"
                    : "从目录打开一本 Book，让阅读与提问发生在同一个地方。"
                }}
              </p>
            </div>
            <div v-if="directory.length" class="reader-home-list">
              <a
                v-for="item in directory"
                :key="item.id"
                :href="linkFor(item)"
                @click.prevent="navigate(item)"
                ><FileText :size="18" />
                <div>
                  <h2>{{ item.title }}</h2>
                  <p v-if="item.summary">{{ item.summary }}</p>
                </div>
                <ArrowUpRight :size="17"
              /></a>
            </div>
            <p v-else class="reader-home-empty">
              {{
                directoryLoading
                  ? "正在加载…"
                  : isBook
                    ? "还没有 Book，可在写作台在线编写或上传文件创建。"
                    : "还没有已发布 Book。"
              }}
            </p>
            <RouterLink
              v-if="isBook"
              to="/admin/books/new"
              class="reader-source-download"
              >新建 Book<ArrowUpRight :size="16"
            /></RouterLink>
          </section> </PageState
        ><RouterLink
          v-if="error && !auth.user"
          :to="{ path: '/login', query: { redirect: route.fullPath } }"
          class="reader-source-download"
          >登录后阅读未发布 Book<ArrowUpRight :size="16"
        /></RouterLink>
      </div>
    </main>
    <aside
      v-if="rightOpen"
      id="reader-conversation"
      ref="rightPane"
      class="reader-conversation"
      :role="mobile ? 'dialog' : undefined"
      :aria-modal="mobile || undefined"
      aria-labelledby="reader-chat-title"
    >
      <div class="reader-panel-title">
        <h2 id="reader-chat-title"><MessageSquare :size="17" />与内容对话</h2>
        <div>
          <button
            class="reader-icon-button"
            :disabled="conversation.busy"
            aria-label="新建对话"
            title="新建对话，保留已有记录"
            @click="newChat"
          >
            <SquarePen :size="16" /></button
          ><button
            class="reader-icon-button"
            aria-label="关闭对话"
            @click="mobile ? closeDrawer() : toggle('right')"
          >
            <X :size="16" />
          </button>
        </div>
      </div>
      <div class="reader-chat-navigation">
        <button type="button" class="reader-text-button" :class="{ active: recentOpen }" :aria-expanded="recentOpen" :disabled="conversation.busy" @click="recentOpen = !recentOpen"><History :size="15" />最近<ChevronRight :size="12" /></button>
        <span :title="conversation.title">{{ conversation.title || '新对话' }}</span>
      </div>
      <p v-if="historyOpening" class="reader-history-status" role="status">正在打开对话…</p>
      <p v-if="historyError" class="reader-chat-error reader-history-status" role="alert">{{ historyError }}</p>
      <ReaderRecentConversations v-if="recentOpen" :key="auth.user?.id || 'anonymous'" :user-id="auth.user?.id" :selected-id="conversation.sessionId"
        :disabled="conversation.busy" @select="openHistory" @deleted="deletedHistory" @close="recentOpen = false" />
      <template v-else>
      <div class="reader-chat-scope">
        <label for="reader-knowledge-scope">问答知识范围</label>
        <KnowledgeScopePicker id="reader-knowledge-scope" v-model="selectedBookIds" v-model:publicOnly="selectedPublicOnly"
          :can-choose-private="!!auth.user" :disabled="conversation.busy || historyOpening" label="选择问答 Book 或文件夹" @change="changeChatScope"
          :empty-text="restoredScope && !selectedBookIds.length ? `${selectedPublicOnly ? '公开书库' : '我的知识'} · 全部 Book` : document ? '当前 Book · 可更换范围' : '选择 Book 或分类文件夹'" />
        <button v-if="selectedBookIds.length || restoredScope" type="button" :disabled="conversation.busy || historyOpening" @click="resetScope">{{ document ? '恢复仅当前 Book' : '清空知识范围' }}</button>
      </div>
      <div ref="transcript" class="reader-chat-scroll" aria-label="对话记录">
        <div v-if="conversation.serverBusy" class="reader-history-status" role="status">这段对话正在处理另一个问题。<button class="reader-text-button" :disabled="historyOpening" @click="openHistory(conversation.sessionId)">刷新对话</button></div>
        <div v-if="!conversation.messages.length" class="reader-chat-empty">
          <MessageSquare :size="28" />
          <h3>一起读懂这段内容</h3>
          <p>解释一个概念，整理一条思路，<br />或从原文中找到答案。</p>
          <button
            :disabled="!canAsk"
            @click="send('请概括核心观点，并引用原文。')"
          >
            概括核心观点<ChevronRight :size="14" /></button
          ><button
            :disabled="!canAsk"
            @click="send('有哪些值得关注的细节？请给出依据。')"
          >
            找出关键细节<ChevronRight :size="14" />
          </button>
        </div>
        <article
          v-for="(message, i) in conversation.messages"
          :key="i"
          class="reader-chat-message"
          :class="message.role"
        >
          <span class="reader-message-role">{{
            message.role === "user" ? "你" : "阅读助手"
          }}</span>
          <p v-if="message.role === 'user'">{{ message.content }}</p>
          <CitedAnswer v-else :content="message.content" :sources="message.sources"
            :book-title="message.bookTitle || document?.title" @cite="cite" />
        </article>
        <p v-if="conversation.busy" class="reader-chat-thinking" role="status">
          <span></span>正在阅读相关内容…
        </p>
        <p v-if="conversation.error" class="reader-chat-error" role="alert">
          {{ conversation.error }} 可修改问题后重试。
        </p>
      </div>
      <form class="reader-composer" @submit.prevent="send()">
        <div class="reader-context-chip">
          <FileText :size="13" /><span>{{ scope }}</span>
        </div>
        <label class="reader-sr-only" for="reader-question">对话问题</label
        ><textarea
          id="reader-question"
          v-model="conversation.question"
          maxlength="500"
          :disabled="conversation.busy || !canAsk"
          :placeholder="canAsk ? '有什么想了解的？' : document ? '等待当前内容索引就绪…' : '请先选择一本 Book'"
          @keydown="enter"
        ></textarea>
        <div class="reader-composer-footer">
          <small>Enter 发送 · Shift + Enter 换行</small
          ><button
            type="submit"
            aria-label="发送问题"
            :disabled="
              conversation.busy || !conversation.question.trim() || !canAsk
            "
          >
            <Send :size="17" />
          </button>
        </div>
        <p
          v-if="document && !canAsk && !error && !loading"
          class="reader-composer-hint"
        >
          {{
            document?.indexStatus === "FAILED"
              ? "索引失败，请在 Book 编写页重试。"
              : "Book 正在建立索引，就绪后即可提问。"
          }}
        </p>
      </form>
      <p class="reader-chat-note">回答以原文为依据，请结合引用核对。</p>
      </template>
    </aside>
  </div>
</template>
