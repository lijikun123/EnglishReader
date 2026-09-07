// A transaction keeps the sync cursor, remote snapshot and pending writes consistent.
export function emptyState() {
  return { cursor: 0, books: {}, positions: {}, pending: {}, errors: {}, serverOffset: 0, lastLocalTime: 0 };
}

export class AccountStore {
  constructor(scope, indexedDB = globalThis.indexedDB) {
    this.scope = scope;
    this.ready = new Promise((resolve, reject) => {
      const request = indexedDB.open("kreader-web", 1);
      request.onupgradeneeded = () => request.result.createObjectStore("accounts");
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(new Error("浏览器无法保存阅读进度，请允许此网站使用本地存储。"));
    });
  }
  async transact(update) {
    const db = await this.ready;
    return new Promise((resolve, reject) => {
      const tx = db.transaction("accounts", "readwrite");
      const store = tx.objectStore("accounts");
      const request = store.get(this.scope);
      let state;
      request.onsuccess = () => {
        try {
          state = request.result || emptyState();
          update(state); // Synchronous callback: no network awaits inside an IDB transaction.
          store.put(state, this.scope);
        } catch (error) { reject(error); tx.abort(); }
      };
      tx.oncomplete = () => resolve(state);
      tx.onerror = tx.onabort = () => reject(tx.error || new Error("阅读进度保存失败，请检查浏览器存储空间。"));
    });
  }
  async read() {
    const db = await this.ready;
    return new Promise((resolve, reject) => {
      const request = db.transaction("accounts").objectStore("accounts").get(this.scope);
      request.onsuccess = () => resolve(request.result || emptyState());
      request.onerror = () => reject(request.error);
    });
  }
}

export function accountScope(baseUrl, userId) {
  return baseUrl + "|" + userId;
}
