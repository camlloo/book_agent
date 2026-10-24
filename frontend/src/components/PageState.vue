<script setup>
import { BookOpen, RefreshCw } from "lucide-vue-next";
defineProps({
  loading: Boolean,
  error: String,
  empty: Boolean,
  title: String,
  description: String,
});
defineEmits(["retry"]);
</script>
<template>
  <div v-if="loading" class="state" role="status">
    <span class="loading-line"></span>正在加载…
  </div>
  <div v-else-if="error" class="state" role="alert">
    <RefreshCw :size="28" />
    <h2>暂时无法加载</h2>
    <p>{{ error }}</p>
    <el-button @click="$emit('retry')">重新加载</el-button>
  </div>
  <div v-else-if="empty" class="state">
    <BookOpen :size="32" />
    <h2>{{ title || "这里还没有内容" }}</h2>
    <p>{{ description }}</p>
    <slot name="action" />
  </div>
  <slot v-else />
</template>
