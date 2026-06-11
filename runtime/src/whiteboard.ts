// The classroom whiteboard. Two surfaces in one file:
//
//   1. `whiteboardStore` — the in-memory source of truth for what's on the
//      board right now: a reactive SLIDE DECK the tutor "ada" builds live as
//      the lesson progresses. Each slide is one concept (title + a few bullets
//      + optional worked example + optional diagram). The tutor calls the
//      `present_slide` tool to push a new slide; the wall always shows ONLY the
//      current (latest) slide, big and legible. The Dean's `open_classroom`
//      tool and AgentManager.spawn call `reset(subject)` to re-theme the board
//      for a new subject (clearing the deck). Modelled on faceState.ts — one
//      small struct, a monotonic `updatedAt`, no persistence (restart → the
//      seeded Algebra welcome slide).
//
//   2. `whiteboardHtml()` — the page served at GET /whiteboard and shown on
//      the in-game map-wall behind the tutor via the cinema pipeline (channel
//      "whiteboard"): face/ headless-Chrome captures it, the plugin paints it
//      onto item-frame maps. Self-contained — no external assets — so it
//      renders identically in a headless browser with no network beyond the
//      runtime. It polls /api/whiteboard/state ~every 1.1s and re-renders only
//      when the deck changed. Big, high-contrast text because the map-wall is
//      low-resolution (~896×384) and only refreshes ~1fps.

export type SlideDiagram =
  | { kind: "steps"; items: string[] } // numbered process / worked steps
  | {
      kind: "compare";
      left: { head: string; items: string[] };
      right: { head: string; items: string[] };
    } // two-column compare
  | { kind: "number_line"; min: number; max: number; mark?: number; ticks?: number; label?: string }
  | { kind: "timeline"; events: { when: string; what: string }[] }
  | { kind: "bars"; items: { label: string; value: number }[] };

export type Slide = {
  n: number; // 1-based, ASSIGNED BY THE STORE (callers omit it)
  title: string;
  bullets?: string[]; // key ideas, each a few words; renderer caps at 4
  example?: string; // a worked example / highlighted box
  note?: string; // small footnote / "remember"
  body?: string; // freeform paragraph (whiteboard_write back-compat)
  diagram?: SlideDiagram;
};

export type WhiteboardSnapshot = {
  subject: string;
  slides: Slide[];
  current: number; // index into slides (0-based)
  updatedAt: number;
  generating: boolean; // true while the Haiku deck is being pre-generated
};

// Coerce a loose, model-friendly `diagram` object into a clean SlideDiagram for
// one of the 5 supported kinds, or undefined if it's missing/unusable. The
// schema is deliberately loose (one flat object with optional fields per kind)
// so the model finds it easy to fill; we defensively pick out only the fields
// the kind needs and drop empties. Centralized here so the `present_slide` tool
// AND the Haiku-generated deck validate diagrams identically.
function normalizeDiagram(raw: any): SlideDiagram | undefined {
  if (!raw || typeof raw !== "object") return undefined;
  const kind = String(raw.kind ?? "");
  const strArr = (v: any): string[] =>
    Array.isArray(v) ? v.map((x) => String(x ?? "")).filter((s) => s.trim() !== "") : [];

  switch (kind) {
    case "steps": {
      const items = strArr(raw.items);
      return items.length ? { kind: "steps", items } : undefined;
    }
    case "compare": {
      const side = (s: any) => ({
        head: String(s?.head ?? ""),
        items: strArr(s?.items),
      });
      const left = side(raw.left);
      const right = side(raw.right);
      if (!left.items.length && !right.items.length && !left.head && !right.head) return undefined;
      return { kind: "compare", left, right };
    }
    case "number_line": {
      const min = Number(raw.min);
      const max = Number(raw.max);
      if (!Number.isFinite(min) || !Number.isFinite(max)) return undefined;
      const d: SlideDiagram = { kind: "number_line", min, max };
      if (Number.isFinite(Number(raw.mark))) d.mark = Number(raw.mark);
      if (Number.isFinite(Number(raw.ticks))) d.ticks = Number(raw.ticks);
      if (raw.label != null && String(raw.label).trim() !== "") d.label = String(raw.label);
      return d;
    }
    case "timeline": {
      const events = Array.isArray(raw.events)
        ? raw.events
            .map((e: any) => ({ when: String(e?.when ?? ""), what: String(e?.what ?? "") }))
            .filter((e: { when: string; what: string }) => e.when.trim() !== "" || e.what.trim() !== "")
        : [];
      return events.length ? { kind: "timeline", events } : undefined;
    }
    case "bars": {
      const items = Array.isArray(raw.items)
        ? raw.items
            .map((i: any) => ({ label: String(i?.label ?? ""), value: Number(i?.value) }))
            .filter((i: { label: string; value: number }) => Number.isFinite(i.value))
        : [];
      return items.length ? { kind: "bars", items } : undefined;
    }
    default:
      return undefined;
  }
}

// Shared defensive validation for a single slide coming from ANY source (the
// `present_slide` tool args OR a Haiku-generated deck). Coerces every field to a
// safe shape; the store assigns `n`, so this returns the slide minus `n`.
export function normalizeSlide(raw: any): Omit<Slide, "n"> {
  const r = (raw ?? {}) as Record<string, any>;
  // Cap string lengths so a pathological model payload can't bloat memory or the
  // read_deck tool result (the wall already line-clamps the display).
  const title = String(r.title ?? "").trim().slice(0, 120);
  const bullets = Array.isArray(r.bullets)
    ? r.bullets
        .map((b: any) => String(b ?? "").slice(0, 160))
        .filter((s: string) => s.trim() !== "")
        .slice(0, 4)
    : undefined;
  const str = (v: any, cap: number): string | undefined =>
    v != null && String(v).trim() !== "" ? String(v).slice(0, cap) : undefined;
  return {
    title,
    bullets: bullets && bullets.length ? bullets : undefined,
    example: str(r.example, 600),
    note: str(r.note, 300),
    body: str(r.body, 600),
    diagram: normalizeDiagram(r.diagram),
  };
}

// A friendly default so the very first `/omo build` looks exactly like
// today's algebra school before anyone re-themes the room.
const DEFAULT_SUBJECT = "Algebra";

// Keep the deck bounded so a long lesson can't grow memory unbounded; we only
// ever render the current (last) slide, but a back-scroll history is harmless.
const MAX_SLIDES = 40;

function welcomeSlide(subject: string): Slide {
  return {
    n: 1,
    title: `Welcome to ${subject}`,
    bullets: ["Take a seat — the lesson appears here", "ada will teach you step by step"],
  };
}

class WhiteboardStore {
  private state: WhiteboardSnapshot = {
    subject: DEFAULT_SUBJECT,
    slides: [welcomeSlide(DEFAULT_SUBJECT)],
    current: 0,
    updatedAt: Date.now(),
    generating: false,
  };

  // The subject the CURRENT deck was built/seeded for. ensureDeck() guards on
  // this (+ `generating`) so a resync re-spawn of the same subject never wipes
  // an in-progress or ready deck.
  private deckSubject = DEFAULT_SUBJECT;

  // True once a generated deck has actually been loaded for `deckSubject` (via
  // loadDeck). Distinguishes a real "ready" deck from the seeded welcome slide
  // so ensureDeck still generates on the very first open of the default subject.
  private deckReady = false;

  get(): WhiteboardSnapshot {
    // Return a copy so callers can't mutate the live deck.
    return {
      subject: this.state.subject,
      slides: this.state.slides.map((s) => ({ ...s })),
      current: this.state.current,
      updatedAt: this.state.updatedAt,
      generating: this.state.generating,
    };
  }

  /**
   * Idempotency status for ensureDeck: the subject the current deck is for, and
   * whether a generated deck is currently being built (`generating`) or has been
   * loaded and is ready (`ready`). The seeded welcome slide is NOT "ready".
   */
  deckStatus(): { subject: string; generating: boolean; ready: boolean } {
    return { subject: this.deckSubject, generating: this.state.generating, ready: this.deckReady };
  }

  /**
   * Re-theme the board for a new subject (manual reset path). Clears the deck
   * back to a single welcome slide, marks "no generated deck yet", and refreshes
   * the timestamp.
   */
  reset(subject: string): void {
    this.deckSubject = subject;
    this.deckReady = false;
    this.state = {
      subject,
      slides: [welcomeSlide(subject)],
      current: 0,
      updatedAt: Date.now(),
      generating: false,
    };
  }

  /**
   * Mark the board as actively generating a deck for `subject`: shows a single
   * "Preparing…" slide so the wall has an honest in-progress state until
   * `loadDeck` swaps in the real slides.
   */
  beginGenerating(subject: string): void {
    this.deckSubject = subject;
    this.deckReady = false;
    this.state = {
      subject,
      slides: [
        {
          n: 1,
          title: `Preparing your ${subject} slides…`,
          bullets: ["one moment — building your deck"],
        },
      ],
      current: 0,
      updatedAt: Date.now(),
      generating: true,
    };
  }

  /**
   * Swap in a freshly generated deck. Every raw slide is run through the shared
   * `normalizeSlide` and re-numbered 1.., the deck is capped to MAX_SLIDES, the
   * current slide resets to the first, and generating is cleared. Empty input
   * falls back to a single welcome slide so the board is never blank.
   */
  loadDeck(subject: string, rawSlides: any[]): void {
    // Drop a stale result: if the board was re-themed to a different subject
    // while this Haiku run was in flight, a later ensureDeck owns the board now
    // (its beginGenerating already moved deckSubject), so this deck is obsolete.
    if (subject !== this.deckSubject) return;
    this.deckSubject = subject;
    this.deckReady = true;
    const clean = (Array.isArray(rawSlides) ? rawSlides : [])
      .map((raw) => normalizeSlide(raw))
      .slice(0, MAX_SLIDES)
      .map((s, i) => ({ ...s, n: i + 1 }));
    const slides = clean.length ? clean : [welcomeSlide(subject)];
    this.state = {
      subject,
      slides,
      current: 0,
      updatedAt: Date.now(),
      generating: false,
    };
  }

  /**
   * The tutor auto-advances by selecting which prepared slide is on the wall.
   * `n` is 1-based and clamped to [1, slides.length].
   */
  showSlide(n: number): void {
    const total = this.state.slides.length;
    if (total === 0) return;
    // Coerce defensively: a malformed show_slide call (NaN/missing) must not
    // blank the wall — fall back to the current slide instead.
    const raw = Math.round(Number(n));
    const idx = Number.isFinite(raw)
      ? Math.max(0, Math.min(total - 1, raw - 1))
      : this.state.current;
    this.state = {
      ...this.state,
      current: idx,
      updatedAt: Date.now(),
    };
  }

  /**
   * Step the current slide by {@code delta} (-1 = back, +1 = forward), clamped to
   * the deck. Drives the player's on-wall ‹ › arrows so they can flip through the
   * slides themselves, independent of the tutor's show_slide.
   */
  nudge(delta: number): void {
    const total = this.state.slides.length;
    if (total === 0) return;
    const idx = Math.max(0, Math.min(total - 1, this.state.current + (delta < 0 ? -1 : 1)));
    if (idx === this.state.current) return;
    this.state = { ...this.state, current: idx, updatedAt: Date.now() };
  }

  /**
   * The tutor pushes a new slide as the lesson moves to a new concept. Runs the
   * slide through the shared `normalizeSlide`, assigns the slide number, appends,
   * makes it the current slide, caps the deck to the last MAX_SLIDES, refreshes
   * the timestamp, and returns the slide number.
   */
  addSlide(slide: Omit<Slide, "n">): number {
    const clean = normalizeSlide(slide);
    const n = this.state.slides.length + 1;
    const next = [...this.state.slides, { ...clean, n }];
    // Keep the array bounded (drop from the front). n stays monotonic; that's
    // fine for display — we only ever render the current slide.
    const slides = next.length > MAX_SLIDES ? next.slice(next.length - MAX_SLIDES) : next;
    this.state = {
      ...this.state,
      slides,
      current: slides.length - 1,
      updatedAt: Date.now(),
    };
    return n;
  }

  /**
   * BACK-COMPAT for the old `whiteboard_write` tool: a simple freeform note
   * becomes its own slide with the text in `body`.
   */
  set(patch: { title?: string; content: string }): void {
    this.addSlide({ title: patch.title ?? "Note", body: patch.content });
  }
}

export const whiteboardStore = new WhiteboardStore();

export function whiteboardHtml(): string {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Classroom Whiteboard</title>
<style>
  :root {
    --ink:#16242f; --board:#f7f8f2; --accent:#1565c0; --accent-soft:#e3eefb;
    --rule:#cfd8c5; --muted:#5b6b59; --dot:#c4cdd6; --dot-on:#1565c0;
  }
  *{margin:0;padding:0;box-sizing:border-box;}
  html,body{height:100%;}
  body{
    background:var(--board); color:var(--ink);
    font-family:"Comic Sans MS","Chalkboard SE","Segoe Print","Bradley Hand",system-ui,sans-serif;
    display:flex; flex-direction:column; height:100vh; overflow:hidden;
    background-image:linear-gradient(rgba(0,0,0,.025) 1px, transparent 1px);
    background-size:100% 6vh;
  }
  /* ── accent bar ───────────────────────────────────────────── */
  header{
    display:flex; align-items:center; justify-content:space-between; gap:1em;
    padding:2.2vh 3.5vw; background:var(--accent); color:#fff; flex:0 0 auto;
  }
  #subject{
    font-weight:700; font-size:5vh; text-transform:uppercase; letter-spacing:.05em;
    white-space:nowrap; overflow:hidden; text-overflow:ellipsis; min-width:0;
  }
  .counter{ display:flex; align-items:center; gap:1.4vw; flex:0 0 auto; }
  #count{ font-weight:700; font-size:4.4vh; letter-spacing:.04em; }
  /* A subtle "preparing…" chip shown while the Haiku deck is still generating. */
  #prep{ display:none; align-items:center; gap:.6vw; font-weight:700; font-size:3.2vh;
    letter-spacing:.03em; color:#fff; opacity:.92; }
  #prep.on{ display:inline-flex; }
  #prep .pulse{ width:1.6vh; height:1.6vh; border-radius:50%; background:#fff;
    animation:prep-pulse 1.1s ease-in-out infinite; }
  @keyframes prep-pulse{ 0%,100%{ opacity:.35; transform:scale(.85); } 50%{ opacity:1; transform:scale(1); } }
  #dots{ display:flex; gap:1vw; align-items:center; }
  #dots .d{ width:1.6vh; height:1.6vh; border-radius:50%; background:rgba(255,255,255,.4); }
  #dots .d.on{ background:#fff; }
  /* ── slide body ───────────────────────────────────────────── */
  main{
    flex:1 1 auto; display:flex; flex-direction:column; justify-content:flex-start;
    /* roomy left/right padding so slide content clears the on-wall ‹ › arrows */
    padding:3vh 11.5vw; overflow:hidden; min-height:0;
  }
  #title{
    font-size:8.5vh; font-weight:700; line-height:1.06; margin-bottom:2.4vh;
    color:var(--ink);
    display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden;
  }
  ul#bullets{ list-style:none; margin:0 0 2vh 0; }
  ul#bullets li{
    position:relative; font-size:5.6vh; font-weight:600; line-height:1.22;
    color:#1f3140; padding-left:1.5em; margin-bottom:1.2vh;
    display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:2; overflow:hidden;
  }
  ul#bullets li::before{
    content:"•"; position:absolute; left:0; top:0; color:var(--accent); font-weight:700;
  }
  #example{
    background:var(--accent-soft); border-left:1vh solid var(--accent);
    border-radius:1vh; padding:2vh 2.6vw; margin:1.4vh 0 2vh 0; overflow:hidden;
  }
  #example .lbl{ font-size:3vh; font-weight:700; color:var(--accent);
    text-transform:uppercase; letter-spacing:.08em; display:block; margin-bottom:.5vh; }
  /* Clamp the example TEXT only (not the label), so 'e.g.' never eats a content line. */
  #example .ex-text{
    font-size:5vh; font-weight:600; line-height:1.22; color:#16242f;
    display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:3; overflow:hidden;
  }
  /* When a diagram shares the slide, tighten title + bullets + example to a single
     line each so the diagram stays above the fold on the low-res ~896x384 wall. */
  body.has-diagram #title{ font-size:6.6vh; -webkit-line-clamp:1; margin-bottom:1.4vh; }
  body.has-diagram ul#bullets li{ font-size:4.8vh; -webkit-line-clamp:1; margin-bottom:.7vh; }
  body.has-diagram ul#bullets{ margin-bottom:1.2vh; }
  body.has-diagram #example{ padding:1.4vh 2.4vw; margin:1vh 0 1.4vh 0; }
  body.has-diagram #example .ex-text{ font-size:4.4vh; -webkit-line-clamp:2; }
  #body{
    font-size:5vh; font-weight:600; line-height:1.28; color:#1f3140;
    white-space:pre-wrap; word-break:break-word; margin-bottom:2vh;
    display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:6; overflow:hidden;
  }
  /* ── diagrams ─────────────────────────────────────────────── */
  #diagram{ margin-top:.5vh; overflow:hidden; }
  .steps{ display:flex; flex-direction:column; gap:1.4vh; }
  .steps .step{ display:flex; align-items:flex-start; gap:1.6vw; font-size:4.8vh;
    font-weight:600; line-height:1.18; color:#1f3140;
    display:-webkit-box; -webkit-box-orient:vertical; -webkit-line-clamp:1; overflow:hidden; }
  .steps .num{ flex:0 0 auto; width:1.6em; height:1.6em; border-radius:50%;
    background:var(--accent); color:#fff; font-weight:700; font-size:3.4vh;
    display:inline-flex; align-items:center; justify-content:center; }
  .steps .txt{ flex:1 1 auto; min-width:0; align-self:center;
    overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .compare{ display:flex; gap:2.5vw; }
  .compare .col{ flex:1 1 0; background:var(--accent-soft); border-radius:1vh;
    padding:1.8vh 1.6vw; min-width:0; overflow:hidden; }
  .compare .head{ font-size:4.6vh; font-weight:700; color:var(--accent);
    margin-bottom:1vh; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .compare .col ul{ list-style:none; }
  .compare .col li{ font-size:4vh; font-weight:600; line-height:1.18; margin-bottom:.8vh;
    color:#1f3140; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .timeline{ display:flex; flex-direction:column; gap:1.2vh; }
  .timeline .row{ display:flex; align-items:baseline; gap:1.6vw; }
  .timeline .when{ flex:0 0 auto; font-size:4.2vh; font-weight:700; color:var(--accent);
    min-width:7em; text-align:right; }
  .timeline .what{ flex:1 1 auto; font-size:4.4vh; font-weight:600; color:#1f3140;
    min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .bars{ display:flex; flex-direction:column; gap:1.4vh; }
  .bars .row{ display:flex; align-items:center; gap:1.6vw; }
  .bars .lbl{ flex:0 0 auto; width:9em; font-size:4vh; font-weight:600; color:#1f3140;
    overflow:hidden; text-overflow:ellipsis; white-space:nowrap; text-align:right; }
  .bars .track{ flex:1 1 auto; height:4.6vh; background:#e7ece3; border-radius:.8vh; overflow:hidden; }
  .bars .fill{ height:100%; background:var(--accent); border-radius:.8vh; }
  .bars .val{ flex:0 0 auto; font-size:3.6vh; font-weight:700; color:var(--accent); min-width:3em; }
  svg{ display:block; width:100%; height:auto; }
  /* Player-driven slide arrows. Big click targets on the wall edges — the cinema
     input pipeline dispatches a real DOM click here via CDP (same path as the
     Listening Room's click-to-copy). Sit in the vertical middle so the header
     counter + footer stay visible; dimmed at the ends of the deck. */
  .nav{ position:fixed; top:27vh; height:46vh; width:10vw; z-index:10; cursor:pointer;
    display:flex; align-items:center; justify-content:center; user-select:none;
    font-size:18vh; font-weight:900; line-height:1; color:#fff;
    background:rgba(21,101,192,.82); transition:opacity .15s; }
  .nav.left{ left:0; border-radius:0 2.5vh 2.5vh 0; }
  .nav.right{ right:0; border-radius:2.5vh 0 0 2.5vh; }
  .nav.dim{ opacity:.16; }
</style></head>
<body>
  <header>
    <div id="subject">…</div>
    <div class="counter"><div id="prep"><span class="pulse"></span>preparing…</div><div id="count"></div><div id="dots"></div></div>
  </header>
  <main>
    <div id="title"></div>
    <ul id="bullets"></ul>
    <div id="example" style="display:none"></div>
    <div id="body" style="display:none"></div>
    <div id="diagram"></div>
  </main>
  <div class="nav left" id="navL">‹</div>
  <div class="nav right" id="navR">›</div>
<script>
  const $ = (id) => document.getElementById(id);
  const subjectEl=$('subject'), countEl=$('count'), dotsEl=$('dots'),
        titleEl=$('title'), bulletsEl=$('bullets'), exampleEl=$('example'),
        bodyEl=$('body'), diagramEl=$('diagram'), prepEl=$('prep'),
        navL=$('navL'), navR=$('navR');

  // Flip slides yourself: clicking an arrow on the wall (cinema click → CDP DOM
  // click) tells the runtime to step the current slide. The wall re-renders on
  // its next poll. Independent of the tutor's show_slide.
  function nav(dir){
    fetch('/api/whiteboard/nav',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({dir})}).catch(()=>{});
  }
  navL.onclick=()=>nav('prev');
  navR.onclick=()=>nav('next');

  function clear(el){ while(el.firstChild) el.removeChild(el.firstChild); }
  function el(tag, cls){ const e=document.createElement(tag); if(cls) e.className=cls; return e; }
  function num(v, dflt){ const n=Number(v); return Number.isFinite(n) ? n : dflt; }

  function renderSteps(host, d){
    const items=Array.isArray(d.items)?d.items.slice(0,5):[];
    const wrap=el('div','steps');
    items.forEach((it,i)=>{
      const row=el('div','step');
      const n=el('span','num'); n.textContent=String(i+1);
      const t=el('span','txt'); t.textContent=String(it??'');
      row.appendChild(n); row.appendChild(t); wrap.appendChild(row);
    });
    host.appendChild(wrap);
  }

  function renderCompareCol(side){
    const col=el('div','col');
    const head=el('div','head'); head.textContent=String((side&&side.head)||'');
    col.appendChild(head);
    const ul=el('ul');
    const items=(side&&Array.isArray(side.items))?side.items.slice(0,5):[];
    items.forEach((it)=>{ const li=el('li'); li.textContent=String(it??''); ul.appendChild(li); });
    col.appendChild(ul);
    return col;
  }
  function renderCompare(host, d){
    const wrap=el('div','compare');
    wrap.appendChild(renderCompareCol(d.left||{}));
    wrap.appendChild(renderCompareCol(d.right||{}));
    host.appendChild(wrap);
  }

  function renderNumberLine(host, d){
    let min=num(d.min,0), max=num(d.max,10);
    if(max<=min) max=min+1;
    const ticks=Math.max(2, Math.min(11, Math.round(num(d.ticks,Math.min(11,Math.round(max-min)+1)))));
    const W=1000, H=220, padX=70, axisY=120;
    const x=(v)=> padX + (W-2*padX) * ((v-min)/(max-min));
    const NS='http://www.w3.org/2000/svg';
    const svg=document.createElementNS(NS,'svg');
    svg.setAttribute('viewBox','0 0 '+W+' '+H);
    const mk=(tag,attrs)=>{ const e=document.createElementNS(NS,tag);
      for(const k in attrs) e.setAttribute(k,String(attrs[k])); return e; };
    svg.appendChild(mk('line',{x1:padX,y1:axisY,x2:W-padX,y2:axisY,stroke:'#16242f','stroke-width':5}));
    for(let i=0;i<ticks;i++){
      const v=min+(max-min)*(i/(ticks-1));
      const tx=padX+(W-2*padX)*(i/(ticks-1));
      svg.appendChild(mk('line',{x1:tx,y1:axisY-14,x2:tx,y2:axisY+14,stroke:'#16242f','stroke-width':4}));
      const lab=mk('text',{x:tx,y:axisY+54,'text-anchor':'middle','font-size':40,fill:'#1f3140','font-weight':700});
      lab.textContent=String(Math.round(v*100)/100);
      svg.appendChild(lab);
    }
    if(d.mark!==undefined && Number.isFinite(Number(d.mark))){
      const mv=num(d.mark,min); const mx=x(Math.max(min,Math.min(max,mv)));
      svg.appendChild(mk('circle',{cx:mx,cy:axisY,r:18,fill:'#1565c0',stroke:'#fff','stroke-width':4}));
      const lab=mk('text',{x:mx,y:axisY-34,'text-anchor':'middle','font-size':46,fill:'#1565c0','font-weight':700});
      lab.textContent=(d.label!==undefined&&String(d.label).trim())?String(d.label):String(mv);
      svg.appendChild(lab);
    } else if(d.label!==undefined && String(d.label).trim()){
      const lab=mk('text',{x:W/2,y:axisY-40,'text-anchor':'middle','font-size':44,fill:'#1565c0','font-weight':700});
      lab.textContent=String(d.label); svg.appendChild(lab);
    }
    host.appendChild(svg);
  }

  function renderTimeline(host, d){
    const events=Array.isArray(d.events)?d.events.slice(0,5):[];
    const wrap=el('div','timeline');
    events.forEach((ev)=>{
      const row=el('div','row');
      const w=el('div','when'); w.textContent=String((ev&&ev.when)||'');
      const t=el('div','what'); t.textContent=String((ev&&ev.what)||'');
      row.appendChild(w); row.appendChild(t); wrap.appendChild(row);
    });
    host.appendChild(wrap);
  }

  function renderBars(host, d){
    const items=Array.isArray(d.items)?d.items.slice(0,5):[];
    let maxV=0;
    items.forEach((it)=>{ const v=num(it&&it.value,0); if(v>maxV) maxV=v; });
    if(maxV<=0) maxV=1;
    const wrap=el('div','bars');
    items.forEach((it)=>{
      const v=num(it&&it.value,0);
      const row=el('div','row');
      const lbl=el('div','lbl'); lbl.textContent=String((it&&it.label)||'');
      const track=el('div','track');
      const fill=el('div','fill'); fill.style.width=Math.max(0,Math.min(100,(v/maxV)*100))+'%';
      track.appendChild(fill);
      const val=el('div','val'); val.textContent=String(Math.round(v*100)/100);
      row.appendChild(lbl); row.appendChild(track); row.appendChild(val);
      wrap.appendChild(row);
    });
    host.appendChild(wrap);
  }

  function renderDiagram(d){
    clear(diagramEl);
    if(!d || typeof d!=='object' || !d.kind) return;
    try{
      switch(d.kind){
        case 'steps': renderSteps(diagramEl,d); break;
        case 'compare': renderCompare(diagramEl,d); break;
        case 'number_line': renderNumberLine(diagramEl,d); break;
        case 'timeline': renderTimeline(diagramEl,d); break;
        case 'bars': renderBars(diagramEl,d); break;
        default: break;
      }
    }catch(e){ clear(diagramEl); }
  }

  function renderSlide(s, subject, total, current){
    subjectEl.textContent=(subject||'').trim();
    countEl.textContent=total>0 ? ((current+1)+' / '+total) : '';
    // progress dots (cap so the bar never overflows)
    clear(dotsEl);
    const shown=Math.min(total,12);
    for(let i=0;i<shown;i++){
      const dot=el('span', 'd'+(i===Math.min(current,shown-1)?' on':''));
      dotsEl.appendChild(dot);
    }

    // A diagram + text on one slide is the encouraged shape; when both are present
    // tighten the text block (single-line bullets, ≤3 bullets) so the diagram below
    // it stays visible instead of being pushed off the bottom of the frame.
    const hasDiagram = !!(s.diagram && typeof s.diagram==='object' && s.diagram.kind);
    document.body.classList.toggle('has-diagram', hasDiagram);

    titleEl.textContent=(s.title||'').trim();

    clear(bulletsEl);
    const bullets=Array.isArray(s.bullets)?s.bullets.slice(0, hasDiagram?3:4):[];
    bullets.forEach((b)=>{ const li=el('li'); li.textContent=String(b??''); bulletsEl.appendChild(li); });
    bulletsEl.style.display = bullets.length ? '' : 'none';

    if(s.example!==undefined && String(s.example).trim()){
      clear(exampleEl);
      const lbl=el('span','lbl'); lbl.textContent='e.g.';
      const txt=el('div','ex-text'); txt.textContent=String(s.example);
      exampleEl.appendChild(lbl); exampleEl.appendChild(txt);
      exampleEl.style.display='';
    } else { exampleEl.style.display='none'; clear(exampleEl); }

    if(s.body!==undefined && String(s.body).trim()){
      bodyEl.textContent=String(s.body); bodyEl.style.display='';
    } else { bodyEl.style.display='none'; bodyEl.textContent=''; }

    renderDiagram(s.diagram);
  }

  let lastStamp=-1;
  async function tick(){
    try{
      const r=await fetch('/api/whiteboard/state',{cache:'no-store'});
      const st=await r.json();
      // Only re-render when the deck actually changed; the wall refreshes ~1fps.
      if(typeof st.updatedAt==='number' && st.updatedAt===lastStamp) return;
      lastStamp=(typeof st.updatedAt==='number')?st.updatedAt:lastStamp;
      const slides=Array.isArray(st.slides)?st.slides:[];
      const total=slides.length;
      let cur=(typeof st.current==='number')?st.current:total-1;
      if(cur<0) cur=0; if(cur>total-1) cur=total-1;
      const slide=total>0 ? slides[cur] : {title:(st.subject||'')};
      renderSlide(slide||{}, st.subject||'', total, cur);
      prepEl.classList.toggle('on', !!st.generating);
      // Dim arrows at the ends of the deck (clicking still no-ops via clamp).
      navL.classList.toggle('dim', cur<=0);
      navR.classList.toggle('dim', cur>=total-1);
    }catch(e){ /* keep last frame; runtime may be reloading */ }
  }
  tick(); setInterval(tick, 1100);
</script>
</body></html>`;
}
