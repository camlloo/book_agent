<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from "vue";
import { Folder, BookOpen, FileText, ChevronDown, Search } from "lucide-vue-next";
import { api } from "../api";
import { buildScopeTree, checkedBookIds, scopeIds } from "../knowledgeScope";
import "../scope-picker.css";

defineOptions({ inheritAttrs: false });
const props = defineProps({
  modelValue: { type: Array, default: () => [] }, publicOnly: Boolean, disabled: Boolean,
  id: String, label: { type: String, default: "选择 Book 或文件夹" }, maxBooks: { type: Number, default: 5000 },
  excludedIds: { type: Array, default: () => [] }, canChoosePrivate: Boolean,
  emptyText: { type: String, default: "选择 Book 或分类文件夹" },
});
const emit = defineEmits(["update:modelValue", "update:publicOnly", "change"]);
const opened = ref(false), loading = ref(false), error = ref(""), notice = ref(""), search = ref(""), view = ref("folders");
const source = ref(props.publicOnly), draft = ref([]), catalogue = ref({ books: [], categories: [] }), treeRef = ref(null);
let generation = 0, active = true;
const nodes = computed(() => buildScopeTree(catalogue.value, props.excludedIds));
const visibleNodes = computed(() => view.value === "folders" ? nodes.value.tree : nodes.value.books);
const selected = computed(() => new Set(draft.value));
const selectedBooks = computed(() => nodes.value.books.filter((book) => selected.value.has(book.bookId)));
const unavailable = computed(() => draft.value.length - selectedBooks.value.filter((book) => !book.disabled).length);

async function syncChecks() {
  await nextTick();
  treeRef.value?.setCheckedKeys(draft.value.map((id) => `book:${id}`));
  treeRef.value?.filter(search.value.trim().toLowerCase());
}
async function load() {
  const run = ++generation;
  loading.value = true; error.value = "";
  try {
    const data = await api(source.value ? "/api/blog/knowledge/catalogue" : "/api/knowledge/catalogue");
    if (!active || run !== generation) return;
    catalogue.value = data;
    loading.value = false;
    await syncChecks();
  } catch (e) { if (active && run === generation) error.value = e.message; }
  finally { if (active && run === generation) loading.value = false; }
}
async function open() {
  opened.value = true; draft.value = scopeIds(props.modelValue); source.value = props.publicOnly;
  search.value = ""; notice.value = ""; view.value = "folders";
  await load();
}
async function changeSource() {
  draft.value = []; notice.value = ""; catalogue.value = { books: [], categories: [] };
  await load();
}
async function checked(_node, state) {
  const ids = checkedBookIds(state.checkedNodes);
  if (ids.length > props.maxBooks) {
    notice.value = `此功能最多选择 ${props.maxBooks} 本 Book，当前勾选包含 ${ids.length} 本。请展开文件夹后减少选择。`;
    await syncChecks(); return;
  }
  draft.value = ids; notice.value = "";
}
function apply() {
  if (loading.value || error.value || unavailable.value || draft.value.length > props.maxBooks) return;
  emit("update:publicOnly", source.value);
  emit("update:modelValue", [...draft.value]);
  emit("change", { bookIds: [...draft.value], publicOnly: source.value });
  opened.value = false;
}
async function clear() { draft.value = []; notice.value = ""; await syncChecks(); }
watch(search, () => treeRef.value?.filter(search.value.trim().toLowerCase()));
watch(view, syncChecks);
onUnmounted(() => { active = false; ++generation; });
</script>

<template>
  <div class="knowledge-scope-picker">
    <button :id="id" type="button" class="scope-picker-trigger" :disabled="disabled" :aria-label="label" @click="open">
      <Folder :size="17" /><span>{{ modelValue.length ? `已选择 ${modelValue.length} 本 Book` : emptyText }}</span><ChevronDown :size="15" />
    </button>
    <el-dialog v-model="opened" :title="label" width="680px" class="knowledge-scope-dialog" append-to-body destroy-on-close @opened="syncChecks">
      <p class="scope-picker-description">文件夹对应 Book 分类。勾选文件夹会包含其子分类中的就绪 Book，也可以单独勾选多本 Book。</p>
      <el-radio-group v-if="canChoosePrivate" v-model="source" aria-label="资料来源" class="scope-source-switch" @change="changeSource">
        <el-radio-button :value="false">我的知识</el-radio-button><el-radio-button :value="true">公开书库</el-radio-button>
      </el-radio-group>
      <div class="scope-picker-toolbar">
        <el-input v-model="search" clearable aria-label="搜索 Book 或文件夹" placeholder="搜索 Book 标题或分类名称"><template #prefix><Search :size="16" /></template></el-input>
        <el-radio-group v-model="view" aria-label="选择器视图"><el-radio-button value="folders">分类目录</el-radio-button><el-radio-button value="books">全部 Book</el-radio-button></el-radio-group>
      </div>
      <p v-if="loading" class="scope-picker-state" role="status">正在读取可访问的 Book 和分类…</p>
      <div v-else-if="error" class="scope-picker-state" role="alert"><p>{{ error }}</p><el-button @click="load">重新加载</el-button></div>
      <div v-else class="scope-picker-tree">
        <el-tree ref="treeRef" :key="view" :data="visibleNodes" node-key="key" show-checkbox :expand-on-click-node="false" :check-on-click-node="true"
          :filter-node-method="(value, node) => !value || node.searchText.includes(value)" empty-text="没有匹配的 Book 或分类" @check="checked">
          <template #default="{ data }"><span class="scope-tree-row"><component :is="data.kind === 'folder' ? Folder : data.sourceMode === 'UPLOAD' ? FileText : BookOpen" :size="16" />
            <span class="scope-tree-title">{{ data.label }}</span><small v-if="data.kind === 'folder'">{{ data.readyCount }} / {{ data.bookCount }} 本就绪</small><small v-else-if="data.status">{{ data.status }}</small></span></template>
        </el-tree>
      </div>
      <div class="scope-picker-selection"><span>已选择 <strong>{{ draft.length }}</strong> 本<span v-if="maxBooks < 5000">，最多 {{ maxBooks }} 本</span></span><button type="button" :disabled="!draft.length || loading" @click="clear">清空选择</button></div>
      <p v-if="unavailable && !loading && !error" class="error" role="alert">{{ unavailable }} 本原选 Book 已不可用或索引未就绪，请重新选择或清空。</p>
      <p v-if="notice" class="error" role="alert">{{ notice }}</p>
      <p v-if="!loading && !error" class="scope-picker-footnote">未就绪的 Book 暂不可选。应用后按这次选中的 Book 建立知识范围。</p>
      <template #footer><el-button @click="opened = false">取消</el-button><el-button type="primary" :disabled="loading || !!error || !!unavailable || draft.length > maxBooks" @click="apply">应用知识范围</el-button></template>
    </el-dialog>
  </div>
</template>
