import assert from "node:assert/strict";
import crypto from "node:crypto";
import test from "node:test";
import {
  buildSecureToken,
  decodeApiBody,
  decryptResponse,
  getServerLinks,
  makeCloudflarePair,
  pluginResponseView,
} from "./cimacloud-live-smoke.mjs";

const SALT = "Gm4il@G00Gl3.Com";

function encryptResponse(plaintext, deviceKey) {
  const md5Hex = (value) => crypto.createHash("md5").update(value, "utf8").digest("hex");
  const aesKey = Buffer.from(md5Hex(SALT + deviceKey), "utf8");
  const iv = Buffer.from(md5Hex(deviceKey + SALT).slice(0, 16), "utf8");
  const cipher = crypto.createCipheriv("aes-256-cbc", aesKey, iv);
  return Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]).toString("base64");
}

test("secure-token envelope has the expected three-part wire format", () => {
  const token = buildSecureToken(1_793_000_000);
  const [encryptedKey, iv, ciphertextAndTag] = token.split(".");

  assert.equal(token.split(".").length, 3);
  assert.equal(Buffer.from(encryptedKey, "base64").length, 256);
  assert.equal(Buffer.from(iv, "base64").length, 12);
  assert(Buffer.from(ciphertextAndTag, "base64").length > 16);
});

test("cloudflare-id is a 15-character prefix plus a stable-format device key", () => {
  const pair = makeCloudflarePair();
  assert.match(pair.cloudflareId, /^[A-Za-z0-9]{15}[a-f0-9]{16}$/);
  assert.equal(pair.cloudflareId.slice(15), pair.deviceKey);
});

test("raw Base64 response decrypts and exposes a top-level servers array", () => {
  const deviceKey = "0123456789abcdef";
  const payload = JSON.stringify({
    status: true,
    auth: false,
    data: [],
    servers: [{ id: 1, type: "embed", link: "https://video.example/embed/abc" }],
  });
  const root = decodeApiBody(encryptResponse(payload, deviceKey), deviceKey);

  assert.equal(root.status, true);
  assert.deepEqual(getServerLinks(pluginResponseView(root)), ["https://video.example/embed/abc"]);
});

test("plain response with an object-valued data field follows apiGet unwrap semantics", () => {
  const root = decodeApiBody(JSON.stringify({
    status: true,
    data: { servers: [{ url: "https://video.example/embed/xyz" }] },
  }), "0123456789abcdef");

  assert.deepEqual(getServerLinks(pluginResponseView(root)), ["https://video.example/embed/xyz"]);
});

test("blocked response is not exposed as a playable server list", () => {
  const root = decodeApiBody(JSON.stringify({ status: false, blocked: true, data: [] }), "0123456789abcdef");
  assert.deepEqual(getServerLinks(pluginResponseView(root)), []);
});

test("encrypted JSON envelope is decrypted before schema parsing", () => {
  const deviceKey = "fedcba9876543210";
  const encryptedData = encryptResponse(JSON.stringify({
    status: true,
    servers: [{ link: "https://video.example/envelope" }],
  }), deviceKey);
  const root = decodeApiBody(JSON.stringify({ encrypted: true, data: encryptedData }), deviceKey);

  assert.equal(root.status, true);
  assert.deepEqual(getServerLinks(pluginResponseView(root)), ["https://video.example/envelope"]);
  assert.equal(decryptResponse(encryptedData, deviceKey).includes("video.example"), true);
});
