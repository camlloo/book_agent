<script setup>
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { ArrowLeft } from "lucide-vue-next";
import { api, date } from "../api";
import Markdown from "../components/Markdown.vue";
import PageState from "../components/PageState.vue";
const route = useRoute(),
  article = ref(null),
  loading = ref(true),
  error = ref("");
async function load() {
  loading.value = true;
  error.value = "";
  try {
    article.value = await api("/api/blog/articles/" + route.params.id);
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="reading">
    <RouterLink to="/" class="back-link"
      ><ArrowLeft :size="16" />全部文章</RouterLink
    ><PageState :loading="loading" :error="error" @retry="load"
      ><article v-if="article">
        <header class="reading-header">
          <div class="article-meta">
            <span>{{ article.categoryName || "随笔" }}</span
            ><time>{{ date(article.publishedAt).slice(0, 10) }}</time
            ><span>{{ article.viewCount }} 次阅读</span>
          </div>
          <h1>{{ article.title }}</h1>
          <p v-if="article.summary" class="reading-summary">
            {{ article.summary }}
          </p>
          <div class="tag-cloud">
            <span v-for="t in article.tags" :key="t.id">#{{ t.name }}</span>
          </div>
        </header>
        <Markdown :content="article.content" /></article
    ></PageState>
  </div>
</template>
