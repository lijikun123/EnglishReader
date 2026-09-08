import test from "node:test";
import assert from "node:assert/strict";
import { learningCacheKey, paragraphsFor, phraseSegments } from "../src/main/resources/web/reader.js";

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
