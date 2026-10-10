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
const options = {model:'claude-sonnet-5-5', effort:'medium', messages:[{role:'user',content:'귀접 뜻'}]};
const event = (data, ending='\n') => 'event: '+data.type+ending+'data: '+JSON.stringify(data)+ending+ending;
const end = event({type:'message_stop'});
const reply = () => new Response(event({type:'content_block_delta',delta:{type:'text_delta',text:'단어의 뜻입니다.'}})+end);
function runtime(fetch, streaming=true) {
  const ctx = vm.createContext({fetch,Response,ReadableStream:streaming?ReadableStream:undefined,TextDecoder,AbortController,URL});
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

function ui({savedKey='',savedPrefs,hash='',fetch=async()=>reply(),abort=AbortController,sharedStorage}={}) {
  let document;
  const walk=n=>[n,...n.children.flatMap(walk)];
  function matches(n,s) {
    if(s[0]==='[')return n.getAttribute(s.slice(1,-1))!==null;
    if(s[0]==='.')return n.className.split(/\s+/).includes(s.slice(1));
    return n.tagName===s.toUpperCase();
  }
  class Node {
    constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.events={};this.attributes={};this.value='';this._text='';this.hidden=false;this.checked=false;this.className='';this.style={setProperty(k,v){this[k]=v;}};this.classList={remove:()=>{}};this.scrollHeight=1000;this.clientHeight=800;this.offsetParent={};}
    get textContent(){return this._text+this.children.map(x=>x.textContent).join('');}
    set textContent(v){this.children.forEach(n=>n.parent=null);this.children=[];this._text=String(v);}
    get firstChild(){return this.children[0]??null;}
    appendChild(n){if(n.tagName==='FRAGMENT'){for(const x of [...n.children])this.appendChild(x);return n;}if(n.parent)n.parent.removeChild(n);this.children.push(n);n.parent=this;return n;}
    removeChild(n){const i=this.children.indexOf(n);if(i>=0)this.children.splice(i,1);n.parent=null;}
    remove(){this.parent?.removeChild(this);}
    setAttribute(k,v){this.attributes[k]=String(v);if(k==='class')this.className=v;}
    getAttribute(k){return this.attributes[k]??null;}
    addEventListener(type,fn){this.events[type]=fn;}
    querySelector(s){return this.querySelectorAll(s)[0]??null;}
    querySelectorAll(s){return walk(this).slice(1).filter(n=>s.split(',').some(x=>matches(n,x)));}
    focus(){document.activeElement=this;}
    blur(){if(document.activeElement===this)document.activeElement=null;}
  }
  const nodes={},body=new Node('body'),stack=[body];
  const markup=html.split('<body>')[1].split('<script>')[0];
  for(const m of markup.matchAll(/<\/?([\w-]+)([^>]*)>/g)){
    const tag=m[1].toLowerCase();
    if(m[0].startsWith('</')){if(stack.at(-1).tagName===tag.toUpperCase())stack.pop();continue;}
    const n=new Node(tag);
    for(const attr of m[2].matchAll(/([\w-]+)(?:="([^"]*)")?/g))n.setAttribute(attr[1],attr[2]??'');
    n.hidden=n.getAttribute('hidden')!==null;n.checked=n.getAttribute('checked')!==null;n.disabled=n.getAttribute('disabled')!==null;
    if(n.getAttribute('id'))nodes[n.getAttribute('id')]=n;
    stack.at(-1).appendChild(n);
    if(!['meta','input','path','circle','rect','br','hr'].includes(tag)&&!m[0].endsWith('/>'))stack.push(n);
  }
  const s=sharedStorage??storage();if(savedKey)s.setItem('readeraplus-anthropic-key-v1',savedKey);
  if(savedPrefs)s.setItem('reader-ai-preferences',JSON.stringify(savedPrefs));
  document={getElementById:id=>nodes[id],querySelectorAll:q=>body.querySelectorAll(q),events:{},addEventListener(type,fn){this.events[type]=fn;},documentElement:new Node('html'),createElement:tag=>new Node(tag),createTextNode:text=>Object.assign(new Node('text'),{textContent:text}),createDocumentFragment:()=>new Node('fragment')};
  const window={innerHeight:800,events:{},addEventListener(type,fn){this.events[type]=fn;}};
  const location={hash};
  const context=vm.createContext({fetch,Response,ReadableStream,TextDecoder,AbortController:abort,URL,URLSearchParams,localStorage:s,document,location,window,setTimeout,clearTimeout});
  vm.runInContext(script,context);
  return {nodes,storage:s,document,window,location,walk};
}
const settle=()=>new Promise(r=>setTimeout(r,15));
const click=n=>n.events.click({target:n});
const submit=n=>n.events.submit({preventDefault(){}});
async function askUi(app,q){app.nodes.input.value=q;submit(app.nodes.form);await settle();}
function sessionApi(){const c=vm.createContext({URL});vm.runInContext(core,c);return c.ReaderSessions;}

test('opening the dictionary needs no login or configuration request',()=>{
  let calls=0;
  for(const savedKey of ['',key]) {
    const app=ui({savedKey,fetch:async()=>{calls++;return reply();}});
    assert.equal(calls,0);assert.equal(app.nodes.model.textContent,'Haiku 5.5 낮음');
    assert.equal(app.nodes['web-search'].attributes['aria-pressed'],'true');
    assert.match(app.nodes['web-search'].attributes.title,/켜짐/);
  }
});

test('selection waits for the first key, then auto-sends and persists for reopening',async()=>{
  const calls=[];
  const app=ui({hash:'#q='+encodeURIComponent('귀접 뜻'),fetch:async(url,init)=>{calls.push({url,init});return reply();}});
  assert.equal(calls.length,0);assert.equal(app.nodes['key-dialog'].hidden,false);
  assert.equal(app.nodes.input.value,'귀접 뜻');
  app.nodes['api-key'].value=key;app.nodes['key-form'].events.submit({preventDefault(){}});
  await new Promise(r=>setTimeout(r,15));
  assert.equal(calls.length,1);
  const sent=JSON.parse(calls[0].init.body);
  assert.equal(sent.messages[0].content,'귀접 뜻');
  assert.equal(sent.model,'claude-haiku-5-5');assert.equal(sent.output_config.effort,'low');
  assert.equal(sent.tool_choice.type,'auto');
  assert.equal(app.nodes['api-key'].value,'');
  assert.equal(runtime(()=>{}).loadKey(app.storage),key);
});

const citation={type:'web_search_result_location',url:'https://example.com/article',title:'검색 출처',encrypted_index:'opaque-index',cited_text:'확인한 내용'};
const searchContent=[
  {type:'thinking',thinking:'private reasoning',signature:'opaque-signature'},
  {type:'server_tool_use',id:'srvtoolu_search',name:'web_search',input:{query:'latest information'}},
  {type:'web_search_tool_result',tool_use_id:'srvtoolu_search',content:[
    {type:'web_search_result',url:citation.url,title:citation.title,encrypted_content:'opaque-result',page_age:'October 2026'}
  ]}
];
function searchStream(content,reason='end_turn'){
  return content.map((b,index)=>{
    if(b.type==='text')return event({type:'content_block_start',index,content_block:{type:'text',text:''}})+
      event({type:'content_block_delta',index,delta:{type:'text_delta',text:b.text}})+
      (b.citations||[]).map(c=>event({type:'content_block_delta',index,delta:{type:'citations_delta',citation:c}})).join('')+
      event({type:'content_block_stop',index});
    if(b.type==='thinking')return event({type:'content_block_start',index,content_block:{type:'thinking',thinking:'',signature:''}})+
      event({type:'content_block_delta',index,delta:{type:'thinking_delta',thinking:b.thinking}})+
      event({type:'content_block_delta',index,delta:{type:'signature_delta',signature:b.signature}})+
      event({type:'content_block_stop',index});
    if(b.type==='server_tool_use'){
      const json=JSON.stringify(b.input),cut=8;
      return event({type:'content_block_start',index,content_block:{...b,input:{}}})+
        event({type:'content_block_delta',index,delta:{type:'input_json_delta',partial_json:json.slice(0,cut)}})+
        event({type:'content_block_delta',index,delta:{type:'input_json_delta',partial_json:json.slice(cut)}})+
        event({type:'content_block_stop',index});
    }
    return event({type:'content_block_start',index,content_block:b})+event({type:'content_block_stop',index});
  }).join('')+event({type:'message_delta',delta:{stop_reason:reason}})+end;
}
const searchAnswer={type:'text',text:'검색한 답변',citations:[citation]};

test('search is optional and automatic across all model and effort choices',async()=>{
  const calls=[];const api=runtime(async(url,init)=>{calls.push({url,body:JSON.parse(init.body)});return reply();});
  for(const model of ['claude-haiku-5-5','claude-sonnet-5-5','claude-opus-5-5']){
    for(const effort of ['low','medium','high','xhigh']){
      for(const webSearch of [true,false]){
        await api.request(key,{...options,model,effort,webSearch},()=>{});
        const {url,body}=calls.at(-1);assert.equal(url,'https://api.anthropic.com/v1/messages');
        if(webSearch){
          assert.equal(body.tool_choice.type,'auto');
          assert.deepEqual(body.tools,[{type:'web_search_20250305',name:'web_search',max_uses:3,allowed_callers:['direct']}]);
          assert.match(body.system,/일반 단어 뜻.*검색 없이/);
        }else assert.equal(body.tools,undefined);
      }
    }
  }
  assert.equal(calls.length,24);
});

test('search streaming keeps encrypted results and thinking signatures while showing citations',async()=>{
  const content=[...searchContent,searchAnswer],events=[];
  const api=runtime(async()=>new Response(searchStream(content)));let visible='';
  const result=await api.request(key,{...options,webSearch:true},t=>visible+=t,undefined,k=>events.push(k));
  assert.equal(visible,'검색한 답변 [1]');assert.equal(visible.includes('private'),false);
  assert.equal(result.searched,true);assert.equal(result.searchError,false);
  assert.deepEqual(JSON.parse(JSON.stringify(result.content)),content);
  assert.deepEqual(JSON.parse(JSON.stringify(result.sources)),[{url:citation.url,title:citation.title}]);
  assert.deepEqual(events,['search','search_done','citation']);
});

test('paused search resumes with intact tool state and is reusable on the next turn',async()=>{
  const calls=[];
  const api=runtime(async(url,init)=>{
    calls.push(JSON.parse(init.body));
    return calls.length===1?new Response(searchStream(searchContent,'pause_turn')):
      calls.length===2?new Response(searchStream([searchAnswer])):reply();
  });
  const result=await api.request(key,{...options,webSearch:true},()=>{});
  assert.equal(calls.length,2);assert.deepEqual(calls[1].messages[1].content,searchContent);
  assert.deepEqual(calls[1].tools,calls[0].tools);
  await api.request(key,{...options,webSearch:false,messages:[
    options.messages[0],{role:'assistant',content:result.content},{role:'user',content:'더 알려줘'}
  ]},()=>{});
  assert.equal(calls.length,3);assert.equal(calls[2].tools,undefined);
  assert.deepEqual(calls[2].messages[1].content,[...searchContent,searchAnswer]);
});

test('search failures and continuation limits cannot masquerade as successful searches',async()=>{
  const failed={...searchContent[2],content:{type:'web_search_tool_result_error',error_code:'unavailable'}};
  const api=runtime(async()=>new Response(searchStream([searchContent[1],failed,{type:'text',text:'검색 없이 설명'}])));
  const result=await api.request(key,{...options,webSearch:true},()=>{});
  assert.equal(result.searchError,true);assert.equal(result.sources.length,0);
  const disabled=runtime(async()=>new Response(JSON.stringify({error:{type:'invalid_request_error',message:'web search is not enabled '+key}}),{status:400}));
  await assert.rejects(disabled.request(key,{...options,webSearch:true},()=>{}),/search_disabled/);
  let count=0;
  const paused=runtime(async()=>{count++;return new Response(searchStream(searchContent,'pause_turn'));});
  await assert.rejects(paused.request(key,{...options,webSearch:true},()=>{}),/search_incomplete/);
  assert.equal(count,3);
});

test('non-streaming search displays deduplicated sources and rejects unsafe source URLs',async()=>{
  const bad={...citation,url:'javascript:alert(1)'},duplicate={...citation,title:'같은 주소'};
  const answer={...searchAnswer,citations:[citation,duplicate,bad]};
  const api=runtime(async()=>new Response(JSON.stringify({content:[...searchContent,answer],stop_reason:'end_turn'})),false);
  let text='';const result=await api.request(key,{...options,webSearch:true},t=>text+=t);
  assert.equal(text,'검색한 답변 [1] [1]');assert.equal(result.sources.length,1);
  for(const url of ['javascript:alert(1)','data:text/html,test','file:///secret','intent://example.com','https://user:pass@example.com/', 'https://api.anthropic.com/', 'https://appassets.androidplatform.net/']){
    assert.equal(api.sourceUrl(url),'');
  }
});

test('existing defaults migrate once, manual choices and the search switch persist',async()=>{
  const migrated=ui({savedPrefs:{model:'claude-haiku-5-5',effort:'medium'}});
  assert.equal(migrated.nodes.model.textContent,'Haiku 5.5 낮음');
  const manual=ui({savedPrefs:{model:'claude-haiku-5-5',effort:'medium',defaultsVersion:2,webSearch:false}});
  assert.equal(manual.nodes.model.textContent,'Haiku 5.5 중간');
  assert.equal(manual.nodes['web-search'].attributes['aria-pressed'],'false');
  const calls=[];
  const app=ui({savedKey:key,fetch:async(url,init)=>{calls.push(JSON.parse(init.body));return new Response(searchStream([...searchContent,searchAnswer]));}});
  app.nodes.input.value='검색해 줘';app.nodes.form.events.submit({preventDefault(){}});
  await new Promise(r=>setTimeout(r,15));
  const walk=n=>[n,...n.children.flatMap(walk)];
  const links=walk(app.nodes.chat).filter(n=>n.tagName==='A');
  assert.equal(links.length,1);assert.equal(links[0].href,citation.url);
  assert.equal(links[0].rel,'noreferrer noopener');
  app.nodes['web-search'].events.click();
  assert.equal(app.nodes['web-search'].attributes['aria-pressed'],'false');
  const prefs=JSON.parse(app.storage.getItem('reader-ai-preferences'));
  assert.equal(prefs.webSearch,false);
  const reopened=ui({savedPrefs:prefs});
  assert.equal(reopened.nodes['web-search'].attributes['aria-pressed'],'false');
  assert.match(reopened.nodes['web-search'].attributes.title,/꺼짐/);
  app.nodes.input.value='더 설명해 줘';app.nodes.form.events.submit({preventDefault(){}});
  await new Promise(r=>setTimeout(r,15));
  assert.equal(calls[1].tools,undefined);assert.deepEqual(calls[1].messages[1].content,[...searchContent,searchAnswer]);
});

test('an old WebView gets an update message instead of an uncaught submission error',()=>{
  let calls=0;
  const app=ui({savedKey:key,hash:'#q=word',abort:null,fetch:async()=>{calls++;return reply();}});
  assert.equal(calls,0);assert.match(app.nodes.hint.textContent,/Android System WebView/);
});

test('the page is self-contained, safe to render, and its CSP matches the actual script',()=>{
  new vm.Script(script);
  assert.ok(Buffer.byteLength(html)<65536);
  assert.ok(gzipSync(html).byteLength<20480);
  assert.equal(/<script[^>]*\bsrc=|<link\b|<iframe|innerHTML|eval\(|signin-with-chatgpt|\/api\/(config|key|chat)|requestAnimationFrame|@font-face|animation:|transition:/.test(html),false);
  const digest=createHash('sha256').update(script).digest('base64');
  assert.ok(html.includes("script-src 'sha256-"+digest+"'"));
  assert.ok(html.includes('connect-src https://api.anthropic.com/v1/messages'));
  assert.ok(html.includes("form-action 'none'"));
  const legacy=readFileSync(new URL('app/src/main/res/xml/backup_rules.xml',root),'utf8');
  const modern=readFileSync(new URL('app/src/main/res/xml/data_extraction_rules.xml',root),'utf8');
  assert.ok(legacy.includes('path="app_webview/"'));assert.equal((modern.match(/path="app_webview\/"/g)||[]).length,2);
});

test('custom instructions and answer length apply to every model, with bounded inputs',async()=>{
  const calls=[];const api=runtime(async(u,i)=>{calls.push(JSON.parse(i.body));return reply();});
  for(const model of ['claude-haiku-5-5','claude-sonnet-5-5','claude-opus-5-5']){
    await api.request(key,{...options,model,instructions:'쉬운 비유를 곁들여 줘.',answerLength:'long'},()=>{});
    assert.match(calls.at(-1).system,/사용자 지침:\n쉬운 비유/);
    assert.match(calls.at(-1).system,/충분히 설명/);
  }
  for(const extra of [{instructions:'x'.repeat(4001)},{instructions:3},{answerLength:'unknown'}])await assert.rejects(api.request(key,{...options,...extra},()=>{}),/invalid_request/);
  assert.equal(calls.length,3);
});

test('drawer settings preserve their parent, persist instructions and font, and affect the next request',async()=>{
  const calls=[];const app=ui({savedKey:key,fetch:async(u,i)=>{calls.push(JSON.parse(i.body));return reply();}});
  click(app.nodes.menu);assert.equal(app.nodes['menu-dialog'].hidden,false);assert.equal(app.nodes.app.inert,true);
  click(app.nodes['menu-settings']);assert.equal(app.nodes['settings-dialog'].hidden,false);assert.equal(app.nodes['menu-dialog'].hidden,true);
  app.nodes.instructions.value='비유로 쉽게 설명해 줘.';
  click(app.document.querySelectorAll('[data-length]')[2]);click(app.document.querySelectorAll('[data-size]')[2]);
  app.nodes['settings-search'].checked=false;submit(app.nodes['settings-form']);
  assert.equal(app.nodes['settings-dialog'].hidden,true);assert.equal(app.nodes['menu-dialog'].hidden,false);
  assert.equal(app.document.documentElement.style['--chat-font'],'22px');
  app.document.events.keydown({key:'Escape',preventDefault(){}});assert.equal(app.nodes.app.inert,false);
  await askUi(app,'전당품 뜻');assert.match(calls[0].system,/비유로 쉽게/);assert.equal(calls[0].tools,undefined);
  const again=ui({sharedStorage:app.storage});assert.equal(again.document.documentElement.style['--chat-font'],'22px');
  click(again.nodes.settings);assert.equal(again.nodes.instructions.value,'비유로 쉽게 설명해 줘.');
});

test('sessions reopen without network and retain independent follow-up context',async()=>{
  const calls=[];const app=ui({savedKey:key,fetch:async(u,i)=>{calls.push(JSON.parse(i.body));return reply();}});
  await askUi(app,'첫 단어 뜻');click(app.nodes.menu);click(app.nodes['menu-new']);await askUi(app,'둘째 단어 뜻');
  assert.equal(calls[1].messages.length,1);
  click(app.nodes.menu);const recents=app.nodes['recent-list'].querySelectorAll('.recent-open');assert.equal(recents.length,2);
  click(recents[1]);assert.equal(calls.length,2);assert.match(app.nodes.chat.textContent,/첫 단어 뜻/);assert.doesNotMatch(app.nodes.chat.textContent,/둘째 단어 뜻/);
  await askUi(app,'예문도 알려줘');assert.equal(calls[2].messages[0].content,'첫 단어 뜻');assert.equal(calls[2].messages.length,3);
  let unexpected=0;const restored=ui({sharedStorage:app.storage,fetch:async()=>{unexpected++;return reply();}});
  assert.equal(unexpected,0);assert.match(restored.nodes.chat.textContent,/예문도 알려줘/);
  assert.equal(restored.nodes['key-dialog'].hidden,true);
});

test('selection opens a new session and preserves previous conversation with the unchanged meaning prompt',async()=>{
  const calls=[];const app=ui({savedKey:key,fetch:async(u,i)=>{calls.push(JSON.parse(i.body));return reply();}});
  await askUi(app,'옛 대화');app.location.hash='#q='+encodeURIComponent('귀접 뜻');app.window.events.hashchange();await settle();
  assert.equal(calls[1].messages.length,1);assert.equal(calls[1].messages[0].content,'귀접 뜻');
  click(app.nodes.menu);assert.equal(app.nodes['recent-list'].querySelectorAll('.recent-open').length,2);
});

test('persisted search state keeps citations and signatures, but changing model uses visible text context',async()=>{
  const app=ui({savedKey:key,fetch:async()=>new Response(searchStream([...searchContent,searchAnswer]))});await askUi(app,'검색 질문');
  const calls=[];const again=ui({sharedStorage:app.storage,fetch:async(u,i)=>{calls.push(JSON.parse(i.body));return reply();}});
  assert.equal(again.nodes.chat.querySelectorAll('a').length,1);
  await askUi(again,'이어 설명');assert.deepEqual(calls[0].messages[1].content,[...searchContent,searchAnswer]);
  click(again.document.querySelectorAll('[data-model]')[1]);await askUi(again,'다른 모델 질문');
  assert.equal(calls[1].messages[1].content,'검색한 답변 [1]');
});

test('switching sessions aborts pending streams and does not write their output into another conversation',async()=>{
  let streamController,calls=0,aborted=false;
  const app=ui({savedKey:key,fetch:async(u,i)=>{
    calls++;if(calls!==1)return reply();
    return new Response(new ReadableStream({start(c){streamController=c;c.enqueue(new TextEncoder().encode(event({type:'content_block_delta',delta:{type:'text_delta',text:'이전 부분 답변'}})));i.signal.addEventListener('abort',()=>{aborted=true;c.error(new DOMException('Canceled','AbortError'));});}}));
  }});
  await askUi(app,'이전 질문');click(app.nodes.new);await askUi(app,'새 질문');
  assert.equal(aborted,true);assert.match(app.nodes.chat.textContent,/새 질문/);assert.doesNotMatch(app.nodes.chat.textContent,/이전 부분 답변/);
  click(app.nodes.menu);click(app.nodes['recent-list'].querySelectorAll('.recent-open')[1]);
  assert.match(app.nodes.chat.textContent,/이전 부분 답변/);assert.doesNotMatch(app.nodes.chat.textContent,/새 질문/);
});

test('pagehide preserves partial answers and reopening never resends a request',async()=>{
  let aborted=false;
  const app=ui({savedKey:key,fetch:async(u,i)=>new Response(new ReadableStream({start(c){
    c.enqueue(new TextEncoder().encode(event({type:'content_block_delta',delta:{type:'text_delta',text:'중간까지 답변'}})));
    i.signal.addEventListener('abort',()=>{aborted=true;c.error(new DOMException('Canceled','AbortError'));});
  }}))});
  await askUi(app,'진행 질문');app.window.events.pagehide();await settle();assert.equal(aborted,true);
  const again=ui({sharedStorage:app.storage,fetch:async()=>{throw Error('must not resend');}});
  assert.match(again.nodes.chat.textContent,/중간까지 답변/);assert.match(again.nodes.chat.textContent,/중지/);
});

test('storage failures never prevent an answer or expose the key in conversation data',async()=>{
  const s=storage();s.setItem('readeraplus-anthropic-key-v1',key);
  const blocked={getItem:s.getItem,setItem(){throw Error('quota');},removeItem(){throw Error('denied');}};
  const app=ui({sharedStorage:blocked});await askUi(app,'저장 실패 질문');
  assert.match(app.nodes.chat.textContent,/단어의 뜻입니다/);assert.match(app.nodes.hint.textContent,/저장하지 못/);
  const normal=ui({savedKey:key});await askUi(normal,'질문');
  const index=JSON.parse(normal.storage.getItem('reader-ai-sessions-v1'));
  assert.equal(normal.storage.getItem('reader-ai-session-'+index.current).includes(key),false);
});

test('history off leaves saved conversations intact; confirmed deletes preserve key and instructions',async()=>{
  const app=ui({savedKey:key});await askUi(app,'저장된 대화');
  const before=app.storage.getItem('reader-ai-sessions-v1');click(app.nodes.settings);
  app.nodes.instructions.value='쉽게';app.nodes['remember-history'].checked=false;submit(app.nodes['settings-form']);
  click(app.nodes.new);await askUi(app,'임시 대화');assert.equal(app.storage.getItem('reader-ai-sessions-v1'),before);
  click(app.nodes.menu);click(app.nodes['recent-list'].querySelectorAll('.recent-delete')[1]);
  assert.equal(app.nodes['delete-dialog'].hidden,false);assert.equal(JSON.parse(app.storage.getItem('reader-ai-sessions-v1')).items.length,1);
  click(app.nodes['confirm-delete']);assert.equal(JSON.parse(app.storage.getItem('reader-ai-sessions-v1')).items.length,0);
  assert.equal(runtime(()=>{}).loadKey(app.storage),key);assert.equal(JSON.parse(app.storage.getItem('reader-ai-preferences')).instructions,'쉽게');
  click(app.nodes['menu-settings']);click(app.nodes['clear-history']);click(app.nodes['confirm-delete']);assert.equal(app.nodes.chat.querySelectorAll('.group').length,0);
});

test('session index and bounded records load lazily and reject corrupt or unsafe data',()=>{
  const api=sessionApi(),s=storage();let index=api.empty();
  for(let n=0;n<25;n++)index=api.write(s,index,{id:'session-'+n,title:'제목 '+n,updated:n,turns:[{q:'질문',a:'답변',complete:true,model:'claude-haiku-5-5',sources:[]}]});
  assert.equal(index.items.length,20);assert.equal(s.getItem('reader-ai-session-session-0'),null);
  const reads=[];const tracked={...s,getItem(k){reads.push(k);return s.getItem(k);}};ui({sharedStorage:tracked});
  assert.deepEqual(reads.filter(k=>k.startsWith('reader-ai-session-')),['reader-ai-session-'+index.current]);
  const bounded=api.clean({id:'safe-session',title:'<script>attack</script>',updated:1,turns:Array.from({length:40},()=>({q:'x'.repeat(2001),a:'y'.repeat(24001),sources:[{url:'javascript:alert(1)',title:'bad'}, {url:citation.url,title:'safe'}],content:searchContent,complete:true}))});
  assert.equal(bounded.turns.length,1);assert.equal(bounded.turns[0].q.length,2000);assert.equal(bounded.turns[0].a.length,24000);assert.equal(bounded.turns[0].sources.length,1);
  s.setItem('reader-ai-session-bad-session','null');assert.equal(api.read(s,'bad-session'),null);
  s.setItem('reader-ai-sessions-v1','{broken');assert.equal(api.load(s).items.length,0);
  s.setItem('reader-ai-session-safe-session',JSON.stringify(bounded));s.setItem('reader-ai-sessions-v1',JSON.stringify({version:1,current:'safe-session',items:[{id:'safe-session',title:bounded.title,updated:1}]}));
  const app=ui({sharedStorage:s});click(app.nodes.menu);assert.match(app.nodes['recent-list'].textContent,/<script>attack/);assert.equal(app.nodes['recent-list'].querySelectorAll('script').length,0);
});

test('default migration preserves explicit version 3 model choices',()=>{
  assert.equal(ui({savedPrefs:{model:'claude-sonnet-5-5',effort:'medium',defaultsVersion:2}}).nodes.model.textContent,'Haiku 5.5 낮음');
  assert.equal(ui({savedPrefs:{model:'claude-sonnet-5-5',effort:'medium',defaultsVersion:3}}).nodes.model.textContent,'Sonnet 5.5 중간');
  assert.equal(ui({savedPrefs:{model:'claude-opus-5-5',effort:'high',defaultsVersion:2}}).nodes.model.textContent,'Opus 5.5 높음');
});

test('long sessions bound rendered groups as well as saved and in-memory history',async()=>{
  const app=ui({savedKey:key,fetch:async()=>new Response(event({type:'content_block_delta',delta:{type:'text_delta',text:'답'.repeat(5000)}})+end)});
  for(let n=0;n<8;n++)await askUi(app,'질문 '+n);
  assert.equal(app.nodes.chat.querySelectorAll('.group').length,5);
  const index=JSON.parse(app.storage.getItem('reader-ai-sessions-v1'));
  const record=JSON.parse(app.storage.getItem('reader-ai-session-'+index.current));assert.equal(record.turns.length,5);
  assert.equal(record.turns[0].q,'질문 3');assert.equal(record.turns.at(-1).q,'질문 7');
});

test('retry removes the failed turn and does not replay an incomplete answer as context',async()=>{
  const calls=[];const app=ui({savedKey:key,fetch:async(u,i)=>{calls.push(JSON.parse(i.body));return calls.length===1?new Response(event({type:'content_block_delta',delta:{type:'text_delta',text:'실패한 일부'}})):reply();}});
  await askUi(app,'재시도 질문');const retry=app.nodes.chat.querySelectorAll('button')[0];click(retry);await settle();
  assert.equal(calls.length,2);assert.equal(calls[1].messages.length,1);
  const index=JSON.parse(app.storage.getItem('reader-ai-sessions-v1'));
  const record=JSON.parse(app.storage.getItem('reader-ai-session-'+index.current));assert.equal(record.turns.length,1);assert.equal(record.turns[0].complete,true);
});

test('default instructions allow ordinary conversation without dictionary role or a sentence limit',async()=>{
  let body;const api=runtime(async(u,i)=>{body=JSON.parse(i.body);return reply();});
  await api.request(key,{...options,messages:[{role:'user',content:'오늘 하루를 어떻게 보내면 좋을까?'}],answerLength:'normal'},()=>{});
  assert.equal(body.messages[0].content,'오늘 하루를 어떻게 보내면 좋을까?');
  assert.doesNotMatch(body.system,/AI 사전|독서|2~6|핵심만|뜻을 묻는 질문/);
  assert.match(body.system,/사용자의 질문과 요청/);
});
