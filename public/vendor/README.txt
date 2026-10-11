qrcode-generator (qrcode.js)
============================

Version: 1.4.4
Author:  Kazuhiko Arase (c) 2009
License: MIT — http://www.opensource.org/licenses/mit-license.php
Source:  https://github.com/kazuhikoarase/qrcode-generator

Vendored here unchanged so the tool can render QR codes entirely offline,
inside the browser. The connection link contains a signature (`hash=`) that
must never be sent to a third-party QR service, so a local encoder is required.

Usage in this project (see public/app.js):

    const qr = qrcode(0, 'M');   // 0 = auto-detect version, M = error correction
    qr.addData(url);
    qr.make();
    qr.getModuleCount();          // matrix size
    qr.isDark(row, col);          // draw module

The file exposes both a browser global (`qrcode`) and `module.exports`.
