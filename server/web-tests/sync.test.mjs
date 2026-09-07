import test from "node:test";
import assert from "node:assert/strict";
import { emptyState, accountScope } from "../src/main/resources/web/store.js";
import { SyncEngine, applyPage, positionFor, loadBundle } from "../src/main/resources/web/sync.js";
import { chaptersFor, canonicalText, progressAt, paragraphOffset, textBlocks } from "../src/main/resources/web/reader.js";
import { Api, apiBase } from "../src/main/resources/web/api.js";

class MemoryStore {
  constructor(state = emptyState()) { this.state = structuredClone(state); this.scope = "test"; }
  async read() { return structuredClone(this.state); }
  async transact(update) { const next = structuredClone(this.state); update(next); this.state = next; return this.read(); }
}
const bookId = "8d65cf25-a1e4-42cb-93d8-464b2157a2af";
const metadata = { bookId, title:"A book", author:"", format:"EPUB", contentType:"NOVEL", contentSha256:"a".repeat(64), contentRevision:1, contentBytes:100 };
const pos = (offset = 12) => ({ bookId, chapterIndex:0, charOffset:offset, chapterProgress:offset/100, bookProgress:offset/200 });
function change(cursor, kind, payload, occurredAt = cursor) { return { cursor, kind, entityId:bookId, payload, occurredAt, revision:cursor }; }
function page(changes, nextCursor = changes.at(-1)?.cursor || 0) { return { changes, nextCursor, hasMore:false, serverNow:Date.now() }; }
function seeded() { const s=emptyState(); applyPage(s,page([change(1,"book.upsert",metadata), change(2,"book.bundle_ready",metadata)])); return s; }

test("same-origin API base works behind prefix and direct Ktor", () => {
  assert.equal(apiBase("https://reader.example/kreader-sync/web/"), "https://reader.example/kreader-sync/");
  assert.equal(apiBase("https://reader.example/web/index.html"), "https://reader.example/");
  assert.notEqual(accountScope("server", "alice"), accountScope("server","bob"));
  assert.notEqual(accountScope("server1","alice"), accountScope("server2","alice"));
});
test("canonical text and UTF-16 offsets match Android, including empty paragraphs, CRLF and emoji", () => {
  assert.equal(canonicalText("  First 😀\r\nline.  \r\n \r\n Second. \n\n\n"), "First 😀\r\nline.\n\nSecond.");
  assert.equal(canonicalText("\u001c A \u3000"), "A");
  const chapters = chaptersFor({ format:"EPUB", chapters:[{chapterIndex:0, content:" a \n\n b "},{chapterIndex:1,content:"😀xyz"}] });
  assert.equal(chapters[0].content,"a\n\nb");
  assert.equal(progressAt(bookId,chapters,1,2).charOffset,2);
  assert.equal(progressAt(bookId,chapters,1,2).bookProgress,.7);
  assert.equal(progressAt(bookId,chapters,1,5,true).bookProgress,1);
  assert.equal(paragraphOffset("first\nline\n\nsecond",1),12);
  assert.equal(paragraphOffset("first",9),0);
});
test("book text is preserved by bounded rendering blocks, including astral characters", () => {
  const text = "x".repeat(1399) + "😀" + "\n\n  hello <script>alert(1)</script> ".repeat(150);
  const blocks=textBlocks(text);
  assert.equal(blocks.map(b=>b.text).join(""),text);
  assert.equal(blocks.at(-1).end,text.length);
  for(const b of blocks) assert.ok(!/[\ud800-\udbff]$/.test(b.text));
});
test("history handles pagination, stale bundles, deletes, resurrection, and content revisions", () => {
  const s=seeded(); assert.equal(s.books[bookId].ready,true);
  applyPage(s,page([change(3,"progress.upsert",pos()),change(4,"book.bundle_stale",{})]));
  assert.equal(positionFor(s,bookId).charOffset,12);
  applyPage(s,page([change(5,"book.upsert",{...metadata,contentRevision:2})]));
  assert.equal(s.books[bookId].ready,false);
  applyPage(s,page([change(6,"book.bundle_ready",metadata)])); assert.equal(s.books[bookId].ready,false);
  applyPage(s,page([change(7,"book.delete",{bookId})])); assert.equal(s.books[bookId],undefined);
  applyPage(s,page([change(8,"book.upsert",metadata)])); assert.equal(s.books[bookId].ready,false);
  assert.equal(s.cursor,8);
});
test("local read queues durable progress only and coalesces repeated scrolling", async () => {
  const store=new MemoryStore(seeded()), sync=new SyncEngine(null,store,"user",null);
  await sync.queue(pos(12)); await sync.queue(pos(44));
  const s=await store.read();
  assert.equal(Object.keys(s.pending).length,1);
  assert.equal(s.pending[bookId].kind,"progress.upsert");
  assert.equal(positionFor(s,bookId).charOffset,44);
});
test("a lost push response retries the same mutation UUID", async () => {
  const store=new MemoryStore(seeded()); let seen=[], fail=true;
  const api={async json(path,user,body) {
    if(path.startsWith("v1/sync/pull")) return page([],2);
    seen.push(body.mutations[0].mutationId);
    if(fail) { fail=false; throw new TypeError("offline"); }
    return {acceptedMutationIds:[],duplicateMutationIds:[seen[0]],rejected:[]};
  }};
  const sync=new SyncEngine(api,store,"u",null); await sync.queue(pos());
  await assert.rejects(sync.sync()); assert.equal(Object.keys((await store.read()).pending).length,1);
  await sync.sync(); assert.equal(seen[0],seen[1]); assert.equal(Object.keys((await store.read()).pending).length,0);
});
test("accepted write remains durable if the following pull fails", async () => {
  const store=new MemoryStore(seeded()); let pulls=0;
  const api={async json(path,user,body) {
    if(path.startsWith("v1/sync/pull")) { if(++pulls===2) throw new Error("body stalled"); return page([],2); }
    return {acceptedMutationIds:body.mutations.map(m=>m.mutationId),duplicateMutationIds:[],rejected:[]};
  }};
  const sync=new SyncEngine(api,store,"u",null); await sync.queue(pos());
  await assert.rejects(sync.sync()); assert.equal(positionFor(await store.read(),bookId).charOffset,12);
  assert.equal(Object.keys((await store.read()).pending).length,1);
});
test("server's newer position wins even when older local push is accepted", async () => {
  const store=new MemoryStore(seeded()); let pushed;
  const api={async json(path,user,body) {
    if(path.startsWith("v1/sync/pull")) return pushed ? page([],3) : page([change(3,"progress.upsert",pos(90),Date.now()+1000)]);
    pushed=true; return {acceptedMutationIds:body.mutations.map(m=>m.mutationId),duplicateMutationIds:[],rejected:[]};
  }};
  const sync=new SyncEngine(api,store,"u",null); await sync.queue(pos(40)); await sync.sync();
  assert.equal(positionFor(await store.read(),bookId).charOffset,90);
});
test("a new scroll during a slow push is not removed by the earlier acknowledgement", async () => {
  const store=new MemoryStore(seeded()); let sync;
  const api={async json(path,user,body) {
    if(path.startsWith("v1/sync/pull")) return page([],2);
    await sync.queue(pos(80));
    return {acceptedMutationIds:body.mutations.map(m=>m.mutationId),duplicateMutationIds:[],rejected:[]};
  }};
  sync=new SyncEngine(api,store,"u",null); await sync.queue(pos(20)); await sync.sync();
  assert.equal(positionFor(await store.read(),bookId).charOffset,80);
  assert.equal(Object.keys((await store.read()).pending).length,1);
});
test("deleting a book on Android drops pending web progress without pushing", async () => {
  const store=new MemoryStore(seeded()); let pushes=0;
  const api={async json(path) { if(path.startsWith("v1/sync/pull")) return page([change(3,"book.delete",{bookId})]); pushes++; }};
  const sync=new SyncEngine(api,store,"u",null); await sync.queue(pos()); await sync.sync();
  assert.equal(pushes,0); assert.equal((await store.read()).books[bookId],undefined);
});
test("rejected progress is retained and surfaced, and does not loop every auto-sync", async () => {
  const store=new MemoryStore(seeded()); let pushes=0;
  const api={async json(path,user,body) {
    if(path.startsWith("v1/sync/pull")) return page([],2);
    pushes++; return {acceptedMutationIds:[],duplicateMutationIds:[],rejected:body.mutations.map(m=>({mutationId:m.mutationId,code:"clock_skew",message:"clock"}))};
  }};
  const sync=new SyncEngine(api,store,"u",null); await sync.queue(pos());
  await sync.sync(); await sync.sync(); assert.equal(pushes,1);
  assert.ok((await store.read()).errors[bookId]); await sync.retryRejected(); await sync.sync(); assert.equal(pushes,2);
});
test("pull drains every page and rejects a non-advancing cursor", async () => {
  const store=new MemoryStore(), seen=[];
  const sync=new SyncEngine({async json(path) {
    seen.push(path);
    if(seen.length===1) return {...page([change(1,"book.upsert",metadata)]),hasMore:true};
    return page([change(2,"book.bundle_ready",metadata)]);
  }},store,"u",null);
  await sync.sync(); assert.equal(seen.length,2); assert.equal((await store.read()).books[bookId].ready,true);
  const bad=new SyncEngine({async json(){return {...page([],2),hasMore:true};}},store,"u",null);
  await assert.rejects(bad.sync(),/同步/);
});
test("bundle reader verifies exact byte length/hash and rejects unsupported formats", async () => {
  const raw=new TextEncoder().encode(JSON.stringify({schemaVersion:1,format:"TXT",content:"Hello 😀",chapters:[],toc:[]}));
  const hash=Buffer.from(await crypto.subtle.digest("SHA-256",raw)).toString("hex");
  const book={...metadata,format:"TXT",contentBytes:raw.length,contentSha256:hash};
  const api={async request(){return new Response(raw);}};
  assert.equal((await loadBundle(api,"u",book)).content,"Hello 😀");
  await assert.rejects(loadBundle(api,"u",{...book,contentBytes:1}),/校验/);
});
class Storage {
  constructor(){this.map=new Map();}
  getItem(k){return this.map.get(k)??null;}
  setItem(k,v){this.map.set(k,v);}
  removeItem(k){this.map.delete(k);}
}
test("concurrent requests rotate the refresh token once and preserve device ID", async () => {
  const storage=new Storage(); let refreshes=0;
  const transport=async (url,init)=>{
    if(url.pathname.endsWith("/refresh")) {refreshes++; await new Promise(r=>setTimeout(r,5)); return Response.json({user:{id:"u"},accessToken:"new",accessTokenExpiresAt:Date.now()+999999,refreshToken:"r2"});}
    assert.equal(init.headers.Authorization,"Bearer new"); return Response.json({ok:true});
  };
  const api=new Api("https://example.test/",storage,null,transport);
  api.save({user:{id:"u"},deviceId:"d",accessToken:"old",accessTokenExpiresAt:0,refreshToken:"r1"});
  await Promise.all([api.json("v1/me","u"),api.json("v1/me","u")]);
  assert.equal(refreshes,1); assert.equal(api.session().deviceId,"d"); assert.equal(api.session().refreshToken,"r2");
});
test("logout revokes the rotated token and clears local credentials", async () => {
  const storage=new Storage();
  const api=new Api("https://example.test/",storage,null,async(url,init)=>{
    if(url.pathname.endsWith("/refresh"))return Response.json({user:{id:"u"},accessToken:"new",accessTokenExpiresAt:Date.now()+999999,refreshToken:"r2"});
    assert.equal(JSON.parse(init.body).refreshToken,"r2"); return new Response(null,{status:204});
  });
  api.save({user:{id:"u"},deviceId:"d",accessToken:"old",accessTokenExpiresAt:0,refreshToken:"r1"});
  await api.logout(); assert.equal(api.session(),null);
});
test("one account's API request cannot use another account's session", async () => {
  const api=new Api("https://example.test/",new Storage(),null,()=>{throw new Error("must not send");});
  api.save({user:{id:"bob"}});
  await assert.rejects(api.json("v1/me","alice"),error=>error.code==="session_changed");
});
