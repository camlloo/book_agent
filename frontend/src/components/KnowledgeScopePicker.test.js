import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createApp, h, nextTick } from "vue";
import Picker from "./KnowledgeScopePicker.vue";
import { api } from "../api";

vi.mock("../api", () => ({ api: vi.fn() }));
const fixture = { categories: [{ id: 1, name: "编程" }, { id: 2, name: "Python", parentId: 1 }], books: [
  { id: 10, title: "函数", categoryId: 1, indexStatus: "READY", revision: 1, indexedRevision: 1 },
  { id: 11, title: "文件", categoryId: 2, indexStatus: "READY", revision: 1, indexedRevision: 1 },
  { id: 12, title: "等待索引", categoryId: 2, indexStatus: "QUEUED", revision: 1, indexedRevision: 0 },
] };
let app, host;
const settle = async () => { await new Promise((done) => setTimeout(done, 0)); await nextTick(); };
async function mount(props = {}) {
  host = document.createElement("div"); document.body.append(host);
  app = createApp({ render: () => h(Picker, props) }); app.mount(host);
  host.querySelector("button").click(); await settle(); await settle();
}
function clickFolder() {
  const row = [...document.querySelectorAll(".el-tree-node__content")].find((item) => item.querySelector(".scope-tree-title")?.textContent === "编程");
  row.querySelector("input[type=checkbox]").click();
}
beforeEach(() => { api.mockReset(); api.mockResolvedValue(fixture); vi.stubGlobal("ResizeObserver", class { observe() {} unobserve() {} disconnect() {} }); });
afterEach(() => { app?.unmount(); host?.remove(); document.querySelectorAll(".el-overlay").forEach((item) => item.remove()); vi.unstubAllGlobals(); });

it("selects a folder and its ready descendants, excluding unfinished documents", async () => {
  const update = vi.fn(); await mount({ "onUpdate:modelValue": update });
  clickFolder(); await settle();
  expect(document.querySelector(".scope-picker-selection").textContent).toContain("2");
  const apply = [...document.querySelectorAll("button")].find((item) => item.textContent.includes("应用知识范围"));
  apply.click(); await settle(); expect(update).toHaveBeenCalledWith([10, 11]);
});
it("rejects an oversized folder selection instead of silently selecting only part", async () => {
  await mount({ maxBooks: 1 }); clickFolder(); await settle();
  expect(document.querySelector('[role="alert"]').textContent).toContain("最多选择 1 本");
  expect(document.querySelector(".scope-picker-selection strong").textContent).toBe("0");
});
it("restores existing checks when the catalogue arrives after the dialog opens", async () => {
  let resolve; api.mockReturnValue(new Promise((done) => { resolve = done; }));
  await mount({ modelValue: [11] }); resolve(fixture); await settle(); await settle();
  const checks = [...document.querySelectorAll('.el-tree-node__content input[type="checkbox"]')].filter((item) => item.checked);
  // The child branch may remain collapsed, but the selected child is represented by a partial root.
  expect(document.querySelector(".scope-picker-selection strong").textContent).toBe("1");
  expect(checks.length > 0 || document.querySelector(".el-checkbox__input.is-indeterminate")).toBeTruthy();
});
