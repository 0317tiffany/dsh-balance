## DSH 余额 v0.1.0

手机端查看 DeepSeek 账户余额的小工具。首个版本，功能已可用。

### 主要功能

**两种数据源**
- 官方 API 模式 —— 手机联网直查，**不需要电脑开机**
- 局域网模式 —— 读取 DSH 本地账本，有完整消费历史

**界面**
- 余额主卡 + 今日消耗 + 周期消耗
- 续航提示：按历史日均算出「还够 X 天」，低于 7 天亮黄灯
- 消耗趋势图：7 天 / 按月，可切金额或 Token
- 模型 token 占比排名
- 点柱子查看当日/当月明细

**桌面小组件**
- 4×2 卡片，显示余额 + 今日消耗 + 续航天数
- App 内刷新联动、AlarmManager 约 1 分钟定时、点击手动刷新

**其他**
- 深浅双主题（跟随系统 / 手动切换，340ms 平滑过渡）
- 离线快照：连不上时显示上次数据并标记
- UDP 自动发现：电脑换 IP 也能找到
- 崩溃截获：出问题显示完整堆栈

### 下载

- `DSH余额.apk` —— Android 安装包（192.9 KB）
- `DSH余额-源码.zip` —— 完整源码

**APK 说明**：使用调试密钥签名，仅适合个人使用，不适用于应用商店分发。首次安装需允许「未知来源」。

### 快速开始

**官方 API（推荐，不需要电脑）**

1. 在 [platform.deepseek.com](https://platform.deepseek.com) → API keys 创建 `sk-` 开头的密钥
2. 打开 App → 右上角 ⚙ → 数据来源选「官方 API Key」
3. 粘贴密钥 → 保存并刷新

> API Key 只存在手机本地，不上传、不写进安装包。

**局域网模式（有完整历史）**

```bash
node balance-server.js
```

然后在 App 里选「局域网·电脑」，地址留空即可自动发现。

### 已知限制

- 官方接口只返回余额，不提供消费历史与模型分布（接口限制，非实现问题）
- 小组件 1 分钟定时在深度休眠时会被系统节流
- 局域网模式依赖 DSH 账本格式 `~/.dsh/.dshw-usage.json`

### 从源码构建

不需要 Gradle，纯 `aapt2 + ECJ + d8 + apksigner` 手工流水线。详见 [README](https://github.com/0317tiffany/dsh-balance#方式-c自行编译-apk)。

依赖：JDK 17+、Android SDK build-tools 34 + platform 34、Node.js 18+、Eclipse ECJ。

### 许可

MIT
