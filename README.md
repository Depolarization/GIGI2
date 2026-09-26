<div align="center">

<img src="docs/img/icon-preview.png" width="240" alt="GIGI 图标" />

# GIGI

**七圣召唤赛事助手 · Android**

玩家战绩查询 · 卡面图鉴 · 多语言 · 自动更新

</div>

---

## 这是什么

GIGI 是一款为《原神》「七圣召唤」（Genius Invokation TCG）赛事玩家做的 Android 工具：

- **战绩查询**：输入玩家 UID，查看公开赛事战绩与段位。
- **卡面图鉴**：浏览全部角色牌、行动牌、装备牌、事件牌、支援牌、魔物牌；**包含 GIF 动图卡面**。
- **多语言**：简中 / 繁中 / 英文三语随系统切换。
- **自动更新**：内置 GitHub Releases 更新检查。

> ⚠️ 本应用**不抓取个人账号凭据**。卡面/战绩数据来自七圣赛事站与米游社公开接口；登录凭据仅保存在本机 Keystore 加密区。

## 截图

> 桌面图标、卡面图鉴、关于对话框三处实机截图位如下，由真机补图后替换。

| 桌面图标 | 主页 / 卡面图鉴 | 关于对话框 |
| :---: | :---: | :---: |
| （占位） | （占位） | （占位） |
| 新图标：亮绿渐变背景 + 五角星 + 金色圆点 | 卡面 GIF 动图正常播放 | 「检查更新」「关闭」两按钮，无挤压 |

## 技术栈

| 项 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| UI | Jetpack Compose · Material 3 |
| 图片 | Coil 2.7（含 `coil-gif` 解码器） |
| 异步 | Kotlin Coroutines · Flow · StateFlow |
| 网络 | OkHttp · kotlinx.serialization |
| 签名 | 本地 keystore（gradle 环境变量注入） |
| 最低 Android | API 24 (Android 7.0) |
| 目标 Android | API 36 (Android 16) |

## 主要功能

### 🎴 卡面图鉴（含 GIF）
全部卡面由 Coil 统一加载；GIF 动图通过 `GigiApp : ImageLoaderFactory`
注册 `GifDecoder.Factory()` 实现（coil 2.x 不走 ServiceLoader，必须显式接线）。

### 🏅 段位识别
原实现是硬编码中文 `when` 分支，接口随语言返回英文段位名时会全部落空。
现改为中英双语查表（黄铜/Brass、星银/Silver、赤金/Gold、影幻/Phantom），
查不到时回落原始值而非空白。

### 🌍 三语
- `values/strings.xml`（简体中文）
- `values-zh-rTW/strings.xml`（繁体中文）
- `values-en/strings.xml`（English）

### 🔄 自动更新
关于对话框 → 「检查更新」→ 结果以独立弹窗呈现（含新版本号 / release notes / 下载按钮）；
检查期间按钮禁用，避免重复触发。

### 🎨 图标几何单一来源
所有图标几何（星外径/内径、圆点位置、背景渐变）由 `tools/gen_launcher_icon.py`
统一维护；同一份几何同步产出：
- `mipmap-*/ic_launcher{,_round}.png`（5 档密度）
- `drawable/ic_launcher_foreground.xml`（adaptive icon 前景层，Android 8+ 实际使用）
- `drawable/ic_launcher_monochrome.xml`（Android 13+ themed icon）

> ⚠️ 改形状务必改脚本后重跑，**禁止手改矢量层**——
> 否则必然与位图层漂移（V10-E 已踩过：旧 `ic_launcher_foreground.xml`
> 仍带白框，导致用户真机看到的图标形状与 PNG 不一致）。

## 安装

从 [Releases](../../releases) 页下载 `app-release.apk`，安装到 Android 7.0+ 设备。

> 启用「未知来源安装」即可。首次打开若提示更新权限，请允许。

## 构建（开发者）

```bash
# Debug
./gradlew assembleDebug

# Release（需要签名环境变量，参见 app/build.gradle.kts）
export GIGI_STORE_PASSWORD=...
export GIGI_KEY_ALIAS=...
export GIGI_KEY_PASSWORD=...
./gradlew assembleRelease

# 单元测试
./gradlew testDebugUnitTest

# 重新生成图标（改 tools/gen_launcher_icon.py 后）
python tools/gen_launcher_icon.py
```

## 目录结构（核心）

```
app/src/main/java/com/gigi/tcg/
├── GigiApp.kt                  # Application；ImageLoaderFactory 在此注册 GIF 解码
├── MainActivity.kt
├── ui/
│   ├── about/                  # 关于页 + UpdateState（查询终态判定）
│   └── dialogs/about/          # AboutDialog（V10-B 互斥结果弹窗）
├── data/                       # 数据层
├── di/                         # AppContainer
└── i18n/                       # LocaleStrings

tools/gen_launcher_icon.py      # 图标几何单一来源（4x 超采样 + 矢量 XML）
app/src/main/res/values{,-en,-zh-rTW}/strings.xml
```

## 致谢

- 卡牌数据与卡面图片来自米游社七圣 Wiki；
- 战绩接口基于七圣赛事站公开数据；
- 段位 / 卡牌类型术语按业界通用译名（非官方）。

## 许可

MIT（仓库内 `LICENSE`）。