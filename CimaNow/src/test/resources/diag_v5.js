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
  return (html || '').replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, m => {
    if (m.length > 8000) return `<!-- BIG_SCRIPT_${m.length} -->`;
    return m;
  });
}

async function runQuick(name, port, waitMs, setupCdpFn, dumpOuterHtml) {
  const profDir = '/tmp/cn_diag5f_' + process.pid + '_' + port;
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
    if (!targets || !targets.length) return { name, error: 'no_targets' };
    const t = targets.find(x => x.type === 'page') || targets[0];
    const ws = new WebSocket(t.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

    let id = 1;
    const pending = new Map();
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
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
    if (setupCdpFn) await setupCdpFn(send, majorVer, fullVer);

    await send('Page.navigate', { url: shineUrl, referrer: refererUrl });
    await new Promise(r => setTimeout(r, waitMs));

    const snap = await send('Runtime.evaluate', {
      expression: `(function(){
        var btn = document.getElementById('downloadbtn');
        return JSON.stringify({
          loc: location.href,
          hasBtn: !!btn,
          btnHref: btn ? btn.getAttribute('href') : null,
          bodyTxt: (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').slice(0, 140),
          outerHtml: ${dumpOuterHtml ? 'document.documentElement ? document.documentElement.outerHTML : ""' : '""'}
        });
      })()`,
      returnByValue: true
    });
    const st = (snap && snap.result && snap.result.result && snap.result.result.value)
      ? JSON.parse(snap.result.result.value)
      : {};
    let outerGzB64 = '';
    if (dumpOuterHtml && st.outerHtml) {
      outerGzB64 = zlib.gzipSync(Buffer.from(slimHtml(st.outerHtml), 'utf8')).toString('base64');
    }
    delete st.outerHtml;
    return { name, st, outerGzB64 };
  } finally {
    try { proc.kill('SIGKILL'); } catch (e) {}
    try { fs.rmSync(profDir, { recursive: true, force: true }); } catch (e) {}
  }
}

(async () => {
  const out = {};

  // 1. Dump decrypted outerHTML on PASS (reveals inline get-link.php JS!)
  out.c1_passOuter = await runQuick('1_PassOuterHtml', 19350, 7500, null, true);

  // 2. Dump decrypted outerHTML on FAIL (reveals exact HTML of "استخدم متصفح اخر"!)
  out.c2_failOuter = await runQuick('2_FailOuterHtml', 19351, 7500, async (send, major, full) => {
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36`,
      userAgentMetadata: {
        brands: [{ brand: 'Google Chrome', version: major }, { brand: 'Chromium', version: major }],
        fullVersionList: [{ brand: 'Google Chrome', version: full }, { brand: 'Chromium', version: full }],
        platform: 'Android', platformVersion: '14.0.0', architecture: '', model: 'K', mobile: true
      }
    });
  }, true);

  // 3. Linux Desktop UA + EMPTY Sec-CH-UA brands (isolates empty Sec-CH-UA!)
  out.c3_linuxEmptyBrands = await runQuick('3_LinuxDesktop_EmptyBrands', 19352, 7000, async (send, major) => {
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`,
      userAgentMetadata: { brands: [], platform: '', platformVersion: '', architecture: '', model: '', mobile: false }
    });
  }, false);

  // 4. Windows Desktop UA + FULL Windows Sec-CH-UA brands
  out.c4_windowsFullBrands = await runQuick('4_WindowsDesktop_FullBrands', 19353, 7000, async (send, major, full) => {
    await send('Emulation.setUserAgentOverride', {
      userAgent: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`,
      platform: 'Win32',
      userAgentMetadata: {
        brands: [{ brand: 'Not(A:Brand', version: '8' }, { brand: 'Chromium', version: major }, { brand: 'Google Chrome', version: major }],
        fullVersionList: [{ brand: 'Not(A:Brand', version: '8.0.0.0' }, { brand: 'Chromium', version: full }, { brand: 'Google Chrome', version: full }],
        platform: 'Windows', platformVersion: '15.0.0', architecture: 'x86', model: '', mobile: false, bitness: '64'
      }
    });
  }, false);

  // 5A. HTTP-ONLY Android headers (JS navigator stays Linux Desktop!) -> proves Server PHP vs Client JS!
  out.c5a_httpOnlyAndroid = await runQuick('5A_HttpOnly_AndroidHeaders', 19354, 7000, async (send, major) => {
    await send('Network.setExtraHTTPHeaders', {
      headers: {
        'User-Agent': `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Mobile Safari/537.36`,
        'sec-ch-ua': `"Not(A:Brand";v="8", "Chromium";v="${major}", "Google Chrome";v="${major}"`,
        'sec-ch-ua-mobile': '?1',
        'sec-ch-ua-platform': '"Android"'
      }
    });
  }, false);

  // 5B. JS-ONLY Android navigator (HTTP headers stay Linux Desktop!) -> proves Server PHP vs Client JS!
  out.c5b_jsOnlyAndroid = await runQuick('5B_JsOnly_AndroidNavigator', 19355, 7000, async (send, major) => {
    await send('Page.addScriptToEvaluateOnNewDocument', {
      source: `
        Object.defineProperty(Navigator.prototype, 'userAgent', { get: function(){ return 'Mozilla/5.0 (Linux; Android 14; M2006C3MG; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${major}.0.0.0 Mobile Safari/537.36'; } });
        Object.defineProperty(Navigator.prototype, 'platform', { get: function(){ return 'Linux armv81'; } });
      `
    });
  }, false);

  // 6. HTTP-ONLY X-Requested-With: com.lagradost.cloudstream3 (tests if XRW causes WAF 403 or "استخدم متصفح اخر"!)
  out.c6_httpXrwHeader = await runQuick('6_HttpXRequestedWith', 19356, 7000, async (send) => {
    await send('Network.setExtraHTTPHeaders', {
      headers: { 'X-Requested-With': 'com.lagradost.cloudstream3' }
    });
  }, false);

  fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');
  process.exit(0);
})();
