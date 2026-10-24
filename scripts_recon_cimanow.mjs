import fs from "fs";
import os from "os";
import path from "path";
import vm from "vm";
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
  if (range) args.push("-r", range, "-o", "/dev/null");
  if (proxy) args.push("-x", proxy.replace(/^socks5:\/\//, "socks5h://"));
  if (referer) args.push("-e", referer);
  if (cookieJar) args.push("-b", cookieJar, "-c", cookieJar);
  for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{content_type}");
  args.push(url);
  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 30 * 1024 * 1024 });
  const idx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = idx >= 0 ? stdout.slice(0, idx) : stdout;
  const [codeStr, effectiveUrl, contentType] = (idx >= 0 ? stdout.slice(idx + 15).trim() : "0||").split("|");
  return { status: Number(codeStr) || 0, effectiveUrl, contentType, text };
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

function calcCoreSig(token, id, index, salt = "cnSig12") {
  const sd = `${token}:${id}:${index}:${salt}`;
  let h = 2166136261;
  for (let i = 0; i < sd.length; i++) {
    h ^= sd.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return (h >>> 0).toString(36);
}

function decodeCnBootFrame(html) {
  const cnk = html.match(/data-cnk=["']([^"']+)["']/i)?.[1];
  const cna = html.match(/data-cna=["']([^"']+)["']/i)?.[1];
  if (cnk && cna) {
    let k = "", u = "";
    for (let i = 0; i < cnk.length; i += 2) k += String.fromCharCode(parseInt(cnk.substr(i, 2), 16));
    const a = cna.split(".");
    for (let j = 0; j < a.length; j++) {
      u += String.fromCharCode(parseInt(a[j], 10) ^ k.charCodeAt(j % k.length) ^ ((j * 13 + 7) & 255));
    }
    return u;
  }
  return html.match(/src=["']([^"']+)["']/i)?.[1] || null;
}

function pureMathDecodeWatching(rawHtml) {
  const pTag = rawHtml.match(/<script\b[^>]*type="text\/plain"[^>]*id="(p_[^"]+)"[^>]*data-([A-Za-z0-9]+)="([^"]+)"/i);
  const gTag = rawHtml.match(/<script\b[^>]*id="(g_[^"]+)"[^>]*data-([A-Za-z0-9]+)="([^"]+)"[^>]*>([\s\S]*?)<\/script>/i);
  if (!pTag || !gTag) return { err: "no pTag/gTag" };

  const modG = Number(gTag[4].match(/%\s*(\d+)/)?.[1] || 6);
  const s2 = Buffer.from(uninterleave(gTag[3], modG), "base64").toString("utf8");
  return { s2Sample: s2, pId: pTag[1], gId: gTag[1], gBoot: gTag[4] };
}

async function testPureJetload() {
  const jar = path.join(os.tmpdir(), `cn_jet20_${Math.random().toString(36).slice(2)}.cookies`);
  try {
    const jetGw =
      "https://jetload.top/Jetloadon4/?link=aHR0cHM6Ly9jZG4xLmpldGxvYWQudG9wLyU1QkNpbWEtTm93LkNvTSU1RCUyMEZhbGwuMi5EZWFkcG9pbnQuMjAyNi5IRC0xMDgwcC5tcDQ=";
    await curlReq(jetGw, { cookieJar: jar, referer: "https://cimanow.cc/", timeoutSec: 10 });
    const j2 = await curlReq("https://jetload.top/Jetload4/", { cookieJar: jar, referer: jetGw, timeoutSec: 10 });
    const extraToken = j2.text.match(/window\.extraToken\s*=\s*'([^']+)'/)?.[1] || "";
    const dataToken = j2.text.match(/data-token="([^"]+)"/)?.[1] || "";
    // Test 0s wait first!
    const gl0 = await curlReq(`https://jetload.top/Jetload4/get-link.php?token=${encodeURIComponent(dataToken)}`, {
      cookieJar: jar,
      referer: "https://jetload.top/Jetload4/",
      headers: { "X-Requested-With": "XMLHttpRequest" },
      timeoutSec: 10,
    });
    let finalBase = gl0.text.trim();
    let neededWait = false;
    if (!finalBase.startsWith("http")) {
      neededWait = true;
      await sleep(10300);
      const gl10 = await curlReq(`https://jetload.top/Jetload4/get-link.php?token=${encodeURIComponent(dataToken)}`, {
        cookieJar: jar,
        referer: "https://jetload.top/Jetload4/",
        headers: { "X-Requested-With": "XMLHttpRequest" },
        timeoutSec: 10,
      });
      finalBase = gl10.text.trim();
    }
    const finalUrl = extraToken ? `${finalBase}?t=${extraToken}` : finalBase;
    await curlReq("https://jetload.top/Jetload4/", {
      cookieJar: jar,
      method: "POST",
      formBody: "action=start_timer",
      referer: "https://jetload.top/Jetload4/",
      headers: { "Content-Type": "application/x-www-form-urlencoded", "X-Requested-With": "XMLHttpRequest" },
      timeoutSec: 8,
    });
    const mp4Probe = await curlReq(finalUrl, {
      referer: "https://jetload.top/",
      range: "0-511",
      timeoutSec: 10,
    });
    const mp4ProbeNoRef = await curlReq(finalUrl, {
      range: "0-511",
      timeoutSec: 10,
    });
    return {
      neededWait,
      gl0Body: gl0.text.trim().slice(0, 200),
      finalUrl,
      withRef: { status: mp4Probe.status, ct: mp4Probe.contentType },
      noRef: { status: mp4ProbeNoRef.status, ct: mp4ProbeNoRef.contentType },
    };
  } finally {
    try { fs.rmSync(jar, { force: true }); } catch {}
  }
}

async function testPureCimaNowItem(name, itemUrl, proxies) {
  const errors = [];
  for (const proxy of proxies.slice(0, 5)) {
    const jar = path.join(os.tmpdir(), `cn_p20_${Math.random().toString(36).slice(2)}.cookies`);
    try {
      const mv = await curlReq(itemUrl, { proxy, cookieJar: jar, referer: "https://cimanow.cc/home/" });
      if (mv.status !== 200 || isCimaBlock(mv.text)) {
        errors.push(`${proxy}: mv status=${mv.status} block=${isCimaBlock(mv.text)}`);
        continue;
      }
      const shineUrl =
        mv.text.match(/href=["'](https?:\/\/[^"']*\/loadon\/\?link=[^"']+)["']/i)?.[1] ||
        mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1] ||
        mv.text.match(/<a\b[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*shine[^"']*["']/i)?.[1] ||
        "";
      if (!shineUrl) {
        errors.push(`${proxy}: no shineUrl (len=${mv.text.length})`);
        continue;
      }

      await curlReq(shineUrl, { proxy, cookieJar: jar, referer: itemUrl });
      await curlReq("https://rm.freex2line.online/redirectingfree/", { proxy, cookieJar: jar });
      const r3 = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/", {
        proxy,
        cookieJar: jar,
        referer: "https://rm.freex2line.online/redirectingfree/",
      });
      const gw = buildGatewayPostBody(r3.text);
      if (!gw) {
        errors.push(`${proxy}: buildGatewayPostBody null (r3 status=${r3.status} len=${r3.text.length})`);
        continue;
      }

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
      if (!mintedUrl.startsWith("http") || !mintedUrl.includes("?token=")) {
        errors.push(`${proxy}: bad mintedUrl=${mintedUrl.slice(0, 120)}`);
        continue;
      }

      const watchRes = await curlReq(mintedUrl, {
        proxy,
        cookieJar: jar,
        referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
      });
      const decoded = pureMathDecodeWatching(watchRes.text);
      return { ok: true, proxy, decoded };
    } catch (e) {
      errors.push(`${proxy}: EXC ${String(e.message || e).slice(0, 180)}`);
    } finally {
      try { fs.rmSync(jar, { force: true }); } catch {}
    }
  }
  return { ok: false, errors };
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;

    const items = [
      ["ArabicMovie_BeitElRuby", "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-%d8%a8%d9%8a%d8%aa-%d8%a7%d9%84%d8%b1%d9%88%d8%a8%d9%8a-2023/"],
      ["ForeignEpisode_LupinS4E1", "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/"],
    ];
    out.items = {};
    for (const [name, url] of items) {
      out.items[name] = await testPureCimaNowItem(name, url, proxies);
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v20 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
