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

async function runScenario(name, port, winSize, setupCdpFn, captureScripts) {
  const profDir = '/tmp/cn_diag5c_' + process.pid + '_' + port;
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
  const gzipScripts = [];
  const netErrors = [];

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
    const scriptIds = [];
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
        if (u.includes('blog-post') && len > 4000 && len < 250000) {
          scriptIds.push({ id: m.params.scriptId, url: u, len });
        }
      }
      if (m.method === 'Network.requestWillBeSent') {
        const u = m.params.request.url || '';
        if (u.includes('get-link.php')) glReqId = m.params.requestId;
      }
      if (m.method === 'Network.responseReceived') {
        const u = m.params.response.url || '';
        if (u.includes('get-link.php')) getLinkStatus = m.params.response.status;
        if (m.params.response.status >= 400) netErrors.push(m.params.response.status + ':' + u.slice(0, 100));
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
    if (captureScripts) {
      await send('Debugger.enable');
      await send('Debugger.setBreakpointsActive', { active: false });
    }
    if (setupCdpFn) await setupCdpFn(send, majorVer, fullVer);

    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });

    for (let ms = 0; ms < 14000; ms += 1000) {
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

    if (captureScripts) {
      for (const s of scriptIds) {
        const sr = await send('Debugger.getScriptSource', { scriptId: s.id });
        const src = (sr && sr.result && sr.result.scriptSource) ? sr.result.scriptSource : '';
        if (!src) continue;
        gzipScripts.push({
          len: src.length,
          gzB64: zlib.gzipSync(Buffer.from(src, 'utf8')).toString('base64')
        });
      }
    }

    const snap = await send('Runtime.evaluate', {
      expression: `(function(){
        var btn = document.getElementById('downloadbtn');
        var inp = document.querySelector('input[name="cf-turnstile-response"]');
        return JSON.stringify({
          loc: location.href,
          btnHref: btn ? btn.getAttribute('href') : null,
          tsLen: (inp && inp.value) ? inp.value.length : -1,
          bodyTxt: (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').slice(0, 160),
          ua: navigator.userAgent,
          hasChrome: typeof window.chrome,
          hasNotif: typeof window.Notification
        });
      })()`,
      returnByValue: true
    });
    const finalState = (snap && snap.result && snap.result.result && snap.result.result.value)
      ? JSON.parse(snap.result.result.value)
      : {};

    return { name, fullVer, getLinkStatus, getLinkBody: getLinkBody.slice(0, 180), netErrors: netErrors.slice(0, 10), finalState, gzipScripts };
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
}

(async () => {
  const out = {};

  // 1. Capture gzipped Stage B scripts (56KB + 144KB)
  out.s1_decode = await runScenario('1_DecodeStageB', 19320, '412,915', null, true);

  // 2. Native Desktop UA + Touch enabled (isolates touch/maxTouchPoints)
  out.s2_touchOnly = await runScenario('2_TouchOnly_NativeUA', 19321, '360,800', async (send) => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  }, false);

  // 3. Matched Chrome/155 Android UA + Matched Brands + Touch
  out.s3_matchedAndroid = await runScenario('3_MatchedVerAndroid', 19322, '360,800', async (send, major, full) => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 2, mobile: true });
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36`,
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [
          { brand: 'Google Chrome', version: major },
          { brand: 'Chromium', version: major },
          { brand: 'Not(A:Brand', version: '24' }
        ],
        fullVersionList: [
          { brand: 'Google Chrome', version: full },
          { brand: 'Chromium', version: full },
          { brand: 'Not(A:Brand', version: '24.0.0.0' }
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true
      }
    });
  }, false);

  // 4. Desktop Windows UA with MATCHED Chrome/155 + empty low-entropy brands (what Android WebView sends when userAgentString = Windows Chrome/150!)
  out.s4_matchedDesktopUaEmptyBrands = await runScenario('4_MatchedDesktopUa_EmptyBrands', 19323, '360,800', async (send, major) => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`,
      userAgentMetadata: {
        brands: [],
        platform: '',
        platformVersion: '',
        architecture: '',
        model: '',
        mobile: false
      }
    });
  }, false);

  // 5. Simulate Android WebView globals (`delete window.chrome; delete window.Notification`) with Desktop UA
  out.s5_noWinChromeNoNotif = await runScenario('5_NoWinChromeNoNotif', 19324, '360,800', async (send) => {
    await send('Page.addScriptToEvaluateOnNewDocument', {
      source: 'try { delete window.chrome; delete window.Notification; } catch(e) {}'
    });
  }, false);

  fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');
  process.exit(0);
})();
