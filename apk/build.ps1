# ══════════════════════════════════════════════════════════════
#  build.ps1 —— PowerShell 7 原生版 APK 构建流水线
#  （替代 build.sh：管道/循环/子表达式全部进程内执行，
#    不再有 MINGW64 的 fork+管道模拟开销）
#
#  与 build.sh 保持完全一致的 8 步流程与全部硬门禁。
#  ══════════════════════════════════════════════════════════════
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$SDK  = Join-Path (Split-Path $PSScriptRoot -Parent) 'android-sdk'
$BT   = Join-Path $SDK 'android-14'
$AJ   = Join-Path $SDK 'android-34/android.jar'
$OUT  = Join-Path $PSScriptRoot 'DSH余额.apk'

function Check([string]$name, [bool]$cond, [string]$detail) {
    if (-not $cond) { Write-Host "  ✗ $name" -ForegroundColor Red; if ($detail) { Write-Host "    $detail" }; throw "环境检查失败: $name" }
}
function Say([string]$msg)  { Write-Host "  ✓ $msg" }
function ByteSize([string]$p) { "{0:N0} bytes" -f (Get-Item $p).Length }

Write-Host "▶ 环境检查"
Check "aapt2.exe"      (Test-Path (Join-Path $BT 'aapt2.exe'))
Check "zipalign.exe"   (Test-Path (Join-Path $BT 'zipalign.exe'))
Check "d8.jar"         (Test-Path (Join-Path $BT 'lib/d8.jar'))
Check "apksigner.jar"  (Test-Path (Join-Path $BT 'lib/apksigner.jar'))
Check "android.jar"    (Test-Path $AJ)
Check "java"           ([bool](Get-Command java -ErrorAction SilentlyContinue))
Say "工具链齐全"
Write-Host "  $((java -version 2>&1) | Select-Object -First 1)"

Write-Host ""
Write-Host "▶ 1/8 编译资源"
if (Test-Path build) { Remove-Item build -Recurse -Force }
New-Item -ItemType Directory -Force -Path build/obj, build/dex | Out-Null
& "$BT/aapt2.exe" compile --dir res -o build/res.zip
Say "res.zip $(ByteSize 'build/res.zip')"

Write-Host "▶ 2/8 注入配置 + 链接资源"
node mkassets.mjs
if ($LASTEXITCODE -ne 0) { throw "mkassets.mjs 失败 ($LASTEXITCODE)" }
node ../checkjs.mjs
if ($LASTEXITCODE -ne 0) { throw "JS 语法门禁未通过 ($LASTEXITCODE)" }
Remove-Item build/gen -Recurse -Force -ErrorAction SilentlyContinue
& "$BT/aapt2.exe" link `
  -o build/app.unsigned.apk `
  -I "$AJ" `
  --manifest AndroidManifest.xml `
  -R build/res.zip `
  -A assets `
  --java build/gen `
  --auto-add-overlay `
  --min-sdk-version 21 --target-sdk-version 34 `
  --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败 ($LASTEXITCODE)" }
Say "中间包 $(ByteSize 'build/app.unsigned.apk')"
if (Test-Path 'build/gen/com/dsh/balance/R.java') { Say "生成 R.java（桌面小组件要引用资源 id）" }

Write-Host "▶ 3/8 编译 Java（ECJ）"
# 为什么不用 javac：JDK 25 的 javac 产出的 class 文件，build-tools 34 里的
# d8(R8 8.2) 读不了，会抛 NullPointerException。Eclipse ECJ 只有 3MB，
# 输出的是标准的 Java 8 字节码，d8 完全认。
java -jar "$SDK/ecj.jar" -source 1.8 -target 1.8 -bootclasspath "$AJ" `
  -encoding UTF-8 -nowarn -d build/obj `
  src/com/dsh/balance/*.java build/gen/com/dsh/balance/R.java 2>&1 |
  Out-File -FilePath build/javac.log -Encoding utf8
$classes = Get-ChildItem build/obj -Recurse -Filter *.class
if ($classes.Count -eq 0) {
    Write-Host "  ✗ 编译失败，ECJ 输出：" -ForegroundColor Red
    Get-Content build/javac.log -TotalCount 25 | ForEach-Object { Write-Host "    $_" }
    throw "ECJ 没产出 class"
}
# 硬门禁：哪怕只错一个类也要中止。此前 MainActivity 编译失败（变量重名），
# 其余类照样产出 dex 打进了包，结果一点开就闪退 —— 这种包必须拦住。
$errs = Select-String -Path build/javac.log -Pattern 'ERROR in' -SimpleMatch
if ($errs) {
    Write-Host "  ✗ 存在编译错误，中止构建：" -ForegroundColor Red
    $errs | Select-Object -First 5 | ForEach-Object { Write-Host "    $($_.Line)" }
    throw "ECJ 编译错误"
}
Say "$($classes.Count) 个 class（ECJ，Java 8 字节码）"
$classes.FullName | Set-Content build/classes.txt -Encoding utf8

Write-Host "▶ 4/8 Dex 转换"
java -Xmx1024M -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --lib "$AJ" --min-api 21 --output build/dex `
  "@build/classes.txt" 2>&1 | Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
if (-not (Test-Path build/dex/classes.dex)) { throw "没产出 classes.dex" }
Say "classes.dex $(ByteSize 'build/dex/classes.dex')"

Write-Host "▶ 5/8 把 dex 塞进包"
jar uf build/app.unsigned.apk -C build/dex classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar 更新失败" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip0 = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $PSScriptRoot 'build/app.unsigned.apk'))
Say "条目数 $($zip0.Entries.Count) 个"
$zip0.Dispose()

Write-Host "▶ 6/8 对齐"
& "$BT/zipalign.exe" -f -p 4 build/app.unsigned.apk build/app.aligned.apk
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }
Say "4 字节对齐完成"

Write-Host "▶ 7/8 生成调试签名密钥"
if (-not (Test-Path build/debug.keystore)) {
    keytool -genkeypair -keystore build/debug.keystore `
      -storepass android -keypass android -alias dshkey `
      -keyalg RSA -keysize 2048 -validity 10000 `
      -dname "CN=DSH Balance, O=Personal, C=CN" -noprompt 2>&1 | Out-Null
}
Say "keystore 就绪"

Write-Host "▶ 8/8 签名"
Remove-Item $OUT -Force -ErrorAction SilentlyContinue
java -jar "$BT/lib/apksigner.jar" sign `
  --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android `
  --v1-signing-enabled true --v2-signing-enabled true `
  --out "$OUT" build/app.aligned.apk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Write-Host ""
Write-Host "▶ 校验签名"
java -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT" 2>&1 |
  Select-String -Pattern 'Verifies|v[123] scheme|SHA-256' | ForEach-Object { Write-Host "  $($_.Line.Trim())" }

Write-Host ""
Write-Host "══════════════════════════════════════════"
Write-Host "  产物: $OUT"
Write-Host ("  大小: {0:N0} bytes ({1:N1} KB)" -f (Get-Item $OUT).Length, ((Get-Item $OUT).Length / 1KB))
Write-Host "  内含:"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip2 = [System.IO.Compression.ZipFile]::OpenRead($OUT)
$zip2.Entries | Select-Object -First 12 | ForEach-Object {
    Write-Host ("    {0,-42} {1,8}" -f $_.FullName, $_.Length)
}
Write-Host "══════════════════════════════════════════"
Write-Host "  ✓ 构建完成（PowerShell 7 流水线）"
