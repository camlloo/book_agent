export function scopeIds(values = []) {
  return [...new Set(values.filter((id) => Number.isSafeInteger(id) && id > 0))].sort((a, b) => a - b);
}

export function readerScopeKey(bookId, selectedIds = [], publicOnly = true) {
  const ids = scopeIds(selectedIds);
  return ids.length ? `scope:${publicOnly ? "public" : "personal"}:${ids.join(",")}` : (bookId ? `book:${bookId}` : "all");
}

export function sameKnowledgeScope(scope, ids, publicOnly) {
  return scope?.publicOnly === publicOnly && JSON.stringify(scopeIds(scope.bookIds)) === JSON.stringify(scopeIds(ids));
}

export function buildScopeTree(catalogue, excludedIds = []) {
  const excluded = new Set(excludedIds);
  const folders = new Map((catalogue.categories || []).map((folder) => [folder.id, {
    key: `category:${folder.id}`, label: folder.name, parentId: folder.parentId, kind: "folder", children: [],
  }]));
  const roots = [];
  const loose = { key: "uncategorized", label: "未分类", kind: "folder", children: [] };
  const leaves = [];
  for (const book of catalogue.books || []) {
    if (excluded.has(book.id)) continue;
    const ready = book.indexStatus === "READY" && book.indexedRevision === book.revision;
    const node = { key: `book:${book.id}`, bookId: book.id, label: book.title, kind: "book", disabled: !ready, ready,
      sourceMode: book.sourceMode, status: ready ? "" : book.indexStatus === "FAILED" ? "索引失败" : "索引未就绪" };
    (folders.get(book.categoryId) || loose).children.push(node);
    leaves.push(node);
  }
  for (const [id, folder] of folders) {
    let parent = folders.get(folder.parentId), current = parent;
    const seen = new Set([id]);
    while (current) {
      const key = Number(current.key.split(":")[1]);
      if (seen.has(key)) { parent = null; break; }
      seen.add(key); current = folders.get(current.parentId);
    }
    (parent ? parent.children : roots).push(folder);
  }
  if (loose.children.length) roots.push(loose);
  function annotate(node, ancestors = []) {
    node.searchText = [...ancestors, node.label].join(" ").toLowerCase();
    if (node.kind === "book") { node.bookCount = 1; node.readyCount = node.ready ? 1 : 0; return; }
    node.children.forEach((child) => annotate(child, [...ancestors, node.label]));
    node.bookCount = node.children.reduce((sum, child) => sum + child.bookCount, 0);
    node.readyCount = node.children.reduce((sum, child) => sum + child.readyCount, 0);
    node.disabled = node.readyCount === 0;
  }
  roots.forEach((root) => annotate(root));
  return { tree: roots, books: leaves };
}

export function checkedBookIds(nodes = []) {
  return scopeIds(nodes.filter((node) => node.kind === "book" && !node.disabled).map((node) => node.bookId));
}
