import { createRouter, createWebHistory } from "vue-router";
export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to, from, saved) {
    return saved || { top: 0 };
  },
  routes: [
    {
      path: "/",
      component: () => import("./views/Reader.vue"),
      meta: { title: "阅读空间", reader: true },
    },
    {
      path: "/articles/:id",
      component: () => import("./views/Reader.vue"),
      meta: { title: "阅读 Book", reader: true },
    },
    {
      path: "/ask",
      component: () => import("./views/Reader.vue"),
      meta: { title: "站内问答", reader: true },
    },
    {
      path: "/login",
      component: () => import("./views/Login.vue"),
      meta: { title: "作者登录" },
    },
    { path: "/register", component: () => import("./views/Register.vue"), meta: {title:"创建账号"} },
    { path: "/admin/knowledge", component: () => import("./views/Knowledge.vue"), meta: {admin:true,title:"全库搜索与问答"} },
    { path: "/admin/assistant", component: () => import("./views/Assistant.vue"), meta: {admin:true,title:"笔记与 Agent"} },
    { path: "/admin/learning", component: () => import("./views/Learning.vue"), meta: {admin:true,title:"学习中心"} },
    { path: "/admin/imports", component: () => import("./views/Imports.vue"), meta: {admin:true,title:"采集知识"} },
    { path: "/admin/settings", component: () => import("./views/WorkspaceTools.vue"), meta: {admin:true,title:"空间设置与备份"} },
    { path: "/admin", redirect: "/admin/books" },
    {
      path: "/books",
      component: () => import("./views/Reader.vue"),
      meta: { title: "我的书库", reader: true, privateReader: true },
    },
    {
      path: "/books/:id",
      component: () => import("./views/Reader.vue"),
      meta: { title: "阅读 Book", reader: true },
    },
    {
      path: "/admin/books",
      component: () => import("./views/Books.vue"),
      meta: { admin: true, title: "Book 管理" },
    },
    {
      path: "/admin/books/new",
      component: () => import("./views/BookEditor.vue"),
      meta: { admin: true, title: "新建 Book" },
    },
    {
      path: "/admin/books/:id",
      component: () => import("./views/BookEditor.vue"),
      meta: { admin: true, title: "Book 管理" },
    },
    {
      path: "/admin/articles",
      redirect: "/admin/books",
    },
    {
      path: "/admin/articles/new",
      redirect: "/admin/books/new",
    },
    {
      path: "/admin/articles/:id",
      component: () => import("./views/LegacyBookRedirect.vue"),
      meta: { admin: true, title: "打开 Book" },
    },
    {
      path: "/admin/taxonomy",
      component: () => import("./views/Taxonomy.vue"),
      meta: { admin: true, title: "分类与标签" },
    },
    {
      path: "/admin/tasks",
      component: () => import("./views/Tasks.vue"),
      meta: { admin: true, title: "写作助手" },
    },
    {
      path: "/admin/ask",
      redirect: "/books",
    },
    {
      path: "/:pathMatch(.*)*",
      component: () => import("./views/NotFound.vue"),
      meta: { title: "页面不存在" },
    },
  ],
});
router.beforeEach((to) => {
  document.title = `${to.meta.title || "记录与思考"} · Book_Agent`;
  if (
    (to.meta.admin || to.meta.privateReader) &&
    !sessionStorage.getItem("book-agent-token")
  )
    return { path: "/login", query: { redirect: to.fullPath } };
});
