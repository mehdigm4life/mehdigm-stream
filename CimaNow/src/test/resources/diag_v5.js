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

function slimHtml(html) {
  // Replace huge obfuscated scripts (>10KB) we already unpacked so we keep ALL small/medium scripts (<10KB) + full HTML!
  return html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, m => {
    if (m.length > 10000) return `<!-- LARGE_SCRIPT_${m.length} -->`;
    return m;
  });
}

async function runScenario(name, port, winSize, setupCdpFn, captureHtmlBodies) {
  const profDir = '/tmp/cn_diag5e_' + process.pid + '_' + port;
  const args = [
    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--user-data-dir=' + profDir,
    '--disable-blink-features=AutomationControlled',
    '--window-size=' + winSize,
    '--remote-debugging-port=' + port,
    'about:blank'
  ];
  const proc = fs.existsSync(xvfb)
    ? spawn(xvfb, ['-a', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
    : spawn(chromeBin, ['--headless=new', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });

  let getLinkStatus = 0;
  let getLinkBody = '';
  const docReqs = [];
  const pagesGz = [];

  try {
    let targets = null;
    for (let i = 0; i < 30; i++) {
      try { targets = await getJson('http://127.0.0.1:' + port + '/json'); if (targets.length) break; } catch (e) {}
      await new Promise(r => setTimeout(r, 350));
    }
    if (!targets || !targets.length) return { name, error: 'no_targets' };
    const t = targets.find(x => x.type === 'page') || targets[0];
    const ws = new WebSocket(t.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

    let id = 1;
    const pending = new Map();
    const smallScripts = [];
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
        if (len > 40 && len <= 40000 && (!u || u.includes('freex2line'))) {
          smallScripts.push({ id: m.params.scriptId, url: u, len });
        }
      }
      if (m.method === 'Network.requestWillBeSent') {
        const u = m.params.request.url || '';
        if (u.includes('get-link.php')) glReqId = m.params.requestId;
        if (m.params.type === 'Document' && u.includes('freex2line')) {
          docReqs.push({ reqId: m.params.requestId, url: u, reqHdrs: m.params.request.headers });
        }
      }
      if (m.method === 'Network.responseReceived') {
        const u = m.params.response.url || '';
        if (u.includes('get-link.php')) getLinkStatus = m.params.response.status;
        const dr = docReqs.find(d => d.reqId === m.params.requestId);
        if (dr) dr.status = m.params.response.status;
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
    if (captureHtmlBodies) {
      await send('Debugger.enable');
      await send('Debugger.setBreakpointsActive', { active: false });
    }
    if (setupCdpFn) await setupCdpFn(send, majorVer, fullVer);

    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });

    for (let ms = 0; ms < 13000; ms += 1000) {
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

    const capturedSmallScripts = [];
    if (captureHtmlBodies) {
      for (const dr of docReqs) {
        const rb = await send('Network.getResponseBody', { requestId: dr.reqId });
        if (rb && rb.result && rb.result.body) {
          const raw = rb.result.base64Encoded
            ? Buffer.from(rb.result.body, 'base64').toString('utf8')
            : rb.result.body;
          const slim = slimHtml(raw);
          pagesGz.push({
            url: dr.url.slice(0, 100),
            status: dr.status,
            rawLen: raw.length,
            slimGzB64: zlib.gzipSync(Buffer.from(slim, 'utf8')).toString('base64')
          });
        }
      }
      for (const s of smallScripts) {
        const sr = await send('Debugger.getScriptSource', { scriptId: s.id });
        const src = (sr && sr.result && sr.result.scriptSource) ? sr.result.scriptSource : '';
        if (!src || src.length > 35000) continue;
        capturedSmallScripts.push({
          url: s.url.slice(0, 90),
          len: src.length,
          gzB64: zlib.gzipSync(Buffer.from(src, 'utf8')).toString('base64')
        });
      }
    }

    const snap = await send('Runtime.evaluate', {
      expression: `(function(){
        var btn = document.getElementById('downloadbtn');
        return JSON.stringify({
          loc: location.href,
          btnHref: btn ? btn.getAttribute('href') : null,
          bodyTxt: (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').slice(0, 160)
        });
      })()`,
      returnByValue: true
    });
    const finalState = (snap && snap.result && snap.result.result && snap.result.result.value)
      ? JSON.parse(snap.result.result.value)
      : {};

    return {
      name,
      getLinkStatus,
      getLinkBody: getLinkBody.slice(0, 160),
      docSummary: docReqs.map(d => ({ url: d.url.slice(0, 90), status: d.status, hdrs: d.reqHdrs })),
      finalState,
      pagesGz,
      capturedSmallScripts
    };
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
}

(async () => {
  const out = {};

  // 1. Passing Desktop run: capture ALL document HTMLs + ALL small/medium scripts (<35KB)
  out.u1_passHtml = await runScenario('1_PassHtml', 19340, '412,915', null, true);

  // 2. Failing Android WebView run: capture ALL document HTMLs + ALL small/medium scripts (<35KB)
  out.u2_failWvHtml = await runScenario('2_FailWvHtml', 19341, '360,800', async (send, major, full) => {
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${full} Mobile Safari/537.36`,
      platform: 'Linux armv81',
      userAgentMetadata: {
        brands: [
          { brand: 'Not(A:Brand', version: '8' },
          { brand: 'Chromium', version: major },
          { brand: 'Android WebView', version: major }
        ],
        fullVersionList: [
          { brand: 'Not(A:Brand', version: '8.0.0.0' },
          { brand: 'Chromium', version: full },
          { brand: 'Android WebView', version: full }
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true
      }
    });
  }, true);

  // 3. Test Android Chrome UA WITHOUT overriding navigator.platform (keeps Linux x86_64 matching WebGL/OS!)
  out.u3_androidUaKeepPlatform = await runScenario('3_AndroidUaKeepPlatform', 19342, '360,800', async (send, major, full) => {
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36`,
      userAgentMetadata: {
        brands: [
          { brand: 'Not(A:Brand', version: '8' },
          { brand: 'Chromium', version: major },
          { brand: 'Google Chrome', version: major }
        ],
        fullVersionList: [
          { brand: 'Not(A:Brand', version: '8.0.0.0' },
          { brand: 'Chromium', version: full },
          { brand: 'Google Chrome', version: full }
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'K',
        mobile: true
      }
    });
  }, false);

  fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');
  process.exit(0);
})();
