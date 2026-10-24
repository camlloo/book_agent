import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, nextTick, reactive } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import Reader from "./Reader.vue";
import { api } from "../api";

vi.mock("../api", () => ({ api: vi.fn(), query: () => "pageNum=1", date: value => value || "2026-10-21", flatten: value => value }));
const auth = reactive({ user: { id: 1 } });
vi.mock("../stores/auth", () => ({ useAuth: () => auth }));
const scope = { bookIds: [30], publicOnly: true };
const summary = id => ({ id, title: `函数讨论 ${id}`, mode: "qa", scope, updatedAt: "2026-10-21T10:00:00" });
const detail = (id, selection = scope) => ({ ...summary(id), scope: selection, busy: false,
  messages: [{ id: id * 10, role: "user", content: `问题 ${id}`, status: "SUCCEEDED", sources: [] },
    { id: id * 10 + 1, role: "assistant", content: `回答 ${id}`, status: "SUCCEEDED", sources: [] }] });
let app, host, router;
async function settle() { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); }
async function mount(path = "/books/30") {
  router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/books/:id", component: Reader }, { path: "/:pathMatch(.*)*", component: { template: "<div>其他页面</div>" } }] });
  await router.push(path); await router.isReady();
  host = document.createElement("div"); document.body.append(host); app = createApp(Reader); app.use(router); app.mount(host);
  await settle(); await settle(); await settle();
}
const click = label => { const target = [...host.querySelectorAll("button")].find(button => button.getAttribute("aria-label") === label || button.textContent.trim() === label); expect(target, label).toBeTruthy(); target.click(); };
const transcript = () => host.querySelector(".reader-chat-scroll")?.textContent;
beforeEach(() => {
  auth.user = { id: 1 }; api.mockReset(); localStorage.clear(); sessionStorage.clear();
  vi.stubGlobal("matchMedia", () => ({ matches: false, onchange: null }));
  vi.stubGlobal("IntersectionObserver", class { observe() {} disconnect() {} });
  vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} unobserve() {} });
  Element.prototype.scrollTo = vi.fn(); Element.prototype.scrollIntoView = vi.fn();
  api.mockImplementation((url, options) => {
    if (url.endsWith("/read")) return Promise.resolve({ book: { id: 30, title: "Python", content: "# 函数", revision: 1, indexedRevision: 1, indexStatus: "READY" }, sections: [] });
    if (url.startsWith("/api/blog/books?")) return Promise.resolve({ list: [], total: 0 });
    if (url.includes("categories") || url.includes("tags")) return Promise.resolve([]);
    if (url.startsWith("/api/conversations?")) return Promise.resolve([summary(2), summary(1)]);
    if (url === "/api/conversations" && options?.method === "POST") return Promise.resolve({ id: 3 });
    if (url === "/api/conversations") return Promise.resolve([summary(2), summary(1)]);
    if (url.endsWith("/messages")) return Promise.resolve({ answer: "新回答", sources: [] });
    if (options?.method === "DELETE") return Promise.resolve(null);
    return Promise.resolve(detail(Number(url.split("/").at(-1))));
  });
});
afterEach(() => { app?.unmount(); host?.remove(); vi.unstubAllGlobals(); });

it("opens separate conversations with the same Book scope and resumes the chosen session", async () => {
  await mount(); expect(transcript()).toContain("回答 2");
  click("最近"); await settle(); host.querySelectorAll(".reader-recent-open")[1].click(); await settle(); await settle();
  expect(transcript()).toContain("回答 1"); expect(transcript()).not.toContain("回答 2");
  expect(router.currentRoute.value.query.conversation).toBe("1");
  const input = host.querySelector("#reader-question"); input.value = "继续解释"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  click("发送问题"); await settle();
  expect(api).toHaveBeenCalledWith("/api/conversations/1/messages", expect.objectContaining({ body: { question: "继续解释" } }));
});

it("starts a fresh conversation without deleting or reloading the prior one", async () => {
  await mount(); click("新建对话"); await settle();
  expect(transcript()).not.toContain("回答 2");
  expect(api.mock.calls.some(([, options]) => options?.method === "DELETE")).toBe(false);
  const input = host.querySelector("#reader-question"); input.value = "怎样定义函数？"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  click("发送问题"); await settle(); await settle();
  expect(api).toHaveBeenCalledWith("/api/conversations", expect.objectContaining({ body: expect.objectContaining({ title: "怎样定义函数？", bookIds: [30] }) }));
  expect(api).toHaveBeenCalledWith("/api/conversations/3/messages", expect.anything());
});

it("restores a linked conversation with its original multi-Book scope", async () => {
  const original = api.getMockImplementation();
  api.mockImplementation((url, options) => url === "/api/conversations/8" ? Promise.resolve(detail(8, { bookIds: [31, 32], publicOnly: false })) : original(url, options));
  await mount("/books/30?conversation=8");
  expect(transcript()).toContain("回答 8");
  expect(host.querySelector("#reader-knowledge-scope").textContent).toContain("2 本 Book");
  expect(host.querySelector(".reader-context-chip").textContent).toContain("我的知识");
});

it("a late history response cannot overwrite an explicitly started new chat", async () => {
  let resolve; const pending = new Promise(done => { resolve = done; });
  const original = api.getMockImplementation();
  api.mockImplementation((url, options) => url === "/api/conversations/1" ? pending : original(url, options));
  await mount(); click("最近"); await settle();
  host.querySelectorAll(".reader-recent-open")[1].click(); await nextTick();
  click("新建对话"); resolve(detail(1)); await settle(); await settle();
  expect(transcript()).not.toContain("回答 1"); expect(transcript()).not.toContain("回答 2");
  expect(router.currentRoute.value.query.conversation).toBeUndefined();
});

it("clears private history and the selected session when authentication expires", async () => {
  await mount("/books/30?conversation=1"); expect(transcript()).toContain("回答 1");
  auth.user = null; window.dispatchEvent(new Event("book-agent-session-expired")); await settle();
  expect(transcript()).not.toContain("回答 1"); click("最近"); await settle();
  expect(host.querySelector(".reader-recents").textContent).toContain("登录查看历史");
  expect(host.querySelectorAll(".reader-recent-open")).toHaveLength(0);
});

it("keeps an all-library conversation scope when continuing or starting another chat", async () => {
  const original = api.getMockImplementation();
  api.mockImplementation((url, options) => url === "/api/conversations/8" ? Promise.resolve(detail(8, { bookIds: [], publicOnly: false, searchMode: "keyword", rerank: false })) : original(url, options));
  await mount("/books/30?conversation=8");
  expect(host.querySelector(".reader-context-chip").textContent).toContain("我的知识 · 全库");
  expect(host.querySelector("#reader-knowledge-scope").textContent).toContain("我的知识 · 全部 Book");
  click("新建对话"); await settle();
  expect(host.querySelector(".reader-context-chip").textContent).toContain("我的知识 · 全库");
  const input = host.querySelector("#reader-question"); input.value = "检索函数"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  click("发送问题"); await settle(); await settle();
  expect(api).toHaveBeenCalledWith("/api/conversations", expect.objectContaining({ body: expect.objectContaining({ bookIds: [], publicOnly: false, searchMode: "keyword", rerank: false }) }));
});

it("a pending automatic restore cannot repopulate a new empty conversation", async () => {
  let resolve; const pending = new Promise(done => { resolve = done; });
  const original = api.getMockImplementation();
  api.mockImplementation((url, options) => url === "/api/conversations/2" ? pending : original(url, options));
  await mount(); click("新建对话"); resolve(detail(2)); await settle();
  expect(transcript()).not.toContain("回答 2");
  const input = host.querySelector("#reader-question"); input.value = "新问题"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  click("发送问题"); await settle();
  expect(api).toHaveBeenCalledWith("/api/conversations/3/messages", expect.anything());
});

it("removing a history link cancels a late restoration instead of reopening it", async () => {
  let resolve; const pending = new Promise(done => { resolve = done; });
  const original = api.getMockImplementation();
  api.mockImplementation((url, options) => url === "/api/conversations/8" ? pending : original(url, options));
  await mount("/books/30?conversation=8");
  await router.replace({ query: {} }); resolve(detail(8)); await settle();
  expect(transcript()).not.toContain("回答 8");
  expect(router.currentRoute.value.query.conversation).toBeUndefined();
});

it("restoring the current Book keeps follow-ups added through its history entry", async () => {
  await mount(); click("最近"); await settle();
  host.querySelectorAll(".reader-recent-open")[0].click(); await settle(); await settle();
  const input = host.querySelector("#reader-question"); input.value = "历史中的追问"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  click("发送问题"); await settle(); click("恢复仅当前 Book"); await settle();
  expect(transcript()).toContain("历史中的追问"); expect(transcript()).toContain("新回答");
});
