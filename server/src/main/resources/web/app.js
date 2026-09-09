import { Api, apiBase, friendlyError } from "./api.js";
import { AccountStore, accountScope } from "./store.js";
import { SyncEngine, positionFor, loadBundle } from "./sync.js";
import { chaptersFor, progressAt, paragraphOffset, paragraphsFor, learningCacheKey,
  learningWindow, renderLearningText, restoreOffset, visibleOffset } from "./reader.js";

const $ = id => document.getElementById(id);
const api = new Api(apiBase(location.href));
let session, engine, state, active = null, syncTimer, saveTimer, learningTimer, saving = Promise.resolve();
let restoring = false, userScroll = false, generation = 0, openGeneration = 0, busySync = false;
let savesInFlight = 0, unsaved = null;
let aiPace = Promise.resolve(), nextAiRequestAt = 0, learningRetryAfter = 0;
const AI_REQUEST_SPACING_MS = 1_150;
let statusText = "正在加载书架…";
let aiStatus = { enabled:false, model:"", cacheVersion:"" };
let learningPreferences = { bilingual:false, phrases:false };
try { Object.assign(learningPreferences, JSON.parse(localStorage.getItem("kreader-learning"))); } catch {}
let preferences = { fontSize: 22, lineHeight: "1.9", width: "760", theme: "light" };
try { Object.assign(preferences, JSON.parse(localStorage.getItem("kreader-layout"))); } catch {}
const scroller = $("reading-scroll"), content = $("book-text");

function errorAt(id, error) {
  $(id).textContent = error ? friendlyError(error) : "";
  $(id).hidden = !error;
}
function status(text) {
  statusText = text;
  $("sync-status").textContent = text;
  $("reader-sync-status").textContent = text;
}
function updateLearningButtons() {
  const unavailable = !aiStatus.enabled;
  $("ai-setup-notice").hidden = !unavailable;
  $("ai-settings-status").textContent = unavailable
    ? "当前状态：VPS 尚未配置百炼 API Key。"
    : "当前状态：已启用，使用 VPS 上配置的 " + aiStatus.model + "。";
  for (const [id, enabled] of [["bilingual-button",learningPreferences.bilingual],["phrases-button",learningPreferences.phrases]]) {
    $(id).disabled = unavailable;
    $(id).setAttribute("aria-pressed", String(aiStatus.enabled && enabled));
    $(id).title = aiStatus.enabled ? "由 VPS 上配置的 " + aiStatus.model + " 提供" : "AI 尚未配置，请使用“设置 AI”查看方法";
    if (unavailable) $(id).setAttribute("aria-describedby", "ai-setup-notice");
    else $(id).removeAttribute("aria-describedby");
  }
}
function openAiSettings() {
  updateLearningButtons();
  $("settings-dialog").close();
  $("ai-settings-dialog").showModal();
}
function samePosition(a, b) {
  return a && b && a.chapterIndex === b.chapterIndex && a.charOffset === b.charOffset;
}
function showLogin(error) {
  generation++; openGeneration++;
  clearTimeout(syncTimer); clearTimeout(saveTimer); clearTimeout(learningTimer);
  active = null; engine = null; state = null; session = null; unsaved = null;
  aiStatus = { enabled:false, model:"", cacheVersion:"" }; updateLearningButtons();
  content.replaceChildren(); $("books").replaceChildren(); $("toc-list").replaceChildren();
  document.querySelectorAll("dialog[open]").forEach(d => d.close());
  document.body.classList.remove("reading");
  $("login-view").hidden = false; $("library-view").hidden = true; $("reader-view").hidden = true; $("account").hidden = true;
  errorAt("login-error", error);
  $("password").value = "";
}
function renderLibrary() {
  if (!state) return;
  $("books").replaceChildren();
  const books = Object.values(state.books).sort((a, b) => {
    const time = id => state.pending[id]?.occurredAt || state.positions[id]?.occurredAt || 0;
    return time(b.bookId) - time(a.bookId) || a.title.localeCompare(b.title);
  });
  $("book-count").textContent = books.length + " 本";
  $("empty-library").hidden = books.length > 0 || busySync;
  for (const [index, book] of books.entries()) {
    const position = positionFor(state, book.bookId);
    const percent = Math.round((position?.bookProgress || 0) * 100);
    const card = document.createElement("article");
    card.className = "book-card";
    const meta = document.createElement("div"); meta.className = "book-meta";
    const number = document.createElement("span"); number.textContent = String(index + 1).padStart(2, "0");
    const format = document.createElement("span"); format.textContent = book.format;
    meta.append(number, format);
    const title = document.createElement("h2"); title.textContent = book.title;
    const author = document.createElement("p"); author.className = "book-author"; author.textContent = book.author || "作者未注明";
    const bottom = document.createElement("div"); bottom.className = "book-bottom";
    const label = document.createElement("div"); label.className = "progress-label";
    const text = document.createElement("span"); text.textContent = book.ready ? (position ? "上次读到" : "尚未开始") : "等待 App 同步正文";
    const value = document.createElement("span"); value.textContent = percent + "%";
    label.append(text, value);
    const progress = document.createElement("progress"); progress.max = 100; progress.value = percent; progress.setAttribute("aria-label", "阅读进度");
    const button = document.createElement("button");
    button.textContent = book.ready ? (position ? "继续阅读 →" : "开始阅读 →") : "正文尚未就绪";
    button.disabled = !book.ready;
    button.addEventListener("click", () => openBook(book.bookId, button));
    bottom.append(label, progress, button); card.append(meta, title, author, bottom); $("books").append(card);
  }
}
function checkActive() {
  if (!active || !state) return;
  const book = state.books[active.book.bookId];
  if (!book) {
    returnToLibrary(false);
    errorAt("library-error", new Error("这本书已在其他设备移除。"));
    return;
  }
  if (book.contentSha256 !== active.book.contentSha256 || book.contentRevision !== active.book.contentRevision) {
    returnToLibrary(false);
    errorAt("library-error", new Error("书籍正文已更新，请重新打开。"));
    return;
  }
  const remote = positionFor(state, book.bookId);
  const own = active.lastSaved || { chapterIndex: active.chapter.chapterIndex, charOffset: active.offset };
  active.remote = remote && !samePosition(remote, own) ? remote : null;
  $("remote-banner").hidden = !active.remote;
}
async function syncNow(manual = false) {
  if (!engine || busySync) return;
  if (unsaved && active) await persist();
  const mine = engine, runGeneration = generation;
  busySync = true; $("sync-button").disabled = true;
  status("正在同步…");
  try {
    await saving;
    if (manual) await mine.retryRejected();
    const nextState = await mine.sync();
    if (runGeneration !== generation) return;
    state = nextState;
    const rejected = Object.values(state.errors);
    const pending = Object.keys(state.pending).length;
    status(unsaved ? "本机保存失败，请勿关闭页面" : rejected.length ? "进度同步失败，请重试" : pending ? "进度已保存在本机，等待同步" : "阅读进度已同步");
    errorAt("library-error", rejected.length ? rejected[0] : null);
    errorAt("reader-error", unsaved || (rejected.length ? rejected[0] : null));
    checkActive();
  } catch (error) {
    if (runGeneration !== generation) return;
    status("暂未同步 · 进度保留在本机");
    errorAt(active ? "reader-error" : "library-error", error);
    if (error.status === 401 || error.code === "session_changed") showLogin(error);
  } finally {
    busySync = false; $("sync-button").disabled = false;
    if (runGeneration === generation && state) { renderLibrary(); updateFooter(); }
  }
}
function scheduleSync() {
  clearTimeout(syncTimer);
  syncTimer = setTimeout(() => syncNow(), 700);
}
async function startSession() {
  session = api.session();
  if (!session) return showLogin();
  const runGeneration = ++generation;
  engine = new SyncEngine(api, new AccountStore(accountScope(api.base, session.user.id)), session.user.id);
  try { state = await engine.store.read(); }
  catch (error) { return showLogin(error); }
  if (generation !== runGeneration) return;
  try { aiStatus = await api.json("v1/ai/status", session.user.id); }
  catch (error) {
    if (error.status === 401) return showLogin(error);
    aiStatus = { enabled:false, model:"", cacheVersion:"" };
  }
  updateLearningButtons();
  $("login-view").hidden = true; $("library-view").hidden = false; $("reader-view").hidden = true;
  $("account").hidden = false; $("account-email").textContent = session.user.email;
  errorAt("library-error", null); renderLibrary();
  await syncNow();
}
async function openBook(bookId, button) {
  const token = ++openGeneration, runGeneration = generation;
  const previous = button.textContent;
  button.disabled = true; button.textContent = "正在打开…"; errorAt("library-error", null);
  try {
    await syncNow();
    if (runGeneration !== generation || token !== openGeneration || !state) return;
    const book = state.books[bookId];
    if (!book?.ready) throw new Error("书籍正文尚未就绪，请稍后同步书架。");
    const bundle = await loadBundle(api, session.user.id, book);
    if (runGeneration !== generation || token !== openGeneration) return;
    active = { book, bundle, chapters: chaptersFor(bundle), offset: 0, remote: null, lastSaved: null,
      translations:{}, phrases:{}, translationLoading:new Set(),
      learningPending:{translation:new Set(),phrases:new Set()}, learningEpoch:0 };
    $("book-title").textContent = book.title;
    $("library-view").hidden = true; $("reader-view").hidden = false;
    document.body.classList.add("reading"); errorAt("reader-error", null); errorAt("learning-error", null);
    const position = positionFor(state, bookId);
    showChapter(position?.chapterIndex ?? active.chapters[0].chapterIndex, position?.charOffset || 0);
    $("remote-banner").hidden = true;
    scroller.focus({ preventScroll: true });
  } catch (error) { if (runGeneration === generation) errorAt("library-error", error); }
  finally { button.disabled = false; button.textContent = previous; }
}
function updateFooter() {
  if (!active) return;
  const index = active.chapters.indexOf(active.chapter);
  const position = active.lastSaved || progressAt(active.book.bookId, active.chapters, active.chapter.chapterIndex, active.offset);
  $("reading-progress").textContent = Math.round(position.bookProgress * 100) + "% · " + (index + 1) + " / " + active.chapters.length + " 章";
  $("reader-sync-status").textContent = statusText;
  $("previous-page").disabled = index === 0 && scroller.scrollTop < 2;
  $("next-page").textContent = index === active.chapters.length - 1 &&
    scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 4 ? "标记读完 ✓" : "下一页 →";
}
function restore(offset) {
  if (!active) return;
  restoring = true; userScroll = false;
  const current = active;
  requestAnimationFrame(() => {
    if (current !== active) return;
    restoreOffset(scroller, content, offset);
    requestAnimationFrame(() => { if (current === active) { restoring = false; updateFooter(); } });
  });
}
function showChapter(index, offset = 0) {
  if (!active) return;
  clearTimeout(saveTimer); clearTimeout(learningTimer); userScroll = false;
  active.chapter = active.chapters.find(c => c.chapterIndex === index) || active.chapters[0];
  active.offset = Math.max(0, Math.min(offset, active.chapter.content.length));
  active.lastSaved = null;
  $("chapter-title").textContent = active.chapter.title || "正文";
  $("chapter-label").textContent = active.chapter.title || "正文";
  $("chapter-end").textContent = active.chapter === active.chapters.at(-1) ? "— 全书结束 —" : "— 本章结束 —";
  active.learningEpoch++;
  active.translations = {}; active.phrases = {}; active.translationLoading = new Set();
  active.learningPending = { translation:new Set(), phrases:new Set() };
  errorAt("learning-error", null);
  hydrateLearningCache();
  renderChapterLearning(false);
  scroller.scrollTop = 0; restore(active.offset); updateFooter();
  beginLearning(active.learningEpoch);
}

function currentLearningKey(kind, paragraphIndex, current = active) {
  return learningCacheKey(aiStatus.cacheVersion, current.book.contentSha256,
    current.chapter.chapterIndex, paragraphIndex, kind);
}

function hydrateLearningCache() {
  if (!active || !state?.aiCache) return;
  for (const paragraph of paragraphsFor(active.chapter.content)) {
    const translation = state.aiCache[currentLearningKey("translation", paragraph.index)];
    const phrases = state.aiCache[currentLearningKey("phrases", paragraph.index)];
    if (translation) active.translations[paragraph.index] = translation.value;
    if (phrases) active.phrases[paragraph.index] = phrases.value;
  }
}

function showPhrase(phrase, source) {
  $("phrase-title").textContent = phrase.phrase;
  $("phrase-type").textContent = phrase.type || "精读词组";
  $("phrase-explanation").textContent = phrase.explanation || "暂无讲解。";
  $("phrase-source").textContent = source;
  $("phrase-dialog").showModal();
}

function renderChapterLearning(preserve = true) {
  if (!active) return;
  const anchor = preserve && content.children.length ? visibleOffset(scroller, content) : active.offset;
  if (preserve) active.offset = anchor;
  renderLearningText(content, active.chapter.content, {
    translations:learningPreferences.bilingual ? active.translations : {},
    translationLoading:learningPreferences.bilingual ? active.translationLoading : new Set(),
    phrases:learningPreferences.phrases ? active.phrases : {},
    onPhrase:showPhrase,
  });
  if (preserve) restore(anchor);
}

async function saveLearningCache(key, value, current, epoch) {
  const mine = engine;
  if (!mine || current !== active || current.learningEpoch !== epoch) return false;
  const next = await mine.store.transact(saved => {
    saved.aiCache ||= {};
    saved.aiCache[key] = { value, savedAt:Date.now() };
    const entries = Object.entries(saved.aiCache);
    if (entries.length > 4000) {
      entries.sort((a,b) => (a[1].savedAt || 0) - (b[1].savedAt || 0));
      for (const [oldKey] of entries.slice(0, entries.length - 4000)) delete saved.aiCache[oldKey];
    }
  });
  if (mine === engine) state = next;
  return current === active && current.learningEpoch === epoch;
}

function beginLearning(epoch) {
  if (!active || !aiStatus.enabled) return;
  if (learningPreferences.bilingual) processLearning("translation", epoch);
  if (learningPreferences.phrases) processLearning("phrases", epoch);
}

function scheduleLearning(delay = 250) {
  clearTimeout(learningTimer);
  const current = active, epoch = current?.learningEpoch;
  if (!current || !aiStatus.enabled) return;
  const wait = Math.max(delay, learningRetryAfter - Date.now());
  learningTimer = setTimeout(() => {
    if (current !== active || current.learningEpoch !== epoch) return;
    if (Date.now() < learningRetryAfter) return scheduleLearning(learningRetryAfter - Date.now());
    learningRetryAfter = 0;
    beginLearning(epoch);
  }, wait);
}

async function paceAiRequest() {
  const turn = aiPace.then(async () => {
    const wait = Math.max(0, nextAiRequestAt - Date.now());
    if (wait) await new Promise(resolve => setTimeout(resolve, wait));
    nextAiRequestAt = Date.now() + AI_REQUEST_SPACING_MS;
  });
  aiPace = turn.catch(() => {});
  await turn;
}

async function processLearning(kind, epoch) {
  const current = active;
  if (!current || current.learningEpoch !== epoch) return;
  const paragraphs = paragraphsFor(current.chapter.content);
  const values = kind === "translation" ? current.translations : current.phrases;
  const pending = current.learningPending[kind];
  const queue = learningWindow(paragraphs, current.offset)
    .filter(paragraph => !Object.hasOwn(values, paragraph.index) && !pending.has(paragraph.index));
  const claimed = queue.map(paragraph => paragraph.index);
  claimed.forEach(index => pending.add(index));
  try {
    while (queue.length && current === active && current.learningEpoch === epoch) {
      const paragraph = queue.shift();
      const enabled = kind === "translation" ? learningPreferences.bilingual : learningPreferences.phrases;
      if (!enabled) break;
      await paceAiRequest();
      if (current !== active || current.learningEpoch !== epoch) break;
      if (kind === "translation") {
        current.translationLoading.add(paragraph.index);
        renderChapterLearning();
      }
      try {
        const result = await api.json("v1/ai/" + (kind === "translation" ? "translate" : "phrases"), session.user.id, {
          bookId:current.book.bookId,
          contentSha256:current.book.contentSha256,
          chapterIndex:current.chapter.chapterIndex,
          paragraphIndex:paragraph.index,
          text:paragraph.text,
        });
        const value = kind === "translation" ? result.translation : result.phrases;
        const key = currentLearningKey(kind, paragraph.index, current);
        if (!await saveLearningCache(key, value, current, epoch)) break;
        if (kind === "translation") current.translations[paragraph.index] = value;
        else current.phrases[paragraph.index] = value;
      } catch (error) {
        if (error.code === "ai_rate_limited") {
          learningRetryAfter = Math.max(learningRetryAfter, Date.now() + 61_000);
          errorAt("learning-error", null);
          scheduleLearning();
        } else if (error.code === "ai_upstream_rate_limited") {
          learningRetryAfter = Math.max(learningRetryAfter, Date.now() + 15_000);
          errorAt("learning-error", null);
          scheduleLearning();
        } else if (current === active && current.learningEpoch === epoch) {
          errorAt("learning-error", error);
        }
        break;
      } finally {
        if (kind === "translation") current.translationLoading.delete(paragraph.index);
        if (current === active && current.learningEpoch === epoch) renderChapterLearning();
      }
    }
  } finally {
    claimed.forEach(index => pending.delete(index));
  }
}

function toggleLearning(kind) {
  if (!aiStatus.enabled) return;
  learningPreferences[kind] = !learningPreferences[kind];
  try { localStorage.setItem("kreader-learning", JSON.stringify(learningPreferences)); } catch {}
  updateLearningButtons();
  if (!active) return;
  active.learningEpoch++;
  active.translationLoading.clear();
  errorAt("learning-error", null);
  renderChapterLearning();
  beginLearning(active.learningEpoch);
}

function persist(atEnd = false) {
  clearTimeout(saveTimer); saveTimer = null;
  if (!active || !engine) return saving;
  const current = active, mine = engine, runGeneration = generation;
  const payload = progressAt(current.book.bookId, current.chapters, current.chapter.chapterIndex, current.offset, atEnd);
  current.lastSaved = payload;
  savesInFlight++;
  $("remote-banner").hidden = true; current.remote = null;
  status("正在保存进度…"); updateFooter();
  saving = saving.then(() => mine.queue(payload)).then(nextState => {
    if (generation !== runGeneration) return;
    unsaved = null; state = nextState; status("进度已保存在本机，等待同步"); scheduleSync();
  }).catch(error => {
    if (generation === runGeneration) { unsaved = error; status("本机保存失败，请勿关闭页面"); errorAt("reader-error", error); }
  }).finally(() => { savesInFlight--; });
  return saving;
}
function captureScroll() {
  if (!active || restoring || !userScroll) return;
  active.offset = visibleOffset(scroller, content);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => persist(), 120);
  scheduleLearning();
  updateFooter();
}
function page(direction) {
  if (!active) return;
  const chapterIndex = active.chapters.indexOf(active.chapter);
  const bottom = scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 4;
  if (direction > 0 && bottom) {
    if (chapterIndex < active.chapters.length - 1) {
      showChapter(active.chapters[chapterIndex + 1].chapterIndex, 0); persist();
    } else { active.offset = active.chapter.content.length; persist(true); }
  } else if (direction < 0 && scroller.scrollTop <= 2 && chapterIndex > 0) {
    const previous = active.chapters[chapterIndex - 1];
    showChapter(previous.chapterIndex, previous.content.length); persist(true);
  } else {
    userScroll = true;
    scroller.scrollBy({ top: direction * scroller.clientHeight * .85, behavior: "instant" });
    captureScroll();
  }
}
async function returnToLibrary(save = true) {
  if (save && saveTimer) await persist();
  if (save) { await saving; if (unsaved) return; }
  active = null; openGeneration++;
  content.replaceChildren(); $("toc-list").replaceChildren();
  $("reader-view").hidden = true; $("library-view").hidden = false; document.body.classList.remove("reading");
  renderLibrary(); $("library-heading").focus?.();
  if (save) syncNow();
}
function openToc() {
  if (!active) return;
  $("toc-list").replaceChildren();
  const entries = active.bundle.toc.length ? [...active.bundle.toc].sort((a,b) => a.orderIndex-b.orderIndex)
    : active.chapters.map(c => ({ chapterIndex:c.chapterIndex, label:c.title || "正文", level:0, anchorParagraph:-1 }));
  for (const entry of entries) {
    const chapter = active.chapters.find(c => c.chapterIndex === entry.chapterIndex);
    if (!chapter) continue;
    const button = document.createElement("button");
    button.textContent = entry.label || chapter.title;
    button.style.paddingLeft = (12 + Math.max(0, Math.min(6, Number(entry.level) || 0)) * 14) + "px";
    button.setAttribute("aria-current", String(chapter === active.chapter));
    button.addEventListener("click", () => {
      $("toc-dialog").close();
      showChapter(entry.chapterIndex, paragraphOffset(chapter.content, entry.anchorParagraph));
      persist(); scroller.focus({ preventScroll:true });
    });
    $("toc-list").append(button);
  }
  $("toc-dialog").showModal();
}
function applyPreferences() {
  preferences.fontSize = Math.min(34, Math.max(16, Number(preferences.fontSize) || 22));
  if (!["1.6","1.9","2.2"].includes(preferences.lineHeight)) preferences.lineHeight = "1.9";
  if (!["620","760","960"].includes(preferences.width)) preferences.width = "760";
  if (!["light","sepia","dark"].includes(preferences.theme)) preferences.theme = "light";
  const root = document.documentElement;
  root.dataset.theme = preferences.theme;
  root.style.setProperty("--font-size", preferences.fontSize + "px");
  root.style.setProperty("--line-height", preferences.lineHeight);
  root.style.setProperty("--reader-width", preferences.width + "px");
  $("font-size").value = preferences.fontSize; $("font-size-value").value = preferences.fontSize;
  $("line-height").value = preferences.lineHeight; $("reader-width").value = preferences.width;
  document.querySelectorAll('[name="theme"]').forEach(input => { input.checked = input.value === preferences.theme; });
}

$("login-form").addEventListener("submit", async event => {
  event.preventDefault(); $("login-button").disabled = true; errorAt("login-error", null);
  try { await api.login($("email").value, $("password").value); $("password").value = ""; await startSession(); }
  catch (error) { errorAt("login-error", error); }
  finally { $("login-button").disabled = false; }
});
$("logout").addEventListener("click", async () => {
  $("logout").disabled = true;
  try {
    if (saveTimer) await persist();
    await saving; await syncNow();
    await api.logout();
  } catch {} finally { showLogin(); $("logout").disabled = false; }
});
$("sync-button").addEventListener("click", () => syncNow(true));
$("back").addEventListener("click", () => returnToLibrary());
$("previous-page").addEventListener("click", () => page(-1));
$("next-page").addEventListener("click", () => page(1));
$("bilingual-button").addEventListener("click", () => toggleLearning("bilingual"));
$("phrases-button").addEventListener("click", () => toggleLearning("phrases"));
$("toc-button").addEventListener("click", openToc);
$("settings-button").addEventListener("click", () => $("settings-dialog").showModal());
$("ai-settings-button").addEventListener("click", openAiSettings);
$("open-ai-settings").addEventListener("click", openAiSettings);
document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => $(button.dataset.close).close()));
$("use-remote").addEventListener("click", () => {
  if (!active?.remote) return;
  const remote = active.remote;
  showChapter(remote.chapterIndex, remote.charOffset);
  active.remote = null; $("remote-banner").hidden = true;
});
$("keep-position").addEventListener("click", () => persist());
$("settings-dialog").addEventListener("input", () => {
  preferences = { fontSize:Number($("font-size").value), lineHeight:$("line-height").value,
    width:$("reader-width").value, theme:document.querySelector('[name="theme"]:checked').value };
  applyPreferences();
  try { localStorage.setItem("kreader-layout", JSON.stringify(preferences)); } catch {}
  if (active) restore(active.offset);
});
scroller.addEventListener("scroll", captureScroll, { passive:true });
for (const event of ["wheel","touchmove"]) scroller.addEventListener(event, () => { userScroll = true; }, { passive:true });
scroller.addEventListener("pointerdown", event => {
  if (!event.target.closest("button")) userScroll = true;
}, { passive:true });
document.addEventListener("keydown", event => {
  if (!active || document.querySelector("dialog[open]") ||
      /INPUT|SELECT|TEXTAREA|BUTTON/.test(event.target.tagName) || event.ctrlKey || event.metaKey || event.altKey) return;
  if (["ArrowRight","PageDown"," "].includes(event.key)) { event.preventDefault(); page(event.shiftKey ? -1 : 1); }
  else if (["ArrowLeft","PageUp"].includes(event.key)) { event.preventDefault(); page(-1); }
  else if (["ArrowDown","ArrowUp","Home","End"].includes(event.key)) userScroll = true;
});
window.addEventListener("online", () => syncNow());
document.addEventListener("visibilitychange", () => {
  if (document.hidden && saveTimer) persist();
  if (!document.hidden) syncNow();
});
window.addEventListener("pagehide", () => { if (saveTimer) persist(); });
window.addEventListener("beforeunload", event => {
  // A real pending write gets a browser warning; never claim an unload request is guaranteed.
  if (saveTimer || savesInFlight || unsaved || (state && Object.keys(state.pending).length)) {
    event.preventDefault(); event.returnValue = "";
  }
});
window.addEventListener("storage", event => {
  if (event.key !== api.key) return;
  const next = api.session();
  if (!next || next.user.id !== session?.user.id) { showLogin(); if (next) startSession(); }
});
window.addEventListener("resize", () => { if (active) restore(active.offset); });
setInterval(() => { if (!document.hidden) syncNow(); }, 15000);
applyPreferences();
updateLearningButtons();
if (!globalThis.isSecureContext || !crypto.subtle || !crypto.randomUUID || !globalThis.indexedDB || !navigator.locks) {
  $("login-button").disabled = true;
  errorAt("login-error", new Error("请用最新版 Chrome、Edge、Firefox 或 Safari，通过 HTTPS 打开此页面。"));
} else startSession();
