export function positionFor(state, bookId) {
  const pending = state.pending[bookId];
  const remote = state.positions[bookId];
  // Equal timestamps must be settled by the server's device-ID tie breaker.
  return pending && (!remote || pending.occurredAt >= remote.occurredAt)
    ? pending.payload : remote?.payload;
}

export function applyPage(state, page, now = Date.now()) {
  for (const change of page.changes) {
    if (change.cursor <= state.cursor) continue;
    const id = change.entityId;
    const payload = change.payload;
    if (change.kind === "book.upsert") {
      const old = state.books[id];
      state.books[id] = { ...payload, ready: !!old?.ready &&
        old.contentSha256 === payload.contentSha256 && old.contentRevision === payload.contentRevision };
    } else if (change.kind === "book.bundle_ready" && state.books[id]) {
      const book = state.books[id];
      if (book.contentSha256 === payload.contentSha256 && book.contentRevision === payload.contentRevision) book.ready = true;
    } else if (change.kind === "book.delete") {
      delete state.books[id];
      delete state.positions[id];
      delete state.pending[id];
      delete state.errors[id];
    } else if (change.kind === "progress.upsert") {
      state.positions[id] = { payload, occurredAt: change.occurredAt, revision: change.revision };
    }
    // Unknown and stale-bundle events are deliberate no-ops, but advance the cursor.
    state.cursor = change.cursor;
  }
  if (page.nextCursor < state.cursor) throw new Error("服务器返回了无效的同步位置。");
  state.cursor = page.nextCursor;
  state.serverOffset = page.serverNow - now;
}

export class SyncEngine {
  constructor(api, store, userId, locks = globalThis.navigator?.locks) {
    this.api = api;
    this.store = store;
    this.userId = userId;
    this.locks = locks;
    this.running = null;
  }
  async queue(payload) {
    return this.store.transact(state => {
      if (!state.books[payload.bookId]) throw new Error("这本书已从书架移除。");
      const now = Date.now() + state.serverOffset;
      // Recover after correcting a wildly wrong device clock.
      const lastLocalTime = state.lastLocalTime > now + 60000 ? 0 : state.lastLocalTime;
      const occurredAt = Math.max(now, lastLocalTime + 1);
      state.lastLocalTime = occurredAt;
      state.pending[payload.bookId] = { mutationId: crypto.randomUUID(), kind: "progress.upsert", occurredAt, payload };
      delete state.errors[payload.bookId];
    });
  }
  async pull() {
    let hasMore = true;
    while (hasMore) {
      const before = await this.store.read();
      const page = await this.api.json("v1/sync/pull?cursor=" + before.cursor + "&limit=200", this.userId);
      if (page.hasMore && page.nextCursor <= before.cursor) throw new Error("同步暂时无法继续，请稍后重试。");
      await this.store.transact(state => applyPage(state, page));
      hasMore = page.hasMore;
    }
  }
  async runOnce() {
    // Fetch tombstones before sending pending progress; never resurrect deleted books.
    await this.pull();
    const state = await this.store.read();
    const mutations = Object.values(state.pending).filter(m => !state.errors[m.payload.bookId]).slice(0, 100);
    if (mutations.length) {
      const result = await this.api.json("v1/sync/push", this.userId, { mutations });
      const done = new Set([...result.acceptedMutationIds, ...result.duplicateMutationIds]);
      // Keep durable pending writes until the authoritative read succeeds. A dropped
      // response is retried with the same mutation ID, including after a page reload.
      await this.pull();
      await this.store.transact(current => {
        for (const mutation of mutations) {
          const id = mutation.payload.bookId;
          // Reading can continue while the network is busy: never drop a newer write.
          if (current.pending[id]?.mutationId !== mutation.mutationId) continue;
          if (done.has(mutation.mutationId)) delete current.pending[id];
          const rejection = result.rejected.find(r => r.mutationId === mutation.mutationId);
          if (rejection) {
            if (["book_deleted", "book_not_found"].includes(rejection.code)) {
              delete current.books[id]; delete current.positions[id]; delete current.pending[id];
            } else current.errors[id] = rejection;
          }
        }
      });
      // Accepted includes losing LWW mutations: use the pulled position, never assume ours won.
    }
    return this.store.read();
  }
  sync() {
    if (this.running) return this.running;
    const run = () => this.runOnce();
    this.running = this.locks ? this.locks.request("kreader-sync:" + this.store.scope, run) : run();
    this.running = this.running.finally(() => { this.running = null; });
    return this.running;
  }
  async retryRejected() {
    await this.store.transact(state => {
      for (const [id, error] of Object.entries(state.errors)) {
        if (error.code === "clock_skew" && state.pending[id]) {
          state.pending[id].mutationId = crypto.randomUUID();
          state.pending[id].occurredAt = Date.now() + state.serverOffset;
          state.lastLocalTime = state.pending[id].occurredAt;
        }
      }
      state.errors = {};
    });
  }
}

export async function loadBundle(api, userId, book) {
  const response = await api.request("v1/books/" + encodeURIComponent(book.bookId) + "/bundle", userId);
  const raw = await response.arrayBuffer();
  const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", raw))]
    .map(n => n.toString(16).padStart(2, "0")).join("");
  if (hash !== book.contentSha256.toLowerCase() || raw.byteLength !== book.contentBytes)
    throw new Error("书籍内容已变化或校验失败，请同步书架后重新打开。");
  const bundle = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(raw));
  if (bundle.schemaVersion !== 1 || bundle.format !== book.format ||
      !["TXT", "MARKDOWN", "EPUB"].includes(bundle.format) || typeof bundle.content !== "string" ||
      !Array.isArray(bundle.chapters) || !Array.isArray(bundle.toc))
    throw new Error("暂不支持此书籍内容格式。");
  const indices = new Set();
  for (const chapter of bundle.chapters) {
    if (!Number.isInteger(chapter.chapterIndex) || chapter.chapterIndex < 0 ||
        indices.has(chapter.chapterIndex) || typeof chapter.content !== "string" || typeof chapter.title !== "string")
      throw new Error("书籍章节数据无效。");
    indices.add(chapter.chapterIndex);
  }
  if (bundle.format === "EPUB" && !bundle.chapters.length) throw new Error("这本 EPUB 没有可阅读的章节。");
  return bundle;
}
