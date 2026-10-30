#!/usr/bin/env node
/**
 * DSH 余额 · 手机端数据服务
 * ─────────────────────────────────────────────────────────────
 * 零依赖，只用 Node 内置模块。作用：
 *   1. 读 ~/.dsh/.dshw-usage.json（DSH 自己的账本）
 *   2. 把余额/消耗/历史/模型分布以 JSON 发给手机
 *   3. 顺手把 dsh-balance.html 也伺服出去，手机直接访问就能用
 *
 * 用法：  node balance-server.js          （默认端口 8787）
 *        node balance-server.js 9000     （换端口）
 *        PORT=9000 node balance-server.js
 * ─────────────────────────────────────────────────────────────
 */
const http = require('http');
const dgram = require('dgram');
const fs   = require('fs');
const os   = require('os');
const path = require('path');

const PORT = Number(process.argv[2] || process.env.PORT || 8787);
const DIR  = __dirname;
const USAGE = path.join(os.homedir(), '.dsh', '.dshw-usage.json');
const PAGE  = path.join(DIR, 'dsh-balance.html');

/* ── UDP 自动发现 ──────────────────────────────────────────────
 * 电脑 IP 会变（实测：192.168.1.10 → 192.168.1.25），
 * 把地址写死在 App 里迟早失效。改成服务主动广播：
 *   每隔 5 秒往 255.255.255.255:8788 喊一句 {"service":"dsh-balance",...}
 * App 在同一个 WiFi 里监听这个口，收到就知道电脑在哪。
 * 广播不影响正常请求，UDP 丢了也无所谓，下 5 秒再来一次。
 * ───────────────────────────────────────────────────────────── */
const DISCOVERY_PORT = 8788;
const BEACON_MS = 5000;
let beaconTimer = null;

function localIPs() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list || []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address);
    }
  }
  return out;
}

function startBeacon() {
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  sock.on('error', () => { /* 广播失败不影响 HTTP 服务 */ });
  sock.bind(() => {
    try { sock.setBroadcast(true); } catch (e) { }
  });

  const tick = () => {
    const payload = Buffer.from(JSON.stringify({
      service: 'dsh-balance',
      port: PORT,
      ips: localIPs(),
      host: os.hostname(),
      ts: Date.now(),
    }));
    try {
      sock.send(payload, 0, payload.length, DISCOVERY_PORT, '255.255.255.255');
      // 有些路由器不转发全局广播，再对每个网段的定向广播补一发
      for (const ip of localIPs()) {
        const seg = ip.split('.').slice(0, 3).join('.') + '.255';
        sock.send(payload, 0, payload.length, DISCOVERY_PORT, seg, () => { });
      }
    } catch (e) { }
  };

  tick();
  beaconTimer = setInterval(tick, BEACON_MS);
  beaconTimer.unref && beaconTimer.unref();
  return sock;
}

/* 手机也可以主动来问一句，服务直接回（比等广播更快） */
function startResponder() {
  const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  sock.on('error', () => { });
  sock.on('message', (msg, rinfo) => {
    try {
      const text = msg.toString('utf8');
      if (text.indexOf('dsh-balance') < 0) return;
      const reply = Buffer.from(JSON.stringify({
        service: 'dsh-balance', port: PORT, ips: localIPs(),
        host: os.hostname(), ts: Date.now(),
      }));
      sock.send(reply, 0, reply.length, rinfo.port, rinfo.address);
    } catch (e) { }
  });
  sock.bind(DISCOVERY_PORT + 1, () => { });
  return sock;
}

/* ── 读账本并整理成手机要的形状 ── */
function readLedger() {
  const raw = fs.readFileSync(USAGE, 'utf8');
  const j = JSON.parse(raw);

  const history = j.history || {};
  const days = Object.keys(history).sort();

  // 模型按 token 聚合（不用 events.cost —— 那是官方计价口径，
  // 与实际扣费 history 不是一个账，混着显示会显得对不上账）
  function modelsFor(period) {
    const win = days.slice(-period);
    const inWin = new Set(win);
    const byModel = {};
    let total = 0;
    for (const e of (j.events || [])) {
      if (!inWin.has(e.day)) continue;
      const t = e.tokens || 0;
      byModel[e.model || 'unknown'] = (byModel[e.model || 'unknown'] || 0) + t;
      total += t;
    }
    const rows = Object.entries(byModel)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([name, tok]) => ({
        name,
        tokens: tok,
        pct: total ? +(tok / total * 100).toFixed(1) : 0,
      }));
    return { rows, total, win };
  }

  const m7 = modelsFor(7), m14 = modelsFor(14), m30 = modelsFor(30);

  // 逐日 token 与逐日模型分布（历史消费明细靠这个）
  const byDay = {};
  for (const e of (j.events || [])) {
    const d = e.day;
    if (!d) continue;
    if (!byDay[d]) byDay[d] = { tokens: 0, models: {} };
    const t = e.tokens || 0;
    byDay[d].tokens += t;
    const m = e.model || 'unknown';
    byDay[d].models[m] = (byDay[d].models[m] || 0) + t;
  }
  const tokensByDay = {};
  const detailByDay = {};
  for (const d of Object.keys(byDay).sort()) {
    tokensByDay[d] = byDay[d].tokens;
    detailByDay[d] = {
      tokens: byDay[d].tokens,
      models: Object.entries(byDay[d].models)
        .sort((a, b) => b[1] - a[1])
        .map(([name, tokens]) => ({ name, tokens })),
    };
  }
  const tkSum = p => days.slice(-p).reduce((a, d) => a + (tokensByDay[d] || 0), 0);

  /* ── 月度聚合：手机端「按月」视图，一根柱子一个月 ── */
  const costByMonth = {}, tokensByMonth = {}, tokByMonthModel = {};
  for (const d of days) {
    const m = d.slice(0, 7);
    costByMonth[m] = +(((costByMonth[m] || 0) + (history[d] || 0))).toFixed(4);
  }
  for (const d of Object.keys(byDay)) {
    const m = d.slice(0, 7);
    tokensByMonth[m] = (tokensByMonth[m] || 0) + byDay[d].tokens;
    if (!tokByMonthModel[m]) tokByMonthModel[m] = {};
    for (const [name, t] of Object.entries(byDay[d].models)) {
      tokByMonthModel[m][name] = (tokByMonthModel[m][name] || 0) + t;
    }
  }
  const months = Array.from(new Set([...Object.keys(costByMonth), ...Object.keys(tokensByMonth)])).sort();
  const pctRows = obj => {
    const total = Object.values(obj || {}).reduce((a, b) => a + b, 0);
    return Object.entries(obj || {})
      .sort((a, b) => b[1] - a[1])
      .slice(0, 5)
      .map(([name, tokens]) => ({ name, tokens, pct: total ? +(tokens / total * 100).toFixed(1) : 0 }));
  };
  const modelsByMonth = {}, detailByMonth = {};
  for (const m of months) {
    const rows = pctRows(tokByMonthModel[m]);
    modelsByMonth[m] = rows;
    detailByMonth[m] = {
      tokens: tokensByMonth[m] || 0,
      models: rows.map(r => ({ name: r.name, tokens: r.tokens })),
    };
  }

  return {
    ok: true,
    source: 'dsh-ledger',
    updatedAt: fs.statSync(USAGE).mtimeMs,
    balance: +(j.lastBalance || 0).toFixed(4),
    todayUsage: +(j.todayUsage || 0).toFixed(4),
    date: j.date,
    // 日视图给到 30 天；按月视图用下面的月度聚合（不受这个窗口限制）
    history: Object.fromEntries(days.slice(-30).map(d => [d, +(history[d] || 0).toFixed(4)])),
    tokensByDay,
    detailByDay,
    months,
    costByMonth,
    tokensByMonth,
    modelsByMonth,
    detailByMonth,
    models: m7.rows,
    modelsByPeriod: { 7: m7.rows, 14: m14.rows, 30: m30.rows },
    tokens: m7.total,
    tokensByPeriod: { 7: tkSum(7), 14: tkSum(14), 30: tkSum(30) },
    daysAvailable: days.length,
    window: m7.win.length ? m7.win[0] + ' → ' + m7.win[m7.win.length - 1] : null,
  };
}

/* ── 局域网地址（给手机填） ── */
function lanIPs() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list || []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address);
    }
  }
  return out;
}

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization,Content-Type',
  'Access-Control-Allow-Methods': 'GET,OPTIONS',
  'Cache-Control': 'no-store',
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  const send = (code, body, type) => {
    res.writeHead(code, { 'Content-Type': type + '; charset=utf-8', ...CORS });
    res.end(body);
  };

  if (req.method === 'OPTIONS') return send(204, '', 'text/plain');

  if (url.pathname === '/api/balance') {
    try {
      return send(200, JSON.stringify(readLedger()), 'application/json');
    } catch (err) {
      return send(500, JSON.stringify({
        ok: false,
        error: '读不到账本：' + err.message + '（路径 ' + USAGE + '）',
      }), 'application/json');
    }
  }

  if (url.pathname === '/api/ping') {
    return send(200, JSON.stringify({ ok: true, service: 'dsh-balance', port: PORT }), 'application/json');
  }

  if (url.pathname === '/' || url.pathname === '/index.html') {
    try {
      return send(200, fs.readFileSync(PAGE, 'utf8'), 'text/html');
    } catch {
      return send(404, '找不到 dsh-balance.html，请把它和本文件放在同一目录', 'text/plain');
    }
  }

  send(404, 'not found', 'text/plain');
});

server.listen(PORT, '0.0.0.0', () => {
  let first;
  try { first = readLedger(); } catch { first = null; }

  console.log('\n  ┌─────────────────────────────────────────────┐');
  console.log('  │   DSH 余额服务已启动 ✓                      │');
  console.log('  └─────────────────────────────────────────────┘\n');

  if (first) {
    console.log('  当前余额  ¥' + first.balance.toFixed(2) + '   今日消耗  ¥' + first.todayUsage.toFixed(2) +
                (first.window ? '\n  模型窗口  ' + first.window : ''));
  } else {
    console.log('  ⚠ 还没读到账本，先在电脑上用一次 DSH 产生记录');
  }

  console.log('\n  手机浏览器打开（要连同一个 WiFi）：\n');
  for (const ip of lanIPs()) console.log('      http://' + ip + ':' + PORT);
  console.log('\n  电脑本机自测：  http://127.0.0.1:' + PORT + '/api/balance');
  console.log('  账本文件：      ' + USAGE);
  console.log('\n  打开页面后可以用「添加到主屏幕」当 App 用。Ctrl+C 停止。\n');

  /* 启动自动发现：App 会自动收到广播，不用手填 IP */
  try {
    startBeacon();
    startResponder();
    console.log('  ✓ 自动发现已开启（UDP ' + DISCOVERY_PORT + '/应答 ' + (DISCOVERY_PORT + 1) +
                '），App 会自动找到本机，IP 变了也不用改\n');
  } catch (e) {
    console.log('  ⚠ 自动发现启动失败（不影响手动填地址）：' + e.message + '\n');
  }
});

server.on('error', err => {
  if (err.code === 'EADDRINUSE') {
    console.error('\n  端口 ' + PORT + ' 被占用了。换一个：node balance-server.js ' + (PORT + 1) + '\n');
  } else {
    console.error('\n  启动失败：' + err.message + '\n');
  }
  process.exit(1);
});
