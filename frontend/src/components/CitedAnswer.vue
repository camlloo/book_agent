<script setup>
import { computed } from "vue";
import { renderCitedAnswer } from "../citations";
const props = defineProps({
  content: { type: String, default: "" },
  sources: { type: Array, default: () => [] },
  bookTitle: { type: String, default: "" },
});
const emit = defineEmits(["cite"]);
const html = computed(() => renderCitedAnswer(props.content, props.sources, props.bookTitle));
function follow(event) {
  const link = event.target.closest("a[data-book-citation]");
  if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button > 0) return;
  const source = props.sources.find((item) => String(item.citation) === link.dataset.bookCitation);
  if (!source) return;
  event.preventDefault();
  emit("cite", source, link.dataset.bookQuote);
}
</script>
<template><div class="markdown" v-html="html" @click="follow"></div></template>
