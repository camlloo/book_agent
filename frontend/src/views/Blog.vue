<script setup>
import { ref, reactive, onMounted } from "vue";
import { Search, ArrowUpRight } from "lucide-vue-next";
import { api, query, date, flatten } from "../api";
import PageState from "../components/PageState.vue";
const filters = reactive({
  keyword: "",
  categoryId: "",
  tagId: "",
  pageNum: 1,
  pageSize: 10,
});
const data = ref({ total: 0, list: [] }),
  categories = ref([]),
  tags = ref([]),
  loading = ref(true),
  error = ref("");
async function load() {
  loading.value = true;
  error.value = "";
  try {
    data.value = await api("/api/blog/articles?" + query(filters));
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
async function resetFilters() {
  Object.assign(filters, {
    keyword: "",
    categoryId: "",
    tagId: "",
    pageNum: 1,
  });
  await load();
}
onMounted(async () => {
  try {
    const [c, t] = await Promise.all([
      api("/api/blog/categories"),
      api("/api/blog/tags"),
    ]);
    categories.value = flatten(c);
    tags.value = t;
  } catch (e) {
    error.value = e.message;
  }
  await load();
});
</script>
<template>
  <section>
    <div class="blog-intro">
      <h1>记录与思考</h1>
      <p>把好奇写下来，把思考留在这里。</p>
    </div>
    <div class="blog-layout">
      <div>
        <form class="blog-search" @submit.prevent="filter">
          <el-input
            v-model="filters.keyword"
            clearable
            aria-label="搜索文章"
            placeholder="寻找一篇文章…"
            ><template #prefix><Search :size="18" /></template></el-input
          ><el-button native-type="submit">搜索</el-button>
        </form>
        <div
          v-if="filters.tagId || filters.categoryId || filters.keyword"
          class="active-filters"
          aria-live="polite"
        >
          <span v-if="filters.tagId"
            >标签：{{ tags.find((t) => t.id === filters.tagId)?.name }}</span
          >
          <span v-if="filters.categoryId"
            >分类：{{
              categories.find((c) => c.id === filters.categoryId)?.name
            }}</span
          >
          <span v-if="filters.keyword">关键词：{{ filters.keyword }}</span>
          <button @click="resetFilters">清除筛选</button>
        </div>
        <PageState
          :loading="loading"
          :error="error"
          :empty="!data.list.length"
          title="还没有可阅读的文章"
          description="试试其他关键词，或稍后再来看看。"
          @retry="load"
          ><div class="article-feed">
            <article v-for="a in data.list" :key="a.id">
              <div class="article-meta">
                <span>{{ a.categoryName || "随笔" }}</span
                ><time>{{ date(a.publishedAt).slice(0, 10) }}</time>
              </div>
              <h2>
                <RouterLink :to="'/articles/' + a.id"
                  >{{ a.title }}<ArrowUpRight :size="23"
                /></RouterLink>
              </h2>
              <p v-if="a.summary">{{ a.summary }}</p>
              <div class="article-meta">
                <button
                  v-for="t in a.tags"
                  :key="t.id"
                  @click="
                    filters.tagId = t.id;
                    filter();
                  "
                >
                  #{{ t.name }}</button
                ><span>{{ a.viewCount }} 次阅读</span>
              </div>
            </article>
          </div>
          <el-pagination
            v-model:current-page="filters.pageNum"
            :page-size="filters.pageSize"
            :total="data.total"
            layout="prev,pager,next"
            @current-change="load"
        /></PageState>
      </div>
      <aside class="blog-index">
        <h2>按主题阅读</h2>
        <button
          :class="{
            active:
              filters.categoryId === '' && !filters.tagId && !filters.keyword,
          }"
          @click="resetFilters"
        >
          全部文章</button
        ><button
          v-for="c in categories"
          :key="c.id"
          :class="{ active: filters.categoryId === c.id }"
          @click="
            filters.categoryId = c.id;
            filter();
          "
        >
          {{ c.label }}
        </button>
        <h2 v-if="tags.length">标签</h2>
        <div v-if="tags.length" class="mobile-tag-filter">
          <label for="blog-tag">按标签阅读</label>
          <el-select
            id="blog-tag"
            v-model="filters.tagId"
            aria-label="按标签筛选"
            clearable
            placeholder="全部标签"
            @change="filter"
          >
            <el-option label="全部标签" value="" />
            <el-option
              v-for="t in tags"
              :key="t.id"
              :label="t.name"
              :value="t.id"
            />
          </el-select>
        </div>
        <div class="tag-cloud">
          <button
            v-for="t in tags"
            :key="t.id"
            :class="{ active: filters.tagId === t.id }"
            @click="
              filters.tagId = filters.tagId === t.id ? '' : t.id;
              filter();
            "
          >
            {{ t.name }}
          </button>
        </div>
        <RouterLink to="/ask" class="ask-link"
          >在文章中找答案<ArrowUpRight :size="18"
        /></RouterLink>
      </aside>
    </div>
  </section>
</template>
