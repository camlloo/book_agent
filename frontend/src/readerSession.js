// A Reader session owns access scope and discards work from older reads or credentials.
export function createReaderSession({ request, hasToken, state, conversations }) {
  let epoch = 0, readGeneration = 0, directoryGeneration = 0;
  let directoryPrivate = false;

  function operation({ document = false } = {}) {
    const started = epoch, read = readGeneration;
    return { current: () => started === epoch && (!document || read === readGeneration) };
  }

  function conversation(key, privateScope = false) {
    const chat = conversations[key] ||= {
      question: "", messages: [], busy: false, error: "", privateScope: false,
    };
    if (privateScope) chat.privateScope = true;
    return chat;
  }

  function beginRead(bookId, { legacy = false, publicOnly = false } = {}) {
    readGeneration++;
    const work = operation({ document: true });
    state.document = null;
    work.promise = (async () => {
      if (!bookId) return null;
      try {
        if (legacy) {
          const result = await request(`/api/blog/articles/${bookId}/resolve`);
          return work.current() ? { redirectBookId: result.bookId } : null;
        }
        let data, privateAccess = false;
        try {
          data = await request(`/api/blog/books/${bookId}/read`);
        } catch (error) {
          if (!work.current()) return null;
          if (publicOnly || error.code !== 404 || !hasToken()) throw error;
          data = await request(`/api/books/${bookId}/read`);
          privateAccess = true;
        }
        if (!work.current()) return null;
        const document = { book: data.book, sections: data.sections || [], privateAccess };
        state.document = document;
        if (privateAccess) conversation(`book:${bookId}`, true);
        return document;
      } catch (error) {
        if (work.current()) throw error;
        return null;
      }
    })();
    return work;
  }

  function beginDirectory(parameters, { privateAccess = false, append = false } = {}) {
    const run = ++directoryGeneration, session = operation();
    const work = { current: () => session.current() && run === directoryGeneration };
    const endpoint = privateAccess ? "/api/books" : "/api/blog/books";
    work.promise = (async () => {
      try {
        const data = await request(`${endpoint}?${parameters}`);
        if (!work.current()) return null;
        state.directory = append ? [...state.directory, ...data.list] : data.list;
        state.total = data.total;
        directoryPrivate = privateAccess;
        return data;
      } catch (error) {
        if (work.current()) throw error;
        return null;
      }
    })();
    return work;
  }

  function invalidate() {
    epoch++;
    readGeneration++;
    directoryGeneration++;
    const privateDocument = !!state.document?.privateAccess;
    if (privateDocument) state.document = null;
    if (directoryPrivate) {
      state.directory = [];
      state.total = 0;
      directoryPrivate = false;
    }
    for (const [key, chat] of Object.entries(conversations)) {
      if (chat.privateScope) {
        chat.question = "";
        chat.messages = [];
        chat.error = "";
        delete conversations[key];
      } else if (chat.busy) {
        const pending = chat.messages.at(-1);
        if (pending?.role === "user") {
          pending.failed = true;
          chat.question ||= pending.content;
        }
      }
      chat.busy = false;
    }
    return { privateDocument };
  }

  return { beginRead, beginDirectory, operation, conversation, invalidate };
}
