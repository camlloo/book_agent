import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { api } from "../api";
import { useAuth } from "./auth";
vi.mock("../api", () => ({ api: vi.fn() }));

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

describe("Logout reader invalidation", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    sessionStorage.clear();
    api.mockReset();
  });
  it("notifies active readers and removes credentials even when server logout fails", async () => {
    const auth = useAuth(), expired = vi.fn();
    auth.user = { role: "ADMIN" };
    sessionStorage.setItem("book-agent-token", "session");
    api.mockRejectedValueOnce(new Error("Offline"));
    window.addEventListener("book-agent-session-expired", expired);
    try {
      await expect(auth.logout()).rejects.toThrow("Offline");
      expect(auth.user).toBeNull();
      expect(sessionStorage.getItem("book-agent-token")).toBeNull();
      expect(expired).toHaveBeenCalledOnce();
    } finally {
      window.removeEventListener("book-agent-session-expired", expired);
    }
  });

  it("does not restore a user when startup check succeeds after logout", async () => {
    const auth = useAuth(), check = deferred();
    sessionStorage.setItem("book-agent-token", "old-session");
    api.mockReturnValueOnce(check.promise).mockResolvedValueOnce(undefined);
    const pending = auth.check();
    await auth.logout();
    check.resolve({ role: "ADMIN", nickname: "Old user" });
    expect(await pending).toBeNull();
    expect(auth.user).toBeNull();
    expect(sessionStorage.getItem("book-agent-token")).toBeNull();
  });

  it("does not restore a user when startup check succeeds after session expiry", async () => {
    const auth = useAuth(), check = deferred();
    sessionStorage.setItem("book-agent-token", "old-session");
    api.mockReturnValueOnce(check.promise);
    const pending = auth.check();
    // API expiry clears credentials; App clears the authenticated user.
    sessionStorage.removeItem("book-agent-token");
    auth.user = null;
    window.dispatchEvent(new Event("book-agent-session-expired"));
    check.resolve({ role: "ADMIN", nickname: "Old user" });
    expect(await pending).toBeNull();
    expect(auth.user).toBeNull();
  });

  it("does not replace a newer user's state with a late check from an older token", async () => {
    const auth = useAuth(), check = deferred();
    sessionStorage.setItem("book-agent-token", "old-session");
    api.mockReturnValueOnce(check.promise);
    const pending = auth.check();
    sessionStorage.setItem("book-agent-token", "new-session");
    auth.user = { role: "ADMIN", nickname: "New user" };
    check.resolve({ role: "ADMIN", nickname: "Old user" });
    expect(await pending).toBeNull();
    expect(auth.user.nickname).toBe("New user");
    expect(sessionStorage.getItem("book-agent-token")).toBe("new-session");
  });

  it("does not remove a new token when the previous login's check fails late", async () => {
    const auth = useAuth(), check = deferred();
    api.mockResolvedValueOnce({ tokenValue: "old-session" }).mockReturnValueOnce(check.promise);
    const pending = auth.login({ username: "old", password: "test" });
    await Promise.resolve();
    expect(sessionStorage.getItem("book-agent-token")).toBe("old-session");
    sessionStorage.setItem("book-agent-token", "new-session");
    auth.user = { role: "ADMIN", nickname: "New user" };
    check.reject(new Error("Late check failure"));
    await expect(pending).rejects.toThrow("Late check failure");
    expect(sessionStorage.getItem("book-agent-token")).toBe("new-session");
    expect(auth.user.nickname).toBe("New user");
  });

  it("does not install credentials from a login that finishes after logout", async () => {
    const auth = useAuth(), login = deferred();
    api.mockReturnValueOnce(login.promise).mockResolvedValueOnce(undefined);
    const pending = auth.login({ username: "old", password: "test" });
    await auth.logout();
    login.resolve({ tokenValue: "old-session" });
    await expect(pending).rejects.toThrow("登录状态已变化");
    expect(sessionStorage.getItem("book-agent-token")).toBeNull();
    expect(auth.user).toBeNull();
  });

  it("does not clear a newer login when an earlier logout finishes late", async () => {
    const auth = useAuth(), logout = deferred();
    sessionStorage.setItem("book-agent-token", "old-session");
    api.mockReturnValueOnce(logout.promise);
    const pending = auth.logout();
    api.mockResolvedValueOnce({ tokenValue: "new-session" })
      .mockResolvedValueOnce({ role: "ADMIN", nickname: "New user" });
    await auth.login({ username: "new", password: "test" });
    logout.resolve();
    await pending;
    expect(sessionStorage.getItem("book-agent-token")).toBe("new-session");
    expect(auth.user.nickname).toBe("New user");
  });
});
