import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import fs from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const APP_USER_AGENT = "okhttp/4.10.0";
const DEFAULT_API_BASE = "https://1654865.xyz";
const API_VERSION_PATH = "/v1.3/api";
const CONFIG_WORKER_URLS = [
  "https://config-backup2.animegateshorts.workers.dev/",
  "https://api-config.animegateshorts.workers.dev/",
];
const CERT_SHA256 = "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7";
const HMAC_SECRET = "97a54e5dec15b4f1754abc5939643bbe57a0d8ae8db88529e268289457728002";
const DECRYPT_SALT = "Gm4il@G00Gl3.Com";
const ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
const RSA_PUB_SPKI_B64 =
  "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6+BtHI/" +
  "Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3QCh65Nbi4mKRFFJyDk" +
  "JVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9FtgJkuz17n265rLB6ruUUrHQVeEDl8" +
  "ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvsq4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IE" +
  "YV47OyddG32qbriJbxdvW+n5OzowiYQrsC8hG65GPBGCuj3zO7wIDAQAB";

const publicKey = crypto.createPublicKey({
  key: Buffer.from(RSA_PUB_SPKI_B64, "base64"),
  format: "der",
  type: "spki",
});

// Match the transport used by the historical authenticated probe (Python urllib)
// instead of relying on Node fetch defaults such as compression negotiation.
const PYTHON_GET = String.raw`
import json, sys, urllib.error, urllib.request
args = json.load(sys.stdin)
request = urllib.request.Request(args["url"], headers=args["headers"], method="GET")
try:
    with urllib.request.urlopen(request, timeout=args["timeout"]) as response:
        result = {"status": response.status, "contentType": response.headers.get("content-type", ""), "body": response.read().decode("utf-8", "replace")}
except urllib.error.HTTPError as error:
    result = {"status": error.code, "contentType": error.headers.get("content-type", "") if error.headers else "", "body": error.read(2_000_000).decode("utf-8", "replace")}
except Exception as error:
    result = {"error": str(error)}
sys.stdout.write(json.dumps(result))
`;

function md5Hex(value) {
  return crypto.createHash("md5").update(value, "utf8").digest("hex");
}

function hmacSha256Hex(secret, value) {
  return crypto.createHmac("sha256", Buffer.from(secret, "utf8")).update(value, "utf8").digest("hex");
}

function randomAlnum(length) {
  let result = "";
  for (let i = 0; i < length; i += 1) result += ALPHANUM[crypto.randomInt(ALPHANUM.length)];
  return result;
}

function makeCloudflarePair() {
  const deviceKey = crypto.randomBytes(8).toString("hex");
  return { cloudflareId: `${randomAlnum(15)}${deviceKey}`, deviceKey };
}

function buildSecureToken(nowSeconds = Math.floor(Date.now() / 1000)) {
  const staticMac = hmacSha256Hex(HMAC_SECRET, `err|${CERT_SHA256}|000|err_open`);
  const payload = JSON.stringify({
    a: "err",
    b: `${CERT_SHA256}|/data/app/com.app.cimacloud/base.apk`,
    c: "false",
    d: "empty",
    e: "empty",
    f: "false",
    g: "false",
    h: "false",
    i: "false",
    j: "false",
    r: "false",
    s: "err_open",
    m: staticMac,
    t: nowSeconds,
    n: crypto.randomBytes(8).toString("hex"),
  });

  const aesKey = crypto.randomBytes(32);
  const iv = crypto.randomBytes(12);
  const gcm = crypto.createCipheriv("aes-256-gcm", aesKey, iv);
  const sealed = Buffer.concat([gcm.update(payload, "utf8"), gcm.final(), gcm.getAuthTag()]);
  const encryptedKey = crypto.publicEncrypt(
    { key: publicKey, padding: crypto.constants.RSA_PKCS1_PADDING },
    aesKey,
  );

  return [encryptedKey, iv, sealed].map((part) => part.toString("base64")).join(".");
}

function decryptResponse(encryptedBase64, deviceKey) {
  const aesKey = Buffer.from(md5Hex(DECRYPT_SALT + deviceKey), "utf8");
  const iv = Buffer.from(md5Hex(deviceKey + DECRYPT_SALT).slice(0, 16), "utf8");
  const decipher = crypto.createDecipheriv("aes-256-cbc", aesKey, iv);
  return Buffer.concat([
    decipher.update(Buffer.from(encryptedBase64.trim(), "base64")),
    decipher.final(),
  ]).toString("utf8");
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function decodeApiBody(rawBody, deviceKey) {
  const body = rawBody.trim();
  let root = parseJson(body);

  if (Array.isArray(root)) return { items: root };
  if (root && typeof root === "object") {
    if (root.encrypted === true && typeof root.data === "string" && root.data.trim()) {
      root = parseJson(decryptResponse(root.data, deviceKey));
    }
    if (!root || typeof root !== "object" || Array.isArray(root)) {
      throw new Error("decrypted API envelope was not a JSON object");
    }
    return root;
  }

  root = parseJson(decryptResponse(body, deviceKey));
  if (Array.isArray(root)) return { items: root };
  if (!root || typeof root !== "object") throw new Error("response was neither JSON nor decryptable ciphertext");
  return root;
}

function getServerLinks(root) {
  const values = Array.isArray(root?.servers)
    ? root.servers
    : Array.isArray(root?.items)
      ? root.items
      : Array.isArray(root?.data)
        ? root.data
        : [];
  return values
    .map((item) => (typeof item === "string" ? item : item?.link || item?.url || ""))
    .filter((url) => /^https?:\/\//i.test(url));
}

function pluginResponseView(root) {
  if (!root || typeof root !== "object" || Array.isArray(root) || root.blocked === true) return {};
  return root.data && typeof root.data === "object" && !Array.isArray(root.data)
    ? root.data
    : root;
}

function hostOf(link) {
  try {
    return new URL(link).hostname.toLowerCase().replace(/^www\./, "");
  } catch {
    return null;
  }
}

async function getApiRoot() {
  for (const workerUrl of CONFIG_WORKER_URLS) {
    try {
      const response = await fetch(workerUrl, {
        headers: { "User-Agent": APP_USER_AGENT, Accept: "application/json" },
        signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) continue;
      const config = await response.json();
      const entry = Array.isArray(config.data)
        ? config.data[0]
        : config.data && typeof config.data === "object"
          ? config.data
          : config;
      const host = String(entry?.cloudday || "").trim().replace(/\/+$/, "");
      if (!/^https?:\/\//i.test(host)) continue;
      return host.endsWith(API_VERSION_PATH) ? host : `${host}${API_VERSION_PATH}`;
    } catch {
      // Try the backup worker, then the verified default.
    }
  }
  return `${DEFAULT_API_BASE}${API_VERSION_PATH}`;
}

async function requestServerList(apiRoot, label, path) {
  const { cloudflareId, deviceKey } = makeCloudflarePair();
  const url = `${apiRoot.replace(/\/+$/, "")}/${path.replace(/^\/+/, "")}`;
  const headers = {
    "User-Agent": APP_USER_AGENT,
    Accept: "application/json, text/plain, */*",
    "cloudflare-id": cloudflareId,
    firebase_id: buildSecureToken(),
  };
  const request = spawnSync(
    "python3",
    ["-c", PYTHON_GET],
    {
      input: JSON.stringify({ url, headers, timeout: 22 }),
      encoding: "utf8",
      timeout: 25_000,
      maxBuffer: 2_500_000,
    },
  );
  if (request.error) throw request.error;
  if (request.status !== 0) throw new Error(request.stderr || `Python HTTP helper exited with ${request.status}`);
  const response = JSON.parse(request.stdout);
  if (response.error) throw new Error(response.error);
  const rawBody = response.body;
  let root;
  let decodeError = null;
  try {
    root = decodeApiBody(rawBody, deviceKey);
  } catch (error) {
    decodeError = error instanceof Error ? error.message : String(error);
    root = {};
  }

  const pluginView = pluginResponseView(root);
  const links = getServerLinks(pluginView);
  const nestedServers = root.data && !Array.isArray(root.data) && Array.isArray(root.data.servers)
    ? root.data.servers
    : [];
  const genericPluginCount = getServerLinks(pluginView).length;

  return {
    label,
    path,
    httpStatus: response.status,
    contentType: response.contentType || "",
    rawLength: Buffer.byteLength(rawBody),
    rawFormat: rawBody.trim().startsWith("{")
      ? "json-object"
      : rawBody.trim().startsWith("[")
        ? "json-array"
        : "encrypted-or-text",
    apiStatus: root.status ?? null,
    blocked: root.blocked ?? false,
    auth: root.auth ?? null,
    rootKeys: Object.keys(root),
    topLevelServerCount: Array.isArray(root.servers) ? root.servers.length : 0,
    acceptedLinkCount: links.length,
    nestedDataServerCount: nestedServers.length,
    pluginVisibleServerCount: genericPluginCount,
    topLevelServersWouldBeLostByGenericUnwrap:
      Boolean(Array.isArray(root.servers) && root.servers.length > 0 && genericPluginCount === 0),
    serverHosts: [...new Set(links.map(hostOf).filter(Boolean))].sort(),
    decodeError,
  };
}

async function main() {
  const apiRoot = await getApiRoot();
  const episodeId = process.env.CIMA_EPISODE_ID?.trim() || "384876";
  const referenceEpisodeId = process.env.CIMA_REFERENCE_EPISODE_ID?.trim() || "370096";
  const movieId = process.env.CIMA_MOVIE_ID?.trim() || "27295";
  const targets = [
    { label: "reported episode", path: `episode/${episodeId}/servers` },
    { label: "known episode reference", path: `episode/${referenceEpisodeId}/servers` },
    { label: "known movie reference", path: `movie/${movieId}/servers` },
  ].filter((target, index, all) => all.findIndex((other) => other.path === target.path) === index);

  console.log(`CimaCloud API root: ${apiRoot}`);
  const results = [];
  for (const target of targets) {
    try {
      const result = await requestServerList(apiRoot, target.label, target.path);
      results.push(result);
      console.log(JSON.stringify(result));
    } catch (error) {
      const result = {
        label: target.label,
        path: target.path,
        requestError: error instanceof Error ? error.message : String(error),
      };
      results.push(result);
      console.log(JSON.stringify(result));
    }
  }

  const notice = JSON.stringify(
    results.map((result) => ({
      label: result.label,
      path: result.path,
      httpStatus: result.httpStatus,
      apiStatus: result.apiStatus,
      blocked: result.blocked,
      auth: result.auth,
      rootKeys: result.rootKeys,
      topLevelServerCount: result.topLevelServerCount,
      acceptedLinkCount: result.acceptedLinkCount,
      nestedDataServerCount: result.nestedDataServerCount,
      pluginVisibleServerCount: result.pluginVisibleServerCount,
      topLevelServersWouldBeLostByGenericUnwrap: result.topLevelServersWouldBeLostByGenericUnwrap,
      serverHosts: result.serverHosts,
      decodeError: result.decodeError,
      requestError: result.requestError,
    })),
  )
    .replaceAll("%", "%25")
    .replaceAll("\r", "%0D")
    .replaceAll("\n", "%0A");
  console.log(`::notice title=CimaCloud authenticated API response summary::${notice}`);

  if (process.env.GITHUB_STEP_SUMMARY) {
    const lines = [
      "## CimaCloud server-list API smoke test",
      "",
      `API root: \`${apiRoot}\``,
      "",
      "| Target | HTTP | API status | Blocked | Top-level servers | Parsed links | Decode error |",
      "|---|---:|---|---|---:|---:|---|",
      ...results.map((result) =>
        `| ${result.label} (\`${result.path}\`) | ${result.httpStatus ?? "—"} | ${result.apiStatus ?? "—"} | ${result.blocked ?? "—"} | ${result.topLevelServerCount ?? "—"} | ${result.acceptedLinkCount ?? "—"} | ${result.decodeError ?? result.requestError ?? "—"} |`,
      ),
      "",
      "Only response shape, server counts, and hostnames are logged; full embed/media URLs and auth headers are omitted.",
    ];
    await fs.appendFile(process.env.GITHUB_STEP_SUMMARY, `${lines.join("\n")}\n`);
  }

  if (results.every((result) => !result.acceptedLinkCount)) {
    console.error("No test target returned any parsed server links. Check the response status/blocked/decode fields above.");
    process.exitCode = 1;
  }
}

const invokedDirectly = process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1]);
if (invokedDirectly) main().catch((error) => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});

export { buildSecureToken, decodeApiBody, decryptResponse, getServerLinks, makeCloudflarePair, pluginResponseView };
