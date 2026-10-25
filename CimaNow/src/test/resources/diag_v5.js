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
  const port = 19370;
  const profDir = '/tmp/cn_diag5h_' + process.pid;
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
        if (!u && len >= 135000 && len <= 225000) {
          candidates.push({ id: m.params.scriptId, len });
        }
      }
    };
    const send = (method, params = {}) => new Promise(r => {
      const cid = id++;
      pending.set(cid, r);
      ws.send(JSON.stringify({ id: cid, method, params }));
    });

    await send('Page.enable');
    await send('Debugger.enable');
    await send('Debugger.setBreakpointsActive', { active: false });
    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });
    await new Promise(r => setTimeout(r, 7500));

    // Find the script whose source contains XMLHttpRequest or turnstile or get-link or استخدم after decoding!
    const found = [];
    for (const c of candidates) {
      const sr = await send('Debugger.getScriptSource', { scriptId: c.id });
      const src = (sr && sr.result && sr.result.scriptSource) ? sr.result.scriptSource : '';
      if (!src) continue;
      // Extract only the string blob + const table + alphabets (~12KB instead of 145KB!) AND gzip!
      const tblM = src.match(/const\s+[A-Za-z0-9_$]+\s*=\s*\[[^\]]{40,3500}\]/);
      const bigBlobs = [];
      const bigRe = /"((?:\\.|[^"\\]){1200,25000})"/g;
      let bm;
      while ((bm = bigRe.exec(src)) !== null) bigBlobs.push(bm[0]);
      const alphas = [];
      const aRe = /"((?:\\.|[^"\\]){85,105})"/g;
      let am;
      while ((am = aRe.exec(src)) !== null) {
        try {
          const s = JSON.parse(am[0]);
          if (s.length === 91 && new Set(s).size === 91) alphas.push(s);
        } catch (e) {}
      }
      const calls = [];
      const cRe = /\b[A-Za-z0-9_$]+\(\s*([A-Za-z0-9_$]+\[(?:0x[0-9a-fA-F]+|\d+)\]|0x[0-9a-fA-F]+|\d+)\s*,\s*([A-Za-z0-9_$]+\[(?:0x[0-9a-fA-F]+|\d+)\]|0x[0-9a-fA-F]+|\d+)\s*\)/g;
      let cm;
      while ((cm = cRe.exec(src)) !== null) calls.push([cm[1], cm[2]]);
      found.push({
        len: src.length,
        tbl: tblM ? tblM[0] : '',
        blobsCount: bigBlobs.length,
        blobsGz: zlib.gzipSync(Buffer.from(JSON.stringify(bigBlobs), 'utf8')).toString('base64'),
        alphas,
        callsCount: calls.length,
        callsGz: zlib.gzipSync(Buffer.from(JSON.stringify(calls), 'utf8')).toString('base64')
      });
    }

    fs.writeFileSync(outPath, JSON.stringify({ found }, null, 2), 'utf8');
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
  process.exit(0);
})();
