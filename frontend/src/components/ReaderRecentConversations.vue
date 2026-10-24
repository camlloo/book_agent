<script setup>
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ArrowLeft, MessageSquare, Search, Trash2 } from "lucide-vue-next";
import { api } from "../api";

const props = defineProps({ userId: [Number, String], selectedId: Number, disabled: Boolean });
const emit = defineEmits(["select", "deleted", "close"]);
const sessions = ref([]), loading = ref(false), error = ref(""), search = ref(""), confirming = ref(null), deleting = ref(null);
let active = true, generation = 0;
const groups = computed(() => {
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
  const week = new Date(today); week.setDate(today.getDate() - 7);
  const result = new Map();
  const matching = sessions.value.filter(item => item.title.toLowerCase().includes(search.value.trim().toLowerCase()));
  for (const item of matching) {
    const time = new Date(item.updatedAt);
    const label = time >= today ? "今天" : time >= yesterday ? "昨天" : time >= week ? "过去 7 天" : "更早";
    if (!result.has(label)) result.set(label, []);
    result.get(label).push(item);
  }
  return [...result].map(([title, items]) => ({ title, items }));
});
const modes = { qa: "问答", summary: "总结", compare: "对比" };
function scopeLabel(item) {
  return `${modes[item.mode] || "问答"} · ${item.scope.publicOnly ? "公开" : "我的知识"} · ${item.scope.bookIds?.length ? `${item.scope.bookIds.length} 本 Book` : "全库"}`;
}
function timeLabel(value) {
  const time = new Date(value);
  return Number.isNaN(time.getTime()) ? "" : time.toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
}
async function load() {
  if (!props.userId) return;
  const run = ++generation; loading.value = true; error.value = "";
  try { const data = await api("/api/conversations"); if (active && run === generation) sessions.value = data; }
  catch (e) { if (active && run === generation) error.value = e.message; }
  finally { if (active && run === generation) loading.value = false; }
}
async function remove(item) {
  if (props.disabled || deleting.value) return;
  deleting.value = item.id; error.value = "";
  try {
    await api(`/api/conversations/${item.id}`, { method: "DELETE" });
    if (!active) return;
    sessions.value = sessions.value.filter(session => session.id !== item.id);
    confirming.value = null; emit("deleted", item.id);
  } catch (e) { if (active) error.value = e.message; }
  finally { if (active) deleting.value = null; }
}
onMounted(load);
onUnmounted(() => { active = false; generation++; });
</script>

<template>
  <section class="reader-recents" aria-label="最近的对话">
    <div class="reader-recents-heading"><h3>最近</h3><button type="button" class="reader-text-button" @click="emit('close')"><ArrowLeft :size="13" />返回对话</button></div>
    <template v-if="userId">
      <label class="reader-recents-search"><Search :size="15" /><input v-model="search" aria-label="搜索历史对话" placeholder="搜索对话标题" /></label>
      <p v-if="loading" class="reader-recents-state" role="status">正在加载历史对话…</p>
      <div v-if="error" class="reader-recents-state" role="alert"><p>{{ error }}</p><button class="reader-text-button" @click="load">重新加载</button></div>
      <div v-if="!loading" class="reader-recents-list">
        <section v-for="group in groups" :key="group.title" class="reader-recents-group">
          <h4>{{ group.title }}</h4>
          <ul><li v-for="item in group.items" :key="item.id" :class="{ selected: selectedId === item.id }">
            <div class="reader-recent-row">
              <button type="button" class="reader-recent-open" :disabled="disabled || !!deleting" :aria-current="selectedId === item.id ? 'true' : undefined" @click="emit('select', item)">
                <MessageSquare :size="15" /><span><strong>{{ item.title }}</strong><small>{{ scopeLabel(item) }}</small><time :datetime="item.updatedAt">{{ timeLabel(item.updatedAt) }}</time></span>
              </button>
              <button type="button" class="reader-icon-button reader-recent-delete" :disabled="disabled || !!deleting" :aria-label="`删除对话：${item.title}`" @click="confirming = confirming === item.id ? null : item.id"><Trash2 :size="14" /></button>
            </div>
            <div v-if="confirming === item.id" class="reader-recent-confirm"><span>删除后无法恢复，Book 会保留。</span><div><button type="button" class="reader-text-button" :disabled="!!deleting" @click="confirming = null">取消</button><button type="button" class="reader-text-button danger" :disabled="disabled || !!deleting" @click="remove(item)">{{ deleting === item.id ? '正在删除…' : '确认删除' }}</button></div></div>
          </li></ul>
        </section>
        <p v-if="!groups.length && !error" class="reader-recents-state">{{ search.trim() ? '没有匹配的对话，试试其他标题。' : '还没有历史对话。发送第一个问题后，会自动保存在这里。' }}</p>
      </div>
      <p class="reader-recents-footnote">按最近更新排序，最多显示 200 段对话。</p>
    </template>
    <div v-else class="reader-recents-state"><MessageSquare :size="24" /><p>登录后自动保存对话，随时接着聊。</p><RouterLink :to="{ path: '/login', query: { redirect: $route.fullPath } }">登录查看历史</RouterLink></div>
  </section>
</template>
