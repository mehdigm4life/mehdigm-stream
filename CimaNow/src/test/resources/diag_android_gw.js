const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');

const shineUrl = process.argv[2];
const refererUrl = process.argv[3];
const outPath = process.argv[4];

const chromeCands = [
  '/usr/bin/google-chrome-stable',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
  '/usr/bin/chromium',
];
const chromeBin = chromeCands.find(p => fs.existsSync(p));
const xvfb = '/usr/bin/xvfb-run';

function getJson(u) {
  return new Promise((res, rej) => {
    http.get(u, r => {
      let d = '';
      r.on('data', c => (d += c));
      r.on('end', () => {
        try { res(JSON.parse(d)); } catch (e) { rej(e); }
      });
    }).on('error', rej);
  });
}

(async () => {
  const profDir = '/tmp/cn_trap_' + process.pid;
  const port = 19455;
  const args = [
    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--user-data-dir=' + profDir,
    '--disable-blink-features=AutomationControlled',
    '--window-size=412,915',
    '--remote-debugging-port=' + port,
    'about:blank',
  ];
  const proc = fs.existsSync(xvfb)
    ? spawn(xvfb, ['-a', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
    : spawn(chromeBin, ['--headless=new', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
  if (proc.stderr) proc.stderr.resume();

  const out = { logs: [] };
  try {
    let verInfo = null;
    let targets = null;
    for (let i = 0; i < 30; i++) {
      try {
        verInfo = await getJson('http://127.0.0.1:' + port + '/json/version');
        targets = await getJson('http://127.0.0.1:' + port + '/json');
        if (targets && targets.length) break;
      } catch (e) {}
      await new Promise(r => setTimeout(r, 350));
    }
    const browserVerFull = (verInfo && verInfo.Browser ? verInfo.Browser.replace(/^Chrome\//, '') : '131.0.0.0');
    const browserMajor = browserVerFull.split('.')[0];
    const t = targets.find(x => x.type === 'page') || targets[0];
    const ws = new WebSocket(t.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    let id = 1;
    const pending = new Map();
    const events = [];
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
      else if (m.method) events.push(m);
    };
    const send = (method, params = {}) =>
      new Promise(r => { const cid = id++; pending.set(cid, r); ws.send(JSON.stringify({ id: cid, method, params })); });

    await send('Page.enable');
    await send('Network.enable');

    await send('Network.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${browserMajor}.0.0.0 Mobile Safari/537.36`,
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [
          { brand: 'Not/A)Brand', version: '8' },
          { brand: 'Chromium', version: browserMajor },
          { brand: 'Google Chrome', version: browserMajor },
        ],
        fullVersionList: [
          { brand: 'Not/A)Brand', version: '8.0.0.0' },
          { brand: 'Chromium', version: browserVerFull },
          { brand: 'Google Chrome', version: browserVerFull },
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true,
      },
    });

    await send('Page.addScriptToEvaluateOnNewDocument', {
      source: `(function(){
        const traps = [];
        const sym = Symbol.for('cn_diag_traps');
        Object.defineProperty(Array.prototype, sym, { value: traps, configurable: true, enumerable: false });
        const ihDesc = Object.getOwnPropertyDescriptor(Element.prototype, 'innerHTML');
        if (ihDesc && ihDesc.set) {
          const origSet = ihDesc.set;
          const patched = function(v) {
            const s = String(v || '');
            if (s.includes('متصفح') || s.includes('استخدم') || s.includes('cf-turnstile') || s.includes('data-sitekey')) {
              traps.push({
                t: Math.round(performance.now()),
                type: 'innerHTML',
                tag: this && this.tagName,
                id: this && this.id,
                cls: this && this.className,
                val: s.slice(0, 320),
                stack: (new Error().stack || '').slice(0, 600),
              });
            }
            return origSet.call(this, v);
          };
          Object.defineProperty(Element.prototype, 'innerHTML', { configurable: true, enumerable: true, get: ihDesc.get, set: patched });
        }
        const tcDesc = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
        if (tcDesc && tcDesc.set) {
          const origTc = tcDesc.set;
          const patchedTc = function(v) {
            const s = String(v || '');
            if (s.includes('متصفح') || s.includes('استخدم')) {
              traps.push({
                t: Math.round(performance.now()),
                type: 'textContent',
                tag: this && this.tagName,
                id: this && this.id,
                cls: this && this.className,
                val: s.slice(0, 320),
                stack: (new Error().stack || '').slice(0, 600),
              });
            }
            return origTc.call(this, v);
          };
          Object.defineProperty(Node.prototype, 'textContent', { configurable: true, enumerable: true, get: tcDesc.get, set: patchedTc });
        }
      })();`,
    });

    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });
    let idx = 0;
    for (let ms = 500; ms <= 14500; ms += 500) {
      await new Promise(r => setTimeout(r, 500));
      while (idx < events.length) {
        const ev = events[idx++];
        if (ev.method === 'Network.requestWillBeSent') {
          const u = ev.params.request.url || '';
          if (u.includes('challenges.cloudflare.com') || u.includes('freex2line') || u.includes('get-link')) {
            out.logs.push(`t=${ms} REQ ${ev.params.request.method} ${u.slice(0, 150)}`);
          }
        } else if (ev.method === 'Network.responseReceived') {
          const u = ev.params.response.url || '';
          if (u.includes('challenges.cloudflare.com') || u.includes('freex2line') || u.includes('get-link')) {
            out.logs.push(`t=${ms} RES ${ev.params.response.status} ${u.slice(0, 150)}`);
          }
        }
      }
      if (ms >= 13500) {
        const r = await send('Runtime.evaluate', {
          expression: `JSON.stringify({
            loc: location.href,
            bodyHtml: (document.body && document.body.innerHTML || '').replace(/\\s+/g,' ').slice(0, 700),
            traps: [][Symbol.for('cn_diag_traps')] || [],
            env: {
              ua: navigator.userAgent,
              plat: navigator.platform,
              mtp: navigator.maxTouchPoints,
              touchInWin: 'ontouchstart' in window,
              iw: window.innerWidth,
              ow: window.outerWidth,
            },
            iframes: Array.from(document.querySelectorAll('iframe')).map(f => ({ src: (f.src||'').slice(0,110), w: Math.round(f.getBoundingClientRect().width), h: Math.round(f.getBoundingClientRect().height) }))
          })`,
          returnByValue: true,
        });
        out.finalEval = JSON.parse(r?.result?.result?.value || '{}');
        break;
      }
    }
    try { ws.close(); } catch (e) {}
  } catch (e) {
    out.err = String(e && e.stack || e);
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }

  fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');
  process.exit(0);
})();
