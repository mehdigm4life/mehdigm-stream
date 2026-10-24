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
  const headSample = vaultHtml.slice(0, 800);

  // Extract the big base64 string array `window["..."] = ["...", "...", ...];`
  const arrStart = vaultHtml.search(/window\["[^"]+"\]\s*=\s*\[/);
  const openBracket = arrStart >= 0 ? vaultHtml.indexOf("[", vaultHtml.indexOf("=", arrStart)) : -1;
  const arrEnd = openBracket >= 0 ? vaultHtml.indexOf("];", openBracket) : -1;
  const arrSlice = openBracket >= 0 && arrEnd > openBracket ? vaultHtml.slice(openBracket + 1, arrEnd) : "";
  const afterArr = arrEnd > 0 ? vaultHtml.slice(arrEnd + 2) : "";

  // Decode the `e=[...],k=...` stage-2 loader right after `];` so we have the exact author code!
  let outerDecoderJs = "";
  const eMatch = afterArr.match(/e\s*=\s*\[([\d,\s]+)\]\s*,\s*k\s*=\s*(\d+)/);
  if (eMatch) {
    const kVal = Number(eMatch[2]);
    const nums = eMatch[1].split(",").map((x) => Number(x.trim()));
    outerDecoderJs = nums.map((n) => String.fromCharCode(n ^ kVal)).join("");
  }

  const b64Joined = [...arrSlice.matchAll(/"([A-Za-z0-9+/=]+)"/g)].map((m) => m[1]).join("");
  const raw = Buffer.from(b64Joined, "base64");

  let decoded = "";
  let keyUsed = "";

  // Exact O(1) known-plaintext solver: decoded HTML starts (within first 8 bytes of whitespace/BOM) with `<script>` followed by `window.`!
  const needle = Buffer.from("<script>");
  outer: for (let off = 0; off <= 8; off++) {
    for (let klen = 1; klen <= 10; klen++) {
      const key = Buffer.alloc(klen);
      for (let j = 0; j < klen; j++) {
        if (j < needle.length) {
          key[(off + j) % klen] = raw[off + j] ^ needle[j];
        }
      }
      // Verify `<script>` matches across all 8 bytes AND `window.` appears in first 96 bytes!
      let ok = true;
      for (let j = 0; j < needle.length; j++) {
        if ((raw[off + j] ^ key[(off + j) % klen]) !== needle[j]) {
          ok = false;
          break;
        }
      }
      if (!ok) continue;
      const probe = Buffer.from(raw.slice(0, 96).map((v, i) => v ^ key[i % klen])).toString("utf8");
      if (probe.includes("<script>") && probe.includes("window.")) {
        keyUsed = key.toString("utf8");
        decoded = Buffer.from(raw.map((v, i) => v ^ key[i % klen])).toString("utf8");
        break outer;
      }
    }
  }

  return { headSample, outerDecoderJs, keyUsed, decodedLen: decoded.length, decoded };
}

// Deobfuscate / execute `nbm2oHaQcN60y6Ozwm.js` + watching inline scripts in a rich VM to get `_getCoreToken()` AND log its exact inputs/algorithm!
function analyzeNbmAndToken(nbmJsCode, watchingHtml, watchUrl) {
  const report = {};

  try {
    const allWatchScripts = [...watchingHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((m, idx) => ({
      idx,
      attrs: m[1].trim(),
      len: m[2].length,
      preview: m[2].slice(0, 500),
      code: m[2],
    }));
    report.watchingScriptsSummary = allWatchScripts.map(({ idx, attrs, len, preview }) => ({ idx, attrs, len, preview }));

    // Save FULL code of all inline scripts under 80KB (excluding JSON-LD and known x19WCM90B19F if desired, actually include all under 40KB!)
    report.watchingInlineScriptsFull = allWatchScripts
      .filter((s) => !s.attrs.includes("src=") && !s.attrs.includes("ld+json") && s.len < 60000)
      .map((s) => ({ idx: s.idx, attrs: s.attrs, len: s.len, code: s.code }));

    // Check any external script `src` in `watchingHtml`!
    report.watchingExternalSrcs = allWatchScripts
      .map((s) => s.attrs.match(/src=["']([^"']+)["']/i)?.[1])
      .filter(Boolean);

    const domListeners = [];
    const timers = [];
    const dummyEl = () => ({
      style: {},
      classList: { add() {}, remove() {}, contains: () => false },
      setAttribute() {},
      getAttribute: () => null,
      hasAttribute: () => false,
      appendChild() {},
      removeChild() {},
      insertBefore() {},
      addEventListener: (ev, fn) => { domListeners.push({ ev, fn }); },
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
      setTimeout: (fn, ms) => {
        if (typeof fn === "function" && (!ms || ms < 500)) timers.push(fn);
        return timers.length;
      },
      clearTimeout() {},
      setInterval: () => 1,
      clearInterval() {},
      MutationObserver: class {
        observe() {}
        disconnect() {}
      },
      XMLHttpRequest: function () {},
      fetch: async () => ({ ok: true, text: async () => "", json: async () => ({}) }),
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
        subtle: crypto.webcrypto.subtle,
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
        createDocumentFragment: dummyEl,
        getElementById: () => null,
        querySelector: () => null,
        querySelectorAll: () => [],
        getElementsByTagName: () => [dummyEl()],
        addEventListener: (ev, fn) => { domListeners.push({ ev, fn }); },
      },
    };
    sb.XMLHttpRequest.prototype = { open() {}, send() {}, setRequestHeader() {} };
    sb.window = sb;
    sb.self = sb;
    sb.globalThis = sb;
    sb.top = sb;
    sb.parent = sb;

    vm.createContext(sb);

    // Run inline scripts that set `window._0x_cfg`, `ctx_...`, `ptr_...`, `map_...` or `_getCoreToken`!
    for (const s of allWatchScripts) {
      if (!s.attrs.includes("src=") && !s.attrs.includes("ld+json") && !s.code.includes("jQuery") && !s.code.includes("$(")) {
        try {
          vm.runInContext(s.code, sb, { timeout: 2500 });
        } catch (e) {
          // ignore DOM errors in non-token inline scripts
        }
      }
    }

    report.hasGetCoreTokenBeforeNbm = typeof sb._getCoreToken === "function";

    // Now run `nbmJsCode`!
    try {
      vm.runInContext(nbmJsCode, sb, { timeout: 6000 });
    } catch (e) {
      report.nbmRunErr = String(e.message || e);
    }

    // Fire any DOMContentLoaded listeners and short timers!
    for (const l of domListeners.slice(0, 10)) {
      if (l.ev === "DOMContentLoaded" || l.ev === "load") {
        try { l.fn(); } catch {}
      }
    }
    for (const tfn of timers.slice(0, 10)) {
      try { tfn(); } catch {}
    }

    report.hasGetCoreTokenAfterNbm = typeof sb._getCoreToken === "function";
    if (typeof sb._getCoreToken === "function") {
      const tok1 = sb._getCoreToken();
      const tok2 = sb._getCoreToken();
      report.token1 = tok1;
      report.token2 = tok2;
      report.getCoreTokenFnSource = sb._getCoreToken.toString().slice(0, 4000);
    }

    report._sb = sb;
    report._mintFn = typeof sb._getCoreToken === "function" ? () => sb._getCoreToken() : null;
  } catch (e) {
    report.vmError = String(e.stack || e);
  }

  return report;
}

async function inspectDecodedWatching(watchingHtml, watchUrl, nbmJsCode, proxies) {
  const res = {};
  const tokenAnalysis = analyzeNbmAndToken(nbmJsCode, watchingHtml, "https://rm.freex2line.online/2020/02/blog-post.html/");
  let mintFn = tokenAnalysis._mintFn;
  const sb = tokenAnalysis._sb;
  delete tokenAnalysis._mintFn;
  delete tokenAnalysis._sb;

  // If `_getCoreToken` wasn't defined yet, check if `watchingHtml` has a different external JS file on cimanow.cc and execute it!
  if (!mintFn && sb && Array.isArray(tokenAnalysis.watchingExternalSrcs)) {
    tokenAnalysis.fetchedExtraScripts = [];
    for (const src of tokenAnalysis.watchingExternalSrcs) {
      if (src.includes("cimanow") && !src.includes("jquery") && !src.includes("owl.carousel") && !src.includes("0CYA6X1KhKIS") && !src.includes("nbm2oHaQcN60y6Ozwm")) {
        try {
          const fullU = new URL(src, "https://cimanow.cc/").toString();
          const jr = await smartReq(fullU, proxies, { referer: "https://rm.freex2line.online/" });
          tokenAnalysis.fetchedExtraScripts.push({ url: fullU, len: jr.text.length, preview: jr.text.slice(0, 1500) });
          vm.runInContext(jr.text, sb, { timeout: 5000 });
          if (typeof sb._getCoreToken === "function") {
            mintFn = () => sb._getCoreToken();
            tokenAnalysis.tokenFromExtraScript = mintFn();
            tokenAnalysis.extraScriptUrl = fullU;
            tokenAnalysis.extraScriptFullIfSmall = jr.text.length < 100000 ? jr.text : undefined;
          }
        } catch (e) {
          tokenAnalysis.fetchedExtraScripts.push({ url: src, err: String(e.message || e) });
        }
      }
    }
  }
  res.tokenAnalysis = tokenAnalysis;

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
        { referer: "https://rm.freex2line.online/", extraHeaders: ["Origin: https://rm.freex2line.online"] }
      );
      res.switchWithoutToken = { status: rNo.status, body: rNo.text.trim().slice(0, 500) };
    }
    const freshTok = mintFn ? mintFn() : (tokenAnalysis.token1 || "");
    const url =
      `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php` +
      (freshTok ? `?token=${encodeURIComponent(freshTok)}&` : `?`) +
      `action=switch&index=${encodeURIComponent(t.dataIndex)}&id=${encodeURIComponent(t.dataId)}`;
    let sr = await smartReq(url, proxies, {
      referer: "https://rm.freex2line.online/",
      extraHeaders: ["Origin: https://rm.freex2line.online"],
    });
    if (!sr.text.includes("<iframe") && i === 0) {
      const tokAlt = mintFn ? mintFn() : freshTok;
      const urlAlt =
        `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php` +
        (tokAlt ? `?token=${encodeURIComponent(tokAlt)}&` : `?`) +
        `action=switch&index=${encodeURIComponent(t.dataIndex)}&id=${encodeURIComponent(t.dataId)}`;
      const srAlt = await smartReq(urlAlt, proxies, {
        referer: watchUrl,
        extraHeaders: ["X-Requested-With: XMLHttpRequest"],
      });
      res.switchAltRefererTest = { status: srAlt.status, body: srAlt.text.trim().slice(0, 500) };
    }
    res.switchCalls.push({
      name: t.text,
      index: t.dataIndex,
      id: t.dataId,
      tokenSent: freshTok,
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
        outerDecoderJs: idx === 0 ? dec.outerDecoderJs : undefined,
        tokenAnalysis:
          idx === 0
            ? inspected.tokenAnalysis
            : {
                hasGetCoreTokenBeforeNbm: inspected.tokenAnalysis.hasGetCoreTokenBeforeNbm,
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
