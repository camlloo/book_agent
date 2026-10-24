import { describe, it, expect, vi } from "vitest";
import { createReaderSession } from "./readerSession";

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function missing() { return Object.assign(new Error("Not published"), { code: 404 }); }
function document(id, title = `Book ${id}`) {
  return { book: { id, title, content: `Body ${id}` }, sections: [{ text: `Section ${id}` }] };
}
function fixture() {
  const calls = [];
  const request = vi.fn((path) => {
    const call = { path, ...deferred() };
    calls.push(call);
    return call.promise;
  });
  const state = { document: null, directory: [], total: 0 }, conversations = {};
  const token = { present: true };
  const session = createReaderSession({ request, hasToken: () => token.present, state, conversations });
  return { session, state, conversations, token, calls };
}
async function privateFallback(read, calls, index) {
  calls[index].reject(missing());
  await Promise.resolve();
  expect(calls[index + 1].path).toMatch(/^\/api\/books\/\d+\/read$/);
}

describe("Reader API session boundary", () => {
  it("does not let a late private read change a newer public Book or its scope", async () => {
    const { session, state, calls } = fixture();
    const first = session.beginRead("11");
    await privateFallback(first, calls, 0);
    expect(calls[1].path).toBe("/api/books/11/read");
    const second = session.beginRead("22");
    calls[2].resolve(document(22));
    await second.promise;
    calls[1].resolve(document(11));
    expect(await first.promise).toBeNull();
    expect(state.document).toEqual({ ...document(22), privateAccess: false });
  });

  it("does not let a late public read remove the newer private Book's scope", async () => {
    const { session, state, calls } = fixture();
    const first = session.beginRead("11");
    const second = session.beginRead("22");
    await privateFallback(second, calls, 1);
    calls[2].resolve(document(22));
    await second.promise;
    calls[0].resolve(document(11));
    expect(await first.promise).toBeNull();
    expect(state.document).toEqual({ ...document(22), privateAccess: true });
  });

  it("does not start a private fallback for a superseded public request", async () => {
    const { session, state, calls } = fixture();
    const first = session.beginRead("11"), second = session.beginRead("22");
    calls[0].reject(missing());
    expect(await first.promise).toBeNull();
    expect(calls.map((call) => call.path)).toEqual([
      "/api/blog/books/11/read", "/api/blog/books/22/read",
    ]);
    calls[1].resolve(document(22));
    await second.promise;
    expect(state.document.book.id).toBe(22);
  });

  it("discards a private read and catalog that finish after credential revocation", async () => {
    const { session, state, token, calls } = fixture();
    const read = session.beginRead("11");
    await privateFallback(read, calls, 0);
    const directory = session.beginDirectory("pageNum=1", { privateAccess: true });
    const ask = session.operation(), poll = session.operation({ document: true });
    token.present = false;
    session.invalidate();
    calls[1].resolve(document(11));
    calls[2].resolve({ list: [{ id: 11, title: "Private title" }], total: 1 });
    expect(await read.promise).toBeNull();
    expect(await directory.promise).toBeNull();
    expect(ask.current()).toBe(false);
    expect(poll.current()).toBe(false);
    expect(state).toEqual({ document: null, directory: [], total: 0 });
  });

  it("erases private body, sections, catalog, chat and draft while keeping public conversations", async () => {
    const { session, state, conversations, calls } = fixture();
    const publicChat = session.conversation("book:22");
    publicChat.messages.push({ role: "assistant", content: "Public answer" });
    const read = session.beginRead("11");
    await privateFallback(read, calls, 0);
    calls[1].resolve(document(11));
    await read.promise;
    const privateChat = session.conversation("book:11");
    privateChat.question = "Private draft";
    privateChat.messages.push({ role: "assistant", content: "Private answer" });
    privateChat.busy = true;
    const catalog = session.beginDirectory("pageNum=1", { privateAccess: true });
    calls[2].resolve({ list: [{ id: 11, title: "Private title" }], total: 1 });
    await catalog.promise;
    expect(session.invalidate()).toEqual({ privateDocument: true });
    expect(state).toEqual({ document: null, directory: [], total: 0 });
    expect(conversations["book:11"]).toBeUndefined();
    expect(privateChat).toMatchObject({ question: "", messages: [], error: "", busy: false });
    expect(session.conversation("book:22")).toBe(publicChat);
    expect(publicChat.messages[0].content).toBe("Public answer");
  });

  it("keeps an already displayed public Book and public directory on expiry", async () => {
    const { session, state, calls } = fixture();
    const read = session.beginRead("22");
    calls[0].resolve(document(22));
    await read.promise;
    const catalog = session.beginDirectory("pageNum=1");
    calls[1].resolve({ list: [{ id: 22, title: "Public title" }], total: 1 });
    await catalog.promise;
    const before = state.document;
    expect(session.invalidate()).toEqual({ privateDocument: false });
    expect(state.document).toBe(before);
    expect(state.directory[0].title).toBe("Public title");
  });

  it("reloads revoked private content only through public access, even if credentials reappear", async () => {
    const { session, state, calls } = fixture();
    session.invalidate();
    const read = session.beginRead("11", { publicOnly: true });
    calls[0].reject(missing());
    await expect(read.promise).rejects.toMatchObject({ code: 404 });
    expect(calls).toHaveLength(1);
    expect(state.document).toBeNull();
  });

  it("preserves per-Book conversation continuation across navigation but cancels it on expiry", async () => {
    const { session, calls } = fixture();
    const first = session.beginRead("11");
    calls[0].resolve(document(11));
    await first.promise;
    const chat = session.conversation("book:11");
    chat.busy = true;
    chat.messages.push({ role: "user", content: "Continue Book 11" });
    const ask = session.operation(), download = session.operation({ document: true });
    const second = session.beginRead("22");
    calls[1].resolve(document(22));
    await second.promise;
    expect(session.conversation("book:22").messages).toEqual([]);
    expect(session.conversation("book:11")).toBe(chat);
    expect(ask.current()).toBe(true);
    expect(download.current()).toBe(false);
    session.invalidate();
    expect(ask.current()).toBe(false);
    expect(chat.busy).toBe(false);
    expect(chat.messages[0].failed).toBe(true);
    expect(chat.question).toBe("Continue Book 11");
  });

  it("commits only the latest directory response", async () => {
    const { session, state, calls } = fixture();
    const first = session.beginDirectory("keyword=old"), second = session.beginDirectory("keyword=new");
    calls[1].resolve({ list: [{ id: 22 }], total: 1 });
    await second.promise;
    calls[0].resolve({ list: [{ id: 11 }], total: 1 });
    expect(await first.promise).toBeNull();
    expect(state.directory).toEqual([{ id: 22 }]);
  });

  it("ignores a legacy resolver that completes after navigation", async () => {
    const { session, state, calls } = fixture();
    const legacy = session.beginRead("7", { legacy: true });
    const next = session.beginRead("22");
    calls[0].resolve({ bookId: 11 });
    expect(await legacy.promise).toBeNull();
    calls[1].resolve(document(22));
    await next.promise;
    expect(state.document.book.id).toBe(22);
  });
});
