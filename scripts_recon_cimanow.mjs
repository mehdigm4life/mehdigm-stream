import fs from "node:fs";
import vm from "node:vm";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const KNOWN_GOOD_PROXIES = [
  "socks5h://91.107.179.68:10809",
  "socks5h://184.178.172.5:15303",
  "socks5h://184.178.172.25:15291",
  "socks5h://45.74.31.46:8287",
  "socks5h://45.74.31.46:8506",
  "socks5h://45.74.31.46:8478",
];

const PROXY_FEEDS = [
  ["socks5h", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5h", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"],
  ["socks5h", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt"],
];

function isCimaBlock(html) {
  return !html || html.includes("ستوب! المخرج عايز كدة") || html.includes("مشهد محذوف");
}

async function curlReq(
  url,
  {
    method = "GET",
    postData = null,
    proxy = null,
    referer = null,
    extraHeaders = [],
    cookieJarFile = null,
    timeoutSec = 14,
    follow = true,
    includeHeaders = false,
  } = {}
) {
  const hdrFile = `/tmp/curl_h_${Math.random().toString(36).slice(2)}.txt`;
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
  if (postData !== null) args.push("--data", postData);
  if (includeHeaders) args.push("-D", hdrFile);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}");
  if (follow) args.push("-L", "--max-redirs", "6");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  if (cookieJarFile) args.push("-b", cookieJarFile, "-c", cookieJarFile);
  for (const h of extraHeaders) args.push("-H", h);
  args.push(url);

  try {
    const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
    const markerIdx = stdout.lastIndexOf("\n__CURL_META__:");
    const text = markerIdx >= 0 ? stdout.slice(0, markerIdx) : stdout;
    const [codeStr, finalUrl, redirectUrl] =
      markerIdx >= 0 ? stdout.slice(markerIdx + "\n__CURL_META__:".length).trim().split("|") : ["0", url, ""];
    const respHeaders = includeHeaders && fs.existsSync(hdrFile) ? fs.readFileSync(hdrFile, "utf8") : "";
    return { status: Number(codeStr) || 0, finalUrl, redirectUrl, respHeaders, text };
  } finally {
    if (fs.existsSync(hdrFile)) try { fs.unlinkSync(hdrFile); } catch {}
  }
}

async function getWorkingProxies() {
  const working = [];
  await Promise.all(
    KNOWN_GOOD_PROXIES.map(async (p) => {
      try {
        const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 8 });
        if (r.status === 200 && !isCimaBlock(r.text) && r.text.length > 5000) working.push(p);
      } catch {}
    })
  );
  if (working.length >= 2) return working;

  const candidates = [];
  await Promise.all(
    PROXY_FEEDS.map(async ([scheme, feedUrl]) => {
      try {
        const res = await fetch(feedUrl, { signal: AbortSignal.timeout(6000) });
        const txt = await res.text();
        for (const raw of txt.split(/\r?\n/).slice(0, 100)) {
          const line = raw.trim().replace(/^(https?|socks5h?):\/\//i, "");
          if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(line)) candidates.push(`${scheme}://${line}`);
        }
      } catch {}
    })
  );
  let idx = 0;
  await Promise.all(
    Array.from({ length: 30 }, async () => {
      while (idx < candidates.length && working.length < 5) {
        const p = candidates[idx++];
        try {
          const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
          if (r.status === 200 && !isCimaBlock(r.text) && r.text.length > 5000) working.push(p);
        } catch {}
      }
    })
  );
  return working;
}

// Trace the full redirect chain from `.shine` (`rm.freex2line.online/loadon/?link=...`) to `/watching/`!
async function followGatewayChain(shineUrl, itemUrl, proxies) {
  const jar = `/tmp/cn_chain_${Math.random().toString(36).slice(2)}.txt`;
  const hops = [];
  let curUrl = shineUrl;
  let curRef = itemUrl;
  let curMethod = "GET";
  let curPost = null;
  let finalResp = null;
  let stickyProxy = proxies[0];

  try {
    for (let step = 0; step < 12; step++) {
      // Unwrap `https://href.li/?<target>` just like href.li does (meta refresh with noreferrer!)
      if (curUrl.startsWith("https://href.li/?")) {
        const unwrapped = curUrl.slice("https://href.li/?".length);
        hops.push({ step, note: "unwrap href.li (strip referer)", from: curUrl, to: unwrapped });
        curUrl = unwrapped;
        curRef = null;
      }

      // Try stickyProxy first (up to 2 tries), then fall back to any other working proxy!
      const order = [stickyProxy, stickyProxy, ...proxies.filter((x) => x !== stickyProxy)];
      let r = null;
      let hopErr = null;
      for (const p of order) {
        try {
          const cand = await curlReq(curUrl, {
            method: curMethod,
            postData: curPost,
            proxy: p,
            referer: curRef,
            cookieJarFile: jar,
            follow: false,
            includeHeaders: true,
            timeoutSec: 16,
          });
          if (cand.status > 0 && !isCimaBlock(cand.text) && !cand.text.includes("Just a moment...")) {
            r = cand;
            stickyProxy = p;
            break;
          }
          hopErr = `blocked_or_zero:${cand.status}`;
        } catch (e) {
          hopErr = String(e.message || e);
        }
      }

      if (!r) {
        hops.push({ step, reqUrl: curUrl, reqReferer: curRef, failedAllProxies: hopErr });
        break;
      }

      hops.push({
        step,
        usedProxy: stickyProxy,
        reqUrl: curUrl,
        reqMethod: curMethod,
        reqReferer: curRef,
        reqPost: curPost,
        status: r.status,
        redirectUrl: r.redirectUrl,
        headers: r.respHeaders.slice(0, 1000),
        len: r.text.length,
        bodySample: r.text.length < 10000 ? r.text : r.text.slice(0, 3000),
      });

      finalResp = r;

      // Did we reach `/watching/` with `#watch`?
      if (r.status === 200 && (r.text.includes('id="watch"') || r.text.includes("id='watch'")) && r.text.includes("data-index")) {
        break;
      }

      // 1) HTTP 301/302/303/307/308 redirect
      if (r.redirectUrl) {
        const noRef = /referrer-policy:\s*no-referrer/i.test(r.respHeaders);
        curRef = noRef ? null : curUrl;
        curUrl = new URL(r.redirectUrl, curUrl).toString();
        curMethod = "GET";
        curPost = null;
        continue;
      }

      // 2) JS `location.href = '...'` / `location.replace('...')` / `window.location = '...'`
      const jsLocMatch =
        r.text.match(/(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']/i) ||
        r.text.match(/location\.replace\(\s*["']([^"']+)["']\s*\)/i);
      if (jsLocMatch) {
        const nextU = new URL(jsLocMatch[1].trim(), curUrl).toString();
        curRef = curUrl;
        curUrl = nextU;
        curMethod = "GET";
        curPost = null;
        continue;
      }

      // 3) `<meta http-equiv="refresh" content="...;url=...">`
      const metaMatch = r.text.match(/http-equiv=["']?refresh["']?[^>]*content=["'][^"']*url=([^"'>\s]+)/i);
      if (metaMatch) {
        const nextU = new URL(metaMatch[1].trim(), curUrl).toString();
        curRef = curUrl;
        curUrl = nextU;
        curMethod = "GET";
        curPost = null;
        continue;
      }

      // 4) `<form action="..." method="...">`
      const formMatch = r.text.match(/<form\b([^>]*)>([\s\S]*?)<\/form>/i);
      if (formMatch && !curUrl.includes("cimanow.cc")) {
        const attrs = formMatch[1];
        const inner = formMatch[2];
        const action = attrs.match(/action=["']([^"']*)["']/i)?.[1] || curUrl;
        const method = (attrs.match(/method=["']([^"']*)["']/i)?.[1] || "GET").toUpperCase();
        const inputs = [...inner.matchAll(/<input\b([^>]*)>/gi)]
          .map((m) => {
            const n = m[1].match(/name=["']([^"']+)["']/i)?.[1];
            const v = m[1].match(/value=["']([^"']*)["']/i)?.[1] || "";
            return n ? `${encodeURIComponent(n)}=${encodeURIComponent(v)}` : null;
          })
          .filter(Boolean);
        curRef = curUrl;
        curUrl = new URL(action, curUrl).toString();
        curMethod = method;
        curPost = method === "POST" ? inputs.join("&") : null;
        if (method === "GET" && inputs.length > 0) {
          curUrl += (curUrl.includes("?") ? "&" : "?") + inputs.join("&");
        }
        continue;
      }

      // 5) Direct `<a href="...">` pointing to cimanow or next step on gateway page
      const aMatch = [...r.text.matchAll(/<a\b[^>]*href=["']([^"']+)["'][^>]*>/gi)]
        .map((m) => m[1])
        .find((h) => h.includes("cimanow") || h.includes("redirect") || h.includes("freex2line") || h.includes("href.li"));
      if (aMatch && !curUrl.includes("cimanow.cc")) {
        curRef = curUrl;
        curUrl = new URL(aMatch, curUrl).toString();
        curMethod = "GET";
        curPost = null;
        continue;
      }

      break;
    }

    const cookiesAtEnd = fs.existsSync(jar) ? fs.readFileSync(jar, "utf8") : "";
    return { hops, finalResp, stickyProxy, cookiesAtEnd, jar };
  } catch (e) {
    return { hops, error: String(e.stack || e), stickyProxy, jar };
  }
}

async function inspectWatchingPage(watchHtml, watchUrl, proxies, jar) {
  const info = {};

  // 1. Inspect all `<script>` tags on `/watching/`!
  const scripts = [...watchHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((m, idx) => ({
    idx,
    attrs: m[1].trim(),
    len: m[2].length,
    preview: m[2].slice(0, 600),
    code: m[2],
  }));
  info.scriptsMeta = scripts.map(({ idx, attrs, len, preview }) => ({ idx, attrs, len, preview }));

  // Check if any script mentions `_getCoreToken` or `core.php` or if there is a new external JS file on `/watching/`!
  info.inlineTokenScripts = scripts
    .filter((s) => s.code.includes("_getCoreToken") || s.code.includes("core.php") || (s.len > 0 && s.len < 50000))
    .filter((s) => !s.code.includes("__CF$cv$params") && s.attrs !== 'type="application/ld+json"')
    .map((s) => ({ idx: s.idx, attrs: s.attrs, code: s.code.slice(0, 8000) }));

  const extSrcs = scripts
    .map((s) => s.attrs.match(/src=["']([^"']+)["']/i)?.[1])
    .filter((u) => u && u.includes("cimanow") && !u.includes("jquery") && !u.includes("owl.carousel") && !u.includes("0CYA6X1KhKIS"));
  info.watchingSpecificExternalJs = [];
  let computedToken = "";

  for (const srcUrl of extSrcs) {
    try {
      const fullJsUrl = new URL(srcUrl, watchUrl).toString();
      const jr = await smartReq(fullJsUrl, proxies, { referer: watchUrl, cookieJarFile: jar });
      const entry = { url: fullJsUrl, len: jr.text.length, preview: jr.text.slice(0, 3000) };
      // Try running in VM to see if it defines `window._getCoreToken`!
      const sb = {
        window: {},
        document: { cookie: "" },
        navigator: { userAgent: UA },
        location: new URL(watchUrl),
        atob: (b) => Buffer.from(b, "base64").toString("binary"),
        btoa: (b) => Buffer.from(b, "binary").toString("base64"),
      };
      sb.window = sb;
      sb.self = sb;
      sb.globalThis = sb;
      vm.createContext(sb);
      try {
        vm.runInContext(jr.text, sb, { timeout: 3000 });
        if (typeof sb._getCoreToken === "function") {
          computedToken = sb._getCoreToken();
          entry.definedGetCoreToken = true;
          entry.tokenSample = computedToken;
          entry.fnSource = sb._getCoreToken.toString().slice(0, 2000);
        }
      } catch (e) {
        entry.vmErr = String(e.message || e);
      }
      if (jr.text.length < 60000) entry.fullCode = jr.text;
      info.watchingSpecificExternalJs.push(entry);
    } catch (e) {
      info.watchingSpecificExternalJs.push({ url: srcUrl, err: String(e.message || e) });
    }
  }

  // Also check inline scripts for `_getCoreToken` definition!
  for (const s of scripts) {
    if (s.code.includes("_getCoreToken") && !s.attrs.includes("x19WCM90B19F")) {
      const sb = {
        window: {},
        document: { cookie: "" },
        navigator: { userAgent: UA },
        location: new URL(watchUrl),
        atob: (b) => Buffer.from(b, "base64").toString("binary"),
        btoa: (b) => Buffer.from(b, "binary").toString("base64"),
      };
      sb.window = sb;
      sb.self = sb;
      sb.globalThis = sb;
      vm.createContext(sb);
      try {
        vm.runInContext(s.code, sb, { timeout: 3000 });
        if (typeof sb._getCoreToken === "function") {
          computedToken = sb._getCoreToken();
          info.inlineComputedToken = computedToken;
        }
      } catch {}
    }
  }

  // 2. Parse `#watch` server tabs + initial `li[aria-label="embed"]`!
  const watchSection = watchHtml.match(/<ul[^>]*id=["']watch["'][^>]*>([\s\S]*?)<\/ul>/i)?.[0] || "";
  info.watchUlHtml = watchSection.slice(0, 5000);

  const liMatches = [...watchSection.matchAll(/<li\b([^>]*)>([\s\S]*?)<\/li>/gi)].map((m) => ({
    attrs: m[1],
    ariaLabel: m[1].match(/aria-label=["']([^"']*)["']/i)?.[1] || "",
    dataIndex: m[1].match(/data-index=["']([^"']*)["']/i)?.[1] || "",
    dataId: m[1].match(/data-id=["']([^"']*)["']/i)?.[1] || "",
    inner: m[2].trim(),
  }));
  info.parsedWatchTabs = liMatches;

  // 3. Call `core.php` for EVERY valid server tab (test both WITH token and WITHOUT token on first tab!)
  info.switchResults = [];
  const validTabs = liMatches.filter(
    (t) => t.ariaLabel !== "embed" && t.ariaLabel !== "uptobox" && t.dataIndex !== "00" && t.dataId
  );
  for (let i = 0; i < validTabs.length; i++) {
    const t = validTabs[i];
    if (i === 0) {
      const noTokUrl = `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${encodeURIComponent(t.dataIndex)}&id=${encodeURIComponent(t.dataId)}`;
      const rNoTok = await smartReq(noTokUrl, proxies, {
        referer: watchUrl,
        cookieJarFile: jar,
        extraHeaders: ["X-Requested-With: XMLHttpRequest"],
      });
      info.switchWithoutTokenTest = { status: rNoTok.status, resp: rNoTok.text.slice(0, 500) };
    }

    const switchUrl =
      `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php` +
      (computedToken ? `?token=${encodeURIComponent(computedToken)}&` : `?`) +
      `action=switch&index=${encodeURIComponent(t.dataIndex)}&id=${encodeURIComponent(t.dataId)}`;
    const sr = await smartReq(switchUrl, proxies, {
      referer: watchUrl,
      cookieJarFile: jar,
      extraHeaders: ["X-Requested-With: XMLHttpRequest"],
    });
    info.switchResults.push({
      serverName: t.inner.replace(/<[^>]+>/g, "").trim(),
      dataIndex: t.dataIndex,
      dataId: t.dataId,
      status: sr.status,
      resp: sr.text.trim(),
    });
  }

  // 4. Parse `<ul id="download">` quality groups & links!
  const dlIdx = watchHtml.search(/<ul[^>]*id=["']download["']/i);
  const dlSlice = dlIdx >= 0 ? watchHtml.slice(dlIdx, dlIdx + 18000) : "";
  info.downloadHtmlSample = dlSlice.slice(0, 8000);
  const dlLinks = [...dlSlice.matchAll(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)].map((m) => ({
    href: m[1].replace("web2.cnvids.com", "fr.cimanow.cc"),
    rawHref: m[1],
    text: m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
  }));
  info.downloadLinks = dlLinks;

  return info;
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

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;
    const p0 = proxies[0];

    try {
      const nbm = await smartReq(
        "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/nbm2oHaQcN60y6Ozwm.js?v=2.3",
        proxies,
        { referer: "https://cimanow.cc/home/" }
      );
      out.nbmPreloadScript = { status: nbm.status, len: nbm.text.length, preview: nbm.text.slice(0, 2500) };
    } catch (e) {
      out.nbmPreloadScript = { err: String(e.message || e) };
    }

    const testItems = [
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

    out.itemFlows = {};
    for (const item of testItems) {
      try {
        const mv = await smartReq(item.url, proxies, { referer: "https://cimanow.cc/home/" });
        const shineMatch = mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i);
        const shineUrl = shineMatch?.[1] || "";

        const orderedProxies = [mv.usedProxy || p0, ...proxies.filter((x) => x !== (mv.usedProxy || p0))];
        const chain = shineUrl
          ? await followGatewayChain(shineUrl, item.url, orderedProxies)
          : { error: "No .shine href found" };

        const flowReport = {
          usedProxy: chain?.stickyProxy || mv.usedProxy || p0,
          shineUrl,
          hops: chain?.hops || [],
          chainError: chain?.error || null,
          cookiesAtEnd: chain?.cookiesAtEnd || "",
        };

        if (chain?.finalResp && (chain.finalResp.text.includes('id="watch"') || chain.finalResp.text.includes("id='watch'"))) {
          const finalHop = chain.hops[chain.hops.length - 1];
          flowReport.watching = await inspectWatchingPage(chain.finalResp.text, finalHop.reqUrl, orderedProxies, chain.jar);
        }

        if (chain?.jar && fs.existsSync(chain.jar)) try { fs.unlinkSync(chain.jar); } catch {}
        out.itemFlows[item.name] = flowReport;
      } catch (e) {
        out.itemFlows[item.name] = { error: String(e.stack || e) };
      }
    }

    const firstFlowHops = out.itemFlows.ForeignMovie_Fall2?.hops || [];
    const winningHop = firstFlowHops.find((h) => h.reqUrl?.includes("/watching/") && h.status === 200);
    if (winningHop?.reqReferer) {
      try {
        const directTest = await smartReq(
          "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad2-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/watching/",
          proxies,
          { referer: winningHop.reqReferer, follow: false }
        );
        out.oneShotRefererBypassTest = {
          testedReferer: winningHop.reqReferer,
          status: directTest.status,
          hasWatch: directTest.text.includes('id="watch"') && directTest.text.includes("data-index"),
          len: directTest.text.length,
        };
      } catch (e) {
        out.oneShotRefererBypassTest = { err: String(e.message || e) };
      }
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v7b recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
