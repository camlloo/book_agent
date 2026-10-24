<script setup>
import { ref } from "vue";
import { Send, ArrowUpRight } from "lucide-vue-next";
import { api } from "../api";
import Markdown from "../components/Markdown.vue";
const question = ref(""),
  answer = ref(null),
  busy = ref(false),
  error = ref("");
async function ask() {
  if (!question.value.trim()) return;
  busy.value = true;
  error.value = "";
  answer.value = null;
  try {
    answer.value = await api("/api/blog/ask", {
      method: "POST",
      body: { question: question.value.trim() },
    });
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="ask-page">
    <div class="page-heading">
      <div>
        <h1>在文章中找答案</h1>
        <p>提一个问题，看看已发布的文章里写过什么。</p>
      </div>
    </div>
    <form @submit.prevent="ask">
      <label for="question">你的问题</label
      ><el-input
        id="question"
        v-model="question"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="例如：作者如何使用 AI 辅助写作？"
      /><el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        :disabled="!question.trim()"
        ><Send :size="17" />查找答案</el-button
      >
    </form>
    <p class="muted">回答基于站内已发布文章。请结合引用原文核对内容。</p>
    <p v-if="busy" role="status" class="state">正在阅读相关文章…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="answer" class="answer" aria-live="polite">
      <Markdown :content="answer.answer" />
      <div v-if="answer.sources?.length" class="citations">
        <h2>引用文章</h2>
        <article v-for="s in answer.sources" :key="s.citation">
          <RouterLink :to="s.url"
            >[{{ s.citation }}] {{ s.title }}<ArrowUpRight :size="16"
          /></RouterLink>
          <p>{{ s.excerpt }}</p>
        </article>
      </div>
    </div>
  </section>
</template>
