export function chaptersFor(bundle) {
  const chapters = bundle.format === "EPUB" ? [...bundle.chapters].sort((a, b) => a.chapterIndex - b.chapterIndex)
    : [{ chapterIndex: 0, title: "正文", content: bundle.content }];
  return chapters.map(chapter => ({ ...chapter, content: canonicalText(chapter.content) }));
}

// Mirror ReaderText.kt splitIntoParagraphs/buildChapterText, including Kotlin's trim set.
export function canonicalText(content) {
  const trim = /^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$/g;
  return content.split(/\n[ \t\n\x0b\f\r]*\n/).map(p => p.replace(trim, "")).filter(Boolean).join("\n\n");
}

// Offsets are UTF-16 code units, exactly as in Kotlin String / Android pagination.
export function progressAt(bookId, chapters, chapterIndex, offset, atEnd = false) {
  const index = Math.max(0, chapters.findIndex(c => c.chapterIndex === chapterIndex));
  const chapter = chapters[index];
  const charOffset = Math.min(Math.max(0, Math.floor(offset)), chapter.content.length);
  const chapterProgress = atEnd ? 1 : (chapter.content.length ? charOffset / chapter.content.length : 0);
  // Android uses equal chapter weights for its book-level progress display.
  return { bookId, chapterIndex: chapter.chapterIndex, charOffset, chapterProgress,
    bookProgress: (index + chapterProgress) / Math.max(1, chapters.length) };
}

export function paragraphOffset(text, paragraphIndex) {
  if (paragraphIndex < 0) return 0;
  let offset = 0;
  const paragraphs = text.split("\n\n");
  if (paragraphIndex >= paragraphs.length) return 0;
  for (let i = 0; i < paragraphIndex; i++) offset += paragraphs[i].length + 2;
  return offset;
}

export function paragraphsFor(text) {
  if (!text) return [];
  let start = 0;
  return text.split("\n\n").map((value, index) => {
    const paragraph = { index, start, end: start + value.length, text: value };
    start = paragraph.end + 2;
    return paragraph;
  });
}

export function learningCacheKey(cacheVersion, contentSha256, chapterIndex, paragraphIndex, kind) {
  return [cacheVersion, contentSha256, chapterIndex, paragraphIndex, kind].join("|");
}

export function phraseSegments(text, phrases) {
  const ranges = [];
  phrases.forEach((phrase, phraseIndex) => {
    for (const fragment of phrase.fragments || []) {
      if (!fragment) continue;
      for (let start = text.indexOf(fragment); start >= 0; start = text.indexOf(fragment, start + fragment.length)) {
        ranges.push({ start, end:start + fragment.length, phraseIndex });
      }
    }
  });
  ranges.sort((a,b) => a.start-b.start || b.end-a.end || a.phraseIndex-b.phraseIndex);
  const accepted = [];
  for (const range of ranges) {
    if (!accepted.some(other => range.start < other.end && range.end > other.start)) accepted.push(range);
  }
  accepted.sort((a,b) => a.start-b.start);
  const segments = [];
  let cursor = 0;
  for (const range of accepted) {
    if (range.start > cursor) segments.push({ text:text.slice(cursor, range.start), phraseIndex:null });
    segments.push({ text:text.slice(range.start, range.end), phraseIndex:range.phraseIndex });
    cursor = range.end;
  }
  if (cursor < text.length || !segments.length) segments.push({ text:text.slice(cursor), phraseIndex:null });
  return segments;
}

// Preserve every character, including line breaks and indentation. Do not parse book HTML.
export function textBlocks(text, maxLength = 1400) {
  const blocks = [];
  for (let start = 0; start < text.length;) {
    let end = Math.min(start + maxLength, text.length);
    if (end < text.length) {
      const newline = text.lastIndexOf("\n", end);
      const space = text.lastIndexOf(" ", end);
      const boundary = Math.max(newline, space);
      if (boundary > start + maxLength / 2) end = boundary + 1;
      const code = text.charCodeAt(end - 1);
      if (code >= 0xd800 && code <= 0xdbff) end--;
    }
    blocks.push({ start, end, text: text.slice(start, end) });
    start = end;
  }
  return blocks;
}

export function renderText(container, text) {
  renderLearningText(container, text);
}

export function renderLearningText(container, text, options = {}) {
  container.replaceChildren();
  const fragment = document.createDocumentFragment();
  const translations = options.translations || {};
  const phrases = options.phrases || {};
  const translationLoading = options.translationLoading || new Set();
  for (const paragraph of paragraphsFor(text)) {
    const element = document.createElement("p");
    element.className = "reader-paragraph";
    element.dataset.start = paragraph.start;
    element.dataset.end = paragraph.end;
    element.dataset.paragraphIndex = paragraph.index;
    const english = document.createElement("span");
    english.className = "paragraph-english";
    for (const segment of phraseSegments(paragraph.text, phrases[paragraph.index] || [])) {
      if (segment.phraseIndex === null) english.append(document.createTextNode(segment.text));
      else {
        const mark = document.createElement("button");
        mark.type = "button";
        mark.className = "phrase-mark";
        mark.textContent = segment.text;
        mark.setAttribute("aria-label", segment.text + "，查看词组讲解");
        mark.addEventListener("click", () => options.onPhrase?.(phrases[paragraph.index][segment.phraseIndex], paragraph.text));
        english.append(mark);
      }
    }
    element.append(english);
    if (Object.hasOwn(translations, paragraph.index) || translationLoading.has(paragraph.index)) {
      const translated = document.createElement("span");
      translated.className = "paragraph-translation" + (translationLoading.has(paragraph.index) ? " loading" : "");
      translated.lang = "zh-CN";
      translated.textContent = translationLoading.has(paragraph.index) ? "翻译中…" : translations[paragraph.index];
      element.append(translated);
    }
    fragment.append(element);
  }
  container.append(fragment);
}

function offsetElement(container, offset) {
  const children = [...container.querySelectorAll("[data-start][data-end]")];
  const element = children.find(el => Number(el.dataset.end) > offset) || children.at(-1);
  if (!element) return null;
  return { element, root:element.querySelector(".paragraph-english") || element,
    offset:Math.max(0, offset - Number(element.dataset.start)) };
}

function textPoint(root, wanted) {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  let node, remaining = wanted, last = null;
  while ((node = walker.nextNode())) {
    last = node;
    if (remaining <= node.length) return { node, offset:remaining };
    remaining -= node.length;
  }
  return last ? { node:last, offset:last.length } : null;
}

export function restoreOffset(scroller, container, offset) {
  if (offset <= 0) { scroller.scrollTop = 0; return; }
  const block = offsetElement(container, offset);
  const point = block && textPoint(block.root, Math.min(block.root.textContent.length, block.offset));
  if (!point) { scroller.scrollTop = 0; return; }
  const range = document.createRange();
  range.setStart(point.node, point.offset);
  range.setEnd(point.node, Math.min(point.node.length, point.offset + 1));
  const rect = range.getBoundingClientRect();
  if (rect.height) scroller.scrollTop += rect.top - scroller.getBoundingClientRect().top - 24;
}

export function visibleOffset(scroller, container) {
  if (!container.children.length) return 0;
  const targetY = scroller.getBoundingClientRect().top + 25;
  const element = [...container.children].find(el => el.getBoundingClientRect().bottom > targetY) || container.lastElementChild;
  const root = element.querySelector(".paragraph-english") || element;
  const length = root.textContent.length;
  if (!length) return Number(element.dataset.start);
  // Binary search line geometry instead of assuming all paragraphs have equal height.
  const range = document.createRange();
  let low = 0, high = length;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    const start = textPoint(root, mid);
    const end = textPoint(root, Math.min(length, mid + 1));
    if (!start || !end) break;
    range.setStart(start.node, start.offset);
    range.setEnd(end.node, end.offset);
    const rect = range.getBoundingClientRect();
    if (rect.height && rect.bottom <= targetY) low = mid + 1;
    else high = mid;
  }
  return Number(element.dataset.start) + low;
}
