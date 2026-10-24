import { describe, it, expect } from "vitest";
import { renderCitedAnswer, sourceQuote, sourceHref, locateSource, markPlainQuote } from "./citations";
const source = { citation: 1, bookId: 30, sourceName: "Book 正文", fileId: null,
  excerpt: "## 记账\n\n使用整数分保存金额，避免浮点误差。\n\n午餐 18.50 元和交通 3.00 元，合计为 21.50 元。" };
describe("Inline source citations", () => {
  it("links only used sources and retains safe Markdown", () => {
    const html = renderCitedAnswer('合计是 **21.50 元**。[1]\n<script>bad()</script>',
      [source, { ...source, citation: 2, sourceName: "未引用资料" }], "Python 入门与实践");
    const root = document.createElement("div"); root.innerHTML = html;
    expect(root.querySelectorAll("a[data-book-citation]")).toHaveLength(1);
    expect(root.textContent).toContain("Python 入门与实践");
    expect(root.textContent).toContain("21.50");
    expect(root.textContent).not.toContain("未引用资料");
    expect(root.querySelector("a[data-book-citation]").textContent).toBe("《Python 入门与实践》");
    expect(root.querySelector("a[data-book-citation]").title).toContain("21.50 元");
    expect(root.querySelector("script")).toBeNull();
    expect(root.querySelector("details")).toBeNull();
  });
  it("does not rewrite code, existing links, or unknown markers", () => {
    const root = document.createElement("div");
    root.innerHTML = renderCitedAnswer('`values[1]`\n\n```python\nx[1]\n```\n\n[文档](https://example.com) [9]', [source], "Python");
    expect(root.querySelectorAll("[data-book-citation]")).toHaveLength(0);
    expect(root.querySelector("code").textContent).toBe("values[1]");
    expect(root.textContent).toContain("[9]");
  });
  it("uses matching original sentences, preserves file/page and shareable location", () => {
    const file = { ...source, fileId: 6, sourceName: '学习"资料.pdf', page: 2 };
    const quote = sourceQuote(file, "合计为 21.50 元");
    expect(quote).toContain("21.50 元");
    const link = sourceHref(file, quote);
    const location = JSON.parse(decodeURIComponent(link.split("#cite=")[1]));
    expect(location).toMatchObject({ fileId: 6, page: 2, quote });
    const root = document.createElement("div");
    root.innerHTML = '<section data-file="6" data-page="1"><p>错误页</p></section><section data-file="6" data-page="2"><p>使用整数分保存金额，避免浮点误差。</p><p>午餐 18.50 元和交通 3.00 元，合计为 21.50 元。</p></section>';
    expect(locateSource(root, file, quote).textContent).toBe(quote);
  });
  it("escapes source names and removes model-forged navigation attributes", () => {
    const html = renderCitedAnswer('<a data-book-citation="1" data-book-quote="伪造">假来源</a> [1]',
      [{ ...source, sourceName: '<img src=x onerror=bad()>', fileId: 6 }], "Python");
    const root = document.createElement("div"); root.innerHTML = html;
    expect(root.querySelectorAll("[data-book-citation]")).toHaveLength(1);
    expect(root.querySelector("img")).toBeNull();
    expect(root.firstElementChild.querySelector("a").hasAttribute("data-book-citation")).toBe(false);
  });
  it("highlights the exact sentence within a long PDF page without losing text", () => {
    const root = document.createElement("div");
    root.className = "reader-plain-text";
    root.textContent = "前面的说明。\n午餐 18.50 元，\n合计 21.50 元。\n后面的说明。";
    const before = root.textContent;
    const target = markPlainQuote(root, "午餐 18.50 元，合计 21.50 元。");
    expect(target.tagName).toBe("MARK");
    expect(target.textContent).toContain("21.50");
    expect(root.textContent).toBe(before);
  });
});
