# GIGI

原神「七圣召唤」（Genius Invokation TCG）的 Android 原生战绩与卡牌数据工具 —— 扫码登录即用，
战绩、排行榜、卡牌使用统计、卡面图鉴一屏看全。

> ⚠️ 本项目**仅供学习交流**，不用于任何商业用途，**不上架任何应用商店**，分发包仅限个人与社区自取。

- 开源协议：[Apache License 2.0](LICENSE)（第三方声明见 [NOTICE](NOTICE)）
- 仓库：<https://github.com/Depolarization/GIGI2>
- 反馈：[提交 Issue](https://github.com/Depolarization/GIGI2/issues)

## 功能特性

以下为当前代码已实现的功能。

| 模块 | 说明 |
| :-- | :-- |
| 扫码登录 | 米游社 / 云·原神二维码登录，二维码带「已扫描」蒙层与失效重试 |
| 多账户 | 本机保存多个账户，顶栏一键切换；退出仅删除当前账户凭据 |
| 凭据安全 | 登录凭据仅存本机（Keystore 加密），不上传任何服务器 |
| 首页战绩 | 分段/战绩概览 + 最近十局对局列表，单击任一项查看对手详情 |
| 积分排行榜 | 巅峰 / 赛事双 Tab（可左右滑动）、首屏 60 条自动续拉、下拉刷新、前三名金银铜语义色 |
| 卡牌使用统计 | 角色牌 / 行动牌双 Tab、角色牌三键排序、行动牌类型筛选、玩家信息卡可展开 |
| 战绩长图导出 | 统计页一键渲染长图并保存到相册（`Pictures/GIGI`），自动过滤零使用卡牌 |
| 卡牌图鉴 | 三分类 + 搜索 + 四维筛选 + 竖版三列网格，卡面可下载到相册 |
| 玩家查询 | 顶栏输入任意 UID，查询该玩家的七圣召唤信息 |
| 服务器切换 | 官服（天空岛）/ 渠道服（世界树），切服后导航图整体重建，不带入旧服数据 |
| 界面适配 | Material3 主题、深浅色、宽屏（≥840dp）自动切换为 NavigationRail 侧栏 |

**说明**：赛事相关数据依赖米哈游侧接口，官方赛事入口调整后该部分有效性不保证。
更新检查 / 公告 / Bug 上报的数据层（`data/github/`）已就绪，界面入口在后续版本接入。

## 构建

### 环境

| 项目 | 版本 |
| :-- | :-- |
| Android Studio | Ladybug（2024.2）及以上 |
| Gradle | 8.14.5（wrapper 自带，下载地址已换阿里云镜像） |
| Kotlin | 2.1.20（Compose 编译器由 Kotlin 插件提供） |
| Android Gradle Plugin | 8.9.1 |
| JDK | 21（命令行构建请显式指定 `JAVA_HOME`） |
| compileSdk / targetSdk | 36 |
| minSdk | 24（Android 7.0） |

依赖仓库默认指向 `maven.aliyun.com`，国内网络可直接同步，无需代理。

### 命令行

```bash
# 调试包
JAVA_HOME="<你的 JDK 21 路径>" ./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 单元测试（纯逻辑层，JVM 上跑，不需要设备）
JAVA_HOME="<你的 JDK 21 路径>" ./gradlew :app:testDebugUnitTest

# 发布包（未配置签名环境变量时产出未签名 APK）
JAVA_HOME="<你的 JDK 21 路径>" ./gradlew :app:assembleRelease
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。用 Android Studio 打开工程根目录，
直接 Run 'app' 也可以。

### 签名

release 签名凭据**全部走环境变量**，仓库不入库任何密钥（`gigi_release.keystore` 已在 `.gitignore`）：

| 环境变量 | 含义 |
| :-- | :-- |
| `GIGI_STORE_PASSWORD` | keystore 密码；为空时 release 不挂签名 |
| `GIGI_KEY_ALIAS` | 密钥别名，默认 `gigi_key` |
| `GIGI_KEY_PASSWORD` | 密钥密码 |

自行签发 keystore 后设置上述变量重新构建即自动签名：

```bash
keytool -genkeypair -keystore gigi_release.keystore -alias gigi_key \
  -keyalg RSA -keysize 2048 -validity 10000
```

release 构建开启 R8 混淆与资源收缩，keep 规则见 `app/proguard-rules.pro`。

## 仓库内维护文件

以下两个文件放在 `main` 分支根目录，由维护者手工编辑，App 经国内可达的 CDN 镜像读取
（`raw.githubusercontent.com` 国内直连超时，客户端会沿 `jsDelivr → gh-proxy → ghfast.top → ghproxy.net`
依次回退）：

**`update.json`** —— 版本信息：

```json
{
  "versionName": "1.1.0",
  "versionCode": 2,
  "body": "更新说明，支持 Markdown 原文",
  "downloadUrl": "https://github.com/Depolarization/GIGI2/releases/download/v1.1.0/app-release.apk",
  "publishedAt": "2026-10-01"
}
```

**`announcement.json`** —— 公告，顶层可为数组或 `{"announcements": [...]}`：

```json
[
  {
    "id": "2026-10-api-change",
    "title": "接口变更提醒",
    "body": "旧版本将无法拉取战绩，请升级。",
    "url": "https://github.com/Depolarization/GIGI2/releases",
    "minVersion": "1.0.0",
    "maxVersion": "1.0.9"
  }
]
```

`minVersion` / `maxVersion` 用于按版本区间投放；同一 `id` 只提示一次（已读记录由客户端保存）。
Release 数据走 `api.github.com`（国内实测可直连），无需镜像文件。

## 工程结构

```
app/src/main/java/com/gigi/tcg/
├── data/       接口传输、凭据存储、缓存、GitHub 基础设施（更新/公告/反馈）
├── di/         手写依赖容器 AppContainer（不使用 Hilt/Dagger）
├── domain/     纯逻辑：分段、胜率、对局格式、节流、Wiki 解析（全部可 JVM 单测）
└── ui/         Jetpack Compose 界面：登录、四个页面、弹窗、长图渲染、主题
app/src/test/   纯逻辑与渲染布局单测
```

界面文案当前为硬编码中文，未接 i18n。

## 免责声明

- 本项目是**非官方**的社区学习项目，与 **米哈游（miHoYo）/ HoYoverse 及其关联公司无任何关系**。
  「原神」「七圣召唤」「米游社」及相关美术、品牌资源的著作权归米哈游所有。
- 全部数据均来自米哈游对外公开的接口与本人生成战绩的登录凭据，本项目**不存储、不再分发**原始数据资源，
  不做任何数据聚合或上传服务器；登录凭据仅保存在本机加密存储区。
- 卡面图片与卡牌数据来自米游社七圣 Wiki 公开接口，仅用于个人查看与本机保存。
- 依据 Apache License 2.0，本软件按「现状」提供，**不含任何明示或暗示的保证**，
  作者对使用本软件造成的任何损害不承担责任。使用即代表你自行承担相关风险。
- 若权利人认为本项目存在不妥，请通过 [Issue](https://github.com/Depolarization/GIGI2/issues) 联系，将及时处理。
