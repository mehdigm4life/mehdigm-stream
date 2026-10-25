const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');

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

function b91Bytes(str, alpha) {
  const out = [];
  let b = 0, n = 0, v = -1;
  for (let i = 0; i < str.length; i++) {
    const p = alpha.indexOf(str[i]);
    if (p === -1) return null;
    if (v < 0) v = p;
    else {
      v += p * 91;
      b |= v << n;
      n += (v & 8191) > 88 ? 13 : 14;
      do { out.push(b & 255); b >>= 8; n -= 8; } while (n > 7);
      v = -1;
    }
  }
  if (v > -1) out.push((b | (v << n)) & 255);
  return Buffer.from(out);
}

function isCleanUtf8(buf) {
  if (!buf || !buf.length) return false;
  const s = buf.toString('utf8');
  if (Buffer.from(s, 'utf8').compare(buf) !== 0) return false;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 32 && c !== 9 && c !== 10 && c !== 13) return false;
    if (c >= 0x7f && c < 0xa0) return false;
    if (c > 0x06ff && (c < 0x2000 || c > 0x206f)) return false;
  }
  return true;
}

function decodeJsConfuserStrings(code) {
  const alphas = [];
  const strRe = /"((?:\\.|[^"\\]){85,105})"/g;
  let m;
  while ((m = strRe.exec(code)) !== null) {
    try {
      const s = JSON.parse(m[0]);
      if (s.length === 91 && new Set(s).size === 91) alphas.push(s);
    } catch (e) {}
  }
  if (!alphas.length) return { plainSample: code.slice(0, 2500) };

  const decoded = new Set();
  // Mode 1: array of b91 literals
  const litRe = /"((?:\\.|[^"\\]){3,220})"/g;
  while ((m = litRe.exec(code)) !== null) {
    let lit;
    try { lit = JSON.parse(m[0]); } catch (e) { continue; }
    if (lit.length === 91 && new Set(lit).size === 91) continue;
    for (const a of alphas) {
      const buf = b91Bytes(lit, a);
      if (isCleanUtf8(buf)) {
        const s = buf.toString('utf8');
        if (/[a-zA-Z0-9_\u0600-\u06FF\/.-]{3,}/.test(s) && !/^[A-Za-z0-9]{18,}$/.test(s)) {
          decoded.add(s);
        }
      }
    }
    
  }

  // Mode 2: giant concatenated blob + YmWIB_T/const table slices
  const bigRe = /"((?:\\.|[^"\\]){1500,20000})"/g;
  while ((m = bigRe.exec(code)) !== null) {
    let blob;
    try { blob = JSON.parse(m[0]); } catch (e) { continue; }
    const tblM = code.match(/const\s+[A-Za-z0-9_$]+\s*=\s*(\[[^\]]{40,2500}\])/);
    let tbl = [];
    if (tblM) { try { tbl = eval(tblM[1]); } catch (e) {} }
    const evalArg = expr => {
      expr = expr.trim();
      const tm = expr.match(/^[A-Za-z0-9_$]+\[(0x[0-9a-fA-F]+|\d+)\]$/);
      if (tm) return tbl[Number(tm[1])];
      if (/^(0x[0-9a-fA-F]+|\d+)$/.test(expr)) return Number(expr);
      return null;
    };
    const callRe = /\b[A-Za-z0-9_$]+\(\s*([A-Za-z0-9_$]+\[(?:0x[0-9a-fA-F]+|\d+)\]|0x[0-9a-fA-F]+|\d+)\s*,\s*([A-Za-z0-9_$]+\[(?:0x[0-9a-fA-F]+|\d+)\]|0x[0-9a-fA-F]+|\d+)\s*\)/g;
    let cm;
    while ((cm = callRe.exec(code)) !== null) {
      const off = evalArg(cm[1]);
      const len = evalArg(cm[2]);
      if (typeof off !== 'number' || typeof len !== 'number' || off < 0 || len <= 0 || off + len > blob.length) continue;
      const sub = blob.slice(off, off + len);
      for (const a of alphas) {
        const buf = b91Bytes(sub, a);
        if (isCleanUtf8(buf)) {
          const s = buf.toString('utf8');
          if (/[a-zA-Z0-9_\u0600-\u06FF\/.-]{3,}/.test(s)) decoded.add(s);
        }
      }
      
    }
  }
  return { alphas: alphas.length, strings: [...decoded] };
}

async function runFull(name, port, waitMs, setupCdpFn, inspectBigScripts) {
  const profDir = '/tmp/cn_diag5g_' + process.pid + '_' + port;
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

  let getLinkStatus = 0;
  let getLinkBody = '';

  try {
    let targets = null;
    for (let i = 0; i < 30; i++) {
      try { targets = await getJson('http://127.0.0.1:' + port + '/json'); if (targets.length) break; } catch (e) {}
      await new Promise(r => setTimeout(r, 300));
    }
    if (!targets || !targets.length) return { name, error: 'no_targets' };
    const t = targets.find(x => x.type === 'page') || targets[0];
    const ws = new WebSocket(t.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

    let id = 1;
    const pending = new Map();
    const parsedScripts = [];
    let glReqId = '';

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
          parsedScripts.push({ id: m.params.scriptId, url: u, len });
        }
      }
      if (m.method === 'Network.requestWillBeSent') {
        const u = m.params.request.url || '';
        if (u.includes('get-link.php')) glReqId = m.params.requestId;
      }
      if (m.method === 'Network.responseReceived') {
        const u = m.params.response.url || '';
        if (u.includes('get-link.php')) getLinkStatus = m.params.response.status;
      }
    };

    const send = (method, params = {}) => new Promise(r => {
      const cid = id++;
      pending.set(cid, r);
      ws.send(JSON.stringify({ id: cid, method, params }));
    });

    const verRes = await send('Browser.getVersion');
    const fullVer = (verRes && verRes.result && verRes.result.product) ? verRes.result.product.replace('Chrome/', '') : '155.0.0.0';
    const majorVer = fullVer.split('.')[0];

    await send('Page.enable');
    await send('Network.enable');
    if (inspectBigScripts) {
      await send('Debugger.enable');
      await send('Debugger.setBreakpointsActive', { active: false });
    }
    if (setupCdpFn) await setupCdpFn(send, majorVer, fullVer);

    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });

    for (let ms = 0; ms < waitMs; ms += 1000) {
      await new Promise(r => setTimeout(r, 1000));
      if (glReqId && !getLinkBody) {
        const rb = await send('Network.getResponseBody', { requestId: glReqId });
        if (rb && rb.result && rb.result.body) {
          getLinkBody = rb.result.base64Encoded
            ? Buffer.from(rb.result.body, 'base64').toString('utf8')
            : rb.result.body;
        }
      }
    }

    const scriptReport = [];
    if (inspectBigScripts) {
      for (const s of parsedScripts) {
        const sr = await send('Debugger.getScriptSource', { scriptId: s.id });
        const src = (sr && sr.result && sr.result.scriptSource) ? sr.result.scriptSource : '';
        if (!src) continue;
        scriptReport.push({
          url: s.url.slice(0, 80),
          len: src.length,
          dec: decodeJsConfuserStrings(src)
        });
      }
    }

    const snap = await send('Runtime.evaluate', {
      expression: `(function(){
        var btn = document.getElementById('downloadbtn');
        return JSON.stringify({
          loc: location.href,
          hasBtn: !!btn,
          btnHref: btn ? btn.getAttribute('href') : null,
          bodyTxt: (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').slice(0, 140)
        });
      })()`,
      returnByValue: true
    });
    const st = (snap && snap.result && snap.result.result && snap.result.result.value)
      ? JSON.parse(snap.result.result.value)
      : {};

    return { name, getLinkStatus, getLinkBody: getLinkBody.slice(0, 140), st, scriptReport };
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
}

(async () => {
  const out = {};
  out.d1_allStrings = await runFull('1_AllStrings_143k_215k', 19360, 13000, null, true);
  fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');
  process.exit(0);
})();
