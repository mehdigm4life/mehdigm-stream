import fs from "fs";
import os from "os";
import path from "path";
import vm from "vm";
import crypto from "crypto";
import { spawn, execFile } from "child_process";
import { promisify } from "util";

const execFileAsync = promisify(execFile);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const PROXY_FEEDS = [
  ["socks5", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
  ["socks5", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt"],
];

function isCimaBlock(body) {
  return (
    !body ||
    body.includes("ستوب! المخرج عايز كدة") ||
    body.includes("مشهد محذوف") ||
    (body.includes("403") && body.length < 5000 && body.includes("Cima Now"))
  );
}

async function curlReq(url, opts = {}) {
  const {
    proxy = null,
    referer = null,
    cookieJar = null,
    method = "GET",
    formBody = null,
    headers = {},
    follow = true,
    timeoutSec = 15,
  } = opts;
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "7",
    "-A",
    UA,
  ];
  if (follow) args.push("-L", "--max-redirs", "5");
  if (method !== "GET") args.push("-X", method);
  if (formBody !== null) args.push("--data-raw", formBody);
  if (proxy) args.push("-x", proxy.replace(/^socks5:\/\//, "socks5h://"));
  if (referer) args.push("-e", referer);
  if (cookieJar) args.push("-b", cookieJar, "-c", cookieJar);
  for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}");
  args.push(url);
  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 30 * 1024 * 1024 });
  const idx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = idx >= 0 ? stdout.slice(0, idx) : stdout;
  const [codeStr, effectiveUrl] = (idx >= 0 ? stdout.slice(idx + 15).trim() : "0|").split("|");
  return { status: Number(codeStr) || 0, effectiveUrl, text };
}

async function getWorkingProxies() {
  const candidates = [];
  for (const [scheme, feedUrl] of PROXY_FEEDS) {
    try {
      const r = await curlReq(feedUrl, { timeoutSec: 8 });
      const lines = r.text
        .split(/\r?\n/)
        .map((s) => s.trim().replace(/^(socks5h?|https?):\/\//i, ""))
        .filter((s) => /^\d+\.\d+\.\d+\.\d+:\d+$/.test(s))
        .slice(0, 45);
      for (const hp of lines) candidates.push(`${scheme}://${hp}`);
    } catch {}
  }
  const working = [];
  for (let i = 0; i < candidates.length && working.length < 8; i += 25) {
    const batch = candidates.slice(i, i + 25);
    await Promise.all(
      batch.map(async (p) => {
        if (working.length >= 8) return;
        try {
          const t0 = Date.now();
          const r1 = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 8 });
          if (r1.status === 200 && !isCimaBlock(r1.text) && r1.text.includes("cimanow")) {
            working.push({ proxy: p, ms: Date.now() - t0 });
          }
        } catch {}
      })
    );
  }
  working.sort((a, b) => a.ms - b.ms);
  return working.map((w) => w.proxy);
}

function buildGatewayPostBody(vaultHtml) {
  const arrStart = vaultHtml.search(/window\["[^"]+"\]\s*=\s*\[/);
  const openBracket = arrStart >= 0 ? vaultHtml.indexOf("[", vaultHtml.indexOf("=", arrStart)) : -1;
  if (openBracket < 0) return null;
  const headSlice = vaultHtml.slice(openBracket + 1, openBracket + 4096);
  const b64Joined = [...headSlice.matchAll(/"([A-Za-z0-9+/=]+)"/g)].map((m) => m[1]).join("");
  const raw = Buffer.from(b64Joined, "base64");
  const needle = Buffer.from("<script>");
  for (let off = 0; off <= 8; off++) {
    for (let klen = 1; klen <= 10; klen++) {
      const key = Buffer.alloc(klen);
      for (let j = 0; j < klen && j < needle.length; j++) key[(off + j) % klen] = raw[off + j] ^ needle[j];
      let ok = true;
      for (let j = 0; j < needle.length; j++) {
        if ((raw[off + j] ^ key[(off + j) % klen]) !== needle[j]) { ok = false; break; }
      }
      if (!ok) continue;
      const topDecoded = Buffer.from(raw.map((v, i) => v ^ key[i % klen])).toString("utf8");
      if (!topDecoded.includes("<script>") || !topDecoded.includes("window.")) continue;
      const ts = topDecoded.match(/<script>([\s\S]*?)<\/script>/i)?.[1] || "";
      const mapBlock = ts.match(/window\.map_[a-z0-9]+\s*=\s*\{([\s\S]*?)\}/)?.[1];
      if (!mapBlock) continue;
      const chVar = mapBlock.match(/ch:\s*'([^']+)'/)?.[1];
      const riVar = mapBlock.match(/ri:\s*'([^']+)'/)?.[1];
      const keVar = mapBlock.match(/ke:\s*'([^']+)'/)?.[1];
      const seVar = mapBlock.match(/se:\s*'([^']+)'/)?.[1];
      const getVal = (v) => ts.match(new RegExp(`'${v}'\\s*:\\s*'([^']+)'`))?.[1];
      const ch = getVal(chVar), ri = getVal(riVar), ke = getVal(keVar), se = getVal(seVar);
      if (!ch || !ri || !ke || !se) continue;

      const keBytes = Buffer.from(ke, "base64");
      const seBytes = Buffer.from(se, "utf8");
      const secretHex = Buffer.from(keBytes.map((b, i) => b ^ seBytes[i % seBytes.length])).toString("utf8");
      const fp = "TW96aWxsYS81LjQ4";
      const hmac = crypto
        .createHmac("sha256", Buffer.from(secretHex, "utf8"))
        .update(Buffer.from(ri + ch + fp, "utf8"))
        .digest("base64");

      const postBody =
        `request_id=${encodeURIComponent(ri)}` +
        `&hmac_token=${encodeURIComponent(hmac)}` +
        `&ch=${encodeURIComponent(ch)}` +
        `&fp=${encodeURIComponent(fp)}`;
      return { postBody };
    }
  }
  return null;
}

function uninterleave(raw, stp) {
  const sp = raw.indexOf(".");
  if (sp === -1) return raw;
  const ev = raw.substring(0, sp).split("").reverse().join("");
  const od = raw.substring(sp + 1);
  const tot = ev.length + od.length;
  let res = "", ei = 0, oi = 0;
  for (let i = 0; i < tot; i++) {
    res += i % stp === 0 ? ev.charAt(ei++) : od.charAt(oi++);
  }
  return res;
}

function computeFlexHeight(html) {
  const fxStyle = html.match(/<x-fx\s+style='([^']+)'/)[1];
  const getPx = (st, prop) => Number(st.match(new RegExp(`(?:^|;)\\s*${prop}:(-?\\d+)px`))?.[1] || 0);
  const getPad = (st) => {
    const m = st.match(/(?:^|;)\s*padding:(\d+)px\s+(\d+)px/);
    return m ? [Number(m[1]), Number(m[2])] : [0, 0];
  };
  const getMar = (st) => {
    const m = st.match(/(?:^|;)\s*margin:(\d+)px\s+(\d+)px/);
    return m ? [Number(m[1]), Number(m[2])] : [0, 0];
  };
  const fxW = getPx(fxStyle, "width");
  const [fxPadV, fxPadH] = getPad(fxStyle);
  const colGap = getPx(fxStyle, "column-gap");
  const rowGap = getPx(fxStyle, "row-gap");
  const availW = fxW - 2 * fxPadH;

  const items = [];
  let domIdx = 0;
  for (const m of html.matchAll(/<x-i\s+style='([^']+)'/g)) {
    const st = m[1];
    domIdx++;
    if (st.includes("display:none")) continue;
    const order = Number(st.match(/order:(-?\d+)/)?.[1] || 0);
    const isBorderBox = st.includes("box-sizing:border-box");
    const w = getPx(st, "width");
    const h = getPx(st, "height");
    const [padV, padH] = getPad(st);
    const [marV, marH] = getMar(st);
    const boxW = isBorderBox ? w : w + 2 * padH;
    const boxH = isBorderBox ? h : h + 2 * padV;
    items.push({ order, domIdx, outerW: boxW + 2 * marH, outerH: boxH + 2 * marV });
  }
  items.sort((a, b) => a.order - b.order || a.domIdx - b.domIdx);

  const rows = [];
  let curW = 0, curMaxH = 0, countInRow = 0;
  for (const it of items) {
    const needed = countInRow === 0 ? it.outerW : curW + colGap + it.outerW;
    if (countInRow > 0 && needed > availW) {
      rows.push(curMaxH);
      curW = it.outerW;
      curMaxH = it.outerH;
      countInRow = 1;
    } else {
      curW = needed;
      if (it.outerH > curMaxH) curMaxH = it.outerH;
      countInRow++;
    }
  }
  if (countInRow > 0) rows.push(curMaxH);
  return 2 * fxPadV + rows.reduce((a, b) => a + b, 0) + Math.max(0, rows.length - 1) * rowGap;
}

// 100% PURE MATH DECODER FOR `/watching/?token=...` (NO BROWSER!)
function pureMathDecodeWatching(rawHtml) {
  const pTag = rawHtml.match(/<script\b[^>]*type="text\/plain"[^>]*id="(p_[^"]+)"[^>]*data-([A-Za-z0-9]+)="([^"]+)"/i);
  const gTag = rawHtml.match(/<script\b[^>]*id="(g_[^"]+)"[^>]*data-([A-Za-z0-9]+)="([^"]+)"[^>]*>([\s\S]*?)<\/script>/i);
  if (!pTag || !gTag) return { ok: false, err: "missing_p_or_g" };

  const modG = Number(gTag[4].match(/i\s*%\s*(\d+)/)?.[1] || 6);
  const s2 = Buffer.from(uninterleave(gTag[3], modG), "base64").toString("utf8");

  const evalExpr = (expr) =>
    vm.runInNewContext(
      expr
        .replace(/typeof\s+window\.decodeURIComponent\s*===\s*'function'/g, "true")
        .replace(/\b_envOk\b/g, "1"),
      { Math }
    );

  const tVal = evalExpr(s2.match(/left:'\s*\+\s*String\(([\s\S]*?)\)\s*\+\s*'px!important/)[1]);
  const fxHtml = s2.match(/_fx\.innerHTML\s*=\s*"([\s\S]*?)";_host\.appendChild/)[1].replace(/\\'/g, "'");
  const estFlex = computeFlexHeight(fxHtml);

  const listTailExpr = s2.match(/_listA\s*=\s*\[_tVal,\s*_flexVal,\s*([\s\S]*?)\];for/)[1];
  const listTailSum = vm.runInNewContext(`[${listTailExpr}]`, {
    Math,
    window: { decodeURIComponent },
    _envOk: 1,
  }).reduce((a, b) => a + b, 0);

  const constBlock = s2.match(/var _sB\s*=\s*([\s\S]*?);for\(var _st/)[1];
  const constCtx = { Math, window: { decodeURIComponent }, _envOk: 1 };
  vm.runInNewContext(`var _sB = ${constBlock};`, constCtx);
  const { _sB: initSB, _maxR: maxR, _cm: cm, _ca: ca } = constCtx;

  const stpP = evalExpr(s2.match(/_uninterleave\(_rawP,\s*([\s\S]*?)\);var _dFn/)[1]);
  const decBuf = Buffer.from(uninterleave(pTag[3], stpP), "base64");

  const rc4Block = s2.match(/var _tw\s*=\s*([\s\S]*?);var _S=/)[1];
  const rc4Ctx = { Math, window: { decodeURIComponent } };
  vm.runInNewContext(`var _tw = ${rc4Block};`, rc4Ctx);
  const { _tw: tw, _sb: sb, _ks: ks, _jm: jm } = rc4Ctx;

  const subPrefix = evalExpr(s2.match(/_x35dc\.substring\(([\s\S]*?)\);try\{if\(pEl/)[1]);

  function decryptPrefix(flexCand, len) {
    let sA = tVal + flexCand + listTailSum;
    let sB = initSB;
    for (let st = 0; st < maxR; st++) {
      sA = (sA * cm + ca + ((st * 9) & 255)) % 1000000009;
      sB = (((sB ^ sA) >>> 0) + ((st * 11) & 511)) % 1000000009;
    }
    const kv = (((sA + sB) ^ maxR) >>> 0) % 1000000009;
    const key = "f" + String(kv) + "p";
    const S = new Uint8Array(256);
    for (let i = 0; i < 256; i++) S[i] = (i * ks + sb) & 255;
    let j = 0;
    for (let i = 0; i < 256; i++) {
      j = (j + S[i] + key.charCodeAt(i % key.length) + ((i * 5 + sb) & 255)) & 255;
      const tmp = S[i]; S[i] = S[j]; S[j] = tmp;
    }
    const n = Math.min(len, decBuf.length);
    const out = Buffer.allocUnsafe(n);
    let ii = 0;
    j = 0;
    for (let y = 0; y < n; y++) {
      ii = (ii + 1) & 255;
      j = (j + S[ii]) & 255;
      const tmp = S[ii]; S[ii] = S[j]; S[j] = tmp;
      out[y] = decBuf[y] ^ S[(S[ii] + S[j]) & 255] ^ ((y * 5 + tw) & 255) ^ ((j ^ ii) & jm);
    }
    return { kv, tkM1: kv & 255, tkM2: (kv >> 8) & 255, out };
  }

  let winningDelta = null;
  let kvInfo = null;
  const deltas = [0];
  for (let d = 1; d <= 300; d++) deltas.push(d, -d);
  for (const d of deltas) {
    const probe = decryptPrefix(estFlex + d, subPrefix + 120);
    const head = probe.out.toString("utf8").substring(subPrefix).trimStart().toLowerCase();
    if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<script") || head.startsWith("<div")) {
      winningDelta = d;
      kvInfo = decryptPrefix(estFlex + d, decBuf.length);
      break;
    }
  }
  if (!kvInfo) return { ok: false, err: "rc4_no_match", tVal, estFlex };

  const html = kvInfo.out.toString("utf8").substring(subPrefix);
  const { tkM1, tkM2, kv } = kvInfo;

  // Restore tabs & download links using exact parameters from `_restoreTabs` in s2!
  const tabAttr = s2.match(/querySelectorAll\('li\[(data-[A-Za-z0-9]+)\]'\)/)?.[1];
  const tabBase = Number(s2.match(/parseInt\(bx\[hi\],\s*(\d+)\)/)?.[1] || 24);
  const tabMul = Number(s2.match(/hi\s*\*\s*(\d+)\)\s*\+\s*k/)?.[1] || 9);

  const dlAttr = s2.match(/querySelectorAll\('a\[(data-[A-Za-z0-9]+)\]'\)/)?.[1];
  const dlBase = Number(s2.match(/parseInt\(hx\[j\],\s*(\d+)\)/)?.[1] || 16);
  const dlMul = Number(s2.match(/j\s*\*\s*(\d+)\s*\+\s*_tkM2/)?.[1] || 5);

  const restoredTabs = [];
  if (tabAttr) {
    const reTab = new RegExp(`<li\\b([^>]*?)${tabAttr}="([^"]+)"([^>]*)>([\\s\\S]*?)<\\/li>`, "gi");
    let k = 0;
    for (const m of html.matchAll(reTab)) {
      const bx = m[2].split("_");
      let s = "";
      for (let hi = 0; hi < bx.length; hi++) {
        s += String.fromCharCode(parseInt(bx[hi], tabBase) ^ tkM1 ^ ((hi * tabMul + k + tkM2) & 255));
      }
      const [dataId, dataIndex] = s.split("#");
      const name = m[4].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
      restoredTabs.push({ k, name, dataId, dataIndex });
      k++;
    }
  }

  const restoredDls = [];
  if (dlAttr) {
    const reDl = new RegExp(`<a\\b([^>]*?)${dlAttr}="([^"]+)"([^>]*)>([\\s\\S]*?)<\\/a>`, "gi");
    for (const m of html.matchAll(reDl)) {
      const hx = m[2].split("-");
      let u = "";
      for (let j = 0; j < hx.length; j++) {
        u += String.fromCharCode(parseInt(hx[j], dlBase) ^ tkM1 ^ ((j * dlMul + tkM2) & 255));
      }
      const text = m[4].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
      restoredDls.push({ href: u, text });
    }
  }

  // Search decoded HTML for `_rf` or `_getCoreToken`!
  const rfName = s2.match(/window\['(_rf[0-9a-zA-Z]+)'\]/)?.[1];
  const scriptsWithRfOrCore = [];
  for (const [idx, m] of [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].entries()) {
    const body = m[2];
    if (
      body.includes("_getCoreToken") ||
      (rfName && body.includes(rfName)) ||
      body.includes("core.php") ||
      body.length > 8000
    ) {
      scriptsWithRfOrCore.push({
        idx,
        len: body.length,
        hasRf: rfName ? body.includes(rfName) : false,
        hasCoreTok: body.includes("_getCoreToken"),
        rfCalls: rfName ? [...body.matchAll(new RegExp(`${rfName}\\(["']([^"']+)["']\\)`, "g"))].map((x) => x[1]) : [],
        tail: body.slice(-2500),
      });
    }
  }

  return {
    ok: true,
    tVal,
    estFlex,
    winningDelta,
    kv,
    tkM1,
    tkM2,
    rfName,
    tabAttr,
    dlAttr,
    restoredTabs,
    restoredDlsCount: restoredDls.length,
    restoredDlsSample: restoredDls.slice(0, 12),
    scriptsWithRfOrCore,
  };
}

class CDPClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Map();
  }
  async connect() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => {
      this.ws.addEventListener("open", resolve, { once: true });
      this.ws.addEventListener("error", reject, { once: true });
    });
    this.ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(typeof ev.data === "string" ? ev.data : ev.data.toString());
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method) {
        const arr = this.listeners.get(msg.method) || [];
        for (const fn of arr) fn(msg.params);
      }
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP timeout: ${method}`));
      }, 22000);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  on(method, fn) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(fn);
  }
  close() {
    try { this.ws.close(); } catch {}
  }
}

// Click tabs via real trusted mouse events in Chrome over proxy on `mintedUrl`!
async function trustedClickTabsInRealChrome(mintedUrl, proxyUrl, port) {
  const userDataDir = `/tmp/chrome_v19_${port}_${Math.random().toString(36).slice(2)}`;
  const chromeBin = fs.existsSync("/usr/bin/google-chrome")
    ? "/usr/bin/google-chrome"
    : "/usr/bin/google-chrome-stable";

  const proc = spawn(
    chromeBin,
    [
      "--headless=new",
      "--no-sandbox",
      "--disable-setuid-sandbox",
      "--disable-dev-shm-usage",
      "--disable-gpu",
      "--disable-blink-features=AutomationControlled",
      "--window-size=1280,900",
      `--user-agent=${UA}`,
      `--proxy-server=${proxyUrl}`,
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${userDataDir}`,
      "about:blank",
    ],
    { stdio: "ignore" }
  );

  let cdp = null;
  const reqMap = new Map();
  const coreRequests = [];

  try {
    let wsUrl = null;
    for (let i = 0; i < 60; i++) {
      await sleep(250);
      try {
        const res = await fetch(`http://127.0.0.1:${port}/json/list`);
        const pages = await res.json();
        const page = pages.find((p) => p.type === "page" && p.webSocketDebuggerUrl);
        if (page) { wsUrl = page.webSocketDebuggerUrl; break; }
      } catch {}
    }
    if (!wsUrl) throw new Error("Chrome start failed");

    cdp = new CDPClient(wsUrl);
    await cdp.connect();
    await cdp.send("Page.enable");
    await cdp.send("Network.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("Page.addScriptToEvaluateOnNewDocument", {
      source: `try { delete Object.getPrototypeOf(navigator).webdriver; } catch (e) {}`,
    });

    cdp.on("Network.requestWillBeSent", (p) => {
      if (p.request.url.includes("core.php")) {
        reqMap.set(p.requestId, { url: p.request.url, headers: p.request.headers });
      }
    });
    cdp.on("Network.loadingFinished", async (p) => {
      const entry = reqMap.get(p.requestId);
      if (!entry) return;
      try {
        const b = await cdp.send("Network.getResponseBody", { requestId: p.requestId });
        const rawBody = b.base64Encoded ? Buffer.from(b.body, "base64").toString("utf8") : b.body;
        // Also decode data-cnk + data-cna if present!
        let decodedSrc = null;
        const cnk = rawBody.match(/data-cnk=["']([^"']+)["']/)?.[1];
        const cna = rawBody.match(/data-cna=["']([^"']+)["']/)?.[1];
        if (cnk && cna) {
          let k = "", u = "";
          for (let i = 0; i < cnk.length; i += 2) k += String.fromCharCode(parseInt(cnk.substr(i, 2), 16));
          const a = cna.split(".");
          for (let j = 0; j < a.length; j++) {
            u += String.fromCharCode(parseInt(a[j], 10) ^ k.charCodeAt(j % k.length) ^ ((j * 13 + 7) & 255));
          }
          decodedSrc = u;
        }
        coreRequests.push({ url: entry.url, rawBody: rawBody.slice(0, 600), decodedSrc });
      } catch {}
    });

    // Fire-and-forget navigate (don't let slow subresources time out Page.navigate!)
    cdp.send("Page.navigate", {
      url: mintedUrl,
      referrer: "https://rm.freex2line.online/2020/02/blog-post.html/",
    }).catch(() => {});

    let tabBoxes = null;
    for (let t = 0; t < 24; t++) {
      await sleep(750);
      try {
        const ev = await cdp.send("Runtime.evaluate", {
          expression: `(() => {
            // Remove any invisible popunder overlays (#xqeqjp, #xqeqjp3) before clicking!
            document.querySelectorAll('#xqeqjp, #xqeqjp1, #xqeqjp3, .swal2-container').forEach(el => el.remove());
            const lis = [...document.querySelectorAll('#watch li[data-id]')].filter(
              li => li.getAttribute('data-index') !== '00' && li.getAttribute('aria-label') !== 'embed'
            );
            if (!lis.length) return null;
            lis[0].scrollIntoView({ block: 'center' });
            return JSON.stringify({
              hasGetCoreToken: typeof window._getCoreToken === 'function',
              sampleTok: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
              fnSrc: typeof window._getCoreToken === 'function' ? window._getCoreToken.toString().slice(0, 2000) : null,
              boxes: lis.map(li => {
                const r = li.getBoundingClientRect();
                return { name: (li.innerText || '').trim(), idx: li.dataset.index, id: li.dataset.id, x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
              })
            });
          })()`,
          returnByValue: true,
        });
        if (ev?.result?.value) {
          tabBoxes = JSON.parse(ev.result.value);
          if (tabBoxes?.boxes?.length) break;
        }
      } catch {}
    }

    if (tabBoxes?.boxes) {
      for (const b of tabBoxes.boxes) {
        if (b.w > 0 && b.h > 0) {
          await cdp.send("Runtime.evaluate", {
            expression: `document.querySelectorAll('#xqeqjp, #xqeqjp1, #xqeqjp3, .swal2-container').forEach(el => el.remove());`,
          });
          await cdp.send("Input.dispatchMouseEvent", { type: "mousePressed", x: b.x, y: b.y, button: "left", clickCount: 1 });
          await cdp.send("Input.dispatchMouseEvent", { type: "mouseReleased", x: b.x, y: b.y, button: "left", clickCount: 1 });
          await sleep(1200);
        }
      }
      await sleep(1500);
    }

    return { tabBoxes, coreRequests };
  } finally {
    if (cdp) cdp.close();
    try { proc.kill("SIGKILL"); } catch {}
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch {}
  }
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;

    // 1) Inspect tail of Jetload4/ HTML!
    const jetJar = path.join(os.tmpdir(), `cn_jet_${Math.random().toString(36).slice(2)}.cookies`);
    try {
      const jetGw =
        "https://jetload.top/Jetloadon4/?link=aHR0cHM6Ly9jZG4xLmpldGxvYWQudG9wLyU1QkNpbWEtTm93LkNvTSU1RCUyMEZhbGwuMi5EZWFkcG9pbnQuMjAyNi5IRC0xMDgwcC5tcDQ=";
      await curlReq(jetGw, { cookieJar: jetJar, referer: "https://cimanow.cc/", timeoutSec: 10 });
      const j2 = await curlReq("https://jetload.top/Jetload4/", { cookieJar: jetJar, referer: jetGw, timeoutSec: 10 });
      out.jetloadTail = j2.text.slice(-8000);
    } catch (e) {
      out.jetloadErr = String(e);
    } finally {
      try { fs.rmSync(jetJar, { force: true }); } catch {}
    }

    // 2) Full pure-math decode + real Chrome trusted tab clicks on Fall 2!
    const itemUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
    for (const proxy of proxies.slice(0, 3)) {
      const jar = path.join(os.tmpdir(), `cn_v19_${Math.random().toString(36).slice(2)}.cookies`);
      try {
        const mv = await curlReq(itemUrl, { proxy, cookieJar: jar, referer: "https://cimanow.cc/home/" });
        if (mv.status !== 200 || isCimaBlock(mv.text)) continue;
        const shineUrl = mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1] || "";
        if (!shineUrl) continue;

        await curlReq(shineUrl, { proxy, cookieJar: jar, referer: itemUrl });
        await curlReq("https://rm.freex2line.online/redirectingfree/", { proxy, cookieJar: jar });
        const r3 = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/", {
          proxy,
          cookieJar: jar,
          referer: "https://rm.freex2line.online/redirectingfree/",
        });
        const gw = buildGatewayPostBody(r3.text);
        if (!gw) continue;

        // Mint TWO tokens: one for curl pure-math test, one for real Chrome test (in case ?token= is single-use!)
        await sleep(10300);
        const p10 = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/get-link.php", {
          proxy,
          cookieJar: jar,
          method: "POST",
          formBody: gw.postBody,
          referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            Origin: "https://rm.freex2line.online",
            "X-Requested-With": "XMLHttpRequest",
          },
        });
        const mintedUrl1 = p10.text.trim();
        if (!mintedUrl1.startsWith("http") || !mintedUrl1.includes("?token=")) continue;

        const watchRes = await curlReq(mintedUrl1, {
          proxy,
          cookieJar: jar,
          referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
        });
        out.pureMathResult = pureMathDecodeWatching(watchRes.text);

        // Mint second token for real Chrome trusted clicks!
        await curlReq(shineUrl, { proxy, cookieJar: jar, referer: itemUrl });
        await curlReq("https://rm.freex2line.online/redirectingfree/", { proxy, cookieJar: jar });
        const r3b = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/", {
          proxy,
          cookieJar: jar,
          referer: "https://rm.freex2line.online/redirectingfree/",
        });
        const gwb = buildGatewayPostBody(r3b.text);
        await sleep(10300);
        const p10b = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/get-link.php", {
          proxy,
          cookieJar: jar,
          method: "POST",
          formBody: gwb.postBody,
          referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            Origin: "https://rm.freex2line.online",
            "X-Requested-With": "XMLHttpRequest",
          },
        });
        const mintedUrl2 = p10b.text.trim();
        if (mintedUrl2.startsWith("http") && mintedUrl2.includes("?token=")) {
          out.realChromeClicks = await trustedClickTabsInRealChrome(mintedUrl2, proxy, 9620);
        }
        break;
      } catch (e) {
        out.itemErr = String(e.stack || e);
      } finally {
        try { fs.rmSync(jar, { force: true }); } catch {}
      }
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v19 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
