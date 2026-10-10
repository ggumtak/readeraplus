import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {gzipSync} from 'node:zlib';
import vm from 'node:vm';

const root = new URL('../../', import.meta.url);
const html = readFileSync(new URL('app/src/main/assets/ai-dictionary.html', root), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const core = script.split('/* UI */')[0];
const key = 'sk-' + 'ant-' + 'x'.repeat(48);
const options = {model:'claude-haiku-5-5', effort:'medium', messages:[{role:'user',content:'귀접 뜻'}]};
const event = (data, ending='\n') => 'event: '+data.type+ending+'data: '+JSON.stringify(data)+ending+ending;
const end = event({type:'message_stop'});
const reply = () => new Response(event({type:'content_block_delta',delta:{type:'text_delta',text:'단어의 뜻입니다.'}})+end);
function runtime(fetch, streaming=true) {
  const ctx = vm.createContext({fetch,Response,ReadableStream:streaming?ReadableStream:undefined,TextDecoder,AbortController});
  vm.runInContext(core, ctx);
  return ctx.ReaderAi;
}
function storage() {
  const values = new Map();
  return {getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
}

test('all model/effort combinations call only Claude with the key in a header', async()=>{
  const calls=[];
  const api=runtime(async(url,init)=>{calls.push({url,init});return reply();});
  for(const model of ['claude-haiku-5-5','claude-sonnet-5-5','claude-opus-5-5']) {
    for(const effort of ['low','medium','high','xhigh']) {
      let text='';
      const result=await api.request(key,{...options,model,effort},t=>text+=t);
      const {url,init}=calls.at(-1),body=JSON.parse(init.body);
      assert.equal(url,'https://api.anthropic.com/v1/messages');
      assert.equal(init.headers['x-api-key'],key);
      assert.equal(init.headers['anthropic-dangerous-direct-browser-access'],'true');
      assert.equal(init.credentials,'omit');
      assert.equal(init.referrerPolicy,'no-referrer');
      assert.equal(init.body.includes(key),false);
      assert.equal(body.model,model);
      assert.equal(body.output_config.effort,effort);
      assert.equal(body.thinking.type,'adaptive');
      assert.equal(body.stream,true);
      assert.equal(body.messages[0].content,'귀접 뜻');
      assert.equal(result.truncated,false);
      assert.equal(text,'단어의 뜻입니다.');
    }
  }
  assert.equal(calls.length,12);
});

test('one-byte UTF-8 and CRLF chunks preserve Korean text and omit thinking',async()=>{
  const content=event({type:'ping'},'\r\n')+
    event({type:'content_block_start',content_block:{type:'thinking',thinking:'private'}},'\r\n')+
    event({type:'content_block_delta',delta:{type:'thinking_delta',thinking:'private'}},'\r\n')+
    event({type:'content_block_start',content_block:{type:'text',text:'뜻: '}},'\r\n')+
    event({type:'future_event',unknown:'ignore'},'\r\n')+
    event({type:'content_block_delta',delta:{type:'text_delta',text:'전당품 📚'}},'\r\n')+
    event({type:'message_delta',delta:{stop_reason:'max_tokens'}},'\r\n')+event({type:'message_stop'},'\r\n');
  const bytes=new TextEncoder().encode(content);
  const api=runtime(async()=>new Response(new ReadableStream({start(c){for(const b of bytes)c.enqueue(Uint8Array.of(b));c.close();}})));
  let text='';const result=await api.request(key,options,t=>text+=t);
  assert.equal(text,'뜻: 전당품 📚');assert.equal(result.truncated,true);
});

test('incomplete streams are failures even after visible text',async()=>{
  const api=runtime(async()=>new Response(event({type:'content_block_delta',delta:{type:'text_delta',text:'부분 답변'}})));
  let text='';
  await assert.rejects(api.request(key,options,t=>text+=t),/connection_lost/);
  assert.equal(text,'부분 답변');
});

test('older browsers use a non-streaming request and text-only response',async()=>{
  let sent;
  const api=runtime(async(url,init)=>{sent=JSON.parse(init.body);return new Response(JSON.stringify({content:[{type:'thinking',thinking:'private'},{type:'text',text:'보이는 답변'}],stop_reason:'end_turn'}));},false);
  let text='';await api.request(key,options,t=>text+=t);
  assert.equal(sent.stream,false);assert.equal(text,'보이는 답변');
});

test('provider errors are mapped without exposing response bodies or credentials',async()=>{
  for(const [status,code] of [[400,'invalid_request'],[401,'invalid_key'],[403,'permission_denied'],[404,'model_unavailable'],[429,'rate_limit'],[529,'upstream_busy']]) {
    const api=runtime(async()=>new Response(JSON.stringify({error:{message:key}}),{status}));
    await assert.rejects(api.request(key,options,()=>{}),e=>e.message===code&&!e.message.includes(key));
  }
  const api=runtime(async()=>new Response(event({type:'error',error:{type:'overloaded_error',message:key}})));
  await assert.rejects(api.request(key,options,()=>{}),/upstream_busy/);
});

test('cancel aborts a pending provider stream',async()=>{
  let providerAborted=false;
  const controller=new AbortController();
  const api=runtime(async(url,init)=>new Response(new ReadableStream({
    start(c){init.signal.addEventListener('abort',()=>{providerAborted=true;c.error(new DOMException('Canceled','AbortError'));});}
  })));
  const task=api.request(key,options,()=>{},controller.signal);
  await new Promise(r=>setTimeout(r,0));controller.abort();
  await assert.rejects(task,e=>e.name==='AbortError');
  assert.equal(providerAborted,true);
});

test('invalid options and oversized questions never reach the network',async()=>{
  let calls=0;
  const api=runtime(async()=>{calls++;return reply();});
  for(const invalid of [{...options,model:'other'},{...options,effort:'max'},{...options,messages:[]},{...options,messages:[{role:'assistant',content:'bad'}]},{...options,messages:[{role:'user',content:'x'.repeat(2001)}]}]) {
    await assert.rejects(api.request(key,invalid,()=>{}),/invalid_request/);
  }
  await assert.rejects(api.request('bad',options,()=>{}),/invalid_key/);
  assert.equal(calls,0);
});

test('keys persist only when requested and can be forgotten or used in memory',()=>{
  const api=runtime(()=>{throw Error('no network');}),s=storage();
  assert.equal(api.loadKey(s),'');
  api.saveKey(s,key,true);assert.equal(runtime(()=>{}).loadKey(s),key);
  api.saveKey(s,key,false);assert.equal(api.loadKey(s),'');
  api.saveKey(s,key,true);api.forgetKey(s);assert.equal(api.loadKey(s),'');
  const blocked={getItem(){throw Error('denied');},setItem(){throw Error('quota');}};
  assert.equal(api.loadKey(blocked),'');assert.throws(()=>api.saveKey(blocked,key,true),/quota/);
});

function ui({savedKey='',hash='',fetch=async()=>reply(),abort=AbortController}={}) {
  class Node {
    constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.events={};this.value='';this.textContent='';this.hidden=false;this.checked=true;this.style={};this.classList={remove(){}};this.scrollHeight=1000;this.clientHeight=800;}
    get firstChild(){return this.children[0]??null;}
    appendChild(n){if(n.parent)n.parent.removeChild(n);this.children.push(n);n.parent=this;return n;}
    removeChild(n){this.children.splice(this.children.indexOf(n),1);n.parent=null;}
    remove(){this.parent?.removeChild(this);}
    setAttribute(){}
    addEventListener(type,fn){this.events[type]=fn;}
    querySelector(){return null;}
    querySelectorAll(){return [];}
    focus(){}
    blur(){}
  }
  const nodes=Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Node()]));
  nodes['key-dialog'].hidden=true;nodes.chat.appendChild(nodes.welcome);
  const s=storage();if(savedKey)s.setItem('readeraplus-anthropic-key-v1',savedKey);
  const document={getElementById:id=>nodes[id],querySelectorAll:()=>[],addEventListener(){},createElement:tag=>new Node(tag),createTextNode:text=>Object.assign(new Node('text'),{textContent:text}),createDocumentFragment:()=>new Node('fragment')};
  const context=vm.createContext({fetch,Response,ReadableStream,TextDecoder,AbortController:abort,URLSearchParams,localStorage:s,document,location:{hash},window:{innerHeight:800,addEventListener(){}},setTimeout,clearTimeout});
  vm.runInContext(script,context);
  return {nodes,storage:s};
}

test('opening the dictionary needs no login or configuration request',()=>{
  let calls=0;
  for(const savedKey of ['',key]) {
    const app=ui({savedKey,fetch:async()=>{calls++;return reply();}});
    assert.equal(calls,0);assert.equal(app.nodes.model.textContent,'Haiku 5.5 중간');
  }
});

test('selection waits for the first key, then auto-sends and persists for reopening',async()=>{
  const calls=[];
  const app=ui({hash:'#q='+encodeURIComponent('귀접 뜻'),fetch:async(url,init)=>{calls.push({url,init});return reply();}});
  assert.equal(calls.length,0);assert.equal(app.nodes['key-dialog'].hidden,false);
  assert.equal(app.nodes.input.value,'귀접 뜻');
  app.nodes['api-key'].value=key;app.nodes['key-form'].events.submit({preventDefault(){}});
  await new Promise(r=>setTimeout(r,15));
  assert.equal(calls.length,1);assert.equal(JSON.parse(calls[0].init.body).messages[0].content,'귀접 뜻');
  assert.equal(app.nodes['api-key'].value,'');
  assert.equal(runtime(()=>{}).loadKey(app.storage),key);
});

test('an old WebView gets an update message instead of an uncaught submission error',()=>{
  let calls=0;
  const app=ui({savedKey:key,hash:'#q=word',abort:null,fetch:async()=>{calls++;return reply();}});
  assert.equal(calls,0);assert.match(app.nodes.hint.textContent,/Android System WebView/);
});

test('the page is self-contained, safe to render, and its CSP matches the actual script',()=>{
  new vm.Script(script);
  assert.ok(Buffer.byteLength(html)<32768);
  assert.ok(gzipSync(html).byteLength<12288);
  assert.equal(/<script[^>]*\bsrc=|<link\b|<iframe|innerHTML|eval\(|signin-with-chatgpt|\/api\/(config|key|chat)|requestAnimationFrame|@font-face|animation:|transition:/.test(html),false);
  const digest=createHash('sha256').update(script).digest('base64');
  assert.ok(html.includes("script-src 'sha256-"+digest+"'"));
  assert.ok(html.includes('connect-src https://api.anthropic.com/v1/messages'));
  assert.ok(html.includes("form-action 'none'"));
  const legacy=readFileSync(new URL('app/src/main/res/xml/backup_rules.xml',root),'utf8');
  const modern=readFileSync(new URL('app/src/main/res/xml/data_extraction_rules.xml',root),'utf8');
  assert.ok(legacy.includes('path="app_webview/"'));assert.equal((modern.match(/path="app_webview\/"/g)||[]).length,2);
});
