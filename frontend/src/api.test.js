import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "./api";
vi.mock("element-plus", () => ({ ElMessage: { error: vi.fn() } }));
vi.mock("element-plus/es/components/message/style/css", () => ({}));
vi.mock("element-plus/es/components/message-box/style/css", () => ({}));

describe("API credential expiry boundary", () => {
  const expired = vi.fn();
  beforeEach(() => {
    sessionStorage.clear();
    expired.mockClear();
    window.addEventListener("book-agent-session-expired", expired);
  });
  afterEach(() => {
    window.removeEventListener("book-agent-session-expired", expired);
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });
  it("expires the credentials used by an unauthorized request", async () => {
    sessionStorage.setItem("book-agent-token", "old-session");
    vi.stubGlobal("fetch", vi.fn(async () => ({
      ok: false, status: 401, json: async () => ({ code: 401, message: "Expired" }),
    })));
    await expect(api("/api/books/11")).rejects.toMatchObject({ code: 401 });
    expect(sessionStorage.getItem("book-agent-token")).toBeNull();
    expect(expired).toHaveBeenCalledOnce();
  });
  it("does not erase newer credentials when an old response arrives late", async () => {
    let resolve;
    sessionStorage.setItem("book-agent-token", "old-session");
    vi.stubGlobal("fetch", vi.fn(() => new Promise((done) => { resolve = done; })));
    const pending = api("/api/books/11");
    sessionStorage.setItem("book-agent-token", "new-session");
    resolve({ ok: false, status: 401, json: async () => ({ code: 401, message: "Expired" }) });
    await expect(pending).rejects.toMatchObject({ code: 401 });
    expect(sessionStorage.getItem("book-agent-token")).toBe("new-session");
    expect(expired).not.toHaveBeenCalled();
  });
});
