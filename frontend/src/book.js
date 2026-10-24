export const indexStatus = {
  QUEUED: "等待处理",
  RUNNING: "解析与向量化中",
  READY: "索引已就绪",
  FAILED: "索引失败",
  DELETING: "正在删除",
  DELETED: "已删除",
};
export const fileSize = (size) =>
  size < 1024
    ? size + " B"
    : size < 1024 * 1024
      ? (size / 1024).toFixed(1) + " KB"
      : (size / 1024 / 1024).toFixed(1) + " MB";
