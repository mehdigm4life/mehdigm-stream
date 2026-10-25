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

async function runScenario(name, port, setupCdpFn, enableDebugger) {
  const profDir = '/tmp/cn_diag5_' + process.pid + '_' + port;
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

  const logs = [];
  const scriptsCaptured = [];
  let getLinkStatus = 0;
  let getLinkBody = '';
  let getLinkPostData = '';
  let turnstileFlows = [];

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
      if (m.method === 'Debugger.scriptParsed') {
        const u = m.params.url || '';
        if (!u || u.includes('freex2line') || u.includes('blog-post')) {
          scriptIds.push({ id: m.params.scriptId, url: u, len: m.params.length || 0 });
        }
      }
      if (m.method === 'Network.requestWillBeSent') {
        const u = m.params.request.url || '';
        if (u.includes('get-link.php')) {
          glReqId = m.params.requestId;
          getLinkPostData = (m.params.request.postData || '').slice(0, 300);
          logs.push('REQ_GL hdrs=' + JSON.stringify(m.params.request.headers || {}));
        }
        if (u.includes('challenges.cloudflare.com') && (u.includes('/flow/') || u.includes('/turnstile/'))) {
          turnstileFlows.push('REQ:' + u.slice(0, 120));
        }
        if (u.includes('blog-post.html/')) {
          logs.push('REQ_BLOG hdrs=' + JSON.stringify(m.params.request.headers || {}));
        }
      }
      if (m.method === 'Network.responseReceived') {
        const u = m.params.response.url || '';
        if (u.includes('get-link.php')) {
          getLinkStatus = m.params.response.status;
          logs.push('RES_GL status=' + getLinkStatus);
        }
        if (u.includes('challenges.cloudflare.com') && (u.includes('/flow/') || u.includes('error'))) {
          turnstileFlows.push('RES:' + m.params.response.status + ':' + u.slice(0, 110));
        }
      }
    };

    const send = (method, params = {}) => new Promise(r => {
      const cid = id++;
      pending.set(cid, r);
      ws.send(JSON.stringify({ id: cid, method, params }));
    });

    await send('Page.enable');
    await send('Network.enable');
    if (enableDebugger) await send('Debugger.enable');
    if (setupCdpFn) await setupCdpFn(send);

    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });

    for (let ms = 0; ms < 16000; ms += 1000) {
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

    if (enableDebugger) {
      for (const s of scriptIds) {
        const sr = await send('Debugger.getScriptSource', { scriptId: s.id });
        const src = (sr && sr.result && sr.result.scriptSource) ? sr.result.scriptSource : '';
        if (!src) continue;
        const interesting = src.includes('متصفح') ||
          src.includes('0645\\u062a\\u0635\\u0641\\u062d') ||
          src.includes('turnstile') ||
          src.includes('get-link') ||
          src.includes('downloadbtn') ||
          src.includes('wv') ||
          src.includes('WebView') ||
          src.includes('userAgentData') ||
          (s.url === '' && src.length > 1500);
        if (interesting) {
          scriptsCaptured.push({
            url: s.url,
            len: src.length,
            head: src.slice(0, 2200),
            tail: src.length > 2200 ? src.slice(-2200) : '',
            matches: (src.match(/.{0,120}(?:متصفح|turnstile|error-callback|get-link|WebView|userAgent|webdriver|maxTouchPoints|chrome).{0,120}/gi) || []).slice(0, 25)
          });
        }
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
          bodyTxt: (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').slice(0, 180),
          uad: navigator.userAgentData ? { mobile: navigator.userAgentData.mobile, platform: navigator.userAgentData.platform, brands: navigator.userAgentData.brands } : null,
          ua: navigator.userAgent,
          mtp: navigator.maxTouchPoints,
          hasTouch: ('ontouchstart' in window)
        });
      })()`,
      returnByValue: true
    });
    const finalState = (snap && snap.result && snap.result.result && snap.result.result.value)
      ? JSON.parse(snap.result.result.value)
      : {};

    return { name, getLinkStatus, getLinkPostData, getLinkBody: getLinkBody.slice(0, 220), finalState, logs: logs.slice(0, 8), turnstileFlows: turnstileFlows.slice(0, 12), scriptsCaptured };
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
}

(async () => {
  const results = {};

  // 1. Capture Stage A + Stage B unpacked JS via Debugger (on Desktop so Stage B runs all the way to get-link.php)
  results.r1_debugger = await runScenario('1_DebuggerCapture', 19310, null, true);

  // 2A. Simulate Android WebView with "Android WebView" Sec-CH-UA brand (what WebView sends if UA matches or default)
  results.r2a_wvBrand = await runScenario('2A_AndroidWvBrand', 19311, async send => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 2, mobile: true });
    await send('Emulation.setUserAgentOverride', {
      userAgent: 'Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36',
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [
          { brand: 'Chromium', version: '146' },
          { brand: 'Android WebView', version: '146' },
          { brand: 'Not-A.Brand', version: '99' }
        ],
        fullVersionList: [
          { brand: 'Chromium', version: '146.0.7680.177' },
          { brand: 'Android WebView', version: '146.0.7680.177' },
          { brand: 'Not-A.Brand', version: '99.0.0.0' }
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true
      }
    });
  }, false);

  // 2B. Simulate Android Chrome with clean "Google Chrome" Sec-CH-UA brand + real touch emulation
  results.r2b_chromeBrandTouch = await runScenario('2B_AndroidChromeBrandTouch', 19312, async send => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 2, mobile: true });
    await send('Emulation.setUserAgentOverride', {
      userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36',
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [
          { brand: 'Chromium', version: '146' },
          { brand: 'Google Chrome', version: '146' },
          { brand: 'Not-A.Brand', version: '99' }
        ],
        fullVersionList: [
          { brand: 'Chromium', version: '146.0.7680.177' },
          { brand: 'Google Chrome', version: '146.0.7680.177' },
          { brand: 'Not-A.Brand', version: '99.0.0.0' }
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true
      }
    });
  }, false);

  // 2C. Simulate Android WebView when userAgentString is overridden to DESKTOP_UA (low-entropy empty brands + touch screen!)
  results.r2c_desktopUaOnTouchDevice = await runScenario('2C_DesktopUaOnTouchDevice', 19313, async send => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Emulation.setUserAgentOverride', {
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36',
      platform: 'Linux armv8l',
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

  // 2D. Simulate exact Android WebView when userAgentString = cleanChromeUa (low-entropy empty brands because overridden UA != default "; wv)" UA!)
  results.r2d_cleanUaEmptyBrands = await runScenario('2D_CleanUaEmptyBrands', 19314, async send => {
    await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 2, mobile: true });
    await send('Emulation.setUserAgentOverride', {
      userAgent: 'Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36',
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [],
        platform: '',
        platformVersion: '',
        architecture: '',
        model: '',
        mobile: true
      }
    });
  }, false);

  fs.writeFileSync(outPath, JSON.stringify(results, null, 2), 'utf8');
  process.exit(0);
})();
