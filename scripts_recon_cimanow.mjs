import fs from "node:fs";
import vm from "node:vm";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const PROXY_FEEDS = [
  ["socks5h", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/http.txt"],
  ["socks5h", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"],
  ["socks5h", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt"],
  ["http", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt"],
  ["socks5h", "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt"],
];

function isBlockPage(html) {
  if (!html) return true;
  return (
    html.includes("ستوب! المخرج عايز كدة") ||
    html.includes("Just a moment...") ||
    html.includes("cf-browser-verification") ||
    html.length < 400
  );
}

/**
 * Decode CimaNow's famous JS HTML obfuscation (`eval(atob(...))` / charCode shift -> `document.write(...)`)
 */
function tryDecodeCimaNowObfuscation(rawHtml) {
  if (!rawHtml) return { wasObfuscated: false, decoded: "" };
  if (
    rawHtml.includes("<article") ||
    rawHtml.includes('id="watch"') ||
    rawHtml.includes('aria-label="seasons"') ||
    rawHtml.includes('id="eps"')
  ) {
    return { wasObfuscated: false, decoded: rawHtml, scriptSample: null };
  }
  const scriptMatches = [...rawHtml.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)];
  if (scriptMatches.length === 0) {
    return { wasObfuscated: false, decoded: rawHtml, scriptSample: rawHtml.slice(0, 1000) };
  }

  let written = "";
  const sandbox = {
    atob: (b64) => Buffer.from(b64, "base64").toString("binary"),
    btoa: (bin) => Buffer.from(bin, "binary").toString("base64"),
    decodeURIComponent,
    encodeURIComponent,
    escape,
    unescape,
    String,
    Array,
    Math,
    parseInt,
    Number,
    RegExp,
    Object,
    document: {
      open() {},
      close() {},
      write(s) {
        written += String(s);
      },
      writeln(s) {
        written += String(s) + "\n";
      },
      cookie: "",
      getElementsByTagName: () => [{ appendChild() {} }],
      createElement: () => ({ style: {}, appendChild() {} }),
      addEventListener() {},
      body: null,
      readyState: "complete",
    },
    window: null,
    location: { href: "https://cimanow.cc/home/", origin: "https://cimanow.cc", protocol: "https:", host: "cimanow.cc", pathname: "/home/", reload() {} },
    navigator: { userAgent: UA },
    setTimeout: (fn) => {
      if (typeof fn === "function") fn();
    },
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;

  const fullJs = scriptMatches.map((m) => m[1]).join("\n;\n");
  let vmError = null;
  try {
    vm.runInNewContext(fullJs, sandbox, { timeout: 4000 });
  } catch (e) {
    vmError = String(e.message || e);
  }

  return {
    wasObfuscated: written.length > 0,
    vmError,
    decoded: written.length > 0 ? written : rawHtml,
    rawLength: rawHtml.length,
    decodedLength: written.length,
    scriptHead: fullJs.slice(0, 2500),
    scriptTail: fullJs.slice(-2000),
    cookieSet: sandbox.document.cookie,
  };
}

async function curlGet(url, { proxy = null, referer = null, extraHeaders = [], cookieJarFile = null, timeoutSec = 10, follow = true } = {}) {
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "5",
    "-A",
    UA,
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: ar,en-US;q=0.9,en;q=0.8",
    "-w",
    "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}",
  ];
  if (follow) args.push("-L", "--max-redirs", "5");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  if (cookieJarFile) args.push("-b", cookieJarFile, "-c", cookieJarFile);
  for (const h of extraHeaders) args.push("-H", h);
  args.push(url);

  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
  const markerIdx = stdout.lastIndexOf("\n__CURL_META__:");
  if (markerIdx === -1) return { status: 0, finalUrl: url, redirectUrl: "", text: stdout };
  const text = stdout.slice(0, markerIdx);
  const [codeStr, finalUrl, redirectUrl] = stdout.slice(markerIdx + "\n__CURL_META__:".length).trim().split("|");
  return {
    status: Number(codeStr) || 0,
    finalUrl: finalUrl || url,
    redirectUrl: redirectUrl || "",
    text,
  };
}

async function findWorkingProxies(limit = 4) {
  const candidates = [];
  await Promise.all(
    PROXY_FEEDS.map(async ([scheme, feedUrl]) => {
      try {
        const res = await fetch(feedUrl, { signal: AbortSignal.timeout(7000) });
        if (!res.ok) return;
        const txt = await res.text();
        for (const raw of txt.split(/\r?\n/).slice(0, 120)) {
          const line = raw.trim().replace(/^(https?|socks5h?):\/\//i, "");
          if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(line)) {
            candidates.push(`${scheme}://${line}`);
          }
        }
      } catch {}
    })
  );

  // Shuffle & deduplicate
  const unique = [...new Set(candidates)];
  for (let i = unique.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [unique[i], unique[j]] = [unique[j], unique[i]];
  }

  console.log(`Testing up to ${Math.min(unique.length, 320)} proxy candidates for cimanow.cc...`);
  const working = [];
  const concurrency = 40;
  let idx = 0;

  async function worker() {
    while (idx < Math.min(unique.length, 320) && working.length < limit) {
      const p = unique[idx++];
      try {
        const r = await curlGet("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
        if (r.status === 200 && !isBlockPage(r.text)) {
          console.log(`FOUND WORKING PROXY: ${p} (len=${r.text.length})`);
          working.push(p);
        }
      } catch {}
    }
  }

  await Promise.all(Array.from({ length: concurrency }, () => worker()));
  return working;
}

async function smartGet(url, proxies, opts = {}) {
  for (const proxy of proxies) {
    try {
      const r = await curlGet(url, { ...opts, proxy, timeoutSec: 14 });
      if (r.status > 0 && !r.text.includes("ستوب! المخرج عايز كدة")) {
        return { ...r, usedProxy: proxy };
      }
    } catch {}
  }
  return curlGet(url, { ...opts, timeoutSec: 12 });
}

async function main() {
  const report = {
    timestamp: new Date().toISOString(),
    blockPageBody: null,
    workingProxiesCount: 0,
    workingProxiesSample: [],
    obfuscationAnalysis: null,
    landing: null,
    home: null,
    categoryPage2: null,
    search: null,
    movie: null,
    series: null,
    episode: null,
    watchingRefererMatrix: {},
    watchingDetails: [],
    cimanowNativePlayerSamples: [],
    fatalError: null,
  };

  try {
    // 0. Inspect exact body of the HTTP 403 block page first!
    const directRes = await curlGet("https://cimanow.cc/home/", { timeoutSec: 8 });
    const bodyIdx = directRes.text.toLowerCase().indexOf("<body");
    report.blockPageBody = {
      status: directRes.status,
      bodyHtml: bodyIdx >= 0 ? directRes.text.slice(bodyIdx) : directRes.text.slice(-5000),
    };

    // Also test X-Forwarded-For / CF-Connecting-IP headers directly just in case
    const xffTest = await curlGet("https://cimanow.cc/home/", {
      extraHeaders: ["X-Forwarded-For: 105.159.100.42", "X-Real-IP: 105.159.100.42"],
      timeoutSec: 8,
    });
    report.xffBypassWorks = !isBlockPage(xffTest.text);

    // 1. Harvest working non-ASN-blocked proxies
    const proxies = await findWorkingProxies(5);
    report.workingProxiesCount = proxies.length;
    report.workingProxiesSample = proxies;

    if (proxies.length === 0) {
      throw new Error("No working proxy found in first batch");
    }

    const cookieFile = "/tmp/cimanow_cookies.txt";

    // 2. Landing `/` & `/home/` + exact JS obfuscation analysis!
    const land = await smartGet("https://cimanow.cc/", proxies, { cookieJarFile: cookieFile });
    const landDec = tryDecodeCimaNowObfuscation(land.text);
    report.obfuscationAnalysis = {
      wasObfuscated: landDec.wasObfuscated,
      vmError: landDec.vmError,
      rawLength: land.text.length,
      decodedLength: landDec.decoded.length,
      rawHead: land.text.slice(0, 2500),
      rawTail: land.text.slice(-2500),
      cookieSet: landDec.cookieSet,
    };

    report.landing = {
      status: land.status,
      imgs: [...landDec.decoded.matchAll(/<img[^>]+>/gi)].slice(0, 12).map((m) => m[0]),
      icons: [...landDec.decoded.matchAll(/<link[^>]+icon[^>]*>/gi)].map((m) => m[0]),
    };

    const home = await smartGet("https://cimanow.cc/home/", proxies, {
      referer: "https://cimanow.cc/",
      cookieJarFile: cookieFile,
    });
    const homeDec = tryDecodeCimaNowObfuscation(home.text);
    const homeHtml = homeDec.decoded;

    report.home = {
      status: home.status,
      wasObfuscated: homeDec.wasObfuscated,
      rawLen: home.text.length,
      decodedLen: homeHtml.length,
      navLinks: [...homeHtml.matchAll(/<a[^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)]
        .slice(0, 45)
        .map((m) => ({ href: m[1], text: m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim() })),
      sections: [...homeHtml.matchAll(/<section[^>]*>([\s\S]*?)<\/section>/gi)].slice(0, 22).map((m, idx) => {
        const sec = m[0];
        const openTag = sec.match(/<section[^>]*>/)?.[0] || "";
        const heading = sec.slice(0, 500).replace(/<article[\s\S]*/i, "").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
        const links = [...sec.slice(0, 700).matchAll(/href=["']([^"']+)["']/gi)].map((x) => x[1]);
        const articles = [...sec.matchAll(/<article[^>]*>([\s\S]*?)<\/article>/gi)];
        return {
          idx,
          openTag,
          heading: heading.slice(0, 120),
          topLinks: links.slice(0, 3),
          articleCount: articles.length,
          sampleArticle: articles[0]?.[0]?.slice(0, 1800) || null,
          secondArticle: articles[1]?.[0]?.slice(0, 1200) || null,
        };
      }),
    };

    // 3. Category Page 2 & Pagination HTML
    const cat2 = await smartGet(
      "https://cimanow.cc/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/page/2/",
      proxies,
      { referer: "https://cimanow.cc/home/", cookieJarFile: cookieFile }
    );
    const cat2Html = tryDecodeCimaNowObfuscation(cat2.text).decoded;
    report.categoryPage2 = {
      status: cat2.status,
      articleCount: [...cat2Html.matchAll(/<article[^>]*>/gi)].length,
      sampleArticle: cat2Html.match(/<article[^>]*>[\s\S]*?<\/article>/i)?.[0]?.slice(0, 1800),
      paginationHtml:
        cat2Html.match(/<ul[^>]*pagination[\s\S]*?<\/ul>/i)?.[0] ||
        cat2Html.match(/<ul[^>]*page-numbers[\s\S]*?<\/ul>/i)?.[0] ||
        [...cat2Html.matchAll(/<li[^>]*>\s*<a[^>]+href=["'][^"']*\/page\/\d+[^"']*["'][^>]*>[\s\S]*?<\/li>/gi)]
          .slice(0, 10)
          .map((m) => m[0]),
    };

    // 4. Search & Search Page 2
    const srch = await smartGet("https://cimanow.cc/?s=Spider", proxies, {
      referer: "https://cimanow.cc/home/",
      cookieJarFile: cookieFile,
    });
    const srchHtml = tryDecodeCimaNowObfuscation(srch.text).decoded;
    const srch2 = await smartGet("https://cimanow.cc/page/2/?s=Spider", proxies, {
      referer: "https://cimanow.cc/?s=Spider",
      cookieJarFile: cookieFile,
    });
    const srch2Html = tryDecodeCimaNowObfuscation(srch2.text).decoded;
    report.search = {
      page1Count: [...srchHtml.matchAll(/<article[^>]*>/gi)].length,
      page2Count: [...srch2Html.matchAll(/<article[^>]*>/gi)].length,
      sampleArticle: srchHtml.match(/<article[^>]*>[\s\S]*?<\/article>/i)?.[0]?.slice(0, 1800),
      paginationLinks: [...srchHtml.matchAll(/href=["']([^"']*page\/\d+[^"']*)["']/gi)].slice(0, 10).map((m) => m[1]),
    };

    // 5. Movie page (`Fall 2: Deadpoint 2026`)
    const movieUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
    const mv = await smartGet(movieUrl, proxies, { referer: "https://cimanow.cc/home/", cookieJarFile: cookieFile });
    const mvHtml = tryDecodeCimaNowObfuscation(mv.text).decoded;
    const mvBodyIdx = mvHtml.toLowerCase().indexOf("<body");
    const mvBody = mvBodyIdx >= 0 ? mvHtml.slice(mvBodyIdx) : mvHtml;
    report.movie = {
      status: mv.status,
      decodedLen: mvHtml.length,
      bodyHtml: mvBody.slice(0, 22000),
    };

    // 6. Series `/selary/` page (`Lupin` S4) + Episode page (`Lupin` S4E1)
    const seriesUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
    const sr = await smartGet(seriesUrl, proxies, { referer: "https://cimanow.cc/home/", cookieJarFile: cookieFile });
    const srHtml = tryDecodeCimaNowObfuscation(sr.text).decoded;
    const srBodyIdx = srHtml.toLowerCase().indexOf("<body");
    const srBody = srBodyIdx >= 0 ? srHtml.slice(srBodyIdx) : srHtml;
    report.series = {
      status: sr.status,
      decodedLen: srHtml.length,
      bodyHtml: srBody.slice(0, 24000),
    };

    const epUrl = "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/";
    const ep = await smartGet(epUrl, proxies, { referer: seriesUrl, cookieJarFile: cookieFile });
    const epHtml = tryDecodeCimaNowObfuscation(ep.text).decoded;
    const epBodyIdx = epHtml.toLowerCase().indexOf("<body");
    report.episode = {
      status: ep.status,
      decodedLen: epHtml.length,
      bodyHtml: (epBodyIdx >= 0 ? epHtml.slice(epBodyIdx) : epHtml).slice(0, 20000),
    };

    // 7. `/watching/` Referer matrix!
    const watchUrl = `${movieUrl}watching/`;
    const refTests = {
      none: null,
      cimanowRoot: "https://cimanow.cc/",
      parentMovie: movieUrl,
      freex2lineRoot: "https://rm.freex2line.online/",
      freex2lineBlog: "https://rm.freex2line.online/2020/02/blog-post.html/",
    };
    let bestReferer = "https://cimanow.cc/";
    for (const [label, ref] of Object.entries(refTests)) {
      const wr = await smartGet(watchUrl, proxies, { referer: ref, cookieJarFile: cookieFile });
      const wHtml = tryDecodeCimaNowObfuscation(wr.text).decoded;
      const hasWatch = wHtml.includes('id="watch"') || wHtml.includes("data-index") || wHtml.includes('id="download"');
      report.watchingRefererMatrix[label] = {
        status: wr.status,
        finalUrl: wr.finalUrl,
        decodedLen: wHtml.length,
        hasWatch,
      };
      if (hasWatch) bestReferer = ref;
    }

    // 8. Probe `/watching/` + ALL `core.php?action=switch` tabs + `<ul id="download">` across 4 titles!
    const targets = [
      { label: "Fall 2 (Foreign Movie)", pageUrl: movieUrl },
      { label: "GOAT (Dubbed Animation Movie)", pageUrl: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-goat-2026-%d9%85%d8%af%d8%a8%d9%84%d8%ac-%d9%85%d8%b5%d8%b1%d9%8a/" },
      { label: "Lupin S4E1 (Foreign Series Ep)", pageUrl: epUrl },
      { label: "El Qessa El Kamla E4 (Arabic Series Ep)", pageUrl: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d8%a7%d9%84%d9%82%d8%b5%d8%a9-%d8%a7%d9%84%d9%83%d8%a7%d9%85%d9%84%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-4-%d8%a7%d9%84%d8%b1%d8%a7%d8%a8%d8%b9%d8%a9/" },
    ];

    for (const item of targets) {
      const wUrl = `${item.pageUrl}watching/`;
      const wr = await smartGet(wUrl, proxies, {
        referer: bestReferer || item.pageUrl,
        cookieJarFile: cookieFile,
      });
      const wHtml = tryDecodeCimaNowObfuscation(wr.text).decoded;
      const wBodyIdx = wHtml.toLowerCase().indexOf("<body");
      const wBody = wBodyIdx >= 0 ? wHtml.slice(wBodyIdx) : wHtml;

      const watchUl = wBody.match(/<ul[^>]+id=["']watch["'][\s\S]*?<\/ul>/i)?.[0] || "";
      const downloadUl = wBody.match(/<ul[^>]+id=["']download["'][\s\S]*?<\/ul>/i)?.[0] || "";
      const scriptsInWatch = [...wHtml.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
        .map((m) => m[1])
        .filter((s) => s.includes("switch") || s.includes("core.php") || s.includes("data-index") || s.includes("iframe"))
        .map((s) => s.slice(0, 2000));

      // Extract `<li data-index="..." data-id="...">` regardless of attribute order!
      const liTags = [...watchUl.matchAll(/<li\b[^>]*>[\s\S]*?<\/li>/gi)].map((m) => m[0]);
      const tabs = [];
      for (const li of liTags) {
        const index = li.match(/data-index=["']([^"']+)["']/i)?.[1];
        const id = li.match(/data-id=["']([^"']+)["']/i)?.[1];
        if (index !== undefined && id !== undefined) {
          tabs.push({
            index,
            id,
            name: li.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
            rawLi: li,
          });
        }
      }

      const switchResponses = [];
      for (const tab of tabs) {
        const swUrl = `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${encodeURIComponent(tab.index)}&id=${encodeURIComponent(tab.id)}`;
        const swRes = await smartGet(swUrl, proxies, {
          referer: wUrl,
          extraHeaders: ["X-Requested-With: XMLHttpRequest"],
          cookieJarFile: cookieFile,
        });
        const swDecoded = tryDecodeCimaNowObfuscation(swRes.text).decoded;
        const iframeSrc = swDecoded.match(/src=["']([^"']+)["']/i)?.[1] || null;
        switchResponses.push({
          tab: tab.name,
          index: tab.index,
          id: tab.id,
          status: swRes.status,
          iframeSrc,
          rawSnippet: swDecoded.trim().slice(0, 350),
        });

        // Probe CimaNow's native embed player (`cimanow.net` or tab index 0) up to 2 samples!
        if (
          report.cimanowNativePlayerSamples.length < 2 &&
          iframeSrc &&
          (iframeSrc.includes("cimanow") || tab.name.toLowerCase().includes("cima") || tab.index === "0")
        ) {
          const fullIframeUrl = iframeSrc.startsWith("//") ? `https:${iframeSrc}` : iframeSrc;
          try {
            const pRes = await curlGet(fullIframeUrl, { referer: "https://cimanow.cc/", timeoutSec: 12 });
            report.cimanowNativePlayerSamples.push({
              fromTitle: item.label,
              tabName: tab.name,
              iframeUrl: fullIframeUrl,
              status: pRes.status,
              finalUrl: pRes.finalUrl,
              html: pRes.text.slice(0, 12000),
            });
          } catch (e) {
            report.cimanowNativePlayerSamples.push({
              fromTitle: item.label,
              iframeUrl: fullIframeUrl,
              error: String(e.message || e),
            });
          }
        }
      }

      const downloadLinks = [...downloadUl.matchAll(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)].map((m) => ({
        href: m[1],
        label: m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
        rawTag: m[0].slice(0, 350),
      }));

      report.watchingDetails.push({
        label: item.label,
        watchUrl: wUrl,
        finalUrl: wr.finalUrl,
        watchUlSample: watchUl.slice(0, 3000),
        downloadUlSample: downloadUl.slice(0, 3500),
        scriptsInWatch,
        tabsCount: tabs.length,
        switchResponses,
        downloadLinks,
        bodyFallback: tabs.length === 0 ? wBody.slice(0, 12000) : null,
      });
    }
  } catch (err) {
    report.fatalError = String(err?.stack || err);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(report, null, 2));
  console.log("Wrote complete cimanow_recon.json");
}

main();
