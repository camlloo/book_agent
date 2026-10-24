<script setup>
import { ref, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api";
import PageState from "../components/PageState.vue";
const route = useRoute(),
  router = useRouter(),
  error = ref("");
async function load() {
  try {
    const book = await api("/api/articles/" + route.params.id);
    await router.replace("/admin/books/" + book.id);
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(load);
</script>
<template>
  <PageState :loading="!error" :error="error" @retry="load" />
</template>
