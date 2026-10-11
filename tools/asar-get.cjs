// 从 app.asar 中按路径提取文件：node asar-get.cjs <asar> <outDir> <path> [path...]
const fs = require('fs');
const path = require('path');

const asar = process.argv[2];
const outDir = process.argv[3];
const wanted = process.argv.slice(4);

const fd = fs.openSync(asar, 'r');
const head16 = Buffer.alloc(16);
fs.readSync(fd, head16, 0, 16, 0);
const outerPickleSize = head16.readUInt32LE(4);
const jsonLen = head16.readUInt32LE(12);
const headerJsonBuf = Buffer.alloc(jsonLen);
fs.readSync(fd, headerJsonBuf, 0, jsonLen, 16);
const header = JSON.parse(headerJsonBuf.toString('utf8'));
const dataOffset = 8 + outerPickleSize;

fs.mkdirSync(outDir, { recursive: true });
for (const w of wanted) {
  let node = header;
  for (const seg of w.split('/').filter(Boolean)) {
    node = node.files && node.files[seg];
    if (!node) break;
  }
  if (!node || node.size == null) {
    console.log('NOT FOUND:', w);
    continue;
  }
  const buf = Buffer.alloc(node.size);
  fs.readSync(fd, buf, 0, node.size, dataOffset + Number(node.offset));
  const dest = path.join(outDir, w.replace(/[/\\]/g, '__'));
  fs.writeFileSync(dest, buf);
  console.log('extracted', node.size, 'bytes ->', dest);
}
fs.closeSync(fd);
