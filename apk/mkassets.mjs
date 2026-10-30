/**
 * mkassets.mjs —— 把网页版注入配置后，产出 APK 用的 assets/index.html
 *
 * 为什么不用 URL 查询串：安卓 WebView 对 file://...?query 的支持不稳定，
 * 直接写进 HTML 最保险（三重来源：全局变量 / ?lan= / #lan= 都能用）。
 *
 * 密钥不硬编码在本文件里，从 apk/.env 或环境变量读，避免误提交。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(here, '..', 'dsh-balance.html');
const DST = path.join(here, 'assets', 'index.html');
const ENV = path.join(here, 'build-config.json');

/* ── 读配置：build-config.json 优先，其次环境变量 ── */
function readEnv() {
  try { return JSON.parse(fs.readFileSync(ENV, 'utf8')); } catch { return {}; }
}
const cfg = readEnv();
const API_KEY = '';   // 安全检查：key 绝不写入 APK，由用户自己在 App 里填，只存手机本地
const DEFAULT_MODE = process.env.DSH_MODE || cfg.DSH_MODE || 'api';

/* 局域网候选地址（可选）：App 会自动探测，谁通用谁。
   默认留空 —— 推荐依靠 UDP 自动发现（见 Discovery.java），
   不需要把任何 IP 写死在包里。确有需要可在 build-config.json 的 DSH_LAN 里填。 */
function lanCandidates() {
  const fromEnv = process.env.DSH_LAN || cfg.DSH_LAN || '';
  if (fromEnv) return fromEnv.split('|').filter(Boolean);
  return [];
}
const LAN = lanCandidates().join('|');

const html = fs.readFileSync(SRC, 'utf8');
if (!html.includes('</head>')) {
  console.error('  ✗ 源 HTML 里找不到 </head>，注入失败');
  process.exit(1);
}

const parts = [
  'window.__DSH_LAN=' + JSON.stringify(LAN) + ';',
  'window.__DSH_MODE=' + JSON.stringify(DEFAULT_MODE) + ';',
];
// 注意：不注入任何 API Key。内置 key = 全网公开，只能用户自己填。
const injected = '<script>' + parts.join('') + '<\/script>';
const out = html.replace('</head>', injected + '</head>');

fs.mkdirSync(path.dirname(DST), { recursive: true });
fs.writeFileSync(DST, out);

const okLan = out.includes('__DSH_LAN');
const leaked = /sk-[A-Za-z0-9]{20,}/.test(out);
console.log('  ✓ assets/index.html ' + out.length + ' 字节');
console.log('    默认模式: ' + DEFAULT_MODE +
            ' | 局域网候选: ' + LAN.split('|').length + ' 个');
if (leaked) { console.error('  ✗ 检测到疑似 API Key 被写进包，中止'); process.exit(1); }
if (!okLan) { console.error('  ✗ 局域网地址注入失败'); process.exit(1); }
