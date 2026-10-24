import fs from "node:fs";
import vm from "node:vm";

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const cookieJar = new Map();

function storeCookies(res) {
  const list = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  for (const line of list) {
    const pair = line.split(";")[0];
    const eq = pair.indexOf("=");
    if (eq > 0) cookieJar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }
}

function cookieHeader() {
  return [...cookieJar.entries()].map(([k, v]) => `${k}=${v}`).join("; ");
}

/**
 * If CimaNow returns its JS HTML-obfuscation page (`eval(...)` / `document.write(...)`),
 * capture both the raw script structure AND the decoded HTML via a sandboxed VM!
 */
function tryDecodeCimaNowObfuscation(rawHtml) {
  if (!rawHtml || rawHtml.includes("<article") || rawHtml.includes('id="watch"') || rawHtml.includes("<section")) {
    return { wasObfuscated: false, decoded: rawHtml, scriptSample: null };
  }
  const scriptMatches = [...rawHtml.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)];
  if (scriptMatches.length === 0) {
    return { wasObfuscated: false, decoded: rawHtml, scriptSample: rawHtml.slice(0, 1200) };
  }

  let written = "";
  const sandbox = {
    written: "",
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
    RegExp,
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
    },
    window: null,
    location: { href: "https://cimanow.cc/home/", reload() {} },
    navigator: { userAgent: UA },
    setTimeout: (fn) => {
      if (typeof fn === "function") fn();
    },
  };
  sandbox.window = sandbox;
  sandbox.self = sandbox;
  sandbox.globalThis = sandbox;

  const fullJs = scriptMatches.map((m) => m[1]).join("\n;\n");
  try {
    vm.runInNewContext(fullJs, sandbox, { timeout: 4000 });
  } catch (e) {
    return {
      wasObfuscated: true,
      vmError: String(e.message || e),
      decoded: written || rawHtml,
      rawLength: rawHtml.length,
      scriptHead: fullJs.slice(0, 2000),
      scriptTail: fullJs.slice(-1500),
      cookieSet: sandbox.document.cookie,
    };
  }

  if (sandbox.document.cookie) {
    const pair = sandbox.document.cookie.split(";")[0];
    const eq = pair.indexOf("=");
    if (eq > 0) cookieJar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim());
  }

  return {
    wasObfuscated: written.length > 0 || Boolean(sandbox.document.cookie),
    decoded: written.length > 0 ? written : rawHtml,
    rawLength: rawHtml.length,
    decodedLength: written.length,
    scriptHead: fullJs.slice(0, 2000),
    scriptTail: fullJs.slice(-1500),
    cookieSet: sandbox.document.cookie,
  };
}

async function rawGet(url, extraHeaders = {}, redirect = "follow") {
  const headers = {
    "User-Agent": UA,
    Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ar,en-US;q=0.9,en;q=0.8",
    ...extraHeaders,
  };
  const ck = cookieHeader();
  if (ck && !headers.Cookie) headers.Cookie = ck;
  const res = await fetch(url, { headers, redirect, signal: AbortSignal.timeout(15000) });
  storeCookies(res);
  const text = await res.text();
  return {
    status: res.status,
    finalUrl: res.url,
    location: res.headers.get("location"),
    cookies: Object.fromEntries(cookieJar.entries()),
    text,
  };
}

async function main() {
  const report = {
    timestamp: new Date().toISOString(),
    landing: null,
    home: null,
    categoryPage2: null,
    search: null,
    movie: null,
    series: null,
    episode: null,
    watchingRefererMatrix: {},
    watchingDetails: [],
    cimanowNativePlayerSample: null,
    fatalError: null,
  };

  try {
    // 1. Landing `/` (check logos/icons & obfuscation)
    const land = await rawGet("https://cimanow.cc/");
    const landDec = tryDecodeCimaNowObfuscation(land.text);
    report.landing = {
      status: land.status,
      finalUrl: land.finalUrl,
      rawLen: land.text.length,
      wasObfuscated: landDec.wasObfuscated,
      scriptHead: landDec.scriptHead,
      scriptTail: landDec.scriptTail,
      cookieSet: landDec.cookieSet,
      imgs: [...landDec.decoded.matchAll(/<img[^>]+src=["']([^"']+)["'][^>]*>/gi)].slice(0, 15).map((m) => m[0]),
      icons: [...landDec.decoded.matchAll(/<link[^>]+icon[^>]*>/gi)].map((m) => m[0]),
    };

    // 2. `/home/`
    const home = await rawGet("https://cimanow.cc/home/", { Referer: "https://cimanow.cc/" });
    const homeDec = tryDecodeCimaNowObfuscation(home.text);
    const homeHtml = homeDec.decoded;
    report.home = {
      status: home.status,
      finalUrl: home.finalUrl,
      rawLen: home.text.length,
      decodedLen: homeHtml.length,
      wasObfuscated: homeDec.wasObfuscated,
      scriptHead: homeDec.scriptHead,
      scriptTail: homeDec.scriptTail,
      sections: [...homeHtml.matchAll(/<section[^>]*>([\s\S]*?)<\/section>/gi)].slice(0, 20).map((m, idx) => {
        const sec = m[0];
        const openTag = sec.match(/<section[^>]*>/)?.[0] || "";
        const heading = sec.match(/<span[^>]*>([\s\S]*?)<\/span>/i)?.[1]?.replace(/<[^>]+>/g, "").trim() || "";
        const moreLink = sec.match(/href=["']([^"']+)["'][^>]*>\s*شاهد الكل/i)?.[1] || sec.match(/<a[^>]+href=["']([^"']*category[^"']*)["']/i)?.[1] || null;
        const articles = [...sec.matchAll(/<article[^>]*>([\s\S]*?)<\/article>/gi)];
        return {
          idx,
          openTag,
          heading: heading.slice(0, 100),
          moreLink,
          articleCount: articles.length,
          sampleArticle: articles[0]?.[0]?.slice(0, 1500) || sec.slice(0, 900),
        };
      }),
    };

    // 3. Category Page 2 & Pagination structure
    const cat2 = await rawGet("https://cimanow.cc/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/page/2/", {
      Referer: "https://cimanow.cc/home/",
    });
    const cat2Html = tryDecodeCimaNowObfuscation(cat2.text).decoded;
    report.categoryPage2 = {
      status: cat2.status,
      articleCount: [...cat2Html.matchAll(/<article[^>]*>/gi)].length,
      sampleArticle: cat2Html.match(/<article[^>]*>[\s\S]*?<\/article>/i)?.[0]?.slice(0, 1600),
      paginationHtml: cat2Html.match(/<ul[^>]+id=["']pagination["'][\s\S]*?<\/ul>|<div[^>]+pagination[\s\S]*?<\/div>/i)?.[0]?.slice(0, 1200) ||
        [...cat2Html.matchAll(/href=["']([^"']*\/page\/\d+\/[^"']*)["']/gi)].slice(0, 10).map((m) => m[1]),
    };

    // 4. Search & Search Page 2
    const srch = await rawGet("https://cimanow.cc/?s=Spider", { Referer: "https://cimanow.cc/home/" });
    const srchHtml = tryDecodeCimaNowObfuscation(srch.text).decoded;
    const srch2 = await rawGet("https://cimanow.cc/page/2/?s=Spider", { Referer: "https://cimanow.cc/?s=Spider" });
    const srch2Html = tryDecodeCimaNowObfuscation(srch2.text).decoded;
    report.search = {
      page1Count: [...srchHtml.matchAll(/<article[^>]*>/gi)].length,
      page2Count: [...srch2Html.matchAll(/<article[^>]*>/gi)].length,
      sampleArticle: srchHtml.match(/<article[^>]*>[\s\S]*?<\/article>/i)?.[0]?.slice(0, 1500),
    };

    // 5. Movie detail page
    const movieUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
    const mv = await rawGet(movieUrl, { Referer: "https://cimanow.cc/home/" });
    const mvHtml = tryDecodeCimaNowObfuscation(mv.text).decoded;
    report.movie = {
      status: mv.status,
      len: mvHtml.length,
      snippets: {
        headerAndMeta: mvHtml.slice(0, 6000),
        bodyMid: mvHtml.slice(6000, 16000),
        bodyTail: mvHtml.slice(16000, 28000),
      },
    };

    // 6. Series (`/selary/`) detail page
    const seriesUrl = "https://cimanow.cc/selary/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
    const sr = await rawGet(seriesUrl, { Referer: "https://cimanow.cc/home/" });
    const srHtml = tryDecodeCimaNowObfuscation(sr.text).decoded;
    report.series = {
      status: sr.status,
      len: srHtml.length,
      seasonsSection: srHtml.match(/<section[^>]+aria-label=["']seasons["'][\s\S]*?<\/section>/i)?.[0] ||
        srHtml.match(/<ul[^>]*>[\s\S]*?الموسم 1[\s\S]*?<\/ul>/i)?.[0]?.slice(0, 2500),
      episodesSection: srHtml.match(/<ul[^>]+id=["']eps["'][\s\S]*?<\/ul>/i)?.[0]?.slice(0, 4000) ||
        srHtml.slice(0, 18000),
    };

    // 7. Test `/watching/` with different Referer headers!
    const watchUrl = `${movieUrl}watching/`;
    const testReferers = {
      none: {},
      selfHome: { Referer: "https://cimanow.cc/home/" },
      parentMovie: { Referer: movieUrl },
      freex2lineRoot: { Referer: "https://rm.freex2line.online/" },
      freex2lineBlog: { Referer: "https://rm.freex2line.online/2020/02/blog-post.html/" },
    };

    let workingRefererHeader = { Referer: "https://cimanow.cc/" };
    for (const [label, hdr] of Object.entries(testReferers)) {
      const wr = await rawGet(watchUrl, hdr);
      const wDec = tryDecodeCimaNowObfuscation(wr.text).decoded;
      const hasWatchList = wDec.includes('id="watch"') || wDec.includes("data-index") || wDec.includes('id="download"');
      report.watchingRefererMatrix[label] = {
        status: wr.status,
        finalUrl: wr.finalUrl,
        decodedLen: wDec.length,
        hasWatchList,
      };
      if (hasWatchList) {
        workingRefererHeader = hdr;
      }
    }

    // Also inspect `rm.freex2line.online/loadon/?link=...` raw HTML just in case!
    const b64Link = Buffer.from(watchUrl, "utf8").toString("base64");
    const gate = await rawGet(`https://rm.freex2line.online/loadon/?link=${encodeURIComponent(b64Link)}`, {
      Referer: movieUrl,
    });
    report.gatewayCheck = {
      status: gate.status,
      finalUrl: gate.finalUrl,
      htmlSnippet: gate.text.slice(0, 3500),
    };

    // 8. Probe `/watching/` + `core.php?action=switch` + `<ul id="download">` across 5 diverse titles!
    const targets = [
      { label: "Fall 2 (Foreign Movie)", pageUrl: movieUrl },
      { label: "GOAT (Dubbed Animation Movie)", pageUrl: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-goat-2026-%d9%85%d8%af%d8%a8%d9%84%d8%ac-%d9%85%d8%b5%d8%b1%d9%8a/" },
      { label: "Mas'alat Hayat (Arabic Movie)", pageUrl: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-%d9%85%d8%b3%d8%a3%d9%84%d8%a9-%d8%ad%d9%8a%d8%a7%d8%a9-%d8%a3%d9%88-%d9%85%d9%88%d8%aa-2025/" },
      { label: "Lupin S4E1 (Foreign Series Ep)", pageUrl: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/" },
      { label: "El Qessa El Kamla E4 (Arabic Series Ep)", pageUrl: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d8%a7%d9%84%d9%82%d8%b5%d8%a9-%d8%a7%d9%84%d9%83%d8%a7%d9%85%d9%84%d8%a9-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-4-%d8%a7%d9%84%d8%b1%d8%a7%d8%a8%d8%b9%d8%a9/" },
    ];

    for (const item of targets) {
      const wUrl = `${item.pageUrl}watching/`;
      const wr = await rawGet(wUrl, workingRefererHeader.Referer ? workingRefererHeader : { Referer: item.pageUrl });
      const wHtml = tryDecodeCimaNowObfuscation(wr.text).decoded;

      const watchSection = wHtml.match(/<ul[^>]+id=["']watch["'][\s\S]*?<\/ul>/i)?.[0] || "";
      const downloadSection = wHtml.match(/<ul[^>]+id=["']download["'][\s\S]*?<\/ul>/i)?.[0] || "";
      const scriptsInWatch = [...wHtml.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
        .map((m) => m[1])
        .filter((s) => s.includes("switch") || s.includes("core.php") || s.includes("data-index") || s.includes("ajax"))
        .map((s) => s.slice(0, 1500));

      const tabs = [...watchSection.matchAll(/<li[^>]*data-index=["']([^"']+)["'][^>]*data-id=["']([^"']+)["'][^>]*>([\s\S]*?)<\/li>/gi)].map((m) => ({
        index: m[1],
        id: m[2],
        name: m[3].replace(/<[^>]+>/g, "").trim(),
      }));

      const switchResponses = [];
      for (const tab of tabs) {
        const swUrl = `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${encodeURIComponent(tab.index)}&id=${encodeURIComponent(tab.id)}`;
        const swRes = await rawGet(swUrl, {
          Referer: wUrl,
          "X-Requested-With": "XMLHttpRequest",
        });
        const iframeSrc = swRes.text.match(/src=["']([^"']+)["']/i)?.[1] || swRes.text.trim().slice(0, 300);
        switchResponses.push({ tab: tab.name, index: tab.index, id: tab.id, status: swRes.status, iframeSrc });

        // If this is CimaNow's native embed (`cimanow.net` / `cn` / `embed`), fetch its HTML once!
        if (!report.cimanowNativePlayerSample && iframeSrc && (iframeSrc.includes("cimanow") || tab.name.toLowerCase().includes("cimanow") || tab.index === "0")) {
          const fullIframeUrl = iframeSrc.startsWith("//") ? `https:${iframeSrc}` : iframeSrc;
          if (fullIframeUrl.startsWith("http")) {
            try {
              const pRes = await rawGet(fullIframeUrl, { Referer: "https://cimanow.cc/" });
              report.cimanowNativePlayerSample = {
                tabName: tab.name,
                iframeUrl: fullIframeUrl,
                status: pRes.status,
                finalUrl: pRes.finalUrl,
                html: pRes.text.slice(0, 8000),
              };
            } catch (e) {
              report.cimanowNativePlayerSample = { error: String(e.message || e), iframeUrl: fullIframeUrl };
            }
          }
        }
      }

      const downloadLinks = [...downloadSection.matchAll(/<a[^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)].map((m) => ({
        href: m[1],
        label: m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
      }));

      report.watchingDetails.push({
        label: item.label,
        watchUrl: wUrl,
        finalUrl: wr.finalUrl,
        watchSectionRaw: watchSection.slice(0, 2500),
        downloadSectionRaw: downloadSection.slice(0, 2500),
        scriptsInWatch,
        tabsCount: tabs.length,
        switchResponses,
        downloadLinks,
        fallbackHtmlSample: tabs.length === 0 ? wHtml.slice(0, 4000) : null,
      });
    }
  } catch (err) {
    report.fatalError = String(err?.stack || err);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(report, null, 2));
  console.log("Wrote cimanow_recon.json successfully");
}

main();
