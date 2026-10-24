import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { streamApi } from "./api";
vi.mock("element-plus", () => ({ ElMessage: { error: vi.fn() } }));
vi.mock("element-plus/es/components/message/style/css", () => ({}));
vi.mock("element-plus/es/components/message-box/style/css", () => ({}));

function sseResponse(frames, { contentType = "text/event-stream", status = 200 } = {}) {
  const encoder = new TextEncoder();
  let index = 0;
  return {
    ok: status < 400,
    status,
    headers: { get: (name) => (name.toLowerCase() === "content-type" ? contentType : null) },
    body: {
      getReader() {
        return {
          read: async () =>
            index < frames.length
              ? { value: encoder.encode(frames[index++]), done: false }
              : { value: undefined, done: true },
        };
      },
    },
  };
}

function jsonResponse(payload, status = 200) {
  return {
    ok: status < 400,
    status,
    headers: { get: (name) => (name.toLowerCase() === "content-type" ? "application/json" : null) },
    json: async () => payload,
  };
}

describe("streamApi server-sent events", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });

  it("parses split frames and returns the done payload", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      sseResponse([
        'event: start\ndata: {"mode":"qa"}\n\nevent: delta\ndata: {"text":"第一段"}\n\nevent: delta\nda',
        'ta: {"text":"第二段"}\n\nevent: reset\ndata: {}\n\nevent: done\ndata: {"answer":"结论","sources":[]}\n\n',
      ]),
    ));
    const events = [];
    const done = await streamApi("/api/x/stream", { body: { q: 1 }, onEvent: (name, data) => events.push([name, data]) });
    expect(done).toEqual({ answer: "结论", sources: [] });
    expect(events).toEqual([
      ["start", { mode: "qa" }],
      ["delta", { text: "第一段" }],
      ["delta", { text: "第二段" }],
      ["reset", {}],
    ]);
  });

  it("throws the message of an error frame and stops", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      sseResponse(['event: delta\ndata: {"text":"部分"}\n\nevent: error\ndata: {"message":"回答流中断"}\n\nevent: done\ndata: {"answer":"不应到达"}\n\n']),
    ));
    const events = [];
    await expect(
      streamApi("/api/x/stream", { body: {}, onEvent: (name) => events.push(name) }),
    ).rejects.toMatchObject({ message: "回答流中断" });
    expect(events).toEqual(["delta"]);
  });

  it("treats a JSON envelope rejection as the regular API error contract", async () => {
    vi.stubGlobal("fetch", vi.fn(async () =>
      jsonResponse({ code: 409, message: "该对话已有问题正在处理" }),
    ));
    await expect(streamApi("/api/x/stream", { body: {} })).rejects.toMatchObject({
      message: "该对话已有问题正在处理",
    });
  });

  it("expires only the credentials used by an unauthorized stream", async () => {
    sessionStorage.setItem("book-agent-token", "old-session");
    const expired = vi.fn();
    window.addEventListener("book-agent-session-expired", expired);
    try {
      vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ code: 401, message: "Expired" })));
      await expect(streamApi("/api/x/stream", { body: {} })).rejects.toMatchObject({ message: "Expired" });
      expect(sessionStorage.getItem("book-agent-token")).toBeNull();
      expect(expired).toHaveBeenCalledOnce();
    } finally {
      window.removeEventListener("book-agent-session-expired", expired);
    }
  });

  it("does not expire newer credentials replaced during the request", async () => {
    sessionStorage.setItem("book-agent-token", "old-session");
    let resolve;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((done) => { resolve = done; })));
    const pending = streamApi("/api/x/stream", { body: {} });
    sessionStorage.setItem("book-agent-token", "new-session");
    resolve(jsonResponse({ code: 401, message: "Expired" }));
    await expect(pending).rejects.toMatchObject({ message: "Expired" });
    expect(sessionStorage.getItem("book-agent-token")).toBe("new-session");
  });
});
