import { defineStore } from "pinia";
import { ref } from "vue";
import { api } from "../api";
export const useAuth = defineStore("auth", () => {
  const user = ref(null);
  let generation = 0;
  const currentToken = () => sessionStorage.getItem("book-agent-token");
  const changed = () => new Error("登录状态已变化，请重新登录。");
  async function check() {
    const token = currentToken(), run = generation;
    const value = await api("/api/auth/me");
    if (run !== generation || token !== currentToken()) return null;
    if (!["ADMIN","USER","READER"].includes(value.role)) throw new Error("该账号没有知识库访问权限。");
    user.value = value;
    return value;
  }
  async function login(credentials) {
    const run = ++generation;
    const result = await api("/api/auth/login", {
      method: "POST",
      body: credentials,
    });
    if (run !== generation) throw changed();
    sessionStorage.setItem("book-agent-token", result.tokenValue);
    try {
      const value = await check();
      if (!value || run !== generation || currentToken() !== result.tokenValue) throw changed();
    } catch (e) {
      if (run === generation && currentToken() === result.tokenValue) {
        generation++;
        sessionStorage.removeItem("book-agent-token");
        user.value = null;
        window.dispatchEvent(new Event("book-agent-session-expired"));
      }
      throw e;
    }
  }
  async function logout() {
    const run = ++generation, token = currentToken();
    try {
      await api("/api/auth/logout", { method: "POST" });
    } finally {
      if (run === generation && (currentToken() === token || currentToken() === null)) {
        sessionStorage.removeItem("book-agent-token");
        user.value = null;
        window.dispatchEvent(new Event("book-agent-session-expired"));
      }
    }
  }
  return { user, check, login, logout };
});
