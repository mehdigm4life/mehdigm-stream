import crypto from "node:crypto";
import fs from "node:fs";

const APP_UA = "okhttp/4.10.0";
const DESKTOP_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const GPHOTO_UA =
  "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36";

const CERT_SHA256 = "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7";
const APK_PATH = "/data/app/com.anime.day/base.apk";
const HMAC_SECRET = "97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002";
const DECRYPT_SALT = "Gm4il@G00Gl3.Com";
const RSA_PUB_SPKI_B64 =
  "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6+BtHI/" +
  "Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh65Nbi4mKRFFJyDk" +
  "JVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz17n265rLB6ruUUrHQVeEDl8" +
  "ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IE" +
  "YV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCuj3zO7wIDAQAB";

const rsaPem =
  "-----BEGIN PUBLIC KEY-----\n" +
  RSA_PUB_SPKI_B64.match(/.{1,64}/g).join("\n") +
  "\n-----END PUBLIC KEY-----\n";

const sessionDeviceKey = crypto.randomBytes(8).toString("hex");
const staticMac = crypto
  .createHmac("sha256", Buffer.from(HMAC_SECRET, "utf8"))
  .update(`err|${CERT_SHA256}|000|err_open`, "utf8")
  .digest("hex");

function randomAlnum(len) {
  const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  let out = "";
  const bytes = crypto.randomBytes(len);
  for (let i = 0; i < len; i++) out += chars[bytes[i] % chars.length];
  return out;
}

function buildSecureToken() {
  const nowSec = Math.floor(Date.now() / 1000);
  const nonce = crypto.randomBytes(8).toString("hex");
  const payload =
    `{"a":"err","b":"${CERT_SHA256}|${APK_PATH}","c":"false",` +
    `"d":"empty","e":"empty","f":"false","g":"false","h":"false","i":"false",` +
    `"j":"false","r":"false","s":"err_open","m":"${staticMac}","t":${nowSec},"n":"${nonce}"}`;

  const aesKey = crypto.randomBytes(32);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", aesKey, iv);
  const ct = Buffer.concat([cipher.update(payload, "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  const sealed = Buffer.concat([ct, tag]);

  const encKey = crypto.publicEncrypt(
    { key: rsaPem, padding: crypto.constants.RSA_PKCS1_PADDING },
    aesKey
  );
  return `${encKey.toString("base64")}.${iv.toString("base64")}.${sealed.toString("base64")}`;
}

function md5Hex(s) {
  return crypto.createHash("md5").update(s, "utf8").digest("hex");
}

function decryptResponse(b64, key) {
  const aesKey = Buffer.from(md5Hex(DECRYPT_SALT + key), "utf8");
  const iv = Buffer.from(md5Hex(key + DECRYPT_SALT).slice(0, 16), "utf8");
  const raw = Buffer.from(b64.trim(), "base64");
  const decipher = crypto.createDecipheriv("aes-256-cbc", aesKey, iv);
  return Buffer.concat([decipher.update(raw), decipher.final()]).toString("utf8");
}

async function apiCall(apiRoot, path, method = "GET", form = null) {
  const prefix = randomAlnum(15);
  const cfId = prefix + sessionDeviceKey;
  const headers = {
    "User-Agent": APP_UA,
    Accept: "application/json, text/plain, */*",
    "cloudflare-id": cfId,
    firebase_id: buildSecureToken(),
  };
  let body;
  if (form) {
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    body = new URLSearchParams(form).toString();
  }
  const resp = await fetch(`${apiRoot}/${path.replace(/^\//, "")}`, {
    method,
    headers,
    body,
    signal: AbortSignal.timeout(15000),
  });
  const text = (await resp.text()).trim();
  if (!resp.ok) return { httpStatus: resp.status, error: text.slice(0, 200) };

  if (!text.startsWith("{") && !text.startsWith("[")) {
    const dec = decryptResponse(text, sessionDeviceKey).trim();
    return { decrypted: true, data: JSON.parse(dec) };
  }
  const parsed = JSON.parse(text);
  if (parsed && parsed.encrypted && typeof parsed.data === "string") {
    const dec = decryptResponse(parsed.data, sessionDeviceKey).trim();
    return { decrypted: true, data: JSON.parse(dec) };
  }
  return { decrypted: false, data: parsed };
}

function parseMp4TkhdHeight(buf) {
  let best = 0;
  for (let i = 4; i <= buf.length - 88; i++) {
    if (buf[i] === 0x74 && buf[i + 1] === 0x6b && buf[i + 2] === 0x68 && buf[i + 3] === 0x64) {
      const ver = buf[i + 4];
      const wOff = ver === 1 ? i + 92 : i + 80;
      if (wOff + 8 <= buf.length) {
        const w = buf.readUInt16BE(wOff);
        const h = buf.readUInt16BE(wOff + 4);
        if (w >= 120 && w <= 7680 && h >= 120 && h <= 4320) {
          best = Math.max(best, Math.min(w, h));
        }
      }
    }
  }
  return best || null;
}

function sanitizeUrl(raw) {
  return encodeURI(String(raw || "").trim());
}

async function probeServerUrl(rawUrl, phpEndpoints) {
  const url = sanitizeUrl(rawUrl);
  let host = "unknown";
  try {
    host = new URL(url).hostname.replace(/^www\./, "").toLowerCase();
  } catch (e) {
    return { url: rawUrl.slice(0, 120), host: "invalid_url", status: `err:${e.message}` };
  }
  const report = { url: url.slice(0, 120), host, status: "untested" };

  try {
    if (
      host.includes("googleusercontent.com") ||
      host.includes("photos.google.com") ||
      url.toLowerCase().includes("googlefinal") ||
      url.toLowerCase().includes("hrrejhp")
    ) {
      let base = (url.match(/https:\/\/lh3\.googleusercontent\.com\/pw\/[A-Za-z0-9_-]{16,}/) || [])[0];
      if (!base) {
        const wrap = url.match(/[?&](?:url|u|src|link)=([^&#]+)/i);
        const target = wrap ? decodeURIComponent(wrap[1]) : url;
        base = (target.match(/https:\/\/lh3\.googleusercontent\.com\/pw\/[A-Za-z0-9_-]{16,}/) || [])[0];
        if (!base) {
          const r = await fetch(target, {
            headers: { "User-Agent": GPHOTO_UA },
            signal: AbortSignal.timeout(10000),
          });
          const html = (await r.text()).replace(/\\u003d/g, "=").replace(/\\\//g, "/");
          base = (html.match(/https:\/\/lh3\.googleusercontent\.com\/pw\/[A-Za-z0-9_-]{16,}/) || [])[0];
        }
      }
      if (!base) {
        report.status = "gphoto_no_base";
        return report;
      }
      const qualities = [];
      for (const suf of ["=m37", "=m22", "=m18"]) {
        const pr = await fetch(base + suf, {
          headers: {
            "User-Agent": GPHOTO_UA,
            Origin: "https://photos.google.com",
            Referer: "https://photos.google.com/",
            Range: "bytes=0-131071",
          },
          signal: AbortSignal.timeout(10000),
        });
        if (pr.status === 200 || pr.status === 206) {
          const ct = pr.headers.get("content-type") || "";
          const buf = Buffer.from(await pr.arrayBuffer());
          const h = parseMp4TkhdHeight(buf);
          if (ct.includes("video") || ct.includes("octet-stream")) {
            qualities.push(`${suf}:${pr.status}:${ct}:${h || "?"}p`);
          }
        }
      }
      if (qualities.length === 0) {
        const dashUrl = base + "=mm,dash-vm-vf,dr.sdr,sdrCodec.vp9.h264?alr=true&mpd_version=5&pacing=0";
        const dr = await fetch(dashUrl, {
          headers: {
            "User-Agent": GPHOTO_UA,
            Origin: "https://photos.google.com",
            Referer: "https://photos.google.com/",
          },
          signal: AbortSignal.timeout(10000),
        });
        const dt = await dr.text();
        if (dr.ok && dt.includes("<MPD")) qualities.push(`DASH:${dr.status}:MPD`);
      }
      report.status = qualities.length > 0 ? "ok_gphoto" : "gphoto_empty";
      report.qualities = qualities;
      return report;
    }

    if (host.includes("upns.") || host.includes("p2pstream.")) {
      const id = url.split("#")[1]?.split("?")[0]?.trim();
      const origin = new URL(url).origin;
      const r = await fetch(`${origin}/api/v1/video?id=${id}`, {
        headers: { "User-Agent": DESKTOP_UA, Referer: `${origin}/`, Origin: origin },
        signal: AbortSignal.timeout(10000),
      });
      const hex = (await r.text()).trim().replace(/^"|"$/g, "");
      const dc = crypto.createDecipheriv("aes-128-cbc", Buffer.from("kiemtienmua911ca"), Buffer.from("1234567890oiuytr"));
      const dec = JSON.parse(Buffer.concat([dc.update(Buffer.from(hex, "hex")), dc.final()]).toString("utf8"));
      report.status = dec.cfNative || dec.source ? "ok_upnshare" : "upnshare_empty";
      report.qualities = [dec.cfNative ? "cfNative_hls" : null, dec.source ? "source_hls" : null].filter(Boolean);
      return report;
    }

    if (host.includes("vidara")) {
      const m = url.match(/\/(?:e|d|v|embed)\/([A-Za-z0-9_-]+)/i);
      const origin = new URL(url).origin;
      if (m) {
        const r = await fetch(`${origin}/api/stream`, {
          method: "POST",
          headers: { "User-Agent": DESKTOP_UA, "Content-Type": "application/json", Referer: `${origin}/e/${m[1]}`, Origin: origin },
          body: JSON.stringify({ filecode: m[1], device: "web" }),
          signal: AbortSignal.timeout(10000),
        });
        const j = await r.json();
        report.status = j.streaming_url ? "ok_vidara" : `vidara_http_${r.status}`;
        report.qualities = j.streaming_url ? [j.title || "hls"] : [];
        return report;
      }
    }

    if (/\.(mp4|mkv|m3u8|mpd)(\?|$)/i.test(url) && !host.includes("byse") && !host.includes("streamtape")) {
      const r = await fetch(url, {
        headers: { "User-Agent": DESKTOP_UA, Range: "bytes=0-1023" },
        signal: AbortSignal.timeout(10000),
      });
      report.status = r.status === 200 || r.status === 206 ? `ok_direct_${r.status}` : `direct_http_${r.status}`;
      return report;
    }

    if (host.includes("developer-pro.workers.dev")) {
      const r = await fetch(url, { headers: { "User-Agent": DESKTOP_UA }, signal: AbortSignal.timeout(10000) });
      const j = await r.json();
      const q = (j.availableQualities || j.sources || j.links || []).map((x) => x.quality || x.label || x.name);
      report.status = q.length > 0 ? "ok_worker" : "worker_empty";
      report.qualities = q;
      return report;
    }

    let r = await fetch(url, {
      headers: { "User-Agent": DESKTOP_UA, Referer: "https://www.anime-day.com/" },
      signal: AbortSignal.timeout(10000),
    });
    let html = await r.text();
    if (html.includes("Video embed restricted") || html.length < 180) {
      const r2 = await fetch(url, {
        headers: { "User-Agent": DESKTOP_UA, Referer: "https://tuktukcima.com/" },
        signal: AbortSignal.timeout(10000),
      });
      const h2 = await r2.text();
      if (h2.length > 250 && !h2.includes("Video embed restricted")) {
        r = r2;
        html = h2;
      }
    }
    const hasPacker = html.includes("eval(function(p,a,c,k,e,");
    const hasM3u8 = /\.m3u8|master\.txt/i.test(html);
    const hasMp4 = /\.mp4/i.test(html);
    const hasEnc = /enc:[A-Za-z0-9+/=]{20,}/.test(html);
    const hasDataOptions = /data-options=/i.test(html);

    let phpCount = 0;
    if (host.includes("vidtube") || (!hasPacker && !hasM3u8 && !hasMp4 && !hasDataOptions)) {
      for (const ep of phpEndpoints) {
        if (!ep) continue;
        try {
          const pr = await fetch(`${ep}?url=${encodeURIComponent(url)}`, {
            headers: { "User-Agent": APP_UA },
            signal: AbortSignal.timeout(10000),
          });
          const pj = await pr.json();
          const arr = pj.servers || pj.links || pj.sources || [];
          if (Array.isArray(arr) && arr.length > 0) {
            phpCount = arr.length;
            break;
          }
        } catch {}
      }
    }

    report.status = `http_${r.status}`;
    try {
      report.finalHost = new URL(r.url).hostname;
    } catch {}
    report.flags = { hasPacker, hasM3u8, hasMp4, hasEnc, hasDataOptions, phpCount };
  } catch (e) {
    report.status = `err:${String(e.message || e).slice(0, 80)}`;
  }
  return report;
}

async function main() {
  const out = { timestamp: new Date().toISOString(), config: null, catalog: {}, search: {}, titles: [], fatalError: null };

  try {
    const cfgRes = await fetch("https://backup-animeday.animegateshorts.workers.dev/", {
      headers: { "User-Agent": APP_UA },
      signal: AbortSignal.timeout(15000),
    });
    const rawCfg = await cfgRes.json();
    const entryCfg = (Array.isArray(rawCfg.data) ? rawCfg.data[0] : rawCfg.data) || rawCfg || {};
    out.config = entryCfg;
    const cloudday = String(entryCfg.cloudday || "https://20042026.site").replace(/\/$/, "");
    const apiRoot = cloudday.includes("/api") ? cloudday : `${cloudday}/v3.8/api`;
    const phpEndpoints = [
      entryCfg.extractorUrl || "https://cloud-day.online/cimacloud/extractor.php",
      entryCfg.htmlExtractorUrl || "https://cloud-day.online/cimacloud/html_extractor.php",
      entryCfg.advancedExtractorUrl || "https://cloud-day.online/cimacloud/advanced_extractor.php",
    ];

    for (const path of [
      "home",
      "section/2?page=2",
      "section/3?page=2",
      "section/5?page=2",
      "section/4?page=2",
      "section/6?page=2",
      "series?page=2",
      "movies?page=2",
    ]) {
      try {
        const res = await apiCall(apiRoot, path);
        const d = res.data || {};
        const arr = d.sections || d.section_items || d.results || [];
        out.catalog[path] = {
          count: Array.isArray(arr) ? arr.length : -1,
          has_more: d.has_more ?? null,
          blocked: d.blocked ?? false,
        };
      } catch (e) {
        out.catalog[path] = { error: String(e.message || e) };
      }
    }

    for (const q of ["فريرن", "كونان", "Hunter", "Avatar"]) {
      try {
        const res = await apiCall(apiRoot, "search", "POST", { title: q, type: "0", sort: "1", page: "1" });
        const d = res.data || {};
        const arr = d.results || d.items || [];
        out.search[q] = {
          count: arr.length,
          first3: arr.slice(0, 3).map((x) => ({ id: x.id, type: x.type, name: x.name, dub: x.dub })),
        };
      } catch (e) {
        out.search[q] = { error: String(e.message || e) };
      }
    }

    const testItems = [
      { kind: "series", id: "12819", note: "Frieren (Dubbed Anime Series)" },
      { kind: "series", id: "12815", note: "Hunter x Hunter (Subbed Anime Series)" },
      { kind: "series", id: "12773", note: "Avatar: Seven Havens (Cartoon Series)" },
      { kind: "series", id: "4561", note: "Detective Conan (Long Dubbed Anime)" },
      { kind: "movie", id: "27070", note: "The Bad Guys 2 (Dubbed Cartoon Movie)" },
      { kind: "movie", id: "27232", note: "Mononoke Movie III (Subbed Anime Movie)" },
      { kind: "movie", id: "27226", note: "Detective Conan Movie (Subbed Anime Movie)" },
    ];

    const allHosts = {};

    for (const t of testItems) {
      const entry = { ...t };
      try {
        const detail = await apiCall(apiRoot, `${t.kind}/${t.id}`);
        const dd = (!Array.isArray(detail.data?.data) && detail.data?.data) || detail.data || {};
        const obj = dd.series || dd.movie || dd;
        entry.detailOk = !detail.data?.blocked && Boolean(obj?.name || obj?.title);
        entry.name = obj?.name || obj?.title || null;
        entry.dub = obj?.dub;
        entry.year = obj?.year || obj?.release_date;
        entry.genres = (obj?.genres || []).map((g) => g.name || g.title);
        entry.recommendationsCount = (obj?.recommendations || dd.recommendations || []).length;
        entry.vote = obj?.vote_average;
        entry.detailDecrypted = detail.decrypted;

        let serversPath = null;
        if (t.kind === "series") {
          const epsRes = await apiCall(apiRoot, `series/${t.id}/episodes`);
          const seasons = epsRes.data?.seasons || [];
          let totalEps = 0;
          let firstEp = null;
          let fillerCount = 0;
          for (const s of seasons) {
            for (const e of s.episodes || []) {
              totalEps++;
              if (e.filer || e.filler) fillerCount++;
              if (!firstEp) firstEp = e;
            }
          }
          entry.seasons = seasons.length;
          entry.totalEpisodes = totalEps;
          entry.fillerEpisodes = fillerCount;
          entry.sampleEp = firstEp
            ? { id: firstEp.id, title: firstEp.title, name: firstEp.name, filer: firstEp.filer }
            : null;
          if (firstEp) serversPath = `episode/${firstEp.id}/servers`;
        } else {
          serversPath = `movie/${t.id}/servers`;
        }

        if (serversPath) {
          const srvRes = await apiCall(apiRoot, serversPath);
          const sd = srvRes.data || {};
          const srvArr = Array.isArray(sd) ? sd : sd.servers || sd.items || sd.data || [];
          entry.serversDecrypted = srvRes.decrypted;
          entry.serverCount = srvArr.length;
          entry.rawServerSample = srvArr.slice(0, 3);

          const urls = srvArr
            .map((s) => (typeof s === "string" ? s : s.link || s.url || ""))
            .filter((u) => String(u).startsWith("http"));

          for (const u of urls) {
            try {
              const h = new URL(sanitizeUrl(u)).hostname.replace(/^www\./, "").toLowerCase();
              allHosts[h] = (allHosts[h] || 0) + 1;
            } catch {}
          }

          entry.probedServers = await Promise.all(urls.map((u) => probeServerUrl(u, phpEndpoints)));
        }
      } catch (e) {
        entry.error = String(e.stack || e).slice(0, 300);
      }
      out.titles.push(entry);
    }

    out.hostHistogram = allHosts;
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("animeday_e2e_report.json", JSON.stringify(out, null, 2));
  console.log("Wrote animeday_e2e_report.json successfully.");
}

main();
