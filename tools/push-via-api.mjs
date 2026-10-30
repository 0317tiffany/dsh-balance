/**
 * push-via-api.mjs —— 通过 GitHub REST API 推送整个仓库
 *
 * 为什么需要这个：本机直连 github.com 的 git 传输通道会被重置
 * （Recv failure: Connection was reset），而 gh 的 API 通道是通的。
 * 所以绕开 git 协议，用 Git Data API 直接构造 tree + commit。
 *
 * 流程：
 *   1. 为每个文件创建 blob（base64 内容）
 *   2. 创建 tree（含所有路径）
 *   3. 创建 commit（指向 tree，无父提交）
 *   4. 更新 refs/heads/main 指向该 commit
 *
 * 保真：
 *   - 保留文件的原始字节（base64 编码传输，不做行尾转换）
 *   - 保留可执行位（files 里 .sh/.mjs 设为 100755）
 *   - commit message / author 与本地仓库一致
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const GH = 'C:\\Program Files\\GitHub CLI\\gh.exe';
const REPO = process.argv[2] || '0317tiffany/dsh-balance';
const BRANCH = process.argv[3] || 'main';
const ROOT = process.cwd();

function gh(args, input) {
  // 用 gh api，通过 stdin 传 JSON body（-F 参数对大量数据不稳）
  const r = execFileSync(GH, args, {
    input: input ? JSON.stringify(input) : undefined,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    timeout: 120000,
  });
  return r;
}

function api(method, endpoint, body) {
  const args = ['api', endpoint, '-X', method, '--input', '-'];
  try {
    const out = execFileSync(GH, args, {
      input: JSON.stringify(body || {}),
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      timeout: 120000,
    });
    return out.trim();
  } catch (e) {
    const msg = (e.stderr || '') + (e.stdout || '') + (e.message || '');
    throw new Error('API 调用失败 [' + endpoint + ']: ' + msg.slice(0, 300));
  }
}

/* ── 1. 收集文件（用 git ls-files 拿已跟踪文件，保证和 .gitignore 一致）── */
console.log('▶ 收集文件');
// 用 -z 获取未转义的文件名（中文路径默认会被 git 转义成 \345\220... 形式）
const tracked = execFileSync('git', ['ls-files', '-z', '--', '.'], { cwd: ROOT, encoding: 'buffer' })
  .toString('utf8').split('\0').filter(Boolean);
console.log('  已跟踪: ' + tracked.length + ' 个文件');

const entries = [];
for (const rel of tracked) {
  const abs = path.join(ROOT, rel);
  const buf = fs.readFileSync(abs);
  // 二进制判断：检查是否含 NUL 或常见二进制扩展名
  const isBinary = buf.includes(0) || /\.(png|jpg|jpeg|jar|apk|zip|keystore)$/i.test(rel);
  entries.push({
    path: rel.replace(/\\/g, '/'),
    buf,
    isBinary,
    exec: rel.endsWith('.sh') || rel.endsWith('.mjs'),
  });
}

/* ── 2. 创建 blob ── */
console.log('▶ 创建 blob（' + entries.length + ' 个）');
const blobs = [];
for (const e of entries) {
  const content = e.buf.toString('base64');
  const res = JSON.parse(api('POST', 'repos/' + REPO + '/git/blobs', {
    content,
    encoding: 'base64',
  }));
  blobs.push({ path: e.path, sha: res.sha, exec: e.exec });
  process.stdout.write('.');
}
console.log('\n  完成');

/* ── 3. 创建 tree ── */
console.log('▶ 创建 tree');
const treeItems = blobs.map(b => ({
  path: b.path,
  mode: b.exec ? '100755' : '100644',
  type: 'blob',
  sha: b.sha,
}));
const treeRes = JSON.parse(api('POST', 'repos/' + REPO + '/git/trees', { tree: treeItems }));
console.log('  tree sha: ' + treeRes.sha);

/* ── 4. 创建 commit ── */
console.log('▶ 创建 commit');
const localMsg = execFileSync('git', ['log', '-1', '--format=%B'], { cwd: ROOT, encoding: 'utf8' }).trim();
const localAuthor = execFileSync('git', ['log', '-1', '--format=%an'], { cwd: ROOT, encoding: 'utf8' }).trim();
const localEmail = execFileSync('git', ['log', '-1', '--format=%ae'], { cwd: ROOT, encoding: 'utf8' }).trim();

const commitRes = JSON.parse(api('POST', 'repos/' + REPO + '/git/commits', {
  message: localMsg || 'feat: 初始提交',
  tree: treeRes.sha,
  parents: [],
  author: { name: localAuthor, email: localEmail },
  committer: { name: localAuthor, email: localEmail },
}));
console.log('  commit sha: ' + commitRes.sha);

/* ── 5. 更新分支 ref ── */
console.log('▶ 设置 ' + BRANCH + ' 分支');
let updated = false;
try {
  // 空仓库可能还没有 ref，尝试创建
  api('POST', 'repos/' + REPO + '/git/refs', {
    ref: 'refs/heads/' + BRANCH,
    sha: commitRes.sha,
  });
  updated = true;
  console.log('  ✓ refs/heads/' + BRANCH + ' 已创建');
} catch (e) {
  // 已存在则 PATCH
  try {
    api('PATCH', 'repos/' + REPO + '/git/refs/heads/' + BRANCH, {
      sha: commitRes.sha,
      force: true,
    });
    updated = true;
    console.log('  ✓ refs/heads/' + BRANCH + ' 已更新');
  } catch (e2) {
    console.error('  ✗ 设置分支失败: ' + e2.message);
  }
}

/* ── 6. 设置默认分支 ── */
if (updated) {
  try {
    api('PATCH', 'repos/' + REPO, { default_branch: BRANCH });
    console.log('  ✓ 默认分支设为 ' + BRANCH);
  } catch (e) { /* 已经是默认就跳过 */ }
}

/* ── 7. 写回本地 git，让本地也知道远程 commit ── */
if (updated) {
  try {
    execFileSync('git', ['update-ref', 'refs/remotes/origin/' + BRANCH, commitRes.sha], { cwd: ROOT });
    console.log('  ✓ 本地 origin/' + BRANCH + ' 已同步');
  } catch (e) { }
}

console.log('');
console.log('══════════════════════════════════════');
console.log('  完成');
console.log('  提交: ' + commitRes.sha.slice(0, 7));
console.log('  文件: ' + entries.length);
console.log('  分支: ' + BRANCH);
console.log('  地址: https://github.com/' + REPO);
console.log('══════════════════════════════════════');
