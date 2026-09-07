export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function apiBase(pageUrl) {
  // /kreader-sync/web/ and /web/ both work; never trust a query-string server URL.
  return new URL("../", pageUrl).href;
}

export class Api {
  constructor(base, storage = globalThis.localStorage, locks = globalThis.navigator?.locks, transport = (...args) => globalThis.fetch(...args)) {
    this.base = base;
    this.storage = storage;
    this.locks = locks;
    this.transport = transport;
    this.key = "kreader-session:" + base;
    this.refreshing = null;
  }
  session() {
    try { return JSON.parse(this.storage.getItem(this.key)) || null; }
    catch { return null; }
  }
  save(session) { this.storage.setItem(this.key, JSON.stringify(session)); }
  clear() { this.storage.removeItem(this.key); }
  async raw(path, { token, body, keepalive = false } = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 25000);
    try {
      const response = await this.transport(new URL(path, this.base), {
        method: body === undefined ? "GET" : "POST",
        headers: { ...(token ? { Authorization: "Bearer " + token } : {}),
          ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "omit", cache: "no-store", redirect: "error", signal: controller.signal, keepalive,
      });
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        throw new ApiError(response.status, error.code || "http_error", error.message || "请求失败");
      }
      if (response.status === 204) return null;
      // Cover stalled response bodies with the same timeout as the connection.
      const bytes = await response.arrayBuffer();
      return new Response(bytes, { status: response.status, headers: response.headers });
    } catch (error) {
      if (error.name === "AbortError") throw new Error("连接超时，进度会保留并稍后重试。");
      throw error;
    } finally { clearTimeout(timeout); }
  }
  async login(email, password) {
    const deviceId = crypto.randomUUID();
    const response = await this.raw("v1/auth/login", { body: {
      email: email.trim(), password, deviceId, deviceName: "KReader Web",
    } });
    const session = { ...await response.json(), deviceId };
    this.save(session);
    return session;
  }
  async refresh(expectedUser, failedToken) {
    if (this.refreshing) return this.refreshing;
    const task = async () => {
      const session = this.session();
      if (!session || session.user.id !== expectedUser) throw new ApiError(401, "session_changed", "请重新登录。");
      if (failedToken && session.accessToken !== failedToken) return session;
      if (!failedToken && session.accessTokenExpiresAt > Date.now() + 60000) return session;
      try {
        const response = await this.raw("v1/auth/refresh", { body: {
          refreshToken: session.refreshToken, deviceId: session.deviceId,
        } });
        const renewed = { ...await response.json(), deviceId: session.deviceId };
        if (this.session()?.refreshToken !== session.refreshToken) throw new ApiError(401, "session_changed", "登录状态已更改。");
        this.save(renewed);
        return renewed;
      } catch (error) {
        if (error.status === 401 && this.session()?.refreshToken === session.refreshToken) this.clear();
        throw error;
      }
    };
    this.refreshing = this.locks
      ? this.locks.request("kreader-auth:" + this.base, task) : task();
    try { return await this.refreshing; }
    finally { this.refreshing = null; }
  }
  async request(path, userId, body) {
    let session = this.session();
    if (!session || session.user.id !== userId) throw new ApiError(401, "session_changed", "请重新登录。");
    if (session.accessTokenExpiresAt <= Date.now() + 60000) session = await this.refresh(userId);
    const send = current => {
      if (current.user.id !== userId || this.session()?.user.id !== userId)
        throw new ApiError(401, "session_changed", "登录状态已更改。");
      return this.raw(path, { token: current.accessToken,
        body: path === "v1/sync/push" ? { ...body, deviceId: current.deviceId }
          : path === "v1/auth/logout" ? { refreshToken: current.refreshToken } : body });
    };
    let response;
    try { response = await send(session); }
    catch (error) {
      if (error.status !== 401) throw error;
      session = await this.refresh(userId, session.accessToken);
      response = await send(session);
    }
    if (this.session()?.user.id !== userId) throw new ApiError(401, "session_changed", "登录状态已更改。");
    return response;
  }
  async json(path, userId, body) {
    return (await this.request(path, userId, body))?.json();
  }
  async logout() {
    const session = this.session();
    if (!session) return;
    try {
      await this.request("v1/auth/logout", session.user.id, { refreshToken: session.refreshToken });
    } finally {
      if (this.session()?.deviceId === session.deviceId) this.clear();
    }
  }
}

export function friendlyError(error) {
  const messages = {
    invalid_credentials: "邮箱或密码不正确，请使用 App 中的同步账号。",
    invalid_refresh_token: "登录已过期，请重新登录。未同步的进度仍保留在此浏览器。",
    unauthorized: "登录已过期，请重新登录。",
    account_disabled: "此账号已停用。",
    rate_limited: "尝试次数较多，请稍后重试。",
    bundle_not_found: "书籍正文尚未就绪，请先在 App 中完成同步。",
    book_deleted: "这本书已在其他设备删除。",
    clock_skew: "设备时间异常，请校准系统时间后重试。",
  };
  return messages[error.code] || (error instanceof TypeError ? "暂时无法连接服务器，请检查网络后重试。" : error.message) || "操作失败，请重试。";
}
