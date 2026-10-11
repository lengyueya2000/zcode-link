'use strict';

/*
 * ZCode 手机连接助手 —— 本地服务端
 *
 * 零第三方依赖（仅 Node 内置模块）。职责：
 *   1. 托管 public/ 下的静态页面
 *   2. GET  /api/device  读取本机 ZCode 配置，返回当前已注册的远程设备信息
 *   3. GET  /api/links   返回历史连接链接
 *   4. POST /api/links   保存一条链接（自动去重）
 *   5. DELETE /api/links 删除一条链接
 *
 * 安全：默认只监听 127.0.0.1。连接链接里带有 hash 签名，属于敏感凭据，
 * 因此本工具不会把它发往任何外部服务（二维码在浏览器本地生成）。
 */

const http = require('http');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

// ---------------------------------------------------------------- 配置

function readArg(name, fallback) {
  const i = process.argv.indexOf(name);
  if (i !== -1 && process.argv[i + 1]) return process.argv[i + 1];
  const pref = process.argv.find((a) => a.startsWith(name + '='));
  if (pref) return pref.slice(name.length + 1);
  return fallback;
}

const PORT = Number(readArg('--port', process.env.PORT || 8787));
const HOST = readArg('--host', process.env.HOST || '127.0.0.1');
const SHOULD_OPEN = process.argv.includes('--open');

const PUBLIC_DIR = path.join(__dirname, 'public');
const DATA_DIR = path.join(__dirname, 'data');
const STORE_PATH = path.join(DATA_DIR, 'links.json');
const MAX_LINKS = 50;
const MAX_BODY = 64 * 1024;

// ZCode 配置目录：默认 ~/.zcode，可用 ZCODE_HOME 覆盖
const ZCODE_HOME = process.env.ZCODE_HOME || path.join(os.homedir(), '.zcode');
const SETTING_CANDIDATES = [
  path.join(ZCODE_HOME, 'v2', 'setting.json'),
  path.join(ZCODE_HOME, 'setting.json'),
];

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
};

// ---------------------------------------------------------------- 链接解析

function normalizeTimestamp(raw) {
  if (!raw || !/^\d+$/.test(raw)) return null;
  // 13 位及以上按毫秒，10 位按秒
  if (raw.length >= 12) return { ms: Number(raw), unit: 'ms' };
  if (raw.length === 10) return { ms: Number(raw) * 1000, unit: 's' };
  return { ms: Number(raw), unit: 'raw' };
}

function parseRemoteLink(raw) {
  const input = String(raw == null ? '' : raw).trim();
  if (!input) return { ok: false, error: '链接为空' };

  let url;
  try {
    url = new URL(input);
  } catch (_) {
    return { ok: false, error: '不是合法的 URL（需要以 https:// 开头）' };
  }

  const host = url.hostname.toLowerCase();
  const pathname = url.pathname;
  const pathOk = /^\/remote\/v\d+\/?$/.test(pathname);
  const sp = url.searchParams;

  const sid = sp.get('sid') || '';
  const hash = sp.get('hash') || '';
  const tRaw = sp.get('t') || '';
  const mid = sp.get('mid') || '';
  const name = sp.get('name') || '';
  const appVersion = sp.get('app_version') || '';
  const ts = normalizeTimestamp(tRaw);

  const warnings = [];
  if (host !== 'zcode.z.ai') warnings.push(`域名不是 zcode.z.ai（当前为 ${host}）`);
  if (!pathOk) warnings.push(`路径不是 /remote/vN 形式（当前为 ${pathname}）`);
  if (!sid) warnings.push('缺少 sid');
  if (!hash) warnings.push('缺少 hash');
  if (!tRaw) warnings.push('缺少 t');
  if (!mid) warnings.push('缺少 mid');

  const ok = host === 'zcode.z.ai' && pathOk && !!sid && !!hash;

  return {
    ok,
    error: ok ? null : '缺少必需参数（sid / hash）或域名路径不匹配',
    url: input,
    host,
    path: pathname,
    sid,
    hash,
    hashPreview: hash ? hash.slice(0, 6) + '…' + hash.slice(-4) : '',
    t: ts ? ts.ms : null,
    tUnit: ts ? ts.unit : null,
    mid,
    name,
    appVersion,
    warnings,
  };
}

// ---------------------------------------------------------------- 存储

async function loadStore() {
  try {
    const txt = await fsp.readFile(STORE_PATH, 'utf8');
    const parsed = JSON.parse(txt);
    if (parsed && Array.isArray(parsed.links)) return parsed;
  } catch (_) {
    /* 首次运行或文件损坏，回退为空 */
  }
  return { links: [] };
}

async function saveStore(store) {
  await fsp.mkdir(DATA_DIR, { recursive: true });
  const tmp = STORE_PATH + '.tmp';
  await fsp.writeFile(tmp, JSON.stringify(store, null, 2), { encoding: 'utf8', mode: 0o600 });
  await fsp.rename(tmp, STORE_PATH);
}

function publicLink(entry) {
  // 列表展示时对 hash 做脱敏，完整值仅在加载进详情面板时使用
  return {
    id: entry.id,
    url: entry.url,
    sid: entry.sid,
    hashPreview: entry.hashPreview,
    t: entry.t,
    mid: entry.mid,
    name: entry.name,
    appVersion: entry.appVersion,
    addedAt: entry.addedAt,
    seenCount: entry.seenCount || 1,
  };
}

// ---------------------------------------------------------------- 设备信息

async function readDeviceInfo() {
  const info = {
    hostname: os.hostname(),
    platform: process.platform,
    zcodeHome: ZCODE_HOME,
    settingPath: null,
    settingExists: false,
    settingMtime: null,
    deviceSid: null,
    lastEnabledContext: null,
    recentProjects: [],
    configError: null,
  };

  let settingPath = null;
  for (const p of SETTING_CANDIDATES) {
    try {
      await fsp.access(p, fs.constants.R_OK);
      settingPath = p;
      break;
    } catch (_) {
      /* 继续找下一个 */
    }
  }

  if (!settingPath) {
    info.configError = '未找到 ZCode 配置文件（setting.json）';
    return info;
  }

  info.settingPath = settingPath;
  info.settingExists = true;
  try {
    const stat = await fsp.stat(settingPath);
    info.settingMtime = stat.mtime.toISOString();
    const json = JSON.parse(await fsp.readFile(settingPath, 'utf8'));
    const relay = json && json.webRemoteControlExternalRelayDevice;
    info.deviceSid = (relay && relay.deviceSid) || null;
    info.lastEnabledContext = (json && json.webRemoteControlLastEnabledContext) || null;
    info.recentProjects = Array.isArray(json && json.recentProjects) ? json.recentProjects : [];
  } catch (err) {
    info.configError = '读取 ZCode 配置失败：' + err.message;
  }
  return info;
}

// ---------------------------------------------------------------- HTTP 工具

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) {
        reject(new Error('请求体过大'));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

async function serveStatic(req, res, urlPath) {
  let rel = decodeURIComponent(urlPath);
  if (rel === '/' || rel === '') rel = '/index.html';
  const target = path.join(PUBLIC_DIR, rel);
  const resolved = path.resolve(target);
  if (!resolved.startsWith(path.resolve(PUBLIC_DIR))) {
    return sendJson(res, 403, { ok: false, error: 'forbidden' });
  }
  try {
    const stat = await fsp.stat(resolved);
    if (stat.isDirectory()) return sendJson(res, 404, { ok: false, error: 'not found' });
    const ext = path.extname(resolved).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': stat.size,
      'Cache-Control': 'no-cache',
    });
    fs.createReadStream(resolved).pipe(res);
  } catch (_) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('404 Not Found');
  }
}

// ---------------------------------------------------------------- 路由

async function handleApi(req, res, url) {
  const route = url.pathname;

  if (route === '/api/health' && req.method === 'GET') {
    return sendJson(res, 200, { ok: true, name: 'zcode-mobile-link', now: new Date().toISOString() });
  }

  if (route === '/api/device' && req.method === 'GET') {
    const info = await readDeviceInfo();
    return sendJson(res, 200, { ok: true, ...info });
  }

  if (route === '/api/parse' && req.method === 'POST') {
    const body = await readBody(req);
    let payload = {};
    try {
      payload = JSON.parse(body || '{}');
    } catch (_) {
      return sendJson(res, 400, { ok: false, error: '请求体不是合法 JSON' });
    }
    return sendJson(res, 200, parseRemoteLink(payload.url));
  }

  if (route === '/api/links' && req.method === 'GET') {
    const store = await loadStore();
    return sendJson(res, 200, { ok: true, links: store.links.map(publicLink) });
  }

  if (route === '/api/links' && req.method === 'POST') {
    const body = await readBody(req);
    let payload = {};
    try {
      payload = JSON.parse(body || '{}');
    } catch (_) {
      return sendJson(res, 400, { ok: false, error: '请求体不是合法 JSON' });
    }
    const parsed = parseRemoteLink(payload.url);
    if (!parsed.ok) {
      return sendJson(res, 400, { ok: false, error: parsed.error || '链接无效', parsed });
    }
    const store = await loadStore();
    const existing = store.links.find((l) => l.url === parsed.url);
    let entry;
    if (existing) {
      existing.seenCount = (existing.seenCount || 1) + 1;
      existing.addedAt = new Date().toISOString();
      entry = existing;
    } else {
      entry = {
        id: crypto.randomUUID(),
        url: parsed.url,
        sid: parsed.sid,
        hash: parsed.hash,
        hashPreview: parsed.hashPreview,
        t: parsed.t,
        mid: parsed.mid,
        name: parsed.name,
        appVersion: parsed.appVersion,
        addedAt: new Date().toISOString(),
        seenCount: 1,
      };
      store.links.unshift(entry);
      if (store.links.length > MAX_LINKS) store.links.length = MAX_LINKS;
    }
    await saveStore(store);
    return sendJson(res, 200, { ok: true, link: publicLink(entry), links: store.links.map(publicLink) });
  }

  if (route === '/api/links' && req.method === 'DELETE') {
    const id = url.searchParams.get('id');
    if (!id) return sendJson(res, 400, { ok: false, error: '缺少 id' });
    const store = await loadStore();
    const before = store.links.length;
    store.links = store.links.filter((l) => l.id !== id);
    if (store.links.length === before) return sendJson(res, 404, { ok: false, error: '未找到该记录' });
    await saveStore(store);
    return sendJson(res, 200, { ok: true, links: store.links.map(publicLink) });
  }

  return sendJson(res, 404, { ok: false, error: 'unknown api route' });
}

const server = http.createServer(async (req, res) => {
  let url;
  try {
    url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  } catch (_) {
    return sendJson(res, 400, { ok: false, error: 'bad request url' });
  }

  if (url.pathname.startsWith('/api/')) {
    try {
      return await handleApi(req, res, url);
    } catch (err) {
      return sendJson(res, 500, { ok: false, error: err.message });
    }
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return sendJson(res, 405, { ok: false, error: 'method not allowed' });
  }
  return serveStatic(req, res, url.pathname);
});

server.listen(PORT, HOST, () => {
  const shown = HOST === '0.0.0.0' ? '127.0.0.1' : HOST;
  const link = `http://${shown}:${PORT}/`;
  console.log('');
  console.log('  ZCode 手机连接助手已启动');
  console.log('  ----------------------------------------');
  console.log(`  本机访问: ${link}`);
  console.log(`  ZCode 配置: ${ZCODE_HOME}`);
  console.log(`  数据文件: ${STORE_PATH}`);
  if (HOST === '0.0.0.0') {
    console.log('  注意: 已监听 0.0.0.0，局域网内其他设备也能访问此页面。');
  }
  console.log('  按 Ctrl+C 停止');
  console.log('');

  if (SHOULD_OPEN) {
    const cmd =
      process.platform === 'win32' ? `start "" "${link}"` : process.platform === 'darwin' ? `open "${link}"` : `xdg-open "${link}"`;
    require('child_process').exec(cmd, () => {});
  }
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`端口 ${PORT} 已被占用，请用 --port <其他端口> 重新启动。`);
  } else {
    console.error('服务启动失败：', err.message);
  }
  process.exit(1);
});
