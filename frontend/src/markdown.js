import { marked } from "marked";
import DOMPurify from "dompurify";
marked.setOptions({ gfm: true, breaks: false });
export function renderMarkdown(content) {
  return DOMPurify.sanitize(marked.parse(content || ""), {
    FORBID_TAGS: ["style", "iframe", "form", "input"],
    FORBID_ATTR: ["style"],
  });
}
