<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { diffLines } from "diff";
import { api, action, stageText, statusText, date } from "../api";
import Markdown from "../components/Markdown.vue";
import PageState from "../components/PageState.vue";
const route = useRoute(),
  router = useRouter(),
  tasks = ref({ total: 0, list: [] }),
  current = ref(null),
  history = ref([]),
  approved = ref(""),
  baseline = ref(""),
  loading = ref(true),
  error = ref(""),
  busy = ref(false),
  tab = ref("preview"),
  page = ref(1);
const form = reactive({
    topic: "",
    instructions: "",
    sourceArticleId: route.query.source ? Number(route.query.source) : null,
    bookId: route.query.book ? Number(route.query.book) : null,
  }),
  source = ref(null),
  knowledgeBook = ref(null);
let timer,
  alive = true,
  selection = 0,
  polling = false;
const difference = computed(() =>
  diffLines(current.value?.approvedContent || "", approved.value),
);
const dirty = computed(
  () =>
    current.value?.status === "WAITING_REVIEW" &&
    approved.value !== baseline.value,
);
async function confirmDiscard() {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "当前阶段的修改尚未确认，离开后会丢失。",
      "保留你的修改",
      {
        confirmButtonText: "丢弃修改",
        cancelButtonText: "继续修改",
        type: "warning",
      },
    );
    return true;
  } catch {
    return false;
  }
}
function beforeUnload(event) {
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onBeforeRouteLeave(confirmDiscard);
async function refresh() {
  tasks.value = await api("/api/tasks?pageNum=" + page.value);
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    await refresh();
    if (route.query.task) await select(Number(route.query.task));
    if (form.sourceArticleId) {
      source.value = await api("/api/articles/" + form.sourceArticleId);
      form.topic = source.value.title;
    }
    if (form.bookId) {
      knowledgeBook.value = await api("/api/books/" + form.bookId);
      form.topic = knowledgeBook.value.title;
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function select(id, force = false) {
  if (!force && current.value?.id === id) return;
  if (!force && !(await confirmDiscard())) return;
  const version = ++selection;
  const [detail, checkpoints] = await Promise.all([
    api("/api/tasks/" + id),
    api("/api/tasks/" + id + "/checkpoints"),
  ]);
  if (!alive || version !== selection) return;
  current.value = detail;
  approved.value = detail.output || "";
  baseline.value = approved.value;
  history.value = checkpoints;
  router.replace({ query: { ...route.query, task: id } });
}
async function create() {
  if (!form.topic.trim()) return;
  if (!(await confirmDiscard())) return;
  busy.value = true;
  await action(async () => {
    const result = await api("/api/tasks", { method: "POST", body: form });
    form.topic = "";
    form.instructions = "";
    form.sourceArticleId = null;
    source.value = null;
    await refresh();
    await select(result.id, true);
    ElMessage.success("写作任务已创建");
  });
  busy.value = false;
}
async function review() {
  if (!approved.value.trim()) return;
  const id = current.value.id;
  try {
    await ElMessageBox.confirm(
      current.value.stage === "POLISH"
        ? current.value.bookId
          ? "将确认后的内容保存为新 Book，并自动建立向量索引。"
          : "将确认后的内容另存为新 Book，原文保持不变。"
        : "确认当前内容并开始下一阶段。",
      "确认" + stageText[current.value.stage],
      {
        confirmButtonText:
          current.value.stage === "POLISH"
            ? current.value.bookId
              ? "保存为新 Book"
              : "保存为新 Book"
            : "确认并继续",
        cancelButtonText: "继续修改",
      },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    current.value = await api("/api/tasks/" + id + "/review", {
      method: "POST",
      body: { runId: current.value.runId, content: approved.value },
    });
    approved.value = current.value.output || "";
    baseline.value = approved.value;
    history.value = await api("/api/tasks/" + id + "/checkpoints");
    await refresh();
    ElMessage.success(
      current.value.status === "COMPLETED"
        ? current.value.resultBookId
          ? "新 Book 已保存，正在建立索引"
          : "新 Book 已保存"
        : "已进入下一阶段",
    );
  });
  busy.value = false;
}
async function mutate(verb) {
  if (verb === "cancel") {
    try {
      await ElMessageBox.confirm(
        "取消后不会再推进此任务，未确认的修改将丢失。已保存的阶段记录仍可查看。",
        "取消任务",
        { confirmButtonText: "取消任务", cancelButtonText: "继续写作" },
      );
    } catch {
      return;
    }
  }
  busy.value = true;
  await action(async () => {
    await api("/api/tasks/" + current.value.id + "/" + verb, {
      method: "POST",
    });
    await select(current.value.id, true);
    await refresh();
  });
  busy.value = false;
}
async function remove() {
  try {
    await ElMessageBox.confirm(
      "删除任务与阶段记录，已经保存的 Book 仍会保留。",
      "删除任务记录",
      {
        confirmButtonText: "删除记录",
        cancelButtonText: "保留记录",
        type: "warning",
      },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    await api("/api/tasks/" + current.value.id, { method: "DELETE" });
    current.value = null;
    history.value = [];
    approved.value = "";
    await router.replace({ query: {} });
    await refresh();
    ElMessage.success("任务记录已删除");
  });
  busy.value = false;
}
async function poll() {
  if (!alive || polling) return;
  polling = true;
  try {
    await refresh();
    if (
      current.value &&
      !busy.value &&
      ["QUEUED", "RUNNING"].includes(current.value.status)
    ) {
      const id = current.value.id,
        version = selection;
      const detail = await api("/api/tasks/" + id);
      if (alive && version === selection && current.value?.id === id) {
        const changed =
          current.value.runId !== detail.runId ||
          current.value.status !== detail.status;
        current.value = detail;
        if (changed) {
          approved.value = detail.output || "";
          baseline.value = approved.value;
        }
      }
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    polling = false;
  }
}
onMounted(() => {
  load();
  timer = setInterval(poll, 2500);
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
    <div class="page-heading">
      <div>
        <h1>写作助手</h1>
        <p>先理清思路，再把想法写完整。每一步，由你确认。</p>
      </div>
    </div>
    <div class="writing-steps">
      <span>1 · 提纲</span><span>2 · 扩写</span><span>3 · 润色</span
      ><span>{{
        knowledgeBook || current?.bookId ? "保存新 Book" : "保存新 Book"
      }}</span>
    </div>
    <form class="task-create" @submit.prevent="create">
      <div>
        <label for="topic">{{ source ? "润色主题" : "想写什么？" }}</label
        ><el-input
          id="topic"
          v-model="form.topic"
          maxlength="128"
          required
          placeholder="写下 Book 主题"
        />
        <p v-if="source" class="muted">
          正在润色《{{ source.title }}》，结果将另存为新 Book。
        </p>
        <p v-if="knowledgeBook" class="muted">
          参考知识库：《{{
            knowledgeBook.title
          }}》。每阶段检索其最新已就绪版本，结果另存为新的 Book。
        </p>
      </div>
      <div>
        <label for="instructions">写作要求（选填）</label
        ><el-input
          id="instructions"
          v-model="form.instructions"
          maxlength="2000"
          placeholder="读者、语气、篇幅或需要关注的重点"
        />
      </div>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        :disabled="!form.topic.trim()"
        >{{ source ? "开始润色" : "开始写作" }}</el-button
      >
    </form>
    <p v-if="error && !loading && tasks.list.length" class="error" role="alert">
      {{ error }}
    </p>
    <PageState
      :loading="loading"
      :error="!tasks.list.length ? error : ''"
      :empty="!tasks.list.length"
      title="从一个想法开始"
      description="输入主题，写作助手会先生成提纲，等待你确认。"
      @retry="load"
      ><div class="task-layout">
        <aside class="task-list">
          <button
            v-for="t in tasks.list"
            :key="t.id"
            :class="{ active: current?.id === t.id }"
            @click="action(() => select(t.id))"
          >
            <strong>{{ t.topic }}</strong
            ><span>{{ stageText[t.stage] }} · {{ statusText[t.status] }}</span
            ><small>{{ date(t.createdAt) }}</small></button
          ><el-pagination
            v-model:current-page="page"
            :total="tasks.total"
            :page-size="10"
            layout="prev,next"
            @current-change="action(refresh)"
          />
        </aside>
        <div v-if="current" class="checkpoint">
          <div class="checkpoint-heading">
            <h2>
              {{ stageText[current.stage]
              }}<span class="status" :class="current.status.toLowerCase()">{{
                statusText[current.status]
              }}</span>
            </h2>
            <el-button
              v-if="
                ['QUEUED', 'RUNNING', 'WAITING_REVIEW'].includes(current.status)
              "
              size="small"
              :disabled="busy"
              @click="mutate('cancel')"
              >取消任务</el-button
            ><el-button v-else size="small" :disabled="busy" @click="remove"
              >删除记录</el-button
            >
          </div>
          <div
            v-if="['QUEUED', 'RUNNING'].includes(current.status)"
            class="state"
            role="status"
          >
            <span class="loading-line"></span
            >{{
              current.status === "QUEUED"
                ? "正在等待模型空闲…"
                : "正在生成" +
                  stageText[current.stage] +
                  "，稍后会在这里等你确认。"
            }}
          </div>
          <div
            v-else-if="current.status === 'FAILED'"
            class="state"
            role="alert"
          >
            <h3>这一阶段没有完成</h3>
            <p>{{ current.error }}</p>
            <el-button :loading="busy" @click="mutate('retry')"
              >重试当前阶段</el-button
            >
          </div>
          <div v-else-if="current.status === 'COMPLETED'" class="state">
            <h3>
              {{ current.resultBookId ? "新 Book 已保存" : "新 Book 已准备好" }}
            </h3>
            <p>
              {{
                current.resultBookId
                  ? "继续编写 Book，新正文会自动建立向量索引。"
                  : "继续检查内容、设置分类标签，再决定是否发布。"
              }}
            </p>
            <RouterLink
              :to="
                current.resultBookId
                  ? '/admin/books/' + current.resultBookId
                  : '/admin/articles/' + current.articleId
              "
              ><el-button type="primary">{{
                current.resultBookId ? "打开 Book" : "打开 Book"
              }}</el-button></RouterLink
            >
          </div>
          <p v-else-if="current.status === 'CANCELLED'" class="state">
            任务已取消，已确认的记录仍保留在下方。
          </p>
          <template v-else-if="current.status === 'WAITING_REVIEW'"
            ><el-tabs v-model="tab"
              ><el-tab-pane label="预览" name="preview" /><el-tab-pane
                label="修改内容"
                name="edit" /><el-tab-pane
                v-if="current.stage === 'POLISH'"
                label="改写对照"
                name="diff" /></el-tabs
            ><Markdown v-if="tab === 'preview'" :content="approved" /><textarea
              v-else-if="tab === 'edit'"
              v-model="approved"
              class="checkpoint-input"
              aria-label="修改当前阶段内容"
            ></textarea>
            <pre
              v-else
              class="diff"
            ><span v-for="(part,i) in difference" :key="i" :class="{added:part.added,removed:part.removed}">{{ part.value }}</span></pre>
            <div class="review-action">
              <p>核对事实、结构和语气后再确认。可先修改内容。</p>
              <el-button
                type="primary"
                :loading="busy"
                :disabled="!approved.trim()"
                @click="review"
                >{{
                  current.stage === "POLISH"
                    ? current.bookId
                      ? "确认并保存新 Book"
                      : "确认并保存为新 Book"
                    : "确认" + stageText[current.stage] + "，继续"
                }}</el-button
              >
            </div></template
          >
          <details v-if="history.length" class="checkpoint-history">
            <summary>已确认的阶段记录（{{ history.length }}）</summary>
            <section v-for="(h, i) in history" :key="i">
              <h3>{{ stageText[h.stage] }} · {{ date(h.createdAt) }}</h3>
              <Markdown :content="h.approvedContent" />
            </section>
          </details>
        </div>
        <div v-else class="state">
          <h2>选择一个任务</h2>
          <p>查看阶段结果，或继续尚未完成的写作。</p>
        </div>
      </div></PageState
    >
  </section>
</template>
