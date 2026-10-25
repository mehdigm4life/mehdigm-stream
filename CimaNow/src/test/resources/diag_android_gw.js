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

async function launchBrowser(port, extraArgs = []) {
  const profDir = '/tmp/cn_diag_' + process.pid + '_' + port;
  const args = [
    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--user-data-dir=' + profDir,
    '--disable-blink-features=AutomationControlled',
    ...extraArgs,
    '--remote-debugging-port=' + port,
    'about:blank',
  ];
  const proc = fs.existsSync(xvfb)
    ? spawn(xvfb, ['-a', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
    : spawn(chromeBin, ['--headless=new', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
  if (proc.stderr) proc.stderr.resume();

  let targets = null;
  for (let i = 0; i < 35; i++) {
    try {
      targets = await getJson('http://127.0.0.1:' + port + '/json');
      if (targets && targets.length) break;
    } catch (e) {}
    await new Promise(r => setTimeout(r, 400));
  }
  const t = targets && (targets.find(x => x.type === 'page') || targets[0]);
  if (!t) {
    try { proc.kill('SIGKILL'); } catch (e) {}
    throw new Error('no_targets on port ' + port);
  }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let id = 1;
  const pending = new Map();
  const events = [];
  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      pending.get(m.id)(m);
      pending.delete(m.id);
    } else if (m.method) {
      events.push(m);
    }
  };
  const send = (method, params = {}) =>
    new Promise(r => {
      const cid = id++;
      pending.set(cid, r);
      ws.send(JSON.stringify({ id: cid, method, params }));
    });
  const kill = () => {
    try { ws.close(); } catch (e) {}
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  };
  return { send, events, kill };
}

function extractContextSnippets(code, keywords, radius = 260) {
  const out = [];
  for (const kw of keywords) {
    let idx = 0;
    let hits = 0;
    while (hits < 5) {
      const found = code.indexOf(kw, idx);
      if (found === -1) break;
      const s = Math.max(0, found - radius);
      const e = Math.min(code.length, found + kw.length + radius);
      out.push(`[${kw}@${found}] ` + code.slice(s, e).replace(/\s+/g, ' '));
      idx = found + kw.length;
      hits++;
    }
  }
  return out;
}

(async () => {
  const report = { stageSnippets: [], simLogs: [], finalMinted: '' };

  // Phase 1: Capture Stage A + Stage B source code from blog-post.html
  const b1 = await launchBrowser(19411, ['--window-size=412,915']);
  try {
    let blogReqId = '';
    await b1.send('Page.enable');
    await b1.send('Network.enable');
    await b1.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `
        (function(){
          window.__capScripts = [];
          const origFn = window.Function;
          window.Function = function(...args) {
            try { window.__capScripts.push('FN:' + args.join('\\n')); } catch(e) {}
            return origFn.apply(this, args);
          };
          window.Function.prototype = origFn.prototype;
          const origWrite = Document.prototype.write;
          Document.prototype.write = function(...args) {
            try { window.__capScripts.push('DW:' + args.join('')); } catch(e) {}
            return origWrite.apply(this, args);
          };
          const origAppend = Element.prototype.appendChild;
          Element.prototype.appendChild = function(ch) {
            try {
              if (ch && ch.tagName === 'SCRIPT' && ch.textContent && ch.textContent.length > 120) {
                window.__capScripts.push('SC:' + ch.textContent);
              }
            } catch(e) {}
            return origAppend.apply(this, arguments);
          };
        })();
      `,
    });
    await b1.send('Page.navigate', { url: shineUrl, referrer: refererUrl });
    for (let i = 0; i < 16; i++) {
      await new Promise(r => setTimeout(r, 500));
      for (const ev of b1.events) {
        if (ev.method === 'Network.responseReceived') {
          const u = (ev.params && ev.params.response && ev.params.response.url) || '';
          if (u.includes('blog-post.html') && !u.includes('get-link.php')) {
            blogReqId = ev.params.requestId;
          }
        }
      }
      const evRes = await b1.send('Runtime.evaluate', {
        expression: `JSON.stringify({ url: location.href, caps: (window.__capScripts || []).length, domLen: document.documentElement.outerHTML.length })`,
        returnByValue: true,
      });
      const val = evRes?.result?.result?.value;
      if (val && val.includes('blog-post') && i >= 9) break;
    }

    if (blogReqId) {
      const rb = await b1.send('Network.getResponseBody', { requestId: blogReqId });
      const rawHtml = rb?.result?.body
        ? (rb.result.base64Encoded ? Buffer.from(rb.result.body, 'base64').toString('utf8') : rb.result.body)
        : '';
      report.blogHtmlLen = rawHtml.length;
      report.stageSnippets.push(...extractContextSnippets(rawHtml, [
        'webdriver', 'wv', 'WebView', 'X-Requested-With', 'get-link.php', 'watch-ready', 'downloadbtn', 'window.open'
      ], 220));
    }

    const capsRes = await b1.send('Runtime.evaluate', {
      expression: `JSON.stringify({
        caps: window.__capScripts || [],
        inlineScripts: Array.from(document.querySelectorAll('script')).map(s => s.textContent || '').filter(t => t.length > 100),
        btnHtml: (document.getElementById('downloadbtn') || {}).outerHTML || 'NONE',
        htmlDir: document.documentElement.dir || '',
        bodyDir: (document.body && document.body.dir) || ''
      })`,
      returnByValue: true,
    });
    const parsedCaps = JSON.parse(capsRes?.result?.result?.value || '{}');
    report.btnHtmlInitial = parsedCaps.btnHtml;
    report.htmlDir = parsedCaps.htmlDir;
    report.bodyDir = parsedCaps.bodyDir;
    const allJs = [...(parsedCaps.caps || []), ...(parsedCaps.inlineScripts || [])].join('\n\n===SPLIT===\n\n');
    report.allJsLen = allJs.length;
    report.stageSnippets.push(...extractContextSnippets(allJs, [
      'get-link.php',
      'watch-ready',
      'downloadbtn',
      'window.open',
      'target',
      'location',
      'isTrusted',
      'requestAnimationFrame',
      'visibilityState',
      'hasFocus',
      'webdriver',
      'wv',
      'turnstile',
    ], 320));
  } catch (e) {
    report.phase1Err = String(e && e.stack || e);
  } finally {
    b1.kill();
  }

  // Phase 2: Clean UNHOOKED simulation matching Xiaomi M2006C3MG landscape WebView 150
  // WITHOUT exiting on Network.getResponseBody — testing actual #downloadbtn tap & X-Requested-With!
  const b2 = await launchBrowser(19412, [
    '--window-size=800,360',
    '--lang=ar-MA',
  ]);
  try {
    await b2.send('Page.enable');
    await b2.send('Network.enable');
    await b2.send('Target.setDiscoverTargets', { discover: true });
    await b2.send('Emulation.setDeviceMetricsOverride', {
      width: 800,
      height: 360,
      deviceScaleFactor: 2,
      mobile: true,
    });
    await b2.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    await b2.send('Network.setUserAgentOverride', {
      userAgent: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.63 Mobile Safari/537.36',
      acceptLanguage: 'ar-MA,ar;q=0.9,en-US;q=0.8,en;q=0.7',
      platform: 'Linux armv8l',
    });
    await b2.send('Network.setExtraHTTPHeaders', {
      headers: { 'X-Requested-With': 'com.lagradost.cloudstream3' },
    });

    await b2.send('Page.navigate', { url: shineUrl, referrer: refererUrl });

    let processedIdx = 0;
    let lastBtnTap = 0;
    let lastTsTap = 0;
    let firstBtnMs = 0;

    const tapTouch = async (x, y) => {
      await b2.send('Input.dispatchTouchEvent', {
        type: 'touchStart',
        touchPoints: [{ x: Math.round(x), y: Math.round(y), radiusX: 6, radiusY: 6, force: 0.68, id: 0 }],
      });
      await new Promise(r => setTimeout(r, 55));
      await b2.send('Input.dispatchTouchEvent', {
        type: 'touchEnd',
        touchPoints: [],
      });
    };

    for (let ms = 500; ms <= 26000; ms += 500) {
      await new Promise(r => setTimeout(r, 500));
      while (processedIdx < b2.events.length) {
        const ev = b2.events[processedIdx++];
        if (ev.method === 'Page.windowOpen') {
          report.simLogs.push(`t=${ms} WINDOW_OPEN url=${ev.params.url} name=${ev.params.windowName} feat=${ev.params.windowFeatures}`);
          if (ev.params.url && ev.params.url.includes('/watching/') && ev.params.url.includes('token=')) {
            report.finalMinted = ev.params.url;
          }
        } else if (ev.method === 'Target.targetCreated') {
          const ti = ev.params.targetInfo || {};
          if (ti.url && ti.url !== 'about:blank') {
            report.simLogs.push(`t=${ms} TARGET_CREATED type=${ti.type} url=${ti.url}`);
          }
        } else if (ev.method === 'Page.frameNavigated' && !ev.params.frame.parentId) {
          const u = ev.params.frame.url || '';
          report.simLogs.push(`t=${ms} MAIN_NAV url=${u}`);
          if (u.includes('/watching/') && u.includes('token=')) {
            report.finalMinted = u;
          }
        } else if (ev.method === 'Network.requestWillBeSent') {
          const u = ev.params.request.url || '';
          if (u.includes('get-link.php') || u.includes('/watching/') || u.includes('redirectingfree') || u.includes('blog-post.html')) {
            report.simLogs.push(`t=${ms} REQ ${ev.params.request.method} ${u.slice(0, 140)}`);
          }
        } else if (ev.method === 'Network.responseReceived') {
          const u = ev.params.response.url || '';
          if (u.includes('get-link.php') || u.includes('blog-post.html')) {
            report.simLogs.push(`t=${ms} RES ${ev.params.response.status} ${u.slice(0, 140)}`);
          }
        }
      }

      if (report.finalMinted) {
        report.simLogs.push(`t=${ms} CAPTURED_VIA_NAVIGATION=${report.finalMinted}`);
        break;
      }

      if (ms < 3500) continue;

      const probe = await b2.send('Runtime.evaluate', {
        expression: `(function(){
          try {
            var loc = String(location.href || '');
            var btn = document.getElementById('downloadbtn');
            var h = btn ? String(btn.getAttribute('href') || '') : '';
            var tgt = btn ? String(btn.getAttribute('target') || '') : '';
            var txt = btn ? String(btn.textContent || '').trim().slice(0, 40) : '';
            var inp = document.querySelector('input[name="cf-turnstile-response"]');
            var tokLen = (inp && inp.value) ? inp.value.length : 0;
            var tsEl = document.querySelector('.cf-turnstile, [data-sitekey], iframe[src*="challenges.cloudflare.com"]');
            var tsRect = null;
            if (tsEl) {
              tsEl.scrollIntoView({block:'center', inline:'center'});
              var tr = tsEl.getBoundingClientRect();
              tsRect = { l: Math.round(tr.left), r: Math.round(tr.right), t: Math.round(tr.top), w: Math.round(tr.width), h: Math.round(tr.height) };
            }
            var btnRect = null;
            if (btn && h === '#watch-ready') {
              if (btn.hidden || getComputedStyle(btn).display === 'none') {
                btn.removeAttribute('hidden');
                btn.style.setProperty('display', 'inline-block', 'important');
              }
              btn.scrollIntoView({block:'center', inline:'center'});
              var br = btn.getBoundingClientRect();
              btnRect = { x: Math.round(br.left + br.width / 2), y: Math.round(br.top + br.height / 2), w: Math.round(br.width), h: Math.round(br.height) };
            }
            return JSON.stringify({ loc, hasBtn: !!btn, h, tgt, txt, tokLen, tsRect, btnRect, dir: document.documentElement.dir || '', lang: navigator.language });
          } catch(e) { return JSON.stringify({ err: e.message }); }
        })()`,
        returnByValue: true,
      });
      const st = JSON.parse(probe?.result?.result?.value || '{}');
      if (!firstBtnMs && st.hasBtn) firstBtnMs = ms;
      if (ms % 1500 === 0 || st.h === '#watch-ready') {
        report.simLogs.push(`t=${ms} STATE=${JSON.stringify(st)}`);
      }
      if (st.btnRect && st.btnRect.w > 4 && ms - lastBtnTap >= 1800) {
        lastBtnTap = ms;
        report.simLogs.push(`t=${ms} TAPPING_BTN (${st.btnRect.x},${st.btnRect.y})`);
        await tapTouch(st.btnRect.x, st.btnRect.y);
      } else if (st.tsRect && st.tokLen === 0 && firstBtnMs > 0 && ms - firstBtnMs >= 6500 && ms - lastTsTap >= 4500) {
        lastTsTap = ms;
        const tx = st.tsRect.l + 28;
        const ty = st.tsRect.t + Math.round(st.tsRect.h / 2);
        report.simLogs.push(`t=${ms} TAPPING_TS_LEFT (${tx},${ty})`);
        await tapTouch(tx, ty);
      }
    }
  } catch (e) {
    report.phase2Err = String(e && e.stack || e);
  } finally {
    b2.kill();
  }

  fs.writeFileSync(outPath, JSON.stringify(report, null, 2), 'utf8');
  process.exit(0);
})();
