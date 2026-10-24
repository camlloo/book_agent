<script setup>
import { ref, reactive, onMounted } from "vue";
import { Plus, Search } from "lucide-vue-next";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, action, query, statusText, date, flatten } from "../api";
import PageState from "../components/PageState.vue";
const filters = reactive({
  keyword: "",
  status: "",
  categoryId: "",
  pageNum: 1,
  pageSize: 10,
});
const data = ref({ total: 0, list: [] }),
  categories = ref([]),
  loading = ref(true),
  error = ref(""),
  busy = ref(false);
async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/api/articles?" + query(filters));
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
async function filter() {
  filters.pageNum = 1;
  await load();
}
async function change(row, verb) {
  try {
    await ElMessageBox.confirm(
      verb === "delete"
        ? `删除《${row.title}》后无法恢复。`
        : `${verb === "publish" ? "发布后访客将可以阅读" : "下线后访客将无法阅读"}《${row.title}》。`,
      verb === "delete"
        ? "删除文章"
        : verb === "publish"
          ? "发布文章"
          : "下线文章",
      {
        confirmButtonText:
          verb === "delete" ? "删除" : verb === "publish" ? "发布" : "下线",
        cancelButtonText: "取消",
        type: verb === "delete" ? "warning" : "info",
      },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    await api(
      "/api/articles/" + row.id + (verb === "delete" ? "" : "/" + verb),
      { method: verb === "delete" ? "DELETE" : "PUT" },
    );
    ElMessage.success("操作成功");
    await load();
  });
  busy.value = false;
}
onMounted(async () => {
  await load();
  await action(async () => {
    categories.value = flatten(await api("/api/categories"));
  });
});
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <h1>我的文章</h1>
        <p>记录思考，让想法成为文章。</p>
      </div>
      <RouterLink to="/admin/articles/new"
        ><el-button type="primary"
          ><Plus :size="18" />写文章</el-button
        ></RouterLink
      >
    </div>
    <p class="list-count">
      {{ data.total }} 篇{{
        filters.status ? statusText[filters.status] : "文章"
      }}
    </p>
    <form class="filters" @submit.prevent="filter">
      <el-input
        v-model="filters.keyword"
        placeholder="搜索文章标题…"
        aria-label="搜索文章标题"
        clearable
        ><template #prefix><Search :size="18" /></template></el-input
      ><el-select
        v-model="filters.status"
        aria-label="文章状态"
        placeholder="全部状态"
        clearable
        @change="filter"
        ><el-option
          v-for="s in ['DRAFT', 'PUBLISHED', 'OFFLINE']"
          :key="s"
          :value="s"
          :label="statusText[s]" /></el-select
      ><el-select
        v-model="filters.categoryId"
        aria-label="分类筛选"
        placeholder="全部分类"
        clearable
        @change="filter"
        ><el-option
          v-for="c in categories"
          :key="c.id"
          :value="c.id"
          :label="c.label" /></el-select
      ><el-button native-type="submit">搜索</el-button>
    </form>
    <PageState
      :loading="loading"
      :error="error"
      :empty="!data.list.length"
      title="还没有符合条件的文章"
      description="调整筛选条件，或开始写下第一篇文章。"
      @retry="load"
    >
      <div class="table-scroll">
        <table class="article-table">
          <thead>
            <tr>
              <th>标题</th>
              <th>状态</th>
              <th>分类</th>
              <th>浏览</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in data.list" :key="a.id">
              <td>
                <RouterLink
                  :to="'/admin/articles/' + a.id"
                  class="article-title"
                  >{{ a.title }}</RouterLink
                >
                <div class="row-tags">
                  {{ a.tags.map((t) => t.name).join(" · ") }}
                </div>
              </td>
              <td>
                <span class="status" :class="a.status.toLowerCase()">{{
                  statusText[a.status]
                }}</span>
              </td>
              <td>{{ a.categoryName || "未分类" }}</td>
              <td>{{ a.viewCount }}</td>
              <td class="time">{{ date(a.createdAt) }}</td>
              <td>
                <div class="row-actions">
                  <RouterLink :to="'/admin/articles/' + a.id">编辑</RouterLink
                  ><button
                    :disabled="busy"
                    @click="
                      change(
                        a,
                        a.status === 'PUBLISHED' ? 'offline' : 'publish',
                      )
                    "
                  >
                    {{ a.status === "PUBLISHED" ? "下线" : "发布" }}</button
                  ><button
                    class="danger"
                    :disabled="busy"
                    @click="change(a, 'delete')"
                  >
                    删除
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <el-pagination
        v-model:current-page="filters.pageNum"
        :page-size="filters.pageSize"
        :total="data.total"
        layout="prev,pager,next,total"
        @current-change="load"
      />
    </PageState>
  </section>
</template>
