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
  container.replaceChildren();
  const fragment = document.createDocumentFragment();
  for (const block of textBlocks(text)) {
    const element = document.createElement("span");
    element.dataset.start = block.start;
    element.dataset.end = block.end;
    element.textContent = block.text;
    fragment.append(element);
  }
  container.append(fragment);
}

function offsetNode(container, offset) {
  const children = [...container.children];
  const element = children.find(el => Number(el.dataset.end) > offset) || children.at(-1);
  if (!element?.firstChild) return null;
  return { node: element.firstChild, offset: Math.min(element.textContent.length, Math.max(0, offset - Number(element.dataset.start))) };
}

export function restoreOffset(scroller, container, offset) {
  if (offset <= 0) { scroller.scrollTop = 0; return; }
  const point = offsetNode(container, offset);
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
  const node = element.firstChild;
  if (!node) return Number(element.dataset.start);
  // Binary search line geometry instead of assuming all paragraphs have equal height.
  const range = document.createRange();
  let low = 0, high = node.length;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    range.setStart(node, mid);
    range.setEnd(node, Math.min(node.length, mid + 1));
    const rect = range.getBoundingClientRect();
    if (rect.height && rect.bottom <= targetY) low = mid + 1;
    else high = mid;
  }
  return Number(element.dataset.start) + low;
}
