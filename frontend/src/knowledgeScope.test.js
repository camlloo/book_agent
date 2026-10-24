import { describe, expect, it } from "vitest";
import { buildScopeTree, checkedBookIds, readerScopeKey, sameKnowledgeScope } from "./knowledgeScope";

const book = (id, categoryId, status = "READY") => ({ id, title: `Book ${id}`, categoryId, revision: 2,
  indexedRevision: status === "READY" ? 2 : 1, indexStatus: status, sourceMode: "ONLINE" });
describe("knowledge folder scope", () => {
  it("groups Books in their category and descendants, with accurate ready counts", () => {
    const result = buildScopeTree({ categories: [{ id: 1, name: "编程" }, { id: 2, name: "Python", parentId: 1 }],
      books: [book(10, 1), book(11, 2), book(12, 2, "RUNNING"), book(13, null)] });
    const folder = result.tree.find((item) => item.key === "category:1");
    expect(folder.readyCount).toBe(2); expect(folder.bookCount).toBe(3);
    expect(folder.children.find((item) => item.key === "category:2").children).toHaveLength(2);
    expect(result.tree.find((item) => item.key === "uncategorized").readyCount).toBe(1);
    expect(result.books.find((item) => item.bookId === 11).searchText).toContain("编程 python");
    expect(result.books.find((item) => item.bookId === 12).disabled).toBe(true);
  });
  it("excludes the current Book and never selects unready leaves or folder IDs", () => {
    const result = buildScopeTree({ categories: [{ id: 1, name: "编程" }], books: [book(10, 1), book(11, 1), book(12, 1, "FAILED")] }, [10]);
    expect(result.books.map((node) => node.bookId)).toEqual([11, 12]);
    expect(checkedBookIds([...result.tree, ...result.books, ...result.books])).toEqual([11]);
  });
  it("keeps same-name folders distinct and tolerates a legacy category cycle", () => {
    const result = buildScopeTree({ categories: [{ id: 1, name: "编程", parentId: 2 }, { id: 2, name: "编程", parentId: 1 }], books: [book(10, 1), book(11, 2)] });
    expect(result.tree).toHaveLength(2);
    expect(result.tree.map((item) => item.key)).toEqual(["category:1", "category:2"]);
  });
  it("isolates current Book, public multi-Book and personal multi-Book conversations", () => {
    expect(readerScopeKey(10)).toBe("book:10");
    expect(readerScopeKey(10, [12, 11, 11], true)).toBe(readerScopeKey(99, [11, 12], true));
    expect(readerScopeKey(10, [11, 12], true)).not.toBe(readerScopeKey(10, [11, 12], false));
    expect(readerScopeKey(10, [10], true)).not.toBe(readerScopeKey(10));
    expect(sameKnowledgeScope({ publicOnly: true, bookIds: [12, 11] }, [11, 12], true)).toBe(true);
    expect(sameKnowledgeScope({ publicOnly: false, bookIds: [11, 12] }, [11, 12], true)).toBe(false);
  });
});
