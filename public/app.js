'use strict';

/* ZCode 手机连接助手 —— 前端逻辑
 * 二维码在本地用 vendor/qrcode.js 生成，链接不出本机。
 */

(function () {
  const $ = (id) => document.getElementById(id);

  const els = {
    input: $('linkInput'),
    status: $('status'),
    generate: $('btnGenerate'),
    clipboard: $('btnClipboard'),
    clear: $('btnClear'),
    watchClip: $('watchClip'),

    chipSid: $('chipSid'),
    chipDot: $('chipDot'),
    deviceInfo: $('deviceInfo'),
    dSid: $('dSid'),
    dHost: $('dHost'),
    dContext: $('dContext'),
    dPath: $('dPath'),
    dMtime: $('dMtime'),
    deviceNote: $('deviceNote'),
    refreshDevice: $('btnRefreshDevice'),

    detailState: $('detailState'),
    pSid: $('pSid'),
    pName: $('pName'),
    pMid: $('pMid'),
    pVer: $('pVer'),
    pT: $('pT'),
    pHash: $('pHash'),
    pHost: $('pHost'),
    detailNote: $('detailNote'),

    qrCanvas: $('qrCanvas'),
    qrEmpty: $('qrEmpty'),
    qrSize: $('qrSize'),
    qrSizeOut: $('qrSizeOut'),
    qrEc: $('qrEc'),
    download: $('btnDownload'),
    copy: $('btnCopy'),
    print: $('btnPrint'),

    history: $('historyList'),
    histCount: $('histCount'),
  };

  const state = {
    device: null,
    current: null, // 当前解析出的链接对象
    currentUrl: '',
    links: [],
    watchTimer: null,
  };

  const LINK_RE = /^https?:\/\/zcode\.z\.ai\/remote\/v\d+\/?\?/i;

  // ------------------------------------------------------------ 通用

  function setStatus(msg, kind) {
    els.status.textContent = msg || '';
    els.status.className = 'status' + (kind ? ' ' + kind : '');
  }

  async function api(path, options) {
    const res = await fetch(path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, options));
    const data = await res.json().catch(() => ({ ok: false, error: '响应不是 JSON' }));
    if (!res.ok && data && data.ok !== false) data.ok = false;
    return data;
  }

  function fmtTime(ms) {
    if (!ms) return '—';
    const d = new Date(ms);
    if (isNaN(d.getTime())) return '—';
    const pad = (n) => String(n).padStart(2, '0');
    return (
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
      `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    );
  }

  function fmtAgo(ms) {
    if (!ms) return '';
    const diff = Date.now() - ms;
    const abs = Math.abs(diff);
    const min = 60_000, hour = 3_600_000, day = 86_400_000;
    let text;
    if (abs < min) text = '不到 1 分钟';
    else if (abs < hour) text = `${Math.round(abs / min)} 分钟`;
    else if (abs < day) text = `${Math.round(abs / hour)} 小时`;
    else text = `${Math.round(abs / day)} 天`;
    return diff >= 0 ? `${text}前` : `${text}后`;
  }

  // ------------------------------------------------------------ 链接解析

  function normalizeTimestamp(raw) {
    if (!raw || !/^\d+$/.test(raw)) return null;
    if (raw.length >= 12) return { ms: Number(raw), unit: 'ms' };
    if (raw.length === 10) return { ms: Number(raw) * 1000, unit: 's' };
    return { ms: Number(raw), unit: 'raw' };
  }

  function parseLink(raw) {
    const input = String(raw || '').trim();
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
    if (!pathOk) warnings.push(`路径不是 /remote/vN 形式`);
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
      t: ts ? ts.ms : null,
      tUnit: ts ? ts.unit : null,
      mid,
      name,
      appVersion,
      warnings,
    };
  }

  // ------------------------------------------------------------ 二维码

  function renderQR(text) {
    if (!text) {
      els.qrCanvas.hidden = true;
      els.qrEmpty.hidden = false;
      [els.download, els.copy, els.print].forEach((b) => (b.disabled = true));
      return false;
    }

    const ec = els.qrEc.value;
    const size = Number(els.qrSize.value);
    let qr;
    try {
      qr = qrcode(0, ec);
      qr.addData(text);
      qr.make();
    } catch (err) {
      setStatus('二维码生成失败：' + err.message + '（链接过长？）', 'err');
      return false;
    }

    const count = qr.getModuleCount();
    const margin = 4; // 静默区，单位：模块
    const total = count + margin * 2;
    const scale = Math.max(2, Math.floor(size / total));
    const cssSize = total * scale;
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    const canvas = els.qrCanvas;

    canvas.width = Math.round(cssSize * dpr);
    canvas.height = Math.round(cssSize * dpr);
    canvas.style.width = cssSize + 'px';
    canvas.style.height = cssSize + 'px';

    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.imageSmoothingEnabled = false;
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, cssSize, cssSize);
    ctx.fillStyle = '#000000';
    for (let r = 0; r < count; r++) {
      for (let c = 0; c < count; c++) {
        if (qr.isDark(r, c)) ctx.fillRect((margin + c) * scale, (margin + r) * scale, scale, scale);
      }
    }

    canvas.hidden = false;
    els.qrEmpty.hidden = true;
    [els.download, els.copy, els.print].forEach((b) => (b.disabled = false));
    els.download.dataset.modules = String(count);
    return true;
  }

  // ------------------------------------------------------------ 设备信息

  function renderDevice() {
    const d = state.device;
    if (!d) return;

    els.chipSid.textContent = d.deviceSid || '未注册';
    els.dSid.textContent = d.deviceSid || '—';
    els.dHost.textContent = d.hostname || '—';
    els.dPath.textContent = d.settingPath || '—';
    els.dMtime.textContent = d.settingMtime ? fmtTime(Date.parse(d.settingMtime)) : '—';

    const ctx = d.lastEnabledContext;
    els.dContext.textContent = ctx && ctx.workspacePath
      ? ctx.workspacePath + (ctx.initialTaskId ? '  ·  ' + ctx.initialTaskId : '')
      : '—';

    if (d.configError) {
      els.chipDot.className = 'dot bad';
      els.deviceNote.textContent = d.configError;
      els.deviceNote.className = 'note';
    } else if (!d.deviceSid) {
      els.chipDot.className = 'dot warn';
      els.deviceNote.textContent = '配置中尚无远程设备记录。请在 ZCode 里开启「手机远控」后再刷新。';
      els.deviceNote.className = 'note';
    } else {
      els.chipDot.className = 'dot ok';
      els.deviceNote.textContent =
        '设备名（' + (state.current && state.current.name ? state.current.name : '见链接 name 参数') +
        '）由 ZCode 中自定义，可能与计算机名不同；判断是否为同一台机器请看 sid 是否一致。';
      els.deviceNote.className = 'note';
    }

    // 已有链接时同步刷新 sid 匹配状态
    if (state.current) applyDeviceMatch();
  }

  async function refreshDevice() {
    try {
      const data = await api('/api/device');
      if (data.ok) {
        state.device = data;
        renderDevice();
      }
    } catch (_) {
      els.chipDot.className = 'dot bad';
      els.chipSid.textContent = '服务未连接';
    }
  }

  function applyDeviceMatch() {
    const p = state.current;
    const sid = state.device && state.device.deviceSid;
    const dd = els.pSid;
    if (!p || !p.sid) {
      dd.className = 'mono';
      return;
    }
    if (!sid) {
      dd.className = 'mono';
      return;
    }
    if (p.sid === sid) {
      dd.className = 'mono match';
      if (!els.detailNote.dataset.keep) els.detailNote.textContent = 'sid 与本机已注册设备一致，这就是发给这台电脑的链接。';
      els.detailNote.className = 'note';
    } else {
      dd.className = 'mono mismatch';
      els.detailNote.textContent = '注意：链接里的 sid 与本机注册的设备不一致，可能是给另一台机器 / 另一台设备的链接。';
      els.detailNote.className = 'note';
      els.detailNote.dataset.keep = '';
    }
  }

  // ------------------------------------------------------------ 链接详情

  function renderDetail(p) {
    state.current = p;
    els.detailNote.dataset.keep = '';

    if (!p || !p.ok) {
      els.detailState.textContent = p && p.error ? p.error : '尚未解析';
      els.pSid.textContent = '—';
      els.pName.textContent = '—';
      els.pMid.textContent = '—';
      els.pVer.textContent = '—';
      els.pT.textContent = '—';
      els.pHash.textContent = '—';
      els.pHost.textContent = '—';
      els.pSid.className = 'mono';
      els.detailNote.textContent = p && p.warnings && p.warnings.length ? '提示：' + p.warnings.join('；') : '';
      els.detailNote.className = 'note';
      return;
    }

    els.detailState.textContent = '有效链接';
    els.pSid.textContent = p.sid;
    els.pName.textContent = p.name || '—';
    els.pMid.textContent = p.mid || '—';
    els.pVer.textContent = p.appVersion || '—';
    els.pT.textContent = p.t ? `${fmtTime(p.t)}（${fmtAgo(p.t)}）` : '—';
    els.pHash.textContent = p.hash;
    els.pHost.textContent = p.host + p.path;

    applyDeviceMatch();

    const bits = [];
    if (p.warnings.length) bits.push('提示：' + p.warnings.join('；'));
    if (p.t) bits.push(`链接 t 参数生成于 ${fmtAgo(p.t)}；若手机连不上，请在 ZCode 中重新生成地址后粘贴到这里。`);
    els.detailNote.textContent = bits.join(' ');
  }

  // ------------------------------------------------------------ 历史

  function renderHistory() {
    const list = els.history;
    list.innerHTML = '';
    els.histCount.textContent = state.links.length + ' 条';

    const sid = state.device && state.device.deviceSid;
    state.links.forEach((item) => {
      const li = document.createElement('li');
      if (item.url === state.currentUrl) li.classList.add('current');

      const meta = document.createElement('div');
      meta.className = 'hist-meta';

      const title = document.createElement('div');
      title.className = 'hist-title';
      const nameSpan = document.createElement('span');
      nameSpan.textContent = item.name || '(未命名设备)';
      title.appendChild(nameSpan);

      const sidTag = document.createElement('span');
      sidTag.className = 'tag' + (sid && item.sid === sid ? ' match' : '');
      sidTag.textContent = item.sid ? item.sid.slice(0, 10) + '…' : 'no-sid';
      title.appendChild(sidTag);

      if (item.seenCount > 1) {
        const seenTag = document.createElement('span');
        seenTag.className = 'tag';
        seenTag.textContent = '×' + item.seenCount;
        title.appendChild(seenTag);
      }

      const sub = document.createElement('div');
      sub.className = 'hist-sub';
      sub.textContent = `${item.t ? fmtTime(item.t) : '无 t'}  ·  hash ${item.hashPreview || '—'}`;

      meta.appendChild(title);
      meta.appendChild(sub);

      const actions = document.createElement('div');
      actions.className = 'hist-actions';
      actions.appendChild(iconBtn('load', '载入此链接', () => applyLink(item.url, { persist: false, silent: false })));
      actions.appendChild(iconBtn('copy', '复制链接', () => copyText(item.url)));
      actions.appendChild(iconBtn('trash', '删除', () => deleteLink(item.id), true));

      li.appendChild(meta);
      li.appendChild(actions);
      list.appendChild(li);
    });
  }

  const ICONS = {
    load: '<path d="M5 12h14"/><path d="m13 6 6 6-6 6"/>',
    copy: '<rect x="9" y="9" width="11" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h8"/>',
    trash: '<path d="M4 7h16"/><path d="M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/><path d="M6 7l1 13h10l1-13"/>',
  };

  function iconBtn(kind, label, onClick, danger) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'icon-btn' + (danger ? ' danger' : '');
    b.title = label;
    b.setAttribute('aria-label', label);
    b.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[kind]}</svg>`;
    b.addEventListener('click', onClick);
    return b;
  }

  async function loadLinks() {
    try {
      const data = await api('/api/links');
      if (data.ok) {
        state.links = data.links;
        renderHistory();
      }
    } catch (_) {
      /* 忽略 */
    }
  }

  async function persistLink(url) {
    try {
      const data = await api('/api/links', { method: 'POST', body: JSON.stringify({ url }) });
      if (data.ok) {
        state.links = data.links;
        renderHistory();
      }
    } catch (_) {
      /* 忽略 */
    }
  }

  async function deleteLink(id) {
    try {
      const data = await api('/api/links?id=' + encodeURIComponent(id), { method: 'DELETE' });
      if (data.ok) {
        state.links = data.links;
        renderHistory();
      }
    } catch (_) {
      /* 忽略 */
    }
  }

  async function copyText(text) {
    try {
      await navigator.clipboard.writeText(text);
      setStatus('已复制到剪贴板', 'ok');
    } catch (_) {
      // 剪贴板 API 可能因权限失败，回退到临时选中
      const ta = document.createElement('textarea');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand('copy');
        setStatus('已复制到剪贴板', 'ok');
      } catch (_e) {
        setStatus('复制失败，请手动选择文本复制', 'err');
      }
      document.body.removeChild(ta);
    }
  }

  // ------------------------------------------------------------ 主流程

  function applyLink(raw, opts) {
    opts = opts || {};
    const url = String(raw || '').trim();
    els.input.value = url;

    if (!url) {
      state.currentUrl = '';
      renderDetail(null);
      renderQR('');
      renderHistory();
      setStatus('请输入连接链接', '');
      return;
    }

    const p = parseLink(url);
    if (!p.ok) {
      els.input.classList.add('invalid');
      els.input.classList.remove('valid');
      renderDetail(p);
      renderQR('');
      setStatus('链接无效：' + p.error, 'err');
      return;
    }

    els.input.classList.remove('invalid');
    els.input.classList.add('valid');

    state.currentUrl = url;
    renderDetail(p);
    const drawn = renderQR(url);
    renderHistory();

    if (drawn) {
      const sid = state.device && state.device.deviceSid;
      const mismatch = sid && p.sid && p.sid !== sid;
      if (mismatch) {
        setStatus('二维码已生成，但 sid 与本机不一致（可能是别的设备）', 'warn');
      } else {
        setStatus('二维码已生成，用 ZCode 手机端扫码连接' + (p.name ? `（${p.name}）` : ''), 'ok');
      }
    }

    if (opts.persist !== false) persistLink(url);
  }

  async function readClipboard() {
    if (!navigator.clipboard || !navigator.clipboard.readText) {
      setStatus('当前环境不支持读取剪贴板，请手动粘贴', 'warn');
      return '';
    }
    try {
      return (await navigator.clipboard.readText()).trim();
    } catch (_) {
      setStatus('读取剪贴板被拒绝，请手动粘贴（Ctrl+V）', 'warn');
      return '';
    }
  }

  function setWatchClip(on) {
    if (state.watchTimer) {
      clearInterval(state.watchTimer);
      state.watchTimer = null;
    }
    if (!on) {
      setStatus('已停止监听剪贴板', '');
      return;
    }
    state.watchTimer = setInterval(async () => {
      if (document.hidden) return;
      const text = await readClipboard();
      if (!text || !LINK_RE.test(text)) return;
      if (text === state.currentUrl) return;
      applyLink(text, { persist: true });
      setStatus('检测到剪贴板中的新连接地址，已自动更新二维码', 'ok');
    }, 2000);
    setStatus('已开始监听剪贴板（每 2 秒检查一次）', 'ok');
  }

  // ------------------------------------------------------------ 事件绑定

  els.generate.addEventListener('click', () => applyLink(els.input.value, { persist: true }));

  els.clipboard.addEventListener('click', async () => {
    const text = await readClipboard();
    if (!text) return;
    applyLink(text, { persist: true });
  });

  els.clear.addEventListener('click', () => {
    els.input.value = '';
    els.input.classList.remove('valid', 'invalid');
    applyLink('', { persist: false });
    setStatus('已清空', '');
  });

  els.input.addEventListener('input', () => {
    els.input.classList.remove('valid', 'invalid');
  });

  els.input.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      applyLink(els.input.value, { persist: true });
    }
  });

  els.input.addEventListener('paste', (e) => {
    const text = (e.clipboardData || window.clipboardData).getData('text');
    if (text && LINK_RE.test(text.trim())) {
      // 让原生粘贴完成后再解析
      setTimeout(() => applyLink(els.input.value, { persist: true }), 0);
    }
  });

  els.refreshDevice.addEventListener('click', refreshDevice);

  els.qrSize.addEventListener('input', () => {
    els.qrSizeOut.textContent = els.qrSize.value;
    if (state.currentUrl) renderQR(state.currentUrl);
  });

  els.qrEc.addEventListener('change', () => {
    if (state.currentUrl) renderQR(state.currentUrl);
  });

  els.download.addEventListener('click', () => {
    if (!state.currentUrl) return;
    els.qrCanvas.toBlob((blob) => {
      if (!blob) return;
      const a = document.createElement('a');
      const p = state.current || {};
      const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
      a.href = URL.createObjectURL(blob);
      a.download = `zcode-link-${(p.name || 'device').replace(/[^\w-]/g, '')}-${stamp}.png`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    }, 'image/png');
  });

  els.copy.addEventListener('click', () => {
    if (state.currentUrl) copyText(state.currentUrl);
  });

  els.print.addEventListener('click', () => window.print());

  els.watchClip.addEventListener('change', () => setWatchClip(els.watchClip.checked));

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) refreshDevice();
  });

  // ------------------------------------------------------------ 启动

  async function boot() {
    els.qrSizeOut.textContent = els.qrSize.value;
    await Promise.all([refreshDevice(), loadLinks()]);
    renderQR('');

    // 剪贴板里若已有连接链接，自动填入（可能被权限拒绝，静默处理）
    const text = await readClipboard().catch(() => '');
    if (text && LINK_RE.test(text) && !els.input.value) {
      applyLink(text, { persist: true });
      setStatus('已从剪贴板自动填入连接链接', 'ok');
    } else if (!text) {
      setStatus('把 ZCode 里的连接地址粘贴到上方，然后点「生成二维码」', '');
    }

    // 定时刷新本机设备信息（配置可能变化）
    setInterval(refreshDevice, 5000);
  }

  boot();
})();
