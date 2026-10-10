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

function ui({savedKey='',savedPrefs,hash='',fetch=async()=>reply(),abort=AbortController}={}) {
  class Node {
    constructor(tag='div'){this.tagName=tag.toUpperCase();this.children=[];this.events={};this.value='';this.textContent='';this.hidden=false;this.checked=true;this.style={};this.classList={remove(){}};this.scrollHeight=1000;this.clientHeight=800;}
    get firstChild(){return this.children[0]??null;}
    appendChild(n){if(n.parent)n.parent.removeChild(n);this.children.push(n);n.parent=this;return n;}
    removeChild(n){this.children.splice(this.children.indexOf(n),1);n.parent=null;}
    remove(){this.parent?.removeChild(this);}
    setAttribute(k,v){(this.attributes??={})[k]=v;}
    addEventListener(type,fn){this.events[type]=fn;}
    querySelector(){return null;}
    querySelectorAll(){return [];}
    focus(){}
    blur(){}
  }
  const nodes=Object.fromEntries([...html.matchAll(/id="([^"]+)"/g)].map(m=>[m[1],new Node()]));
  nodes['key-dialog'].hidden=true;nodes.chat.appendChild(nodes.welcome);
  const s=storage();if(savedKey)s.setItem('readeraplus-anthropic-key-v1',savedKey);
  if(savedPrefs)s.setItem('reader-ai-preferences',JSON.stringify(savedPrefs));
  const document={getElementById:id=>nodes[id],querySelectorAll:()=>[],addEventListener(){},createElement:tag=>new Node(tag),createTextNode:text=>Object.assign(new Node('text'),{textContent:text}),createDocumentFragment:()=>new Node('fragment')};
  const context=vm.createContext({fetch,Response,ReadableStream,TextDecoder,AbortController:abort,URL,URLSearchParams,localStorage:s,document,location:{hash},window:{innerHeight:800,addEventListener(){}},setTimeout,clearTimeout});
  vm.runInContext(script,context);
  return {nodes,storage:s};
}

test('opening the dictionary needs no login or configuration request',()=>{
  let calls=0;
  for(const savedKey of ['',key]) {
    const app=ui({savedKey,fetch:async()=>{calls++;return reply();}});
    assert.equal(calls,0);assert.equal(app.nodes.model.textContent,'Sonnet 5.5 중간');
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
  assert.equal(sent.model,'claude-sonnet-5-5');assert.equal(sent.output_config.effort,'medium');
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
  assert.equal(migrated.nodes.model.textContent,'Sonnet 5.5 중간');
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
  assert.ok(Buffer.byteLength(html)<40960);
  assert.ok(gzipSync(html).byteLength<14336);
  assert.equal(/<script[^>]*\bsrc=|<link\b|<iframe|innerHTML|eval\(|signin-with-chatgpt|\/api\/(config|key|chat)|requestAnimationFrame|@font-face|animation:|transition:/.test(html),false);
  const digest=createHash('sha256').update(script).digest('base64');
  assert.ok(html.includes("script-src 'sha256-"+digest+"'"));
  assert.ok(html.includes('connect-src https://api.anthropic.com/v1/messages'));
  assert.ok(html.includes("form-action 'none'"));
  const legacy=readFileSync(new URL('app/src/main/res/xml/backup_rules.xml',root),'utf8');
  const modern=readFileSync(new URL('app/src/main/res/xml/data_extraction_rules.xml',root),'utf8');
  assert.ok(legacy.includes('path="app_webview/"'));assert.equal((modern.match(/path="app_webview\/"/g)||[]).length,2);
});
