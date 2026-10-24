import { describe, it, expect } from "vitest";
import { renderMarkdown } from "./markdown";
describe("Markdown safety", () => {
  it("keeps Markdown structure while removing executable markup", () => {
    const html = renderMarkdown(
      "# 标题\n<script>alert(1)</script><img src=x onerror=alert(2)><iframe src=x></iframe>\n[攻击](javascript:alert(3))",
    );
    expect(html).toContain("<h1>标题</h1>");
    expect(html).not.toMatch(/script|onerror|iframe|javascript:/i);
  });
  it("supports images and code without allowing styles or forms", () => {
    expect(renderMarkdown("![配图](https://example.com/image.png)")).toContain(
      "<img",
    );
    expect(
      renderMarkdown('<form><input></form><p style="color:red">正文</p>'),
    ).not.toMatch(/form|input|style=/);
  });
});
