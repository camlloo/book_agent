<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter, onBeforeRouteLeave } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { ArrowLeft, ImagePlus } from "lucide-vue-next";
import { api, action, flatten, statusText } from "../api";
import Markdown from "../components/Markdown.vue";
import PageState from "../components/PageState.vue";
const route = useRoute(),
  router = useRouter(),
  id = ref(route.params.id),
  form = reactive({
    title: "",
    summary: "",
    content: "",
    categoryId: null,
    tagIds: [],
  });
const categories = ref([]),
  tags = ref([]),
  loading = ref(true),
  error = ref(""),
  busy = ref(false),
  status = ref("DRAFT"),
  baseline = ref(""),
  fileInput = ref(null),
  mode = ref("split");
const dirty = computed(() => JSON.stringify(form) !== baseline.value);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [c, t] = await Promise.all([
      api("/api/categories"),
      api("/api/tags"),
    ]);
    categories.value = flatten(c);
    tags.value = t;
    if (id.value) {
      const article = await api("/api/articles/" + id.value);
      Object.assign(form, {
        title: article.title,
        summary: article.summary || "",
        content: article.content,
        categoryId: article.categoryId,
        tagIds: article.tags.map((t) => t.id),
      });
      status.value = article.status;
    }
    baseline.value = JSON.stringify(form);
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function save() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning("请填写标题和正文。");
    return false;
  }
  busy.value = true;
  try {
    if (id.value)
      await api("/api/articles/" + id.value, { method: "PUT", body: form });
    else {
      const article = await api("/api/articles", {
        method: "POST",
        body: form,
      });
      id.value = article.id;
    }
    baseline.value = JSON.stringify(form);
    ElMessage.success("文章已保存");
    return true;
  } catch (e) {
    ElMessage.error(e.message);
    return false;
  } finally {
    busy.value = false;
  }
}
async function publish() {
  try {
    await ElMessageBox.confirm(
      "保存当前内容并发布，访客将可以阅读这篇文章。",
      "发布文章",
      { confirmButtonText: "保存并发布", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  if (await save()) {
    busy.value = true;
    await action(async () => {
      await api("/api/articles/" + id.value + "/publish", { method: "PUT" });
      status.value = "PUBLISHED";
      ElMessage.success("文章已发布");
    });
    busy.value = false;
  }
}
async function upload(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  busy.value = true;
  await action(async () => {
    const body = new FormData();
    body.append("file", file);
    const image = await api("/api/images", { method: "POST", body });
    form.content +=
      "\n\n![" +
      file.name.replace(/[\[\]\\\n]/g, "") +
      "](" +
      image.url +
      ")\n";
    ElMessage.success("图片已插入正文");
  });
  busy.value = false;
  event.target.value = "";
}
async function leave() {
  if (!dirty.value) return true;
  try {
    await ElMessageBox.confirm(
      "尚有未保存的修改，离开后会丢失。",
      "离开编辑器",
      {
        confirmButtonText: "离开",
        cancelButtonText: "继续编辑",
        type: "warning",
      },
    );
    return true;
  } catch {
    return false;
  }
}
onBeforeRouteLeave(leave);
function beforeUnload(e) {
  if (dirty.value) {
    e.preventDefault();
    e.returnValue = "";
  }
}
onMounted(() => {
  load();
  window.addEventListener("beforeunload", beforeUnload);
});
onUnmounted(() => window.removeEventListener("beforeunload", beforeUnload));
</script>
<template>
  <section>
    <RouterLink to="/admin/articles" class="back-link"
      ><ArrowLeft :size="16" />返回文章</RouterLink
    >
    <div class="page-heading">
      <div>
        <h1>{{ id ? "编辑文章" : "写一篇文章" }}</h1>
        <p>
          {{ statusText[status] }}<span v-if="dirty"> · 有未保存的修改</span>
        </p>
      </div>
      <div class="button-group">
        <el-button :loading="busy" :disabled="loading || !!error" @click="save"
          >保存{{ status === "DRAFT" ? "草稿" : "" }}</el-button
        ><el-button
          v-if="status !== 'PUBLISHED'"
          type="primary"
          :disabled="busy || loading || !!error"
          @click="publish"
          >发布</el-button
        >
      </div>
    </div>
    <PageState :loading="loading" :error="error" @retry="load"
      ><form class="editor-form" @submit.prevent="save">
        <label for="article-title">文章标题</label
        ><el-input
          id="article-title"
          v-model="form.title"
          maxlength="128"
          placeholder="给你的文章起个标题"
        /><label for="summary">摘要</label
        ><el-input
          id="summary"
          v-model="form.summary"
          type="textarea"
          :rows="2"
          maxlength="500"
          placeholder="用一两句话介绍这篇文章（选填）"
        />
        <div class="metadata-fields">
          <div>
            <label for="category">分类</label
            ><el-select
              id="category"
              v-model="form.categoryId"
              clearable
              placeholder="未分类"
              ><el-option
                v-for="c in categories"
                :key="c.id"
                :value="c.id"
                :label="c.label"
            /></el-select>
          </div>
          <div>
            <label for="tags">标签</label
            ><el-select
              id="tags"
              v-model="form.tagIds"
              multiple
              :multiple-limit="20"
              clearable
              placeholder="添加标签"
              ><el-option
                v-for="t in tags"
                :key="t.id"
                :value="t.id"
                :label="t.name"
            /></el-select>
          </div>
        </div>
        <div class="editor-toolbar">
          <strong>正文 · Markdown</strong>
          <div class="button-group">
            <el-radio-group v-model="mode" size="small"
              ><el-radio-button value="edit">编辑</el-radio-button
              ><el-radio-button value="split">对照</el-radio-button
              ><el-radio-button value="preview"
                >预览</el-radio-button
              ></el-radio-group
            ><el-button size="small" :disabled="busy" @click="fileInput.click()"
              ><ImagePlus :size="16" />插入图片</el-button
            ><input
              ref="fileInput"
              type="file"
              accept="image/jpeg,image/png,image/gif,image/webp"
              hidden
              @change="upload"
            />
          </div>
        </div>
        <div class="editor-panels" :class="mode">
          <textarea
            v-if="mode !== 'preview'"
            v-model="form.content"
            aria-label="Markdown 正文"
            placeholder="从一个想法开始…"
            spellcheck="false"
          ></textarea>
          <div v-if="mode !== 'edit'" class="preview-pane">
            <Markdown :content="form.content || '*正文预览会出现在这里。*'" />
          </div>
        </div>
        <p class="muted">
          {{ form.content.length }} 字符 · 图片支持 JPG、PNG、GIF、WebP，最大
          5MB
        </p>
      </form>
      <RouterLink
        v-if="id"
        :to="{ path: '/admin/tasks', query: { source: id } }"
        class="back-link"
        >用写作助手润色这篇文章</RouterLink
      ></PageState
    >
  </section>
</template>
