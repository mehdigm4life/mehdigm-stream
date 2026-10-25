const { spawn } = require('child_process');
const http = require('http');
const https = require('https');
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

function rawGet(u, headers) {
  return new Promise(res => {
    const req = https.get(u, { headers }, r => {
      let body = '';
      r.on('data', c => { if (body.length < 4000) body += c.toString('utf8'); });
      r.on('end', () => {
        res({
          status: r.statusCode,
          location: r.headers.location || '',
          len: body.length,
          head: body.replace(/\s+/g, ' ').slice(0, 380),
        });
      });
    });
    req.on('error', e => res({ err: e.message }));
  });
}

async function runScenario(name, port, opts) {
  const profDir = '/tmp/cn_sc_' + process.pid + '_' + port;
  const args = [
    '--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--user-data-dir=' + profDir,
    '--disable-blink-features=AutomationControlled',
    '--window-size=' + (opts.winSize || '800,360'),
    ...(opts.extraFlags || []),
    '--remote-debugging-port=' + port,
    'about:blank',
  ];
  const proc = fs.existsSync(xvfb)
    ? spawn(xvfb, ['-a', chromeBin, ...args], { stdio: ['ignore', 'ignore', 'pipe'] })
    : spawn(chromeBin, ['--headless=new', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
  if (proc.stderr) proc.stderr.resume();

  const logs = [];
  let finalMinted = '';
  let getLinkBody = '';
  try {
    let targets = null;
    for (let i = 0; i < 30; i++) {
      try {
        targets = await getJson('http://127.0.0.1:' + port + '/json');
        if (targets && targets.length) break;
      } catch (e) {}
      await new Promise(r => setTimeout(r, 350));
    }
    const t = targets && (targets.find(x => x.type === 'page') || targets[0]);
    if (!t) throw new Error('no_targets');
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

    await send('Page.enable');
    await send('Network.enable');
    await send('Target.setDiscoverTargets', { discover: true });
    if (opts.mobileMetrics) {
      await send('Emulation.setDeviceMetricsOverride', opts.mobileMetrics);
      await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    }
    if (opts.uaOverride) {
      await send('Network.setUserAgentOverride', opts.uaOverride);
    }
    if (opts.extraHeaders) {
      await send('Network.setExtraHTTPHeaders', { headers: opts.extraHeaders });
    }

    await send('Page.navigate', { url: opts.entryUrl || shineUrl, referrer: refererUrl });

    let idx = 0;
    let firstBtnMs = 0;
    let lastBtnTap = 0;
    let lastTsTap = 0;
    let getLinkReqId = '';

    const tap = async (x, y) => {
      if (opts.useTouch) {
        await send('Input.dispatchTouchEvent', {
          type: 'touchStart',
          touchPoints: [{ x: Math.round(x), y: Math.round(y), radiusX: 6, radiusY: 6, force: 0.68, id: 0 }],
        });
        await new Promise(r => setTimeout(r, 55));
        await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
      } else {
        await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
        await new Promise(r => setTimeout(r, 55));
        await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
      }
    };

    for (let ms = 500; ms <= 25000; ms += 500) {
      await new Promise(r => setTimeout(r, 500));
      while (idx < events.length) {
        const ev = events[idx++];
        if (ev.method === 'Page.windowOpen') {
          logs.push(`t=${ms} WINDOW_OPEN url=${ev.params.url} name=${ev.params.windowName}`);
          if (ev.params.url && ev.params.url.includes('/watching/') && ev.params.url.includes('token=')) {
            finalMinted = 'WINOPEN:' + ev.params.url;
          }
        } else if (ev.method === 'Page.frameNavigated' && !ev.params.frame.parentId) {
          const u = ev.params.frame.url || '';
          logs.push(`t=${ms} MAIN_NAV url=${u}`);
          if (u.includes('/watching/') && u.includes('token=')) {
            finalMinted = 'MAINNAV:' + u;
          }
        } else if (ev.method === 'Network.requestWillBeSent') {
          const u = ev.params.request.url || '';
          if (u.includes('loadon') || u.includes('href.li') || u.includes('redirectingfree') || u.includes('blog-post.html') || u.includes('get-link.php') || u.includes('/watching/')) {
            logs.push(`t=${ms} REQ ${ev.params.request.method} ${u.slice(0, 145)}`);
          }
          if (u.includes('get-link.php')) getLinkReqId = ev.params.requestId;
          if (u.includes('/watching/') && u.includes('token=') && !finalMinted) {
            logs.push(`t=${ms} WATCHING_REQ_SEEN=${u}`);
            finalMinted = 'NETREQ:' + u;
          }
        } else if (ev.method === 'Network.responseReceived') {
          const u = ev.params.response.url || '';
          if (u.includes('loadon') || u.includes('href.li') || u.includes('redirectingfree') || u.includes('blog-post.html') || u.includes('get-link.php')) {
            logs.push(`t=${ms} RES ${ev.params.response.status} ${u.slice(0, 145)}`);
          }
        } else if (ev.method === 'Network.loadingFailed') {
          logs.push(`t=${ms} FAIL ${ev.params.errorText} ${ev.params.type}`);
        }
      }

      if (getLinkReqId && !getLinkBody && ms >= 8000) {
        const rb = await send('Network.getResponseBody', { requestId: getLinkReqId });
        if (rb?.result?.body) {
          getLinkBody = rb.result.base64Encoded ? Buffer.from(rb.result.body, 'base64').toString('utf8') : rb.result.body;
          logs.push(`t=${ms} GET_LINK_BODY(not-exiting!)=${getLinkBody.slice(0, 160)}`);
        }
      }

      if (finalMinted) break;
      if (ms < 3000) continue;

      const probe = await send('Runtime.evaluate', {
        expression: `(function(){
          try {
            var loc = String(location.href || '');
            var bodyTxt = String((document.body && document.body.innerText) || '').replace(/\\s+/g,' ').slice(0, 120);
            var scripts = Array.from(document.querySelectorAll('script')).map(s => (s.src || s.textContent.slice(0, 80))).slice(0, 4);
            var btn = document.getElementById('downloadbtn');
            var h = btn ? String(btn.getAttribute('href') || '') : '';
            var tgt = btn ? String(btn.getAttribute('target') || '') : '';
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
            return JSON.stringify({ loc: loc.slice(0,85), hasBtn: !!btn, h, tgt, tokLen, tsRect, btnRect, bodyTxt, scripts });
          } catch(e) { return JSON.stringify({ err: e.message }); }
        })()`,
        returnByValue: true,
      });
      const st = JSON.parse(probe?.result?.result?.value || '{}');
      if (!firstBtnMs && st.hasBtn) firstBtnMs = ms;
      if (ms === 3500 || ms % 3000 === 0 || st.h === '#watch-ready') {
        logs.push(`t=${ms} ST=${JSON.stringify(st)}`);
      }
      if (st.btnRect && st.btnRect.w > 4 && ms - lastBtnTap >= 1800) {
        lastBtnTap = ms;
        logs.push(`t=${ms} TAP_BTN (${st.btnRect.x},${st.btnRect.y})`);
        await tap(st.btnRect.x, st.btnRect.y);
      } else if (st.tsRect && st.tokLen === 0 && firstBtnMs > 0 && ms - firstBtnMs >= 6500 && ms - lastTsTap >= 4500) {
        lastTsTap = ms;
        const tx = st.tsRect.l + 28;
        const ty = st.tsRect.t + Math.round(st.tsRect.h / 2);
        logs.push(`t=${ms} TAP_TS (${tx},${ty})`);
        await tap(tx, ty);
      }
    }
    try { ws.close(); } catch (e) {}
  } catch (e) {
    logs.push('ERR=' + (e && e.message));
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
  return { name, finalMinted, getLinkBody: getLinkBody.slice(0, 140), logs };
}

(async () => {
  const mobUa = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.7871.63 Mobile Safari/537.36';
  const httpChecks = {};
  httpChecks.loadonDesktop = await rawGet(shineUrl, { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36', Referer: refererUrl });
  httpChecks.loadonMobileClean = await rawGet(shineUrl, { 'User-Agent': mobUa, Referer: refererUrl });
  httpChecks.loadonWithXReqWith = await rawGet(shineUrl, { 'User-Agent': mobUa, Referer: refererUrl, 'X-Requested-With': 'com.lagradost.cloudstream3' });
  httpChecks.blogWithXReqWith = await rawGet('https://rm.freex2line.online/2020/02/blog-post.html/', { 'User-Agent': mobUa, Referer: 'https://rm.freex2line.online/redirectingfree/', 'X-Requested-With': 'com.lagradost.cloudstream3' });

  // Scenario A: Mobile 800x360 + Touch + ar-MA WITHOUT X-Requested-With (and NO Network.getResponseBody shortcut!)
  const scA = await runScenario('MobileCleanNoXRW', 19421, {
    winSize: '800,360',
    extraFlags: ['--lang=ar-MA'],
    mobileMetrics: { width: 800, height: 360, deviceScaleFactor: 2, mobile: true },
    uaOverride: { userAgent: mobUa, acceptLanguage: 'ar-MA,ar;q=0.9', platform: 'Linux armv8l' },
    useTouch: true,
  });

  // Scenario B: Mobile 800x360 + Mouse events (like Android MotionEvent -> Blink GestureTap -> click) WITHOUT Emulation.setDeviceMetricsOverride
  const scB = await runScenario('Window800x360Clean', 19422, {
    winSize: '800,360',
    extraFlags: ['--lang=ar-MA'],
    uaOverride: { userAgent: mobUa, acceptLanguage: 'ar-MA,ar;q=0.9', platform: 'Linux armv8l' },
    useTouch: false,
  });

  fs.writeFileSync(outPath, JSON.stringify({ httpChecks, scA, scB }, null, 2), 'utf8');
  process.exit(0);
})();
