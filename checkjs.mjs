/**
 * checkjs.mjs —— 构建前的 JS 语法门禁
 * 网页版的脚本一挂，整个界面就是白板（只剩静态 HTML 的 "--"）。
 * 所以把内联 <script> 抽出来做一次真实解析，语法不过就直接让构建失败。
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const targets = [
  path.join(here, 'dsh-balance.html'),
  path.join(here, 'apk', 'assets', 'index.html'),
];

let bad = 0, checked = 0;
for (const f of targets) {
  let html;
  try { html = fs.readFileSync(f, 'utf8'); }
  catch (e) {
    console.error('  ✗ 读不到 ' + path.relative(here, f) + '（' + e.code + '）');
    bad++;
    continue;
  }

  const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
  if (!blocks.length) {
    console.error('  ✗ ' + path.relative(here, f) + ' 里没找到 <script> 块');
    bad++;
    continue;
  }
  checked += blocks.length;

  for (let i = 0; i < blocks.length; i++) {
    const code = blocks[i];
    try {
      new vm.Script(code, { filename: path.basename(f) + '#script' + (i + 1) });
    } catch (err) {
      bad++;
      const line = (err.stack || '').split('\n').find(l => l.includes(path.basename(f))) || '';
      console.error('  ✗ JS 语法错误：' + path.basename(f) + ' 第 ' + (i + 1) + ' 个 script');
      console.error('    ' + err.message);
      if (line) console.error('    ' + line.trim());
      // 把出错行附近打出来，方便直接定位
      const ln = /:(\d+)/.exec(err.stack || '');
      if (ln) {
        const n = parseInt(ln[1], 10);
        code.split('\n').slice(Math.max(0, n - 3), n + 1).forEach((l, k) => {
          console.error('    ' + String(Math.max(1, n - 2) + k).padStart(4) + ' | ' + l.slice(0, 120));
        });
      }
    }
  }
  if (!bad) console.log('  ✓ ' + path.basename(f) + ' 内联 JS 语法通过（' + blocks.length + ' 块）');
}

if (bad) { console.error('  ✗ 构建中止'); process.exit(1); }
