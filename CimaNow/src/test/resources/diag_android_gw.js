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

async function runScenario(name, port, configureFn) {
  const profDir = '/tmp/cn_m_' + process.pid + '_' + port;
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

  const logs = [];
  let finalMinted = '';
  let getLinkBody = '';
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
    logs.push(`browserVer=${browserVerFull}`);

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

    const cfg = (await configureFn({ send, browserVerFull, browserMajor })) || {};

    await send('Page.navigate', { url: cfg.entryUrl || shineUrl, referrer: refererUrl });

    let idx = 0;
    let firstBtnMs = 0;
    let lastBtnTap = 0;
    let lastTsTap = 0;
    let getLinkReqId = '';

    const tap = async (x, y) => {
      await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
      await new Promise(r => setTimeout(r, 60));
      await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(x), y: Math.round(y), button: 'left', clickCount: 1 });
    };

    for (let ms = 500; ms <= 21000; ms += 500) {
      await new Promise(r => setTimeout(r, 500));
      while (idx < events.length) {
        const ev = events[idx++];
        if (ev.method === 'Page.windowOpen') {
          logs.push(`t=${ms} WIN_OPEN url=${ev.params.url} name=${ev.params.windowName}`);
          if (ev.params.url && ev.params.url.includes('/watching/') && ev.params.url.includes('token=')) {
            finalMinted = 'WINOPEN:' + ev.params.url;
          }
        } else if (ev.method === 'Target.targetCreated') {
          const ti = ev.params.targetInfo || {};
          if (ti.type === 'page' && ti.url) {
            logs.push(`t=${ms} NEW_TAB url=${ti.url}`);
            if (ti.url.includes('/watching/') && ti.url.includes('token=')) {
              finalMinted = 'NEWTAB:' + ti.url;
            }
          }
        } else if (ev.method === 'Page.frameNavigated' && !ev.params.frame.parentId) {
          const u = ev.params.frame.url || '';
          logs.push(`t=${ms} MAIN_NAV url=${u.slice(0, 140)}`);
          if (u.includes('/watching/') && u.includes('token=')) {
            finalMinted = 'MAINNAV:' + u;
          }
        } else if (ev.method === 'Network.requestWillBeSent') {
          const u = ev.params.request.url || '';
          if (u.includes('href.li') || u.includes('redirectingfree') || u.includes('blog-post.html') || u.includes('get-link.php') || u.includes('/watching/')) {
            logs.push(`t=${ms} REQ ${ev.params.request.method} ${u.slice(0, 130)}`);
          }
          if (u.includes('get-link.php')) getLinkReqId = ev.params.requestId;
          if (u.includes('/watching/') && u.includes('token=') && !finalMinted) {
            finalMinted = 'NETREQ:' + u;
          }
        } else if (ev.method === 'Network.responseReceived') {
          const u = ev.params.response.url || '';
          if (u.includes('href.li') || u.includes('redirectingfree') || u.includes('blog-post.html') || u.includes('get-link.php')) {
            logs.push(`t=${ms} RES ${ev.params.response.status} ${u.slice(0, 130)}`);
          }
        }
      }

      if (getLinkReqId && !getLinkBody && ms >= 9500) {
        const rb = await send('Network.getResponseBody', { requestId: getLinkReqId });
        if (rb?.result?.body) {
          getLinkBody = rb.result.base64Encoded ? Buffer.from(rb.result.body, 'base64').toString('utf8') : rb.result.body;
          logs.push(`t=${ms} GL_BODY=${getLinkBody.slice(0, 130)}`);
        }
      }

      if (finalMinted) break;

      // Exact quiet window matching mintViaHeadlessChromiumCli!
      if (ms < 4500) continue;
      if (firstBtnMs > 0 && ms - firstBtnMs < 7000) continue;

      const allowBtnForce = firstBtnMs > 0 && (ms - firstBtnMs >= 11000);
      const allowTsTap = firstBtnMs === 0 ? (ms >= 4500) : (ms - firstBtnMs >= 7500);

      const probe = await send('Runtime.evaluate', {
        expression: `(function(){
          try {
            var loc = String(location.href || '');
            if (loc.indexOf('/watching/') !== -1 && loc.indexOf('token=') !== -1) return 'URL:' + loc;
            var btn = document.getElementById('downloadbtn');
            var h = btn ? String(btn.getAttribute('href') || '') : '';
            var tgt = btn ? String(btn.getAttribute('target') || '') : '';
            var bodyTxt = String((document.body && document.body.innerText) || '').replace(/\\s+/g,' ').slice(0, 90);
            if (btn) {
              if (h.indexOf('http') === 0 && h.indexOf('/watching/') !== -1 && h.indexOf('token=') !== -1) return 'URL:' + h;
              if (h === '#watch-ready' || (${allowBtnForce} && h && h !== '#' && h !== 'javascript:void(0);' && h.indexOf('blog-post_75') === -1)) {
                if (btn.hidden || getComputedStyle(btn).display === 'none') {
                  btn.removeAttribute('hidden');
                  btn.style.setProperty('display', 'inline-block', 'important');
                }
                btn.scrollIntoView({block:'center', inline:'center'});
                var br = btn.getBoundingClientRect();
                if (br.width > 4 && br.height > 4) {
                  return 'BTN:' + Math.round(br.left + br.width/2) + ',' + Math.round(br.top + br.height/2) + '|tgt=' + tgt + '|h=' + h;
                }
              }
            }
            if (${allowTsTap} && h !== '#watch-ready') {
              var el = document.querySelector('.cf-turnstile, [data-sitekey], iframe[src*="challenges.cloudflare.com"]');
              if (el) {
                el.scrollIntoView({block:'center', inline:'center'});
                var tr = el.getBoundingClientRect();
                if (tr.width >= 40 && tr.height >= 25) {
                  return 'TS:' + Math.round(tr.left + 28) + ',' + Math.round(tr.top + tr.height/2) + (btn ? ':HASBTN' : '');
                }
              }
            }
            return 'WAIT:btn=' + (!!btn) + '|h=' + h + '|txt=' + bodyTxt;
          } catch(e) { return 'ERR:' + e.message; }
        })()`,
        returnByValue: true,
      });
      const pv = String(probe?.result?.result?.value || '');
      if (!firstBtnMs && (pv.includes('btn=true') || pv.includes(':HASBTN') || pv.startsWith('BTN:'))) {
        firstBtnMs = ms;
        logs.push(`t=${ms} FIRST_BTN pv=${pv}`);
      } else if (ms % 2500 === 0 || pv.startsWith('BTN:') || pv.startsWith('URL:') || pv.includes('متصفح')) {
        logs.push(`t=${ms} PV=${pv}`);
      }
      if (pv.startsWith('URL:')) {
        finalMinted = 'PROBEURL:' + pv.slice(4);
        break;
      } else if (pv.startsWith('BTN:') && ms - lastBtnTap >= 1800) {
        lastBtnTap = ms;
        const coordPart = pv.slice(4).split('|')[0];
        const [bx, by] = coordPart.split(',').map(Number);
        logs.push(`t=${ms} TAP_BTN (${bx},${by})`);
        if (bx > 0 && by > 0) await tap(bx, by);
      } else if (pv.startsWith('TS:') && ms - lastTsTap >= 5000) {
        lastTsTap = ms;
        const coordPart = pv.slice(3).replace(':HASBTN', '');
        const [tx, ty] = coordPart.split(',').map(Number);
        logs.push(`t=${ms} TAP_TS (${tx},${ty})`);
        if (tx > 0 && ty > 0) await tap(tx, ty);
      }
    }
    try { ws.close(); } catch (e) {}
  } catch (e) {
    logs.push('ERR=' + (e && e.message));
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
  return { name, finalMinted, getLinkBody, logs };
}

(async () => {
  const sc1 = await runScenario('1_DesktopNoShortcut', 19431, async () => ({}));
  const sc2 = await runScenario('2_AndroidWvWithXRW', 19432, async ({ send, browserVerFull, browserMajor }) => {
    await send('Network.setExtraHTTPHeaders', { headers: { 'X-Requested-With': 'com.lagradost.cloudstream3' } });
    await send('Network.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${browserVerFull} Mobile Safari/537.36`,
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [
          { brand: 'Not/A)Brand', version: '8' },
          { brand: 'Chromium', version: browserMajor },
          { brand: 'Android WebView', version: browserMajor },
        ],
        fullVersionList: [
          { brand: 'Not/A)Brand', version: '8.0.0.0' },
          { brand: 'Chromium', version: browserVerFull },
          { brand: 'Android WebView', version: browserVerFull },
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true,
      },
    });
  });
  const sc3 = await runScenario('3_AndroidWvNoXRW', 19433, async ({ send, browserVerFull, browserMajor }) => {
    await send('Network.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 14; M2006C3MG Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${browserVerFull} Mobile Safari/537.36`,
      platform: 'Linux armv8l',
      userAgentMetadata: {
        brands: [
          { brand: 'Not/A)Brand', version: '8' },
          { brand: 'Chromium', version: browserMajor },
          { brand: 'Android WebView', version: browserMajor },
        ],
        fullVersionList: [
          { brand: 'Not/A)Brand', version: '8.0.0.0' },
          { brand: 'Chromium', version: browserVerFull },
          { brand: 'Android WebView', version: browserVerFull },
        ],
        platform: 'Android',
        platformVersion: '14.0.0',
        architecture: '',
        model: 'M2006C3MG',
        mobile: true,
      },
    });
  });
  const sc4 = await runScenario('4_CleanMobileChromeMatched', 19434, async ({ send, browserVerFull, browserMajor }) => {
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
  });

  fs.writeFileSync(outPath, JSON.stringify({ sc1, sc2, sc3, sc4 }, null, 2), 'utf8');
  process.exit(0);
})();
