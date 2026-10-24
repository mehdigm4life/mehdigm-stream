import fs from "fs";
import os from "os";
import path from "path";
import crypto from "crypto";
import { execFile } from "child_process";
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
    range = null,
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
  if (range) args.push("-r", range);
  if (proxy) args.push("-x", proxy.replace(/^socks5:\/\//, "socks5h://"));
  if (referer) args.push("-e", referer);
  if (cookieJar) args.push("-b", cookieJar, "-c", cookieJar);
  for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{content_type}|%{redirect_url}");
  args.push(url);
  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 30 * 1024 * 1024 });
  const idx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = idx >= 0 ? stdout.slice(0, idx) : stdout;
  const [codeStr, effectiveUrl, contentType, redirectUrl] = (idx >= 0 ? stdout.slice(idx + 15).trim() : "0|||").split("|");
  return { status: Number(codeStr) || 0, effectiveUrl, contentType, redirectUrl, text };
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
      return { ch, ri, secretHex, fp, hmac, postBody };
    }
  }
  return null;
}

function decodeWatchingBootstrap(rawHtml) {
  // Strip the two big data-* values to see the exact HTML skeleton!
  const skeleton = rawHtml.replace(/data-[A-Za-z0-9]+="[^"]{200,}"/g, 'data-REDACTED="<big>"');
  const pTag = rawHtml.match(/<script\b[^>]*type="text\/plain"[^>]*id="(p_[^"]+)"[^>]*data-([A-Za-z0-9]+)="([^"]+)"/i);
  const gTag = rawHtml.match(/<script\b[^>]*id="(g_[^"]+)"[^>]*data-([A-Za-z0-9]+)="([^"]+)"[^>]*>([\s\S]*?)<\/script>/i);
  if (!gTag) return { skeleton };

  const r = gTag[3];
  const inlineCode = gTag[4];
  const modMatch = inlineCode.match(/i\s*%\s*(\d+)/);
  const modN = modNFrom(modMatch);
  function modNFrom(m) { return m ? Number(m[1]) : 6; }

  const p = r.indexOf(".");
  const ev = r.substring(0, p).split("").reverse().join("");
  const od = r.substring(p + 1);
  const t = ev.length + od.length;
  let o = "", e = 0, d = 0;
  for (let i = 0; i < t; i++) {
    o += i % modN === 0 ? ev.charAt(e++) : od.charAt(d++);
  }
  const stage2Js = Buffer.from(o, "base64").toString("utf8");
  return {
    skeleton,
    pId: pTag?.[1],
    pDataAttr: pTag?.[2],
    pDataLen: pTag?.[3]?.length || 0,
    pDataSampleHead: pTag?.[3]?.slice(0, 300),
    pDataSampleTail: pTag?.[3]?.slice(-300),
    gId: gTag[1],
    gDataAttr: gTag[2],
    modN,
    inlineCode,
    stage2JsLen: stage2Js.length,
    stage2Js,
  };
}

async function probeDownloadFlows() {
  const out = {};
  const jar = path.join(os.tmpdir(), `cn_dl_${Math.random().toString(36).slice(2)}.cookies`);
  try {
    // 1) VK Doc (first check without following or with range 0-1023!)
    const vkNoFollow = await curlReq("https://vk.com/doc1100704408_708544332", { follow: false, timeoutSec: 10 });
    const vkPage = await curlReq("https://vk.com/doc1100704408_708544332", { range: "0-2047", timeoutSec: 10 });
    out.vkDoc = {
      noFollowStatus: vkNoFollow.status,
      noFollowRedirect: vkNoFollow.redirectUrl,
      noFollowSample: vkNoFollow.text.slice(0, 1200),
      followStatus: vkPage.status,
      followCt: vkPage.contentType,
      followEff: vkPage.effectiveUrl,
      followSample: vkPage.contentType?.includes("text") ? vkPage.text.slice(0, 1500) : "<binary>",
    };

    // 2) Jetload session flow
    const jetGwUrl =
      "https://jetload.top/Jetloadon4/?link=aHR0cHM6Ly9jZG4xLmpldGxvYWQudG9wLyU1QkNpbWEtTm93LkNvTSU1RCUyMEZhbGwuMi5EZWFkcG9pbnQuMjAyNi5IRC0xMDgwcC5tcDQ=";
    const j1 = await curlReq(jetGwUrl, { cookieJar: jar, referer: "https://cimanow.cc/", timeoutSec: 10 });
    const j2 = await curlReq("https://jetload.top/Jetload4/", {
      cookieJar: jar,
      referer: jetGwUrl,
      timeoutSec: 10,
    });
    out.jetload = {
      j1Status: j1.status,
      j1Sample: j1.text.slice(0, 600),
      j2Status: j2.status,
      j2Len: j2.text.length,
      j2Sample: j2.text.slice(0, 4000),
      cookies: fs.existsSync(jar) ? fs.readFileSync(jar, "utf8") : "",
    };

    // 3) Forafile POST op=download2
    const f2 = await curlReq(
      "https://forafile.com/n4zcdwrwmfy1/%5BCima-Now.CoM%5D_Fall.2.Deadpoint.2026.HD-720p.mp4.html",
      {
        method: "POST",
        formBody: "op=download2&id=n4zcdwrwmfy1&rand=&referer=&method_free=&method_premium=",
        referer: "https://forafile.com/n4zcdwrwmfy1/%5BCima-Now.CoM%5D_Fall.2.Deadpoint.2026.HD-720p.mp4.html",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        timeoutSec: 12,
      }
    );
    out.forafilePost = {
      status: f2.status,
      directLinks: [...f2.text.matchAll(/https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*/gi)].map((m) => m[0]),
      sample: f2.text.slice(0, 2000),
    };
  } catch (e) {
    out.err = String(e.stack || e);
  } finally {
    try { fs.rmSync(jar, { force: true }); } catch {}
  }
  return out;
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;
    out.dlProbes = await probeDownloadFlows();

    const itemUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
    for (const proxy of proxies.slice(0, 4)) {
      const jar = path.join(os.tmpdir(), `cn_v18_${Math.random().toString(36).slice(2)}.cookies`);
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
        const mintedUrl = p10.text.trim();
        if (!mintedUrl.startsWith("http") || !mintedUrl.includes("?token=")) continue;

        const watchRes = await curlReq(mintedUrl, {
          proxy,
          cookieJar: jar,
          referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
        });
        if (watchRes.status !== 200 || isCimaBlock(watchRes.text)) continue;

        out.watchingBootstrap = decodeWatchingBootstrap(watchRes.text);
        out.mintedUrl = mintedUrl;
        break;
      } catch {} finally {
        try { fs.rmSync(jar, { force: true }); } catch {}
      }
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v18 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
