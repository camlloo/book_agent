import { ElMessage } from "element-plus";
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/message-box/style/css";
export async function api(path, options = {}) {
  const headers = { ...options.headers };
  const token = sessionStorage.getItem("book-agent-token");
  if (token) headers.Authorization = token;
  let body = options.body;
  if (body && !(body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch((import.meta.env.VITE_API_BASE || "") + path, {
      ...options,
      headers,
      body,
    });
  } catch {
    throw new Error("无法连接服务，请检查后端是否启动后重试。");
  }
  let envelope;
  try {
    envelope = await response.json();
  } catch {
    throw new Error("服务返回异常，请稍后重试。");
  }
  if (!response.ok || envelope.code !== 200) {
    const code = envelope.code || response.status;
    // A delayed response from an older login must not expire newer credentials.
    if (code === 401 && token && sessionStorage.getItem("book-agent-token") === token) {
      sessionStorage.removeItem("book-agent-token");
      window.dispatchEvent(new Event("book-agent-session-expired"));
    }
    const error = new Error(envelope.message || "请求失败，请重试。");
    error.code = code;
    throw error;
  }
  return envelope.data;
}
export function query(values) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value !== "" && value != null) params.set(key, value);
  return params.toString();
}
export async function action(work) {
  try {
    return await work();
  } catch (e) {
    ElMessage.error(e.message);
    return null;
  }
}
export const statusText = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  OFFLINE: "已下线",
  QUEUED: "排队中",
  RUNNING: "生成中",
  WAITING_REVIEW: "待你确认",
  COMPLETED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
};
export const stageText = { OUTLINE: "提纲", DRAFT: "扩写", POLISH: "润色" };
export const date = (value) =>
  value ? String(value).replace("T", " ").slice(0, 16) : "—";
export function flatten(nodes, depth = 0) {
  return nodes.flatMap((n) => [
    { ...n, label: "　".repeat(depth) + n.name },
    ...flatten(n.children || [], depth + 1),
  ]);
}

export async function downloadFile(path, filename) {
  const token=sessionStorage.getItem("book-agent-token");
  const response=await fetch((import.meta.env.VITE_API_BASE||"")+path,{headers:token?{Authorization:token}:{}});
  if(!response.ok || (response.headers.get("content-type")||"").includes("application/json")) {
    let value={};try{value=await response.json();}catch{}
    if((value.code===401||response.status===401)&&token===sessionStorage.getItem("book-agent-token")){sessionStorage.removeItem("book-agent-token");window.dispatchEvent(new Event("book-agent-session-expired"));}
    throw new Error(value.message||"下载失败，请重试。");
  }
  const blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement("a");link.href=url;link.download=filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),30000);
}
