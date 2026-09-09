import test from "node:test";
import assert from "node:assert/strict";
import { learningCacheKey, learningWindow, paragraphsFor, phraseSegments } from "../src/main/resources/web/reader.js";

test("learning paragraphs retain Android-compatible source offsets", () => {
  assert.deepEqual(paragraphsFor("First.\n\nSecond 😀."), [
    { index:0, start:0, end:6, text:"First." },
    { index:1, start:8, end:18, text:"Second 😀." },
  ]);
});

test("phrase segments mark exact fragments without double-rendering overlaps", () => {
  assert.deepEqual(phraseSegments("take cues from context", [
    { fragments:["take cues from"] },
    { fragments:["cues from", "context"] },
  ]), [
    { text:"take cues from", phraseIndex:0 },
    { text:" ", phraseIndex:null },
    { text:"context", phraseIndex:1 },
  ]);
});

test("learning cache is isolated by model, content, chapter, paragraph and kind", () => {
  const one = learningCacheKey("web-ai-v1:model-a", "hash-a", 1, 2, "translation");
  assert.notEqual(one, learningCacheKey("web-ai-v1:model-b", "hash-a", 1, 2, "translation"));
  assert.notEqual(one, learningCacheKey("web-ai-v1:model-a", "hash-b", 1, 2, "translation"));
  assert.notEqual(one, learningCacheKey("web-ai-v1:model-a", "hash-a", 1, 3, "translation"));
  assert.notEqual(one, learningCacheKey("web-ai-v1:model-a", "hash-a", 1, 2, "phrases"));
});

test("learning requests stay near the current paragraph and prioritize forward reading", () => {
  const paragraphs = Array.from({length:20}, (_, index) => ({index,start:index*10,end:index*10+8,text:String(index)}));
  assert.deepEqual(learningWindow(paragraphs, 83).map(paragraph => paragraph.index), [8,9,10,11,12,13,7]);
  assert.deepEqual(learningWindow(paragraphs, 999).map(paragraph => paragraph.index), [19,18]);
  assert.equal(learningWindow(paragraphs, 83).length, 7);
});
