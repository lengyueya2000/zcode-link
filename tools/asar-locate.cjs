// 在 app.asar 中定位包含指定字符串的文件（通过字节偏移反查文件树），只读
const fs = require('fs');

const asar = process.argv[2];
const needles = process.argv.slice(3);

const fd = fs.openSync(asar, 'r');
const head16 = Buffer.alloc(16);
fs.readSync(fd, head16, 0, 16, 0);
const outerPickleSize = head16.readUInt32LE(4);
const jsonLen = head16.readUInt32LE(12);
const headerJsonBuf = Buffer.alloc(jsonLen);
fs.readSync(fd, headerJsonBuf, 0, jsonLen, 16);
const header = JSON.parse(headerJsonBuf.toString('utf8'));
const dataOffset = 8 + outerPickleSize;

const files = [];
(function walk(node, prefix) {
  if (!node.files) {
    if (node.size != null) files.push({ p: prefix, size: node.size, off: dataOffset + Number(node.offset) });
    return;
  }
  for (const [name, child] of Object.entries(node.files)) walk(child, prefix + '/' + name);
})(header, '');
files.sort((a, b) => a.off - b.off);

function fileAt(off) {
  let lo = 0, hi = files.length - 1, ans = null;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (files[mid].off <= off) { ans = files[mid]; lo = mid + 1; } else hi = mid - 1;
  }
  if (ans && off < ans.off + ans.size) return ans;
  return null;
}

const results = new Map();
const CHUNK = 8 * 1024 * 1024;
const overlap = 64;
let pos = dataOffset;
let tail = Buffer.alloc(0);
const stat = fs.fstatSync(fd);
while (pos < stat.size) {
  const len = Math.min(CHUNK, stat.size - pos);
  const buf = Buffer.alloc(len);
  fs.readSync(fd, buf, 0, len, pos);
  const hay = Buffer.concat([tail, buf]);
  const base = pos - tail.length;
  for (const needle of needles) {
    const nb = Buffer.from(needle, 'utf8');
    let idx = hay.indexOf(nb);
    while (idx !== -1) {
      const abs = base + idx;
      const f = fileAt(abs);
      if (f) {
        const key = needle + ' @ ' + f.p;
        results.set(key, (results.get(key) || 0) + 1);
      }
      idx = hay.indexOf(nb, idx + 1);
    }
  }
  tail = buf.slice(Math.max(0, len - overlap));
  pos += len;
}

for (const [k, v] of [...results.entries()].sort()) console.log(String(v).padStart(5), k);
fs.closeSync(fd);
