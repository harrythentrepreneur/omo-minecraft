// The Listening Room wall. Served at GET /listening and shown on the in-game
// map-wall via the cinema pipeline (channel id "listening"): face/ captures
// this page in headless Chrome, the plugin paints it onto item-frame maps.
//
// Three faces, switched by /api/listening/state:
//   • "live"  transcript — the rolling whisper output (● REC / calm idle).
//   • board "Distilling…" — shown the instant DISTILL is pressed, while Claude
//                           runs (~20s), so a press is ALWAYS visible.
//   • board result — the organised work items (click a card to copy its prompt),
//                    or a clear "nothing actionable — here's what I heard" panel.
//
// Self-contained — no external assets — so it renders identically in a headless
// browser with no network. Big, high-contrast type because the wall is low-res.

export function listeningHtml(): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>The Listening Room</title>
<style>
  :root{
    --bg:#0d1117; --bg2:#11161f; --card:#161d29; --ink:#e8eef6; --muted:#8593a8;
    --rec:#ff4d54; --idle:#586379; --warn:#ffb84d; --accent:#5db0ff; --ok:#46d39a;
    --feature:#5db0ff; --bug:#ff6b6b; --chore:#9aa6bd; --research:#b98cff; --idea:#ffb84d;
    --high:#ff6b6b; --medium:#5db0ff; --low:#7d8aa0;
  }
  *{margin:0;padding:0;box-sizing:border-box;}
  html,body{height:100%;}
  body{
    background:radial-gradient(120% 120% at 50% -10%, var(--bg2), var(--bg));
    color:var(--ink); font-family:"SF Pro Display",-apple-system,system-ui,"Segoe UI",sans-serif;
    height:100vh; overflow:hidden; display:flex; flex-direction:column;
  }
  header{
    display:flex; align-items:center; justify-content:space-between;
    padding:2.4vh 4vw 1.8vh; border-bottom:.4vh solid rgba(255,255,255,.06);
  }
  .title{font-size:3.8vh; font-weight:700; letter-spacing:.04em;}
  .title .sub{display:block; font-size:2vh; font-weight:500; color:var(--muted); letter-spacing:.16em; margin-top:.3vh;}
  .pill{display:flex; align-items:center; gap:1vw; font-size:2.8vh; font-weight:700;
    padding:.9vh 2.2vw; border-radius:999px; background:rgba(255,255,255,.05);}
  .dot{width:2.2vh; height:2.2vh; border-radius:50%; background:var(--idle);}
  .rec .dot{background:var(--rec); animation:pulse 1.2s infinite ease-in-out;}
  .rec{color:var(--rec);} .warn{color:var(--warn);} .warn .dot{background:var(--warn);}
  .done{color:var(--ok);} .done .dot{background:var(--ok); animation:none;}
  .busy{color:var(--accent);} .busy .dot{background:var(--accent); animation:pulse 1s infinite ease-in-out;}
  @keyframes pulse{0%,100%{opacity:1;transform:scale(1);}50%{opacity:.35;transform:scale(.78);}}
  @keyframes spin{0%{transform:rotate(0)}100%{transform:rotate(360deg)}}

  main{flex:1; position:relative; overflow:hidden;}
  .hidden{display:none !important;}

  /* ── LIVE transcript ─────────────────────────────────────────── */
  #live{position:absolute; inset:0;}
  #scroll{position:absolute; inset:0; padding:3vh 5vw 5vh; overflow:hidden;
    display:flex; flex-direction:column; justify-content:flex-end;}
  #text{font-size:5vh; line-height:1.32; font-weight:500; white-space:pre-wrap; word-break:break-word;}
  #text .old{color:var(--muted);} #text .live{color:var(--ink);}
  #live::before{content:""; position:absolute; top:0; left:0; right:0; height:12vh;
    background:linear-gradient(var(--bg), transparent); z-index:2; pointer-events:none;}
  .center{position:absolute; inset:0; display:flex; flex-direction:column;
    align-items:center; justify-content:center; gap:2.6vh; text-align:center; padding:0 8vw;}
  .center .lever{font-size:9vh;} .center .big{font-size:4.6vh; font-weight:600;}
  .center .small{font-size:2.8vh; color:var(--muted);} .center.err .big{color:var(--warn);}

  /* ── DISTILLED board ─────────────────────────────────────────── */
  #board{position:absolute; inset:0; padding:2.4vh 4vw 2vh; overflow:hidden; display:flex; flex-direction:column;}
  #board .summary{font-size:2.9vh; color:var(--muted); margin-bottom:1.8vh; line-height:1.3;}
  #cards{display:flex; flex-direction:column; gap:1.5vh; overflow:hidden; flex:1;}
  .ci{display:flex; gap:2vw; align-items:flex-start; background:var(--card);
    border:.3vh solid rgba(255,255,255,.06); border-left:.8vh solid var(--accent);
    border-radius:1.4vh; padding:1.8vh 2.2vw; transition:border-color .2s, background .2s;}
  .ci .num{font-size:4.2vh; font-weight:800; color:var(--muted); min-width:2.4ch; line-height:1;}
  .ci .body{flex:1; min-width:0;}
  .ci .ttl{font-size:3.4vh; font-weight:700; line-height:1.15; margin-bottom:.7vh;}
  .ci .meta{display:flex; align-items:center; gap:1vw; flex-wrap:wrap; margin-bottom:.7vh;}
  .chip{font-size:2vh; font-weight:700; padding:.3vh 1.2vw; border-radius:999px;
    text-transform:uppercase; letter-spacing:.06em; background:rgba(255,255,255,.08);}
  .chip.cat{color:#0d1117;}
  .chip.prio{border:.25vh solid currentColor; background:transparent;}
  .ci .prev{font-size:2.4vh; color:var(--muted); line-height:1.32; display:-webkit-box;
    -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden;}
  .ci .copy{font-size:2.4vh; font-weight:700; color:var(--accent); white-space:nowrap; align-self:center;}
  .ci.copied{border-left-color:var(--ok); background:#16241f;}
  .ci.copied .copy{color:var(--ok);}
  #more{font-size:2.4vh; color:var(--muted); margin-top:1.2vh; text-align:center;}

  /* non-card board states (distilling / nothing actionable) */
  .state{flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center;
    text-align:center; gap:2.4vh; padding:0 6vw;}
  .state .spin{font-size:9vh; color:var(--accent); animation:spin 1.6s linear infinite; display:inline-block;}
  .state .big{font-size:4.6vh; font-weight:700;}
  .state .small{font-size:2.8vh; color:var(--muted); line-height:1.35;}
  .state .heard{font-size:3vh; color:var(--ink); line-height:1.4; max-width:80vw;
    background:var(--card); border-radius:1.2vh; padding:1.8vh 2.4vw;}
  .state .heard .lbl{color:var(--muted); font-weight:700; margin-right:.6vw;}

  footer{padding:1.6vh 4vw; border-top:.4vh solid rgba(255,255,255,.06);
    font-size:2.4vh; color:var(--muted); letter-spacing:.03em; display:flex; gap:1vw; align-items:center; flex-wrap:wrap;}
  footer .k{color:var(--accent); font-weight:700;}
</style></head>
<body>
  <header>
    <div class="title" id="htitle">THE LISTENING ROOM<span class="sub" id="hsub">LIVE TRANSCRIPT · WHISPER · LOCAL</span></div>
    <div class="pill" id="pill"><span class="dot"></span><span id="status">IDLE</span></div>
  </header>
  <main>
    <div id="live">
      <div id="scroll"><div id="text"></div></div>
      <div class="center" id="idle">
        <div class="lever">🎚️</div>
        <div class="big">Flip the <b>RECORD</b> lever to begin listening.</div>
        <div class="small">I’ll transcribe what you say, right here on the wall.</div>
      </div>
    </div>
    <div id="board" class="hidden">
      <div class="summary" id="bsummary"></div>
      <div id="cards"></div>
      <div id="more" class="hidden"></div>
    </div>
  </main>
  <footer id="foot"><span>press</span><span class="k">✦ DISTILL</span><span>to turn this into agent-ready prompts</span></footer>
<script>
  const $=(id)=>document.getElementById(id);
  const pill=$('pill'),status=$('status'),htitle=$('htitle'),hsub=$('hsub'),foot=$('foot');
  const live=$('live'),board=$('board'),textEl=$('text'),idle=$('idle'),scroll=$('scroll');
  const bsummary=$('bsummary'),cards=$('cards'),more=$('more');
  const MAX_CARDS=5;
  let lastText="", lastSig="";
  function esc(s){return (s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));}
  function setTitle(t){ htitle.childNodes[0].nodeValue=t; }
  function setIdle(kind){ idle.classList.remove('hidden','err'); scroll.classList.add('hidden');
    idle.querySelector('.lever').textContent = kind==='err'?'⚠️':(kind==='listening'?'👂':'🎚️');
    if(kind==='err') idle.classList.add('err'); }
  function setLiveText(){ idle.classList.add('hidden'); scroll.classList.remove('hidden'); }

  function showLive(s){
    board.classList.add('hidden'); live.classList.remove('hidden');
    setTitle('THE LISTENING ROOM'); hsub.textContent='LIVE TRANSCRIPT · WHISPER · LOCAL';
    foot.innerHTML='<span>press</span><span class="k">✦ DISTILL</span><span>to turn this into agent-ready prompts</span>';
    if(s.error){ pill.className='pill warn'; status.textContent='CHECK MIC';
      idle.querySelector('.big').textContent=s.error; setIdle('err'); return; }
    if(!s.armed){ pill.className='pill'; status.textContent='IDLE';
      idle.querySelector('.big').innerHTML='Flip the <b>RECORD</b> lever to begin listening.';
      idle.querySelector('.small').textContent='I’ll transcribe what you say, right here on the wall.';
      setIdle('off'); return; }
    pill.className='pill rec'; status.textContent='● REC';
    const txt=(s.text||'').trim();
    if(!txt){ idle.querySelector('.big').textContent='Listening…';
      idle.querySelector('.small').textContent='say something — your words will appear here'; setIdle('listening'); return; }
    setLiveText();
    if(txt!==lastText){
      const words=txt.split(/\\s+/); const cut=Math.max(0,words.length-24);
      const old=words.slice(0,cut).join(' '), liveW=words.slice(cut).join(' ');
      textEl.innerHTML='<span class="old">'+esc(old)+(old?' ':'')+'</span><span class="live">'+esc(liveW)+'</span>';
      scroll.scrollTop=scroll.scrollHeight; lastText=txt;
    }
  }

  function copyItem(id,ev){ if(ev){ev.preventDefault();ev.stopPropagation();}
    fetch('/api/listening/copy',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id})}).catch(()=>{}); }

  function showBoard(s){
    live.classList.add('hidden'); board.classList.remove('hidden');

    // 1) Distilling in flight — show it the instant the button is pressed.
    if(s.distilling){
      pill.className='pill busy'; status.textContent='✦ WORKING';
      setTitle('DISTILLING…'); hsub.textContent='ORGANISING WHAT YOU SAID';
      foot.innerHTML='<span>turning your transcript into agent-ready prompts…</span>';
      bsummary.textContent=''; more.classList.add('hidden');
      if(lastSig!=='__busy'){ lastSig='__busy';
        cards.innerHTML='<div class="state"><div class="spin">✦</div>'+
          '<div class="big">Distilling…</div>'+
          '<div class="small">organising what you said into clean tasks &amp; agent-ready prompts</div></div>';
      }
      return;
    }

    const b=s.board||{items:[]};
    const items=(b.items||[]);
    const recent=(s.copiedAt && (Date.now()-s.copiedAt<2600))?s.copiedId:null;

    // 2) Nothing actionable — be clearly responsive: show what was heard + why.
    if(!items.length){
      pill.className='pill warn'; status.textContent='NO ITEMS';
      setTitle('NOTHING ACTIONABLE'); hsub.textContent='I COULDN’T FIND A REQUEST';
      foot.innerHTML='<span>flip</span><span class="k">RECORD</span><span>· describe what to build · press</span><span class="k">✦ DISTILL</span>';
      const heard=(s.text||'').trim();
      const sig='__empty:'+heard.length;
      if(sig!==lastSig){ lastSig=sig; bsummary.textContent=''; more.classList.add('hidden');
        cards.innerHTML='<div class="state"><div class="big">'+esc(b.summary||'I didn’t find a clear request in what I heard.')+'</div>'+
          (heard?'<div class="heard"><span class="lbl">I heard:</span>“'+esc(heard.slice(-280))+'”</div>'
                :'<div class="small">I didn’t catch any speech — check that the mic has permission.</div>')+
          '<div class="small">Try: “build me a login screen with email and password, and remember the user.”</div></div>';
      }
      return;
    }

    // 3) The organised plan.
    pill.className='pill done'; status.textContent='✦ DISTILLED';
    setTitle(b.title||'DISTILLED PLAN'); hsub.textContent='AGENT-READY PROMPTS';
    foot.innerHTML='<span class="k">click a card</span><span>to copy its prompt · flip</span><span class="k">RECORD</span><span>to listen again</span>';
    const sig=JSON.stringify({t:b.title,n:items.length,c:recent});
    if(sig===lastSig) return;
    lastSig=sig;
    bsummary.textContent=b.summary||'';
    cards.innerHTML='';
    items.slice(0,MAX_CARDS).forEach(it=>{
      const copied=(it.id===recent);
      const el=document.createElement('div');
      el.className='ci'+(copied?' copied':'');
      el.style.borderLeftColor='var(--'+it.category+')';
      el.onclick=(e)=>copyItem(it.id,e);
      el.innerHTML=
        '<div class="num">'+it.id+'</div>'+
        '<div class="body">'+
          '<div class="ttl">'+esc(it.title)+'</div>'+
          '<div class="meta">'+
            '<span class="chip cat" style="background:var(--'+it.category+')">'+esc(it.category)+'</span>'+
            '<span class="chip prio" style="color:var(--'+it.priority+')">'+esc(it.priority)+'</span>'+
            (it.taskCount?'<span class="chip">'+it.taskCount+' task'+(it.taskCount>1?'s':'')+'</span>':'')+
          '</div>'+
          '<div class="prev">'+esc(it.preview)+'</div>'+
        '</div>'+
        '<div class="copy">'+(copied?'✓ copied':'⧉ copy')+'</div>';
      cards.appendChild(el);
    });
    if(items.length>MAX_CARDS){ more.classList.remove('hidden'); more.textContent='+'+(items.length-MAX_CARDS)+' more in the book'; }
    else more.classList.add('hidden');
  }

  async function tick(){
    try{
      const r=await fetch('/api/listening/state',{cache:'no-store'});
      const s=await r.json();
      if(s.view==='board') showBoard(s); else showLive(s);
    }catch(e){ /* keep last frame; runtime may be reloading */ }
  }
  tick(); setInterval(tick, 700);
</script>
</body></html>`;
}
