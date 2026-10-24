import fs from "fs";
import vm from "vm";
import crypto from "crypto";
import { execFile } from "child_process";
import { promisify } from "util";

const execFileAsync = promisify(execFile);
const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const PROXY_FEEDS = [
  ["socks5h", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5h", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
  ["socks5h", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt"],
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
    method = "GET",
    postData = null,
    proxy = null,
    referer = null,
    cookieJarFile = null,
    extraHeaders = [],
    follow = true,
    timeoutSec = 18,
    includeHeaders = false,
  } = opts;

  const hdrFile = includeHeaders ? `/tmp/curl_h_${Math.random().toString(36).slice(2)}.txt` : null;
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "8",
    "-X",
    method,
    "-A",
    UA,
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: ar,en-US;q=0.9,en;q=0.8",
  ];
  if (follow) args.push("-L", "--max-redirs", "6");
  if (hdrFile) args.push("-D", hdrFile);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  if (cookieJarFile) args.push("-b", cookieJarFile, "-c", cookieJarFile);
  for (const h of extraHeaders) args.push("-H", h);
  if (postData !== null) args.push("--data-raw", postData);
  args.push(url);

  try {
    const { stdout } = await execFileAsync("curl", args, { maxBuffer: 40 * 1024 * 1024 });
    const idx = stdout.lastIndexOf("\n__CURL_META__:");
    const body = idx >= 0 ? stdout.slice(0, idx) : stdout;
    const meta = idx >= 0 ? stdout.slice(idx + 15).trim() : "0||";
    const [codeStr, effectiveUrl, redirectUrl] = meta.split("|");
    let respHeaders = "";
    if (hdrFile && fs.existsSync(hdrFile)) {
      respHeaders = fs.readFileSync(hdrFile, "utf8");
      fs.unlinkSync(hdrFile);
    }
    return { status: Number(codeStr) || 0, effectiveUrl, redirectUrl, respHeaders, text: body };
  } finally {
    if (hdrFile && fs.existsSync(hdrFile)) {
      try { fs.unlinkSync(hdrFile); } catch {}
    }
  }
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
  const batchSize = 25;
  for (let i = 0; i < candidates.length && working.length < 6; i += batchSize) {
    const batch = candidates.slice(i, i + batchSize);
    await Promise.all(
      batch.map(async (p) => {
        if (working.length >= 6) return;
        try {
          const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 9 });
          if (r.status === 200 && !isCimaBlock(r.text) && r.text.includes("cimanow")) {
            working.push(p);
          }
        } catch {}
      })
    );
  }
  return working;
}

async function smartReq(url, proxies, opts = {}) {
  let lastErr = null;
  for (const p of proxies) {
    try {
      const r = await curlReq(url, { ...opts, proxy: p });
      if (r.status > 0 && !isCimaBlock(r.text)) return { ...r, usedProxy: p };
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error(`All proxies failed for ${url}`);
}

// Fetch the encrypted `/watching/` vault from `rm.freex2line.online`!
async function fetchGatewayVault(shineUrl, itemUrl, proxies) {
  const jar = `/tmp/cn_vault_${Math.random().toString(36).slice(2)}.txt`;
  try {
    for (const p of proxies) {
      try {
        // Step 0: GET loadon/?link=<b64> -> sets PHPSESSID
        const r0 = await curlReq(shineUrl, {
          proxy: p,
          referer: itemUrl,
          cookieJarFile: jar,
          follow: false,
          timeoutSec: 15,
        });
        if (r0.status !== 200) continue;

        // Step 1: GET /redirectingfree/ (no Referer, with PHPSESSID)
        const r1 = await curlReq("https://rm.freex2line.online/redirectingfree/", {
          proxy: p,
          referer: null,
          cookieJarFile: jar,
          follow: false,
          timeoutSec: 15,
        });
        const nextMatch = r1.text.match(/location(?:\.href)?\s*=\s*["']([^"']+)["']/i);
        const blogUrl = nextMatch ? new URL(nextMatch[1].trim(), "https://rm.freex2line.online/redirectingfree/").toString() : "https://rm.freex2line.online/2020/02/blog-post.html/";

        // Step 2: GET /2020/02/blog-post.html/ (follow 301 -> /blog-post.html/)
        const r2 = await curlReq(blogUrl, {
          proxy: p,
          referer: "https://rm.freex2line.online/redirectingfree/",
          cookieJarFile: jar,
          follow: true,
          timeoutSec: 22,
        });
        if (r2.status === 200 && r2.text.length > 100000) {
          return { ok: true, usedProxy: p, vaultHtml: r2.text };
        }
      } catch {}
    }
    return { ok: false, err: "Failed across all proxies" };
  } finally {
    if (fs.existsSync(jar)) try { fs.unlinkSync(jar); } catch {}
  }
}

// Decode the `rm.freex2line.online/2020/02/blog-post.html/` vault HTML!
function decodeVaultHtml(vaultHtml) {
  const tailSample = vaultHtml.slice(-8000);
  const headSample = vaultHtml.slice(0, 1200);

  // Extract the big base64 string array `window["..."] = ["...", "...", ...];`
  const arrStart = vaultHtml.search(/window\["[^"]+"\]\s*=\s*\[/);
  const arrEnd = vaultHtml.indexOf("];", arrStart);
  const arrSlice = arrStart >= 0 && arrEnd > arrStart ? vaultHtml.slice(arrStart, arrEnd + 2) : "";
  const b64Joined = [...arrSlice.matchAll(/"([A-Za-z0-9+/=]+)"/g)].map((m) => m[1]).join("");
  const raw = Buffer.from(b64Joined, "base64");

  // Also check if the exact XOR key appears in `tailSample`, or recover cleanly via known prefix `<script>` / space-frequency!
  // Notice: decoded HTML ALWAYS starts with `<script>` (`3c 73 63 72 69 70 74 3e`)!
  let decoded = "";
  let keyUsed = "";

  // Check if keylen 1..32 matches `<script>` at offset 0 (after stripping UTF-8 BOM if any):
  const expectedPrefix = Buffer.from("<script>");
  for (let klen = 1; klen <= 32; klen++) {
    const key = Buffer.alloc(klen);
    for (let pos = 0; pos < klen; pos++) {
      const counts = new Map();
      for (let i = pos; i < Math.min(raw.length, 60000); i += klen) {
        counts.set(raw[i], (counts.get(raw[i]) || 0) + 1);
      }
      key[pos] = [...counts.entries()].sort((a, c) => c[1] - a[1])[0][0] ^ 0x20;
    }
    const testPrefix = Buffer.from(raw.slice(0, 16).map((v, i) => v ^ key[i % klen])).toString("utf8");
    if (testPrefix.startsWith("<script>") || testPrefix.includes("<script")) {
      keyUsed = key.toString("utf8");
      decoded = Buffer.from(raw.map((v, i) => v ^ key[i % klen])).toString("utf8");
      break;
    }
  }

  return { headSample, tailSample, keyUsed, decodedLen: decoded.length, decoded };
}

// Deobfuscate / execute `nbm2oHaQcN60y6Ozwm.js` in a rich browser-like VM to get `_getCoreToken()` AND log its exact inputs/algorithm!
function analyzeNbmAndToken(nbmJsCode, watchingHtml, watchUrl) {
  const report = {};

  // 1) Extract string table from `nbm2oHaQcN60y6Ozwm.js` by running only the string-table rotation + decoder!
  try {
    // Find `(function(){` after the string rotation IIFE!
    // Easier: run the whole `nbmJsCode` in a mocked DOM + window context with Watching's top `<script>` (`_0x_cfg`, `ctx_...`, `ptr_...`, `map_...`)!
    const topScriptMatch = watchingHtml.match(/<script>([\s\S]*?)<\/script>/i);
    const topInitJs = topScriptMatch ? topScriptMatch[1] : "";
    report.topInitJs = topInitJs;

    const cryptoLogs = [];
    const subtleMock = {
      importKey: async (...args) => {
        cryptoLogs.push({ op: "importKey", args: JSON.stringify(args) });
        return crypto.webcrypto.subtle.importKey(...args);
      },
      sign: async (...args) => {
        cryptoLogs.push({ op: "sign", argsLen: args.length });
        return crypto.webcrypto.subtle.sign(...args);
      },
      digest: async (...args) => {
        cryptoLogs.push({ op: "digest" });
        return crypto.webcrypto.subtle.digest(...args);
      },
    };

    const dummyEl = () => ({
      style: {},
      classList: { add() {}, remove() {}, contains: () => false },
      setAttribute() {},
      getAttribute: () => null,
      hasAttribute: () => false,
      appendChild() {},
      removeChild() {},
      addEventListener() {},
      querySelectorAll: () => [],
      querySelector: () => null,
      children: [],
      childNodes: [],
      innerHTML: "",
      textContent: "",
    });

    const locObj = new URL(watchUrl);
    const sb = {
      console: { log() {}, warn() {}, error() {} },
      setTimeout: (fn) => 1,
      clearTimeout() {},
      setInterval: () => 1,
      clearInterval() {},
      MutationObserver: class {
        observe() {}
        disconnect() {}
      },
      TextEncoder,
      TextDecoder,
      Uint8Array,
      ArrayBuffer,
      DataView,
      Promise,
      Date,
      Math,
      JSON,
      parseInt,
      parseFloat,
      encodeURIComponent,
      decodeURIComponent,
      atob: (b) => Buffer.from(b, "base64").toString("binary"),
      btoa: (b) => Buffer.from(b, "binary").toString("base64"),
      crypto: {
        getRandomValues: (arr) => crypto.webcrypto.getRandomValues(arr),
        subtle: subtleMock,
      },
      navigator: {
        userAgent: UA,
        webdriver: false,
        languages: ["ar", "en-US", "en"],
        platform: "Linux armv8l",
      },
      location: {
        href: locObj.href,
        hostname: locObj.hostname,
        pathname: locObj.pathname,
        origin: locObj.origin,
        protocol: locObj.protocol,
        search: locObj.search,
      },
      document: {
        cookie: "",
        readyState: "complete",
        documentElement: dummyEl(),
        head: dummyEl(),
        body: dummyEl(),
        createElement: dummyEl,
        getElementById: () => null,
        querySelector: () => null,
        querySelectorAll: () => [],
        getElementsByTagName: () => [dummyEl()],
        addEventListener: (ev, cb) => {},
      },
    };
    sb.window = sb;
    sb.self = sb;
    sb.globalThis = sb;
    sb.top = sb;
    sb.parent = sb;

    vm.createContext(sb);
    vm.runInContext(topInitJs, sb, { timeout: 3000 });

    // Also run all inline scripts from `watchingHtml` BEFORE `nbmJsCode` (except jQuery/DOM ones) just in case!
    const allWatchScripts = [...watchingHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((m, idx) => ({
      idx,
      attrs: m[1].trim(),
      len: m[2].length,
      preview: m[2].slice(0, 400),
      code: m[2],
    }));
    report.watchingScriptsSummary = allWatchScripts.map(({ idx, attrs, len, preview }) => ({ idx, attrs, len, preview }));

    vm.runInContext(nbmJsCode, sb, { timeout: 6000 });

    report.hasGetCoreTokenAfterNbm = typeof sb._getCoreToken === "function";
    if (typeof sb._getCoreToken === "function") {
      const tok1 = sb._getCoreToken();
      const tok2 = sb._getCoreToken();
      report.token1 = tok1;
      report.token2 = tok2;
      report.getCoreTokenFnSource = sb._getCoreToken.toString().slice(0, 4000);
    }

    // Check what properties were added to `sb`!
    report.addedWindowKeys = Object.keys(sb).filter(
      (k) =>
        ![
          "console", "setTimeout", "clearTimeout", "setInterval", "clearInterval",
          "MutationObserver", "TextEncoder", "TextDecoder", "Uint8Array", "ArrayBuffer",
          "DataView", "Promise", "Date", "Math", "JSON", "parseInt", "parseFloat",
          "encodeURIComponent", "decodeURIComponent", "atob", "btoa", "crypto",
          "navigator", "location", "document", "window", "self", "globalThis", "top", "parent",
        ].includes(k)
    );
    report.cryptoLogs = cryptoLogs;

    // Also dump decoded strings from `nbmJsCode` by grabbing `_0x39aa` in VM!
    // Look at `nbmJsCode`: `function _0x39aa(...)` is top-level inside the file or outer scope? Wait: let's expose `_0x39aa` if top-level or regex-extract `function _0x39aa`!
    const fnMatch = nbmJsCode.match(/function\s+(_0x[a-f0-9]+)\s*\([^)]*\)\s*\{\s*var\s+_0x[a-f0-9]+\s*=\s*_0x2426\(\)/);
    if (fnMatch) {
      report.decoderFnName = fnMatch[1];
    }
  } catch (e) {
    report.vmError = String(e.stack || e);
  }

  // Also dump unique readable strings decoded via `_0x39aa` from `nbmJsCode`!
  try {
    // Find where the second IIFE starts: `),(` after `_0x2426,0x...`
    const splitMatch = nbmJsCode.match(/\}\(_0x[0-9a-f]+,[^)]+\)\),\(function\(\)\{/);
    if (splitMatch) {
      // Find `function _0x2426()` and `function _0x39aa(...)` at the bottom or top of `nbmJsCode`!
      const bottomIdx = nbmJsCode.indexOf("function _0x2426(");
      const rotPart = nbmJsCode.slice(0, splitMatch.index + splitMatch[0].indexOf("),(") + 1);
      const helpersPart = bottomIdx >= 0 ? nbmJsCode.slice(bottomIdx) : "";
      if (helpersPart) {
        const strSb = {};
        vm.createContext(strSb);
        vm.runInContext(rotPart + ";\n" + helpersPart, strSb, { timeout: 4000 });
        if (typeof strSb._0x39aa === "function") {
          const decodedStrings = [];
          for (let i = 0; i < 1500; i++) {
            try {
              const v = strSb._0x39aa(i);
              if (typeof v === "string" && v.length > 0 && !decodedStrings.includes(v)) {
                decodedStrings.push(v);
              }
            } catch {}
          }
          report.nbmDecodedStrings = decodedStrings.slice(0, 600);
        }
      }
    } else {
      // Look for `function _0x39aa` anywhere in `nbmJsCode`
      const idx1 = nbmJsCode.indexOf("function _0x2426");
      const idx2 = nbmJsCode.indexOf("function _0x39aa");
      report.nbmFnOffsets = { idx2426: idx1, idx39aa: idx2, tail500: nbmJsCode.slice(-500) };
    }
  } catch (e) {
    report.strExtractErr = String(e.message || e);
  }

  return report;
}

async function inspectDecodedWatching(watchingHtml, watchUrl, nbmJsCode, proxies) {
  const res = {};
  const tokenAnalysis = analyzeNbmAndToken(nbmJsCode, watchingHtml, watchUrl);
  res.tokenAnalysis = tokenAnalysis;
  const token = tokenAnalysis.token1 || "";

  // Parse `<ul id="watch">`
  const watchUl = watchingHtml.match(/<ul[^>]*id=["']watch["'][^>]*>([\s\S]*?)<\/ul>/i)?.[0] || "";
  res.watchUlHtml = watchUl.slice(0, 5000);

  const tabs = [...watchUl.matchAll(/<li\b([^>]*)>([\s\S]*?)<\/li>/gi)].map((m) => ({
    ariaLabel: m[1].match(/aria-label=["']([^"']*)["']/i)?.[1] || "",
    dataIndex: m[1].match(/data-index=["']([^"']*)["']/i)?.[1] || "",
    dataId: m[1].match(/data-id=["']([^"']*)["']/i)?.[1] || "",
    text: m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
    innerHtml: m[2].trim().slice(0, 600),
  }));
  res.tabs = tabs;

  // Test `core.php` switch on EVERY valid tab!
  const validTabs = tabs.filter(
    (t) => t.ariaLabel !== "embed" && t.ariaLabel !== "uptobox" && t.dataIndex !== "00" && t.dataId
  );
  res.switchCalls = [];
  for (let i = 0; i < validTabs.length; i++) {
    const t = validTabs[i];
    if (i === 0) {
      // Test without token first
      const rNo = await smartReq(
        `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${encodeURIComponent(t.dataIndex)}&id=${encodeURIComponent(t.dataId)}`,
        proxies,
        { referer: watchUrl, extraHeaders: ["X-Requested-With: XMLHttpRequest"] }
      );
      res.switchWithoutToken = { status: rNo.status, body: rNo.text.trim().slice(0, 500) };
    }
    const freshTok = token; // or recompute
    const url =
      `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php` +
      (freshTok ? `?token=${encodeURIComponent(freshTok)}&` : `?`) +
      `action=switch&index=${encodeURIComponent(t.dataIndex)}&id=${encodeURIComponent(t.dataId)}`;
    const sr = await smartReq(url, proxies, {
      referer: watchUrl,
      extraHeaders: ["X-Requested-With: XMLHttpRequest"],
    });
    res.switchCalls.push({
      name: t.text,
      index: t.dataIndex,
      id: t.dataId,
      status: sr.status,
      body: sr.text.trim().slice(0, 600),
    });
  }

  // Parse `<ul id="download">` quality sections & links!
  const dlIdx = watchingHtml.search(/<ul[^>]*id=["']download["']/i);
  const dlSlice = dlIdx >= 0 ? watchingHtml.slice(dlIdx, dlIdx + 25000) : "";
  res.downloadSectionHtml = dlSlice.slice(0, 8000);
  res.downloadLinks = [...dlSlice.matchAll(/<a\b([^>]*)href=["']([^"']+)["']([^>]*)>([\s\S]*?)<\/a>/gi)].map((m) => ({
    href: m[2].replace("web2.cnvids.com", "fr.cimanow.cc"),
    rawHref: m[2],
    text: m[4].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
  }));

  return res;
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;

    const nbmResp = await smartReq(
      "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/nbm2oHaQcN60y6Ozwm.js?v=2.3",
      proxies,
      { referer: "https://cimanow.cc/home/" }
    );
    const nbmJsCode = nbmResp.text;

    const items = [
      {
        name: "ForeignMovie_Fall2",
        url: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/",
      },
      {
        name: "ArabicMovie_BeitElRuby",
        url: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-%d8%a8%d9%8a%d8%aa-%d8%a7%d9%84%d8%b1%d9%88%d8%a8%d9%8a-2023/",
      },
      {
        name: "ForeignEpisode_LupinS4E1",
        url: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/",
      },
    ];

    out.items = {};
    for (let idx = 0; idx < items.length; idx++) {
      const it = items[idx];
      const mv = await smartReq(it.url, proxies, { referer: "https://cimanow.cc/home/" });
      const shineUrl = mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1] || "";
      const vault = await fetchGatewayVault(shineUrl, it.url, proxies);
      if (!vault.ok) {
        out.items[it.name] = { shineUrl, vault };
        continue;
      }
      const dec = decodeVaultHtml(vault.vaultHtml);
      const watchUrl = it.url + "watching/";
      const inspected = await inspectDecodedWatching(dec.decoded, watchUrl, nbmJsCode, proxies);
      out.items[it.name] = {
        shineUrl,
        vaultKeyUsed: dec.keyUsed,
        decodedLen: dec.decodedLen,
        vaultHeadSample: idx === 0 ? dec.headSample : undefined,
        vaultTailSample: idx === 0 ? dec.tailSample : undefined,
        // Only keep full nbmDecodedStrings on first item to keep JSON concise!
        tokenAnalysis:
          idx === 0
            ? inspected.tokenAnalysis
            : {
                topInitJs: inspected.tokenAnalysis.topInitJs,
                hasGetCoreTokenAfterNbm: inspected.tokenAnalysis.hasGetCoreTokenAfterNbm,
                token1: inspected.tokenAnalysis.token1,
                token2: inspected.tokenAnalysis.token2,
              },
        watchUlHtml: inspected.watchUlHtml,
        tabs: inspected.tabs,
        switchWithoutToken: inspected.switchWithoutToken,
        switchCalls: inspected.switchCalls,
        downloadSectionHtml: idx === 0 ? inspected.downloadSectionHtml : undefined,
        downloadLinks: inspected.downloadLinks,
      };
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v8 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
