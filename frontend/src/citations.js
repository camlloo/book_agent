import { renderMarkdown } from "./markdown";

function plain(markdown) {
  const container = document.createElement("div");
  container.innerHTML = renderMarkdown(markdown);
  return container.textContent.replace(/\s+/g, " ").trim();
}
function terms(text) {
  const words = text.toLowerCase().match(/[a-z0-9_.]+|[\u3400-\u9fff]+/g) || [];
  return new Set(words.flatMap((word) => /^[\u3400-\u9fff]+$/.test(word)
    ? Array.from({ length: Math.max(0, word.length - 1) }, (_, i) => word.slice(i, i + 2))
    : [word]));
}
export function sourceQuote(source, context = "") {
  const container = document.createElement("div");
  container.innerHTML = renderMarkdown(source.excerpt || "");
  const blocks = [...container.querySelectorAll("p,li,pre")].map((el) => el.textContent);
  const candidates = (blocks.length ? blocks : [container.textContent])
    .flatMap((text) => text.match(/[^。！？\n]+[。！？]?/g) || [])
    .map((text) => text.replace(/\s+/g, " ").trim()).filter((text) => text.length >= 6);
  const keywords = terms(context);
  return candidates.map((text, index) => ({ text, index,
    score: [...terms(text)].filter((word) => keywords.has(word)).length / Math.sqrt(text.length),
  })).sort((a, b) => b.score - a.score || a.index - b.index)[0]?.text || plain(source.excerpt || "");
}
export function sourceHref(source, quote = sourceQuote(source)) {
  const location = { fileId: source.fileId || null, page: source.page || null, quote: quote.slice(0, 180) };
  return `/books/${encodeURIComponent(source.bookId)}#cite=${encodeURIComponent(JSON.stringify(location))}`;
}
export function renderCitedAnswer(content, sources, bookTitle) {
  const container = document.createElement("div");
  container.innerHTML = renderMarkdown(content);
  // Only links created from verified API sources may trigger source navigation.
  container.querySelectorAll("[data-book-citation],[data-book-quote]").forEach((el) => {
    el.removeAttribute("data-book-citation"); el.removeAttribute("data-book-quote");
  });
  const available = new Map((sources || []).map((source) => [String(source.citation), source]));
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (walker.nextNode()) {
    if (!walker.currentNode.parentElement.closest("a,code,pre")) nodes.push(walker.currentNode);
  }
  for (const node of nodes) {
    const text = node.textContent;
    const matches = [...text.matchAll(/\[(\d+)\]/g)].filter((match) => available.has(match[1]));
    if (!matches.length) continue;
    const fragment = document.createDocumentFragment();
    let cursor = 0;
    for (const match of matches) {
      fragment.append(document.createTextNode(text.slice(cursor, match.index)));
      const source = available.get(match[1]);
      const quote = sourceQuote(source, plain(content));
      const name = source.fileId ? source.sourceName : (source.bookTitle || source.title || bookTitle || source.sourceName);
      const location = `${name || "原文"}${source.page ? ` · 第 ${source.page} 页` : ""}`;
      const link = document.createElement("a");
      link.href = sourceHref(source, quote);
      link.className = "reader-inline-citation";
      link.dataset.bookCitation = String(source.citation);
      link.dataset.bookQuote = quote;
      link.textContent = `《${location}》`;
      link.title = `${location}${quote ? `：${quote}` : ""}`;
      link.setAttribute("aria-label", `查看出处：${link.title}`);
      fragment.append(link);
      cursor = match.index + match[0].length;
    }
    fragment.append(document.createTextNode(text.slice(cursor)));
    node.replaceWith(fragment);
  }
  return container.innerHTML;
}

function normalized(text) { return text.replace(/\s+/g, "").toLowerCase(); }
export function locateSource(root, source, quote) {
  if (!root) return null;
  const section = source.fileId
    ? [...root.querySelectorAll("[data-file]")].find((el) =>
      el.dataset.file === String(source.fileId) && el.dataset.page === String(source.page || 0))
    : root;
  if (!section) return null;
  const candidates = [...section.querySelectorAll("p,li,pre,h1,h2,h3,.reader-plain-text")];
  const needle = normalized(quote || sourceQuote(source));
  const exact = needle && candidates.find((el) => normalized(el.textContent).includes(needle));
  if (exact) return exact;
  const excerpt = normalized(plain(source.excerpt || ""));
  // Chunks can start or end inside a paragraph; match a substantial visible overlap.
  const overlapping = candidates.find((el) => {
    const text = normalized(el.textContent);
    return text.length >= 12 && (excerpt.includes(text) || text.includes(excerpt) && excerpt.length >= 12);
  });
  return overlapping || section;
}

export function markPlainQuote(element, quote) {
  if (!element?.classList.contains("reader-plain-text") || !quote) return element;
  element.normalize();
  const node = element.firstChild;
  if (!node || node.nodeType !== Node.TEXT_NODE) return element;
  const offsets = [];
  let text = "";
  for (let i = 0; i < node.textContent.length; i++) {
    const char = node.textContent[i];
    if (!/\s/.test(char)) { text += char.toLowerCase(); offsets.push(i); }
  }
  const needle = normalized(quote), start = text.indexOf(needle);
  if (!needle || start < 0) return element;
  const range = document.createRange();
  range.setStart(node, offsets[start]);
  range.setEnd(node, offsets[start + needle.length - 1] + 1);
  const mark = document.createElement("mark");
  range.surroundContents(mark);
  return mark;
}
