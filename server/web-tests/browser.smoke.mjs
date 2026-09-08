// Optional browser smoke test: install playwright in your development environment first.
// PLAYWRIGHT_MODULE may point at an existing Playwright module; no dependency is needed in production.
import assert from "node:assert/strict";
import http from "node:http";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || "playwright");
const basePath = "/kreader-sync/";
const bookId = "8d65cf25-a1e4-42cb-93d8-464b2157a2af";
const paragraph = "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. This is a test passage for the reading position. ";
const bundle = { schemaVersion:1, format:"EPUB", content:"", chapters:[
  {chapterIndex:0,title:"Chapter I",content:"  "+paragraph.repeat(10)+"\n\n"+paragraph.repeat(35)+"  "},
  {chapterIndex:1,title:"Chapter II",content:paragraph.repeat(45)}
], toc:[{chapterIndex:0,label:"Chapter I",href:"one.xhtml",level:0,orderIndex:0,anchorParagraph:-1},
{chapterIndex:0,label:"A later paragraph",href:"one.xhtml#later",level:1,orderIndex:1,anchorParagraph:1},
{chapterIndex:1,label:"Chapter II",href:"two.xhtml",level:0,orderIndex:2,anchorParagraph:-1}] };
const raw=Buffer.from(JSON.stringify(bundle));
const book={bookId,title:"Pride and Prejudice",author:"Jane Austen",contentType:"NOVEL",format:"EPUB",
  contentSha256:createHash("sha256").update(raw).digest("hex"),contentBytes:raw.length,contentRevision:1};
const changes=[], writes=[], aiRequests=[], tokens=new Map();
let refreshes=0;
function append(kind,payload,occurredAt=Date.now()) {
  changes.push({cursor:changes.length+1,kind,entityId:bookId,revision:changes.length+1,payload,occurredAt,serverUpdatedAt:Date.now()});
}
append("book.upsert",book); append("book.bundle_ready",book);
append("progress.upsert",{bookId,chapterIndex:0,charOffset:1900,chapterProgress:.2,bookProgress:.1});
function auth(user,deviceId) {
  const id=crypto.randomUUID(); tokens.set(id,{user,deviceId});
  return {accessToken:id,refreshToken:id,accessTokenExpiresAt:Date.now()+900000,user:{id:user,email:user+"@example.test"}};
}
const errors=[];
let aiEnabled=true;
const server=http.createServer(async(req,res)=>{
  const path=new URL(req.url,"http://localhost").pathname;
  const json=(data,status=200)=>{res.writeHead(status,{"Content-Type":"application/json"});res.end(JSON.stringify(data));};
  try {
    if(path.startsWith(basePath+"web/")) {
      const asset=path.slice((basePath+"web/").length)||"index.html";
      if(!/^[a-z.-]+$/.test(asset)){res.writeHead(404);return res.end();}
      const mime=asset.endsWith(".js")?"text/javascript":asset.endsWith(".css")?"text/css":asset.endsWith(".svg")?"image/svg+xml":"text/html; charset=utf-8";
      res.writeHead(200,{"Content-Type":mime,"Content-Security-Policy":"default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"});
      return res.end(await readFile(new URL("../src/main/resources/web/"+asset,import.meta.url)));
    }
    let body=""; for await(const chunk of req)body+=chunk;
    const data=body?JSON.parse(body):null;
    if(path===basePath+"v1/auth/login") {
      if(data.password!=="test-password")return json({code:"invalid_credentials"},401);
      return json(auth(data.email.split("@")[0],data.deviceId));
    }
    if(path===basePath+"v1/auth/refresh") {
      const prior=tokens.get(data.refreshToken); if(!prior)return json({code:"invalid_refresh_token"},401);
      refreshes++; return json(auth(prior.user,prior.deviceId));
    }
    const identity=tokens.get(req.headers.authorization?.replace("Bearer ",""));
    if(!identity)return json({code:"unauthorized"},401);
    if(path===basePath+"v1/auth/logout"){return res.writeHead(204).end();}
    if(path===basePath+"v1/ai/status")return json({enabled:aiEnabled,model:"test-model",cacheVersion:"web-ai-v1:test-model"});
    if(path===basePath+"v1/ai/translate") {
      aiRequests.push({kind:"translation",...data});
      return json({translation:"这是一段用于浏览器测试的自然中文译文。"});
    }
    if(path===basePath+"v1/ai/phrases") {
      aiRequests.push({kind:"phrases",...data});
      return json({phrases:[{phrase:"universally acknowledged",type:"固定搭配",fragments:["universally acknowledged"],explanation:"表示某事得到普遍承认。"}]});
    }
    if(path===basePath+"v1/sync/pull") {
      const cursor=Number(new URL(req.url,"http://localhost").searchParams.get("cursor")||0);
      const list=identity.user==="alice"?changes.filter(c=>c.cursor>cursor):[];
      return json({changes:list,nextCursor:list.at(-1)?.cursor||cursor,hasMore:false,serverNow:Date.now()});
    }
    if(path===basePath+"v1/sync/push") {
      assert.equal(data.deviceId,identity.deviceId);
      for(const mutation of data.mutations) {
        assert.equal(mutation.kind,"progress.upsert");
        if(!writes.some(w=>w.mutationId===mutation.mutationId)) {
          writes.push(mutation); append(mutation.kind,mutation.payload,mutation.occurredAt);
        }
      }
      return json({acceptedMutationIds:data.mutations.map(m=>m.mutationId),duplicateMutationIds:[],rejected:[],serverNow:Date.now()});
    }
    if(path===basePath+"v1/books/"+bookId+"/bundle"&&identity.user==="alice") {
      res.writeHead(200,{"Content-Type":"application/vnd.kreader.book-bundle+json"});return res.end(raw);
    }
    res.writeHead(404);res.end();
  } catch(e){errors.push(e.message);res.writeHead(500);res.end();}
});
await new Promise(r=>server.listen(0,"127.0.0.1",r));
const url="http://127.0.0.1:"+server.address().port+basePath+"web/";
const browser=await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL||"chrome"});
const context=await browser.newContext({viewport:{width:1360,height:900}});
const p=await context.newPage();
p.on("pageerror",e=>errors.push(e.message));
p.on("console",msg=>{if(msg.type()==="error")errors.push(msg.text());});
p.on("dialog",d=>d.accept());
const waitUntil=async(fn)=>{for(let i=0;i<100;i++){if(fn())return;await new Promise(r=>setTimeout(r,100));}throw new Error("Condition timed out");};
try {
  await p.goto(url);
  await p.locator("#email").fill("alice@example.test");await p.locator("#password").fill("wrong");
  await p.locator("#login-button").click();
  await p.locator("#login-error").filter({hasText:"邮箱或密码不正确"}).waitFor();
  await p.locator("#password").fill("test-password");await p.locator("#login-button").click();
  await p.getByRole("button",{name:"继续阅读 →"}).click();
  await p.locator("#reader-view").waitFor({state:"visible"});
  await p.waitForFunction(()=>document.getElementById("reading-scroll").scrollTop>0);
  const beforeLearning=writes.length;
  await p.locator("#bilingual-button").click();
  await p.locator(".paragraph-translation").filter({hasText:"自然中文译文"}).first().waitFor();
  await p.locator("#phrases-button").click();
  await p.locator(".phrase-mark").first().waitFor();
  await p.locator(".phrase-mark").first().click();
  await p.locator("#phrase-dialog[open]").waitFor();
  assert.equal(await p.locator("#phrase-title").textContent(),"universally acknowledged");
  await p.locator('[data-close="phrase-dialog"]').click();
  assert.equal(writes.length,beforeLearning,"AI display must not write reading progress");
  assert.ok(aiRequests.some(r=>r.kind==="translation")&&aiRequests.some(r=>r.kind==="phrases"));
  const atOpen=writes.length;
  await p.locator("#settings-button").click();
  await p.locator("#font-size").fill("28");
  await p.locator('[data-close="settings-dialog"]').click();
  await p.waitForTimeout(1000);
  assert.equal(writes.length,atOpen,"restoring/layout must not write progress");
  await p.locator("#next-page").click();
  await waitUntil(()=>writes.length>atOpen);
  assert.ok(writes.at(-1).payload.charOffset>1900);
  const saved=writes.at(-1).payload;
  await p.locator("#back").click();
  await p.reload();
  await p.getByRole("button",{name:"继续阅读 →"}).click();
  await p.waitForFunction(()=>document.getElementById("reading-scroll").scrollTop>0);
  assert.equal(writes.at(-1).payload.charOffset,saved.charOffset);
  await p.locator("#toc-button").click();
  await p.getByRole("button",{name:"Chapter II",exact:true}).click();
  await waitUntil(()=>writes.at(-1)?.payload.chapterIndex===1);
  assert.equal(writes.at(-1).payload.charOffset,0);
  // Mid-session disconnect: read, keep IDB pending, reconnect, and replay.
  await context.setOffline(true);
  await p.locator("#next-page").click();
  await p.waitForFunction(()=>document.getElementById("reader-sync-status").textContent.includes("本机"));
  const offlineCount=writes.length;
  await p.waitForTimeout(1000);assert.equal(writes.length,offlineCount);
  await context.setOffline(false);
  await waitUntil(()=>writes.length>offlineCount);
  // Mobile reflow must preserve progress and not overflow.
  const countBeforeResize=writes.length;
  await p.setViewportSize({width:390,height:844});
  await p.waitForTimeout(300);
  assert.ok(await p.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
  assert.equal(writes.length,countBeforeResize);
  // Simulated Android update is offered without jumping or echoing a mutation.
  await p.locator("#back").click();
  append("progress.upsert",{bookId,chapterIndex:0,charOffset:100,chapterProgress:.01,bookProgress:.005},Date.now()+10);
  await p.locator("#sync-button").click();
  await p.getByRole("button",{name:"继续阅读 →"}).click();
  await p.locator("#chapter-label").filter({hasText:"Chapter I"}).waitFor();
  // A missing server key leaves learning controls disabled but gives a visible setup path.
  aiEnabled=false;
  await p.locator("#back").click();
  await p.reload();
  await p.locator("#library-view").waitFor({state:"visible"});
  await p.getByRole("button",{name:"继续阅读 →"}).click();
  await p.locator("#ai-setup-notice").waitFor({state:"visible"});
  assert.equal(await p.locator("#bilingual-button").isDisabled(),true);
  assert.equal(await p.locator("#phrases-button").isDisabled(),true);
  await p.getByRole("button",{name:"设置 AI"}).click();
  await p.locator("#ai-settings-dialog[open]").waitFor();
  await p.locator("#ai-settings-status").filter({hasText:"尚未配置百炼 API Key"}).waitFor();
  await p.locator('[data-close="ai-settings-dialog"]').click();
  // Delete tombstone removes book without any book mutation.
  await p.locator("#back").click();
  append("book.delete",{bookId});
  await p.locator("#sync-button").click();
  await p.locator("#empty-library").waitFor({state:"visible"});
  await p.evaluate(()=>{
    const key=Object.keys(localStorage).find(k=>k.startsWith("kreader-session:"));
    const s=JSON.parse(localStorage.getItem(key));s.accessTokenExpiresAt=0;localStorage.setItem(key,JSON.stringify(s));
  });
  await p.locator("#sync-button").click(); await waitUntil(()=>refreshes===1);
  await p.locator("#logout").click();
  await p.locator("#login-view").waitFor({state:"visible"});
  await p.locator("#email").fill("bob@example.test");await p.locator("#password").fill("test-password");await p.locator("#login-button").click();
  await p.locator("#empty-library").waitFor({state:"visible"});
  assert.equal(await p.locator(".book-card").count(),0);
  assert.ok(writes.every(w=>w.kind==="progress.upsert"));
  // Expected console messages from incorrect credentials and the offline exercise are not runtime errors.
  const unexpected=errors.filter(e=>!e.includes("401")&&!e.includes("ERR_INTERNET_DISCONNECTED"));
  assert.deepEqual(unexpected,[]);
  console.log("PASS browser: login, library, Android offsets, settings, paging, refresh restore, TOC, offline replay, mobile, remote changes, deletion, token refresh, account isolation; progress-only writes="+writes.length);
} catch(error) {
  console.error("Browser diagnostics:", errors, await p.locator("#login-error").textContent(), await p.locator("#login-button").isDisabled());
  throw error;
} finally {
  await context.close();await browser.close();await new Promise(r=>server.close(r));
}
