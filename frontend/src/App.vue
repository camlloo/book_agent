<script setup>
import { computed, onMounted, onUnmounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  BookOpen,
  FileText,
  PenLine,
  LayoutGrid,
  MessagesSquare,
  ExternalLink,
  LogOut,
  Search,
  Download,
  Settings,
  Sparkles,
  GraduationCap,
} from "lucide-vue-next";
import { useAuth } from "./stores/auth";
import { action } from "./api";
import zhCn from "element-plus/es/locale/lang/zh-cn";
const route = useRoute(),
  router = useRouter(),
  auth = useAuth();
const admin = computed(() => route.meta.admin);
const links = [
  ["/admin/books", "我的 Book", BookOpen],
  ["/admin/knowledge", "全库搜索与问答", Search],
  ["/admin/assistant", "笔记与 Agent", Sparkles],
  ["/admin/learning", "学习中心", GraduationCap],
  ["/admin/imports", "采集知识", Download],
  ["/admin/settings", "空间设置与备份", Settings],
  ["/admin/tasks", "写作助手", PenLine],
  ["/admin/taxonomy", "分类与标签", LayoutGrid],
  ["/books", "阅读与对话", MessagesSquare],
];
function expired() {
  auth.user = null;
  if (route.meta.admin || route.meta.privateReader)
    router.replace({ path: "/login", query: { redirect: route.fullPath } });
}
async function logout() {
  await action(() => auth.logout());
  router.push("/login");
}
onMounted(() => {
  window.addEventListener("book-agent-session-expired", expired);
  if (sessionStorage.getItem("book-agent-token")) action(() => auth.check());
});
onUnmounted(() =>
  window.removeEventListener("book-agent-session-expired", expired),
);
</script>
<template>
  <el-config-provider :locale="zhCn">
    <a class="skip-link" href="#main">跳到主要内容</a>
    <div v-if="admin" class="admin-shell">
      <aside class="sidebar">
        <RouterLink to="/admin/books" class="brand"
          ><BookOpen :size="25" /><span>Book_Agent</span></RouterLink
        >
        <nav aria-label="管理导航">
          <RouterLink
            v-for="[path, label, icon] in links"
            :key="path"
            :to="path"
            :class="{ selected: route.path.startsWith(path) }"
            ><component :is="icon" :size="20" />{{ label }}</RouterLink
          >
        </nav>
        <div class="sidebar-bottom">
          <RouterLink to="/"><ExternalLink :size="20" />阅读书库</RouterLink
          ><button @click="logout"><LogOut :size="20" />退出登录</button>
        </div>
      </aside>
      <main id="main" class="workspace">
        <RouterView :key="route.path" />
        <footer class="workspace-footer">
          {{
            route.path.startsWith("/admin/books")
              ? "未发布内容仅向你授权的用户开放，由所有者决定何时发布。"
              : "AI 生成的内容先保存为草稿，由你决定何时发布。"
          }}
        </footer>
      </main>
    </div>
    <RouterView v-else-if="route.meta.reader" />
    <div v-else class="public-shell">
      <header class="public-header">
        <RouterLink to="/" class="brand"
          ><BookOpen :size="25" />Book_Agent</RouterLink
        >
        <nav aria-label="Book 导航">
          <RouterLink to="/">书库</RouterLink
          ><RouterLink to="/ask">阅读与对话</RouterLink
          ><RouterLink
            :to="auth.user ? '/admin/books' : '/login'"
            class="author-link"
            >{{ auth.user ? "写作台" : "作者登录" }}</RouterLink
          >
        </nav>
      </header>
      <main id="main" class="public-main">
        <RouterView :key="route.path" />
      </main>
      <footer class="public-footer">
        Book_Agent<span>记录思考，让想法成为 Book。</span>
      </footer>
    </div>
  </el-config-provider>
</template>
