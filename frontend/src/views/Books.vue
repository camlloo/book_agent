<script setup>
import { ref, onMounted, onUnmounted } from "vue";
import { Plus, Search } from "lucide-vue-next";
import { ElMessageBox, ElMessage } from "element-plus";
import { api, action, query, date, statusText } from "../api";
import { indexStatus } from "../book";
import PageState from "../components/PageState.vue";
const data = ref({ list: [], total: 0 }),
  keyword = ref(""),
  status = ref(""),
  sourceKind = ref(""),createdFrom=ref(""),createdTo=ref(""),
  page = ref(1),
  loading = ref(true),
  error = ref(""),
  busy = ref(false);
let timer,
  alive = true,
  polling = false;
async function load(silent = false) {
  if (!silent) loading.value = true;
  error.value = "";
  try {
    const result = await api(
      "/api/books?" +
        query({
          keyword: keyword.value,
          status: status.value,sourceKind:sourceKind.value,createdFrom:createdFrom.value,createdTo:createdTo.value,
          pageNum: page.value,
        }),
    );
    if (alive) data.value = result;
  } catch (e) {
    error.value = e.message;
  } finally {
    if (alive) loading.value = false;
  }
}
async function search() {
  page.value = 1;
  await load();
}
async function remove(book) {
  try {
    await ElMessageBox.confirm(
      `删除《${book.title}》及其私有资料和向量索引，操作无法撤销。`,
      "删除 Book",
      { confirmButtonText: "删除", cancelButtonText: "保留", type: "warning" },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    await api("/api/books/" + book.id, { method: "DELETE" });
    ElMessage.success("Book 已删除，附件与向量正在清理");
    await load();
  });
  busy.value = false;
}
async function poll() {
  if (
    !alive ||
    polling ||
    busy.value ||
    !data.value.list.some((b) => ["QUEUED", "RUNNING"].includes(b.indexStatus))
  )
    return;
  polling = true;
  await load(true);
  polling = false;
}
onMounted(() => {
  load();
  timer = setInterval(poll, 2500);
});
onUnmounted(() => {
  alive = false;
  clearInterval(timer);
});
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <h1>我的 Book</h1>
        <p>在线编写或上传文件创建 Book，让内容支持问答与写作。</p>
      </div>
      <RouterLink to="/admin/books/new"
        ><el-button type="primary"
          ><Plus :size="18" />新建 Book</el-button
        ></RouterLink
      >
    </div>
    <form class="blog-search" @submit.prevent="search">
      <el-input
        v-model="keyword"
        clearable
        aria-label="搜索 Book"
        placeholder="搜索 Book 标题…"
        ><template #prefix><Search :size="18" /></template></el-input
      ><el-button native-type="submit">搜索</el-button>
    </form>
    <el-select
      v-model="status"
      aria-label="按发布状态筛选"
      placeholder="全部状态"
      clearable
      @change="search"
      class="book-status-filter"
      ><el-option
        v-for="s in ['DRAFT', 'PUBLISHED', 'OFFLINE']"
        :key="s"
        :label="statusText[s]"
        :value="s"
    /></el-select>
    <div class="book-source-filters"><el-select v-model="sourceKind" aria-label="按知识来源筛选" clearable placeholder="全部来源" @change="search"><el-option label="在线编写" value="ONLINE"/><el-option label="文件上传" value="UPLOAD"/><el-option label="网页采集" value="WEB"/><el-option label="代码项目" value="REPO"/></el-select><label>创建时间 <input v-model="createdFrom" type="date" aria-label="创建开始日期" @change="search"/> 至 <input v-model="createdTo" type="date" aria-label="创建结束日期" @change="search"/></label></div>
    <PageState
      :loading="loading"
      :error="error"
      :empty="!data.list.length"
      title="建立你的第一份 Book"
      description="在线编写正文，或上传文件直接创建 Book。两种方式都会自动建立知识索引。"
      @retry="load()"
    >
      <div class="table-scroll">
        <table class="article-table book-table">
          <thead>
            <tr>
              <th>Book</th>
              <th>发布与索引状态</th>
              <th>知识分块</th>
              <th>更新时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in data.list" :key="b.id">
              <td>
                <span class="status" :class="b.status.toLowerCase()">{{
                  statusText[b.status]
                }}</span>
                <RouterLink
                  class="article-title"
                  :to="b.accessRole==='VIEWER'?'/books/'+b.id:'/admin/books/'+b.id"
                  >{{ b.title }}</RouterLink
                >
                <p v-if="b.summary" class="row-tags">{{ b.summary }}</p>
                <small class="row-tags">{{
                  ({WEB:'网页采集',REPO:'代码项目',UPLOAD:'文件上传',ONLINE:'在线编写'})[b.originKind]||'在线编写'
                }}</small>
              </td>
              <td>
                <span class="status" :class="b.indexStatus.toLowerCase()">{{
                  indexStatus[b.indexStatus]
                }}</span>
                <div class="row-tags">
                  内容 v{{ b.revision }} · 索引 v{{ b.indexedRevision }}
                </div>
                <p v-if="b.indexError" class="book-error-inline">
                  {{ b.indexError }}
                </p>
              </td>
              <td>{{ b.chunkCount }}<p class="row-tags">{{b.categoryName||'未分类'}}</p><p class="row-tags">{{(b.tags||[]).map(t=>t.name).join(' · ')}}</p></td>
              <td class="time">{{ date(b.updatedAt) }}</td>
              <td>
                <div class="row-actions">
                  <RouterLink :to="'/books/' + b.id">阅读</RouterLink>
                  <RouterLink :to="b.accessRole==='VIEWER'?'/books/'+b.id:'/admin/books/'+b.id">{{
                    b.sourceMode === "UPLOAD" ? "查看" : "编写"
                  }}</RouterLink
                  ><button v-if="['OWNER','ADMIN'].includes(b.accessRole)" class="danger" :disabled="busy" @click="remove(b)">
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <el-pagination
        v-model:current-page="page"
        :total="data.total"
        :page-size="10"
        layout="prev,pager,next,total"
        @current-change="load()"
      />
    </PageState>
    <p class="muted book-private-note">
      草稿与下线内容仅向所有者及被授权用户开放；发布后进入公开书库。
    </p>
  </section>
</template>
