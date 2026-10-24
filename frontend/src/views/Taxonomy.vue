<script setup>
import { ref, reactive, onMounted } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, action, flatten } from "../api";
import PageState from "../components/PageState.vue";
const categories = ref([]),
  tags = ref([]),
  loading = ref(true),
  error = ref(""),
  busy = ref(false);
const category = reactive({ id: null, name: "", parentId: null, sortOrder: 0 }),
  tag = reactive({ id: null, name: "" });
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const [c, t] = await Promise.all([
      api("/api/categories"),
      api("/api/tags"),
    ]);
    categories.value = flatten(c);
    tags.value = t;
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
function reset(kind) {
  Object.assign(
    kind === "categories" ? category : tag,
    kind === "categories"
      ? { id: null, name: "", parentId: null, sortOrder: 0 }
      : { id: null, name: "" },
  );
}
async function save(kind) {
  const form = kind === "categories" ? category : tag;
  if (!form.name.trim()) return;
  busy.value = true;
  await action(async () => {
    await api("/api/" + kind + (form.id ? "/" + form.id : ""), {
      method: form.id ? "PUT" : "POST",
      body: form,
    });
    ElMessage.success("已保存");
    reset(kind);
    await load();
  });
  busy.value = false;
}
async function remove(kind, item) {
  try {
    await ElMessageBox.confirm(
      `删除「${item.name}」？${kind === "tags" ? "文章上的该标签也将移除。" : "有子分类或文章的分类无法删除。"}`,
      "删除" + (kind === "tags" ? "标签" : "分类"),
      { confirmButtonText: "删除", cancelButtonText: "取消", type: "warning" },
    );
  } catch {
    return;
  }
  busy.value = true;
  await action(async () => {
    await api("/api/" + kind + "/" + item.id, { method: "DELETE" });
    reset(kind);
    await load();
  });
  busy.value = false;
}
onMounted(load);
</script>
<template>
  <section>
    <div class="page-heading">
      <div>
        <h1>分类与标签</h1>
        <p>用分类建立结构，用标签连接想法。</p>
      </div>
    </div>
    <PageState :loading="loading" :error="error" @retry="load"
      ><div class="taxonomy-columns">
        <section>
          <h2>分类</h2>
          <form @submit.prevent="save('categories')">
            <label for="category-name">{{
              category.id ? "修改分类" : "新增分类"
            }}</label
            ><el-input
              id="category-name"
              v-model="category.name"
              maxlength="64"
              required
              placeholder="分类名称"
            /><label for="parent-category">上级分类</label
            ><el-select
              id="parent-category"
              v-model="category.parentId"
              clearable
              placeholder="顶级分类"
              ><el-option
                v-for="c in categories.filter((c) => c.id !== category.id)"
                :key="c.id"
                :value="c.id"
                :label="c.label" /></el-select
            ><label for="category-order">排序（越小越靠前）</label
            ><el-input-number
              id="category-order"
              v-model="category.sortOrder"
              :min="0"
              :max="10000"
            />
            <div class="button-group">
              <el-button type="primary" native-type="submit" :loading="busy">{{
                category.id ? "保存分类" : "添加分类"
              }}</el-button
              ><el-button v-if="category.id" @click="reset('categories')"
                >取消修改</el-button
              >
            </div>
          </form>
          <p v-if="!categories.length" class="muted">
            还没有分类，创建第一个分类来整理文章。
          </p>
          <ul class="taxonomy-list">
            <li v-for="c in categories" :key="c.id">
              <span>{{ c.label }}</span>
              <div class="row-actions">
                <button @click="Object.assign(category, c)">修改</button
                ><button
                  class="danger"
                  :disabled="busy"
                  @click="remove('categories', c)"
                >
                  删除
                </button>
              </div>
            </li>
          </ul>
        </section>
        <section>
          <h2>标签</h2>
          <form @submit.prevent="save('tags')">
            <label for="tag-name">{{ tag.id ? "修改标签" : "新增标签" }}</label
            ><el-input
              id="tag-name"
              v-model="tag.name"
              maxlength="64"
              required
              placeholder="标签名称"
            />
            <div class="button-group">
              <el-button type="primary" native-type="submit" :loading="busy">{{
                tag.id ? "保存标签" : "添加标签"
              }}</el-button
              ><el-button v-if="tag.id" @click="reset('tags')"
                >取消修改</el-button
              >
            </div>
          </form>
          <p v-if="!tags.length" class="muted">
            还没有标签，用关键词连接不同分类的文章。
          </p>
          <ul class="taxonomy-list">
            <li v-for="t in tags" :key="t.id">
              <span>{{ t.name }}</span>
              <div class="row-actions">
                <button @click="Object.assign(tag, t)">修改</button
                ><button
                  class="danger"
                  :disabled="busy"
                  @click="remove('tags', t)"
                >
                  删除
                </button>
              </div>
            </li>
          </ul>
        </section>
      </div></PageState
    >
  </section>
</template>
