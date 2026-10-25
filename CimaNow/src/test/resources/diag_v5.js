const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const zlib = require('zlib');

const [,, shineUrl, refererUrl, outPath] = process.argv;
const chromeBin = ['/usr/bin/google-chrome-stable', '/usr/bin/google-chrome', '/usr/bin/chromium-browser', '/usr/bin/chromium']
  .find(p => fs.existsSync(p));
const xvfb = '/usr/bin/xvfb-run';

function getJson(u) {
  return new Promise((res, rej) => {
    http.get(u, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => { try { res(JSON.parse(d)); } catch (e) { rej(e); } });
    }).on('error', rej);
  });
}

(async () => {
  const port = 19378;
  const profDir = '/tmp/cn_diag5j_' + process.pid;
  const args = [
    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--user-data-dir=' + profDir,
    '--disable-blink-features=AutomationControlled',
    '--window-size=412,915',
    '--remote-debugging-port=' + port,
    'about:blank'
  ];
  const proc = fs.existsSync(xvfb)
    ? spawn(xvfb, ['-a', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
    : spawn(chromeBin, ['--headless=new', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });

  try {
    let targets = null;
    for (let i = 0; i < 30; i++) {
      try { targets = await getJson('http://127.0.0.1:' + port + '/json'); if (targets.length) break; } catch (e) {}
      await new Promise(r => setTimeout(r, 300));
    }
    const t = targets.find(x => x.type === 'page') || targets[0];
    const ws = new WebSocket(t.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

    let id = 1;
    const pending = new Map();
    const candidates = [];
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
      if (m.method === 'Debugger.paused') {
        ws.send(JSON.stringify({ id: id++, method: 'Debugger.resume', params: {} }));
      }
      if (m.method === 'Debugger.scriptParsed') {
        const u = m.params.url || '';
        const len = m.params.length || 0;
        if (!u && len >= 140000 && len <= 144500) {
          candidates.push({ id: m.params.scriptId, len });
        }
      }
    };
    const send = (method, params = {}, ms = 2500) => Promise.race([
      new Promise(r => {
        const cid = id++;
        pending.set(cid, r);
        ws.send(JSON.stringify({ id: cid, method, params }));
      }),
      new Promise(r => setTimeout(() => r(null), ms))
    ]);

    await send('Page.enable');
    await send('Debugger.enable');
    await send('Debugger.setBreakpointsActive', { active: false });
    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });
    await new Promise(r => setTimeout(r, 11000));

    let picked = null;
    for (const c of [...candidates].reverse()) {
      const sr = await send('Debugger.getScriptSource', { scriptId: c.id }, 2000);
      const src = (sr && sr.result && sr.result.scriptSource) ? sr.result.scriptSource : '';
      if (src) {
        picked = { len: src.length, brB64: zlib.brotliCompressSync(Buffer.from(src, 'utf8')).toString('base64') };
        break;
      }
    }

    fs.writeFileSync(outPath, JSON.stringify({ candidatesCount: candidates.length, picked }), 'utf8');
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
  process.exit(0);
})();
