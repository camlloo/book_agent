<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowLeft, Upload, Send, RefreshCw, FileText } from "lucide-vue-next";
import { api, action, date, flatten, statusText } from "../api";
import { indexStatus, fileSize } from "../book";
import Markdown from "../components/Markdown.vue";
import PageState from "../components/PageState.vue";
import BookWorkspace from "../components/BookWorkspace.vue";
import KnowledgeScopePicker from "../components/KnowledgeScopePicker.vue";
import CitedAnswer from "../components/CitedAnswer.vue";
import { sourceHref } from "../citations";
const route = useRoute(),
  router = useRouter(),
  id = route.params.id;
const book = ref(null),
  form = reactive({
    title: "",
    summary: "",
    content: "",
    categoryId: null,
    tagIds: [],
  });
const categories = ref([]),
  tags = ref([]);
const baseline = ref(""),
  formRevision = ref(null),
  section = ref("writing"),
  mode = ref("split");
const creationMode = ref("ONLINE"),
  selectedFile = ref(null),
  fileInput = ref(null);
const loading = ref(true),
  error = ref(""),
  busy = ref(false);
const question = ref(""),
  answer = ref(null),
  askError = ref(""),
  asking = ref(false);
const askBookIds = ref([]), askPublicOnly = ref(false);
watch([askBookIds, askPublicOnly], () => { answer.value = null; askError.value = ""; });
const assistantOpen=ref(false),assistantBusy=ref(false),assistantResult=ref(null),assistantOperation=ref(""),assistantQuestion=ref(""),assistantLevel=ref("初学者"),assistantBookIds=ref([]);
let timer,
  alive = true,
  polling = false;
const isUpload = computed(() =>
  book.value
    ? book.value.sourceMode === "UPLOAD"
    : creationMode.value === "UPLOAD",
);
const canEdit=computed(()=>!book.value||["OWNER","ADMIN","EDITOR"].includes(book.value.accessRole));
const canOwn=computed(()=>!book.value||["OWNER","ADMIN"].includes(book.value.accessRole));
const dirty = computed(
  () =>
    JSON.stringify(form) !== baseline.value || (!id && !!selectedFile.value),
);
const stale = computed(
  () => book.value && formRevision.value !== book.value.revision,
);
const ready = computed(
  () =>
    book.value?.indexStatus === "READY" &&
    book.value.indexedRevision === book.value.revision &&
    !dirty.value &&
    !stale.value,
);
const askReady = computed(() => askBookIds.value.length ? !dirty.value && !stale.value : ready.value);
function hydrate(value) {
  book.value = value;
  Object.assign(form, {
    title: value.title,
    summary: value.summary || "",
    content: value.content || "",
    categoryId: value.categoryId || null,
    tagIds: (value.tags || []).map((t) => t.id),
  });
  formRevision.value = value.revision;
  baseline.value = JSON.stringify(form);
  selectedFile.value = null;
  answer.value = null;
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    if (id) {
      hydrate(await api("/api/books/" + id));
      section.value = isUpload.value ? "files" : "writing";
    } else baseline.value = JSON.stringify(form);
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function save() {
  if (!form.title.trim()) {
    ElMessage.warning("请填写 Book 标题。");
    return false;
  }
  if (!id && isUpload.value && !selectedFile.value) {
    ElMessage.warning("请选择要创建为 Book 的文件。");
    return false;
  }
  busy.value = true;
  try {
    let value;
    if (!id && isUpload.value) {
      const body = new FormData();
      body.append("file", selectedFile.value);
      body.append("title", form.title.trim());
      body.append("summary", form.summary);
      if (form.categoryId) body.append("categoryId", form.categoryId);
      for (const tag of form.tagIds) body.append("tagIds", tag);
      value = await api("/api/books/upload", { method: "POST", body });
    } else {
      value = await api("/api/books" + (id ? "/" + id : ""), {
        method: id ? "PUT" : "POST",
        body: {
          ...form,
          content: isUpload.value ? "" : form.content,
          revision: formRevision.value,
        },
      });
    }
    hydrate(value);
    ElMessage.success(
      isUpload.value
        ? "Book 已保存，文件内容将自动解析并向量化"
        : "Book 已保存，正文将自动更新向量索引",
    );
    if (!id) await router.replace("/admin/books/" + value.id);
    return true;
  } catch (e) {
    ElMessage.error(e.message);
    return false;
  } finally {
    busy.value = false;
  }
}
async function ensureSaved() {
  if (stale.value) {
    ElMessage.warning("Book 已有新的版本，请重新加载后再操作。");
    return false;
  }
  return dirty.value ? await save() : true;
}
async function selectFile(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  if (!/\.(pdf|docx|txt|md)$/i.test(file.name)) {
    ElMessage.warning("Book 文件仅支持 PDF、DOCX、TXT 和 Markdown。");
    return;
  }
  if (!file.size || file.size > 20 * 1024 * 1024) {
    ElMessage.warning("请选择非空且不超过 20MB 的 Book 文件。");
    return;
  }
  if (!id) {
    selectedFile.value = file;
    if (!form.title.trim())
      form.title = file.name.replace(/\.[^.]+$/, "").slice(0, 128);
    return;
  }
  if (!(await ensureSaved())) return;
  try {
    await ElMessageBox.confirm(
      "替换后将以新文件作为这本 Book 的内容，原文件及旧索引会被清理。",
      "替换 Book 文件",
      { confirmButtonText: "替换文件", cancelButtonText: "保留原文件" },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    const body = new FormData();
    body.append("file", file);
    body.append("revision", formRevision.value);
    hydrate(await api("/api/books/" + id + "/file", { method: "POST", body }));
    ElMessage.success("Book 文件已替换，正在更新向量索引");
  });
  busy.value = false;
}
async function download(file) {
  await action(async () => {
    const result = await api(
      "/api/books/" + id + "/files/" + file.id + "/download",
    );
    const a = document.createElement("a");
    a.href = result.url;
    a.rel = "noopener";
    a.click();
  });
}
async function reindex() {
  if (!(await ensureSaved())) return;
  busy.value = true;
  await action(async () => {
    await api("/api/books/" + id + "/reindex", { method: "POST" });
    book.value = await api("/api/books/" + id);
    formRevision.value = book.value.revision;
    answer.value = null;
    ElMessage.success("已重新加入索引队列");
  });
  busy.value = false;
}
async function publication(operation) {
  if (!(await ensureSaved())) return;
  try {
    await ElMessageBox.confirm(
      operation === "publish"
        ? "发布后，访客可以阅读这本 Book 的全部内容并提问。"
        : "下线后，访客不能继续读取或提问。",
      "确认" + (operation === "publish" ? "发布" : "下线"),
      {
        confirmButtonText: operation === "publish" ? "发布 Book" : "下线 Book",
        cancelButtonText: "取消",
      },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    hydrate(await api(`/api/books/${id}/${operation}`, { method: "PUT" }));
    ElMessage.success(operation === "publish" ? "Book 已发布" : "Book 已下线");
  });
  busy.value = false;
}
async function runAssistant(operation) {
  if (!id || assistantBusy.value) return;
  if (dirty.value && !(await ensureSaved())) return;
  if (operation === "explain" && !assistantQuestion.value.trim()) { ElMessage.warning("先写下希望解释的问题。"); return; }
  assistantBusy.value = true; assistantResult.value = null; assistantOperation.value = operation;
  try {
    assistantResult.value = await api(`/api/books/${id}/assistant`, { method: "POST", body: {
      operation, instructions: operation === "explain" ? assistantQuestion.value.trim() : "",
      level: assistantLevel.value, bookIds: assistantBookIds.value,
    }});
  } catch (e) { ElMessage.error(e.message); }
  finally { assistantBusy.value = false; }
}
async function applyAssistant() {
  if (!assistantResult.value || stale.value) { ElMessage.warning("Book 版本已变化，请重新加载后再生成建议。"); return; }
  const operation = assistantOperation.value;
  try { await ElMessageBox.confirm(operation === "polish" ? "润色正文将作为一个新修订保存，历史版本仍可恢复。" : "应用标题、简介、分类和标签建议？保存后可在历史版本中恢复。", "确认应用 AI 草稿", { confirmButtonText: "应用并保存", cancelButtonText: "继续修改" }); }
  catch { return; }
  assistantBusy.value = true;
  try {
    const suggestion = assistantResult.value;
    const value = await api(`/api/books/${id}/assistant/apply`, { method: "POST", body: {
      expectedRevision: suggestion.revision, operation,
      title: operation === "organize" ? suggestion.title : form.title,
      summary: operation === "organize" ? suggestion.summary : form.summary,
      category: operation === "organize" ? suggestion.category : null,
      tags: operation === "organize" ? suggestion.tags : null,
      content: operation === "polish" ? suggestion.content : null,
    }});
    hydrate(value); assistantResult.value = null; ElMessage.success("已保存为新修订，索引正在更新。");
  } catch (e) { ElMessage.error(e.message); }
  finally { assistantBusy.value = false; }
}
function followAssistantSource(source) { router.push(sourceHref(source, source.excerpt || "")); }
async function ask() {
  if (!askReady.value || asking.value || !question.value.trim()) return;
  asking.value = true;
  askError.value = "";
  answer.value = null;
  try {
    const revision = book.value.revision;
    const custom = !!askBookIds.value.length;
    const result = await api(custom ? "/api/knowledge/ask" : "/api/books/" + id + "/ask", {
      method: "POST",
      body: { question: question.value.trim(), topK: 5,
        ...(custom ? { bookIds: [...askBookIds.value], publicOnly: askPublicOnly.value, searchMode: "hybrid", mode: "qa", rerank: true } : {}) },
    });
    if (book.value.revision !== revision || dirty.value)
      throw new Error("Book 内容已变化，请保存后重新提问。");
    answer.value = result;
  } catch (e) {
    askError.value = e.message;
  } finally {
    asking.value = false;
  }
}
async function confirmLeave() {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "Book 尚未保存，离开后修改和已选文件会丢失。",
      "保留你的修改",
      {
        confirmButtonText: "丢弃修改",
        cancelButtonText: "继续编写",
        type: "warning",
      },
    );
    return true;
  } catch {
    return false;
  }
}
async function reload() {
  if (await confirmLeave()) await load();
}
function beforeUnload(event) {
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onBeforeRouteLeave(confirmLeave);
async function poll() {
  if (!id || !alive || polling || busy.value) return;
  polling = true;
  try {
    const value = await api("/api/books/" + id);
    if (alive && (!book.value || value.revision >= book.value.revision)) {
      if (book.value?.revision !== value.revision) answer.value = null;
      book.value = value;
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    polling = false;
  }
}
onMounted(() => {
  load();
  Promise.all([api("/api/categories"), api("/api/tags")])
    .then(([c, t]) => {
      categories.value = flatten(c);
      tags.value = t;
    })
    .catch((e) => ElMessage.error(e.message));
  timer = setInterval(poll, 3000);
  window.addEventListener("beforeunload", beforeUnload);
});
onUnmounted(() => {
  alive = false;
  clearInterval(timer);
  window.removeEventListener("beforeunload", beforeUnload);
});
</script>
<template>
  <section>
    <RouterLink to="/admin/books" class="back-link"
      ><ArrowLeft :size="16" />全部 Book</RouterLink
    >
    <RouterLink
      v-if="id"
      :to="'/books/' + id"
      class="back-link"
      style="margin-left: 20px"
      >打开阅读界面<ArrowLeft :size="16" style="transform: rotate(180deg)"
    /></RouterLink>
    <div class="page-heading">
      <div>
        <h1>{{ id ? (isUpload ? "文件 Book" : "Book 编写") : "新建 Book" }}</h1>
        <p>
          {{
            isUpload
              ? "上传的文件就是 Book 内容，解析后可问答和协作写作。"
              : "在线编写 Book 正文，保存后自动建立知识索引。"
          }}
        </p>
      </div>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="loading || !!error || !canEdit"
        @click="save"
        >{{
          isUpload ? (id ? "保存信息" : "上传并创建 Book") : "保存 Book"
        }}</el-button
      >
    </div>
    <PageState :loading="loading" :error="error" @retry="load">
      <BookWorkspace v-if="book" :book="book" :disabled="dirty||stale" @changed="hydrate"/>
      <section v-if="book && canEdit" class="knowledge-panel book-assistant-panel">
        <div class="assistant-section-heading"><div><h2>AI 笔记助手</h2><p class="muted">生成的改动先供你预览；确认后才会保存为新修订。</p></div><el-button @click="assistantOpen=!assistantOpen">{{ assistantOpen ? "收起助手" : "整理或检查这本 Book" }}</el-button></div>
        <div v-if="assistantOpen" class="book-assistant-controls">
          <div class="assistant-tools">
            <el-button :loading="assistantBusy && assistantOperation==='organize'" :disabled="assistantBusy || dirty || stale" @click="runAssistant('organize')">生成标题、简介和标签建议</el-button>
            <el-button :loading="assistantBusy && assistantOperation==='polish'" :disabled="assistantBusy || dirty || stale || isUpload" @click="runAssistant('polish')">预览正文润色</el-button>
            <el-button :loading="assistantBusy && assistantOperation==='review'" :disabled="assistantBusy || dirty || stale" @click="runAssistant('review')">检查重复与冲突</el-button>
            <el-button :loading="assistantBusy && assistantOperation==='related'" :disabled="assistantBusy || dirty || stale" @click="runAssistant('related')">推荐关联知识</el-button>
          </div>
          <p v-if="dirty" class="muted">保存当前编辑后即可生成助手草稿。</p>
          <label for="assistant-related-books">可选：限定关联检查的其他 Book</label>
          <KnowledgeScopePicker id="assistant-related-books" v-model="assistantBookIds" :max-books="100" :excluded-ids="[Number(id)]" :disabled="assistantBusy" label="选择关联检查的 Book 或文件夹" empty-text="全部就绪资料，可按 Book 或分类选择" />
          <div class="assistant-explain-row"><el-input v-model="assistantQuestion" maxlength="500" placeholder="写一个要解释的问题，例如：为什么金额计算使用 Decimal？" aria-label="需要解释的问题"/><el-select v-model="assistantLevel" aria-label="解释水平"><el-option label="初学者" value="初学者"/><el-option label="有基础" value="有基础"/><el-option label="进阶读者" value="进阶读者"/></el-select><el-button :loading="assistantBusy && assistantOperation==='explain'" :disabled="assistantBusy || dirty || stale || !assistantQuestion.trim()" @click="runAssistant('explain')">按水平解释</el-button></div>
          <p v-if="assistantResult && ['organize','polish'].includes(assistantOperation)" class="assistant-review-note">此结果是建议草稿；应用后将保存新修订并重新索引。</p>
          <article v-if="assistantResult && assistantOperation==='organize'" class="assistant-proposal"><h3>整理建议</h3><p><strong>标题：</strong>{{assistantResult.title}}</p><p><strong>简介：</strong>{{assistantResult.summary}}</p><p><strong>分类：</strong>{{assistantResult.category || "不调整"}}</p><p><strong>标签：</strong>{{(assistantResult.tags || []).join("、") || "无"}}</p><el-button type="primary" :loading="assistantBusy" :disabled="stale" @click="applyAssistant">确认应用整理建议</el-button></article>
          <article v-else-if="assistantResult && assistantOperation==='polish'" class="assistant-proposal"><h3>润色预览</h3><Markdown :content="assistantResult.content"/><el-button type="primary" :loading="assistantBusy" :disabled="stale || isUpload" @click="applyAssistant">确认保存为新修订</el-button></article>
          <article v-else-if="assistantResult && assistantOperation==='explain'" class="assistant-proposal"><h3>解释草稿 · {{assistantLevel}}</h3><Markdown :content="assistantResult.content"/></article>
          <article v-else-if="assistantResult && assistantOperation==='review'" class="assistant-proposal"><h3>需要你核对的发现</h3><p v-if="!assistantResult.findings?.length" class="muted">没有找到带有明确出处的重复或冲突。资料不足时不会猜测。</p><ul v-else class="assistant-finding-list"><li v-for="(finding,index) in assistantResult.findings" :key="index"><strong>{{finding.kind}}</strong><p>{{finding.finding}}</p><RouterLink v-for="number in finding.sourceNumbers" :key="number" :to="sourceHref(assistantResult.sources[number-1], assistantResult.sources[number-1]?.excerpt || '')">出处：{{assistantResult.sources[number-1]?.bookTitle}} · {{assistantResult.sources[number-1]?.sourceName}}<span v-if="assistantResult.sources[number-1]?.page"> · 第 {{assistantResult.sources[number-1].page}} 页</span></RouterLink></li></ul></article>
          <article v-else-if="Array.isArray(assistantResult)" class="assistant-proposal"><h3>相关 Book</h3><p v-if="!assistantResult.length" class="muted">没有找到其他相关 Book。</p><div v-for="source in assistantResult" :key="source.chunkId" class="assistant-hit"><RouterLink :to="sourceHref(source, source.excerpt || '')">{{source.bookTitle}} · {{source.sourceName}}<span v-if="source.page"> · 第 {{source.page}} 页</span></RouterLink><p>{{source.excerpt}}</p></div></article>
        </div>
      </section>
      <fieldset class="book-editor-fields" :disabled="!canEdit">
      <div v-if="!id" class="book-create-modes">
        <el-radio-group
          v-model="creationMode"
          :disabled="busy"
          aria-label="添加 Book 的方式"
        >
          <el-radio-button value="ONLINE">在线编写</el-radio-button
          ><el-radio-button value="UPLOAD">文件上传</el-radio-button>
        </el-radio-group>
        <p class="muted">
          {{
            isUpload
              ? "选择一份 PDF、DOCX、TXT 或 Markdown，直接创建一本 Book。"
              : "从正文开始编写，保存后即可作为 Book 检索。"
          }}
        </p>
      </div>
      <div v-if="book" class="book-index-bar" aria-live="polite">
        <span class="status" :class="book.status.toLowerCase()">{{
          statusText[book.status]
        }}</span>
        <el-button
          v-if="canOwn && book.status !== 'PUBLISHED'"
          size="small"
          :disabled="busy || !ready"
          @click="publication('publish')"
          >发布 Book</el-button
        >
        <el-button
          v-else-if="canOwn"
          size="small"
          :disabled="busy"
          @click="publication('offline')"
          >下线 Book</el-button
        >
        <span class="status" :class="book.indexStatus.toLowerCase()">{{
          indexStatus[book.indexStatus]
        }}</span>
        <span
          >{{ book.chunkCount }} 个分块 · 内容 v{{ book.revision }} · 索引 v{{
            book.indexedRevision
          }}</span
        >
        <el-button size="small" :disabled="busy" @click="reindex"
          ><RefreshCw :size="15" />{{
            book.indexStatus === "FAILED" ? "重试索引" : "重新索引"
          }}</el-button
        >
      </div>
      <p v-if="book?.indexError" class="error" role="alert">
        {{ book.indexError }}
      </p>
      <p v-if="stale" class="error">
        Book 已有新的版本。当前输入仍保留，请先<el-button link @click="reload"
          >重新加载</el-button
        >再保存。
      </p>
      <p v-else-if="dirty" class="muted">
        有未保存的修改；保存后才能基于新内容问答。
      </p>
      <label for="book-title">Book 标题</label
      ><el-input
        id="book-title"
        v-model="form.title"
        :disabled="busy"
        maxlength="128"
        placeholder="为你的 Book 起个标题"
      />
      <label for="book-summary">简介（选填）</label
      ><el-input
        id="book-summary"
        v-model="form.summary"
        :disabled="busy"
        type="textarea"
        :rows="2"
        maxlength="500"
      />
      <div class="book-taxonomy-fields">
        <div>
          <label for="book-category">分类</label
          ><el-select
            id="book-category"
            v-model="form.categoryId"
            clearable
            :disabled="busy"
            placeholder="未分类"
            ><el-option
              v-for="c in categories"
              :key="c.id"
              :label="c.label"
              :value="c.id"
          /></el-select>
        </div>
        <div>
          <label for="book-tags">标签</label
          ><el-select
            id="book-tags"
            v-model="form.tagIds"
            multiple
            filterable
            :disabled="busy"
            placeholder="选择标签"
            ><el-option
              v-for="t in tags"
              :key="t.id"
              :label="t.name"
              :value="t.id"
          /></el-select>
        </div>
      </div>
      <el-tabs v-if="id" v-model="section">
        <el-tab-pane v-if="!isUpload" label="编写正文" name="writing" />
        <el-tab-pane
          v-if="isUpload || book?.files.length"
          :label="isUpload ? 'Book 文件' : '已有文件'"
          name="files"
        />
        <el-tab-pane label="RAG 问答" name="ask" />
      </el-tabs>
      <div v-show="!isUpload && (!id || section === 'writing')">
        <div class="editor-toolbar">
          <strong>正文 · Markdown</strong
          ><el-radio-group v-model="mode" size="small"
            ><el-radio-button value="edit">编辑</el-radio-button
            ><el-radio-button value="split">对照</el-radio-button
            ><el-radio-button value="preview"
              >预览</el-radio-button
            ></el-radio-group
          >
        </div>
        <div class="editor-panels" :class="mode">
          <textarea
            v-if="mode !== 'preview'"
            v-model="form.content"
            :disabled="busy"
            maxlength="1000000"
            aria-label="Book Markdown 正文"
            placeholder="从一个章节、提纲或想法开始…"
          ></textarea>
          <div v-if="mode !== 'edit'" class="preview-pane">
            <Markdown
              :content="
                form.content || '*保存后的正文会进入 Book 的知识索引。*'
              "
            />
          </div>
        </div>
        <p class="muted">
          {{ form.content.length }} 字符 · 保存后自动更新 Book 的知识索引。
        </p>
      </div>
      <div
        v-if="(!id && isUpload) || (id && section === 'files')"
        class="book-files"
      >
        <div class="book-file-heading">
          <div>
            <h2>
              {{
                id ? (isUpload ? "Book 原文件" : "已有文件") : "上传 Book 文件"
              }}
            </h2>
            <p class="muted">
              PDF、DOCX、TXT、Markdown · 单文件 ≤20MB · 一份文件创建一本 Book
            </p>
          </div>
          <el-button
            v-if="isUpload"
            type="primary"
            :disabled="busy"
            @click="fileInput.click()"
            ><Upload :size="17" />{{
              id
                ? "替换 Book 文件"
                : selectedFile
                  ? "重新选择文件"
                  : "选择 Book 文件"
            }}</el-button
          >
          <input
            ref="fileInput"
            type="file"
            accept=".pdf,.docx,.txt,.md"
            hidden
            @change="selectFile"
          />
        </div>
        <div v-if="!id" class="book-upload-selection">
          <p v-if="selectedFile">
            <FileText :size="20" />{{ selectedFile.name }} ·
            {{ fileSize(selectedFile.size) }}
          </p>
          <p v-else class="muted">
            选择文件后，点击“上传并创建 Book”。无需先创建空白 Book。
          </p>
        </div>
        <p v-else-if="!book?.files.length" class="state">
          尚无 Book 文件，请替换上传。
        </p>
        <ul v-else class="book-file-list">
          <li v-for="file in book.files" :key="file.id">
            <FileText :size="23" />
            <div>
              <strong>{{ file.originalName }}</strong
              ><small
                >{{ file.extension.toUpperCase() }} ·
                {{ fileSize(file.size) }} · {{ date(file.createdAt) }}</small
              >
            </div>
            <div class="row-actions">
              <button @click="download(file)">下载</button>
            </div>
          </li>
        </ul>
        <p class="muted">
          {{
            isUpload
              ? "文件是 Book 的内容来源。未发布时仅所有者和被授权用户可访问；扫描 PDF 请先 OCR。"
              : "保留之前上传的文件。新文件请通过“新建 Book → 文件上传”添加为独立 Book。"
          }}
        </p>
      </div>
      <div v-if="id && section === 'ask'" class="book-ask">
        <h2>基于所选知识回答</h2>
        <p class="muted">默认使用当前 Book，也可以选择其他 Book 或整个分类文件夹。</p>
        <label for="editor-ask-scope">问答知识范围</label>
        <KnowledgeScopePicker id="editor-ask-scope" v-model="askBookIds" v-model:publicOnly="askPublicOnly" can-choose-private
          :disabled="asking" label="选择问答 Book 或文件夹" empty-text="当前 Book · 可更换范围" />
        <el-button v-if="askBookIds.length" link :disabled="asking" @click="askBookIds = []">恢复仅当前 Book</el-button>
        <p v-if="!askReady" class="book-rag-hint">
          {{
            dirty
              ? "请先保存当前修改。"
              : "等待索引就绪后即可提问；索引失败时可点击重试。"
          }}
        </p>
        <form @submit.prevent="ask">
          <label for="book-question">你的问题</label
          ><el-input
            id="book-question"
            v-model="question"
            :disabled="!askReady || asking"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="所选资料中有哪些相关结论？"
          /><el-button
            type="primary"
            native-type="submit"
            :loading="asking"
            :disabled="!askReady || !question.trim()"
            ><Send :size="17" />检索并回答</el-button
          >
        </form>
        <p v-if="asking" role="status">正在检索所选知识范围…</p>
        <p v-if="askError" class="error" role="alert">{{ askError }}</p>
        <div v-if="answer" class="answer" aria-live="polite">
          <CitedAnswer :content="answer.answer" :sources="answer.sources || []" @cite="(source, quote) => router.push(sourceHref(source, quote))" />
        </div>
        <RouterLink
          v-if="ready"
          :to="{ path: '/admin/tasks', query: { book: id } }"
          class="back-link"
          >使用这份 Book 的知识进行协作写作</RouterLink
        >
      </div>
    </fieldset></PageState>
  </section>
</template>
