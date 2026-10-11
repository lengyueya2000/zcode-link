// 解析 Electron app.asar，列出与远程控制相关的文件（只读，不修改原文件）
const fs = require('fs');
const path = require('path');

const asar = process.argv[2];
const fd = fs.openSync(asar, 'r');

// asar 头部布局（实测）：
//   [0..3]   = 4
//   [4..7]   = 外层 pickle 字节数 outerPickleSize
//   [8..11]  = outer pickle payload 大小
//   [12..15] = 头部 JSON 字符串长度 jsonLen
//   [16..]   = JSON
// 文件数据起点 = 8 + outerPickleSize
const head16 = Buffer.alloc(16);
fs.readSync(fd, head16, 0, 16, 0);
const outerPickleSize = head16.readUInt32LE(4);
const jsonLen = head16.readUInt32LE(12);

const headerJsonBuf = Buffer.alloc(jsonLen);
fs.readSync(fd, headerJsonBuf, 0, jsonLen, 16);
const headerJson = headerJsonBuf.toString('utf8');

let header;
try {
  header = JSON.parse(headerJson);
} catch (e) {
  console.error('header parse failed:', e.message, 'jsonLen=', jsonLen);
  process.exit(1);
}
const dataOffset = 8 + outerPickleSize;

const hits = [];
function walk(node, prefix) {
  if (!node.files) {
    if (node.size != null) hits.push({ p: prefix, size: node.size, off: Number(node.offset) });
    return;
  }
  for (const [name, child] of Object.entries(node.files)) walk(child, prefix + '/' + name);
}
walk(header, '');

console.log('total files in asar:', hits.length);
const named = hits.filter((h) => /remote|relay|pairing/i.test(h.p)).sort((a, b) => b.size - a.size);
console.log('files with remote/relay in name:', named.length);
for (const h of named.slice(0, 80)) console.log(String(h.size).padStart(9), h.p);

const outDir = process.argv[3];
if (outDir) {
  fs.mkdirSync(outDir, { recursive: true });
  let n = 0;
  for (const h of named) {
    if (!/\.(js|mjs|cjs|ts|json)$/.test(h.p)) continue;
    if (h.size > 8 * 1024 * 1024) continue;
    const buf = Buffer.alloc(h.size);
    fs.readSync(fd, buf, 0, h.size, dataOffset + h.off);
    fs.writeFileSync(path.join(outDir, h.p.replace(/[/\\]/g, '__')), buf);
    n++;
  }
  console.log('extracted', n, 'files to', outDir);
}
fs.closeSync(fd);
