import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, nextTick } from "vue";
import Learning from "./Learning.vue";
import { api } from "../api";

vi.mock("../api", () => ({ api: vi.fn(), query: () => "pageSize=100" }));
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; };
const plan = (id, status = "PLANNED") => ({ id, title: `路线 ${id}`, goal: "学习函数", bookTitles: ["Python"],
  currentLevel: "初学者", selfConfidence: 2, durationWeeks: 1, sessionsPerWeek: 1, minutesPerSession: 20,
  progress: { completed: 0, total: 1, percent: 0 }, weakAreas: [], report: null,
  weeks: [{ week: 1, focus: "函数", outcome: "理解返回值", sessions: [{ id: id * 10, title: "阅读练习", status,
    activity: "阅读并复述", dueDate: "2026-10-21", minutes: 20, sources: [], confidence: 2 }] }] });
const summary = (id) => ({ id, title: `路线 ${id}`, currentLevel: "初学者", completedSessions: 0, totalSessions: 1, progress: 0 });
let app, host;
async function settle() { await new Promise((resolve) => setTimeout(resolve, 0)); await nextTick(); }
async function mount() {
  host = document.createElement("div"); document.body.append(host);
  app = createApp(Learning);
  app.component("RouterLink", { template: "<a><slot /></a>" });
  app.mount(host); await settle(); await settle();
}
const heading = () => host.querySelector(".learning-detail h2")?.textContent;
const choices = () => host.querySelectorAll(".learning-plan-item");
beforeEach(() => {
  api.mockReset();
  vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} });
  api.mockImplementation((url) => {
    if (url.startsWith("/api/books?")) return Promise.resolve({ list: [] });
    if (url === "/api/learning/plans") return Promise.resolve([summary(1), summary(2)]);
    return Promise.resolve(plan(Number(url.split("/").at(-1))));
  });
});
afterEach(() => { app?.unmount(); host?.remove(); vi.unstubAllGlobals(); });

it("keeps the latest selected route when detail responses arrive out of order", async () => {
  await mount();
  const old = deferred(), latest = deferred();
  api.mockImplementation((url) => url.endsWith("/2") ? old.promise : latest.promise);
  choices()[1].click(); await nextTick();
  choices()[0].click(); await nextTick();
  latest.resolve(plan(1)); await settle();
  old.resolve(plan(2)); await settle();
  expect(heading()).toBe("路线 1");
});

it("saving a check-in cannot replace another route opened during the request", async () => {
  await mount();
  const saved = deferred();
  api.mockImplementation((url, options) => {
    if (options?.method === "PUT") return saved.promise;
    if (url.endsWith("/2")) return Promise.resolve(plan(2));
    return Promise.resolve([summary(1), summary(2)]);
  });
  const button = [...host.querySelectorAll("button")].find((item) => item.textContent.includes("保存为已完成"));
  button.click(); await nextTick();
  choices()[1].click(); await settle();
  expect(heading()).toBe("路线 2");
  saved.resolve(plan(1, "COMPLETED")); await settle();
  expect(heading()).toBe("路线 2");
});

it("allows a report from a scored needs-review record even with zero completed sessions", async () => {
  api.mockImplementation((url) => {
    if (url.startsWith("/api/books?")) return Promise.resolve({ list: [] });
    if (url === "/api/learning/plans") return Promise.resolve([summary(1)]);
    return Promise.resolve(plan(1, "NEEDS_REVIEW"));
  });
  await mount();
  const button = [...host.querySelectorAll("button")].find((item) => item.textContent.includes("生成学习报告"));
  expect(button).toBeTruthy();
  expect(button.disabled).toBe(false);
});
