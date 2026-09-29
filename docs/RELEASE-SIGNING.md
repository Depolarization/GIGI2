# GIGI Release 签名说明（V38-C）

`app/build.gradle.kts` 的 release signingConfig **只从环境变量读取口令，没有任何回退**：
缺失（或为空）时配置阶段直接抛 `GradleException` 并给出修复指引。
不允许再出现"未配置 ⇒ 悄悄产出未签名包"的行为。

## keystore 基本信息

| 项 | 值 |
|---|---|
| 文件 | `gigi_release.keystore`（仓库根目录，**已 .gitignore，严禁入库**） |
| 格式 | PKCS12（keytool `-keypasswd` 对此格式不支持：PKCS12 强制密钥口令 == 库口令，`-storepasswd` 会一并改掉） |
| alias | `gigi_key`（PrivateKeyEntry，非秘密，可明文） |
| 证书 SHA-256 指纹 | `A8:4F:21:16:1F:09:DA:F2:F2:08:43:BE:E5:C0:52:3B:B5:5B:55:26:AC:09:AA:81:5C:B9:BA:5B:E8:E6:B1:0E` |
| 有效期 | 2026-09-26 → 2054-02-11 |
| 备份 | `gigi_release.keystore.bak-20260929`（换密码前的原件，同样被 .gitignore 覆盖，**不入 git**） |

> 🔴 口令值本身**不写在本文档、探针、注释或任何入库文件里**。丢失时唯一恢复途径是上面的 `.bak` 备份（旧口令见 V38-C 交接记录，不外传）。

## 三个环境变量

| 变量 | 含义 | 取值 |
|---|---|---|
| `GIGI_STORE_PASSWORD` | keystore 库口令 | 当前强口令（48 位十六进制，口头/密码管理器传递） |
| `GIGI_KEY_PASSWORD` | `gigi_key` 密钥口令 | 与上面相同（PKCS12 约束） |
| `GIGI_KEY_ALIAS` | 密钥别名 | `gigi_key`（缺省即此值，可省略） |

### 怎么设置（Windows 用户级，无需管理员）

```bat
setx GIGI_STORE_PASSWORD "<口令>"
setx GIGI_KEY_PASSWORD   "<口令>"
setx GIGI_KEY_ALIAS      gigi_key
```

⚠️ **`setx` 只对之后新启动的进程生效**。已开着的终端、Android Studio、
CI agent 都读不到新值 —— 设置完必须**重启**它们再构建，否则构建会以
`GradleException: 缺少 release 签名所需环境变量…` 失败（这正是 V9-A 时代"莫名失败"的坑，
现在改为带指引的显式失败）。

**Android Studio 里配置**（不想依赖系统环境变量时）：
`Run → Edit Configurations… → 对应 Gradle/Android App 配置 → Environment variables`
填入 `GIGI_STORE_PASSWORD=<口令>;GIGI_KEY_PASSWORD=<口令>`。改完重启 AS 生效。

验证当前 shell 是否读得到（Git-Bash，不回显口令值）：

```bash
[ -n "$GIGI_STORE_PASSWORD" ] && echo set || echo MISSING
```

## 构建 / 签名

```bash
export JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1"   # 必须 JDK 21，JBR25/Java8 都不行
./gradlew :app:assembleRelease                          # 产出 app/build/outputs/apk/release/
```

负向自检（应失败且报出上面的指引）：

```bash
env -u GIGI_STORE_PASSWORD -u GIGI_KEY_PASSWORD ./gradlew :app:assembleRelease
```

查看/核对指纹（口令用 `:env` 传，不落命令行）：

```bash
export KP="$GIGI_STORE_PASSWORD"
"$JAVA_HOME/bin/keytool" -list -v -keystore gigi_release.keystore -storepass:env KP | grep -i sha256
```

## ⚠️ 换密码对已装机的影响（V38-C 实测定论）

**换库/密钥口令不触碰私钥与证书 ⇒ APK 签名指纹不变 ⇒ 真机上已装的
`com.gigi.tcg` 仍可 `adb install -r` 覆盖安装，登录态不丢、无需卸载重装。**
（前提：只 `-storepasswd`，绝不 `-importkeystore` 重建/换密钥。换密钥才会导致
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。）
2026-09-29 换密码前后证书 SHA-256 均为上表同一值，已实测一致。

## 换密钥（仅在密钥泄露/过期时才做，后果严重）

若确需重新生成 keystore（**签名必然改变**）：

```bash
keytool -genkeypair -keystore gigi_release.keystore -storetype PKCS12 \
  -alias gigi_key -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=GIGI" -storepass:env KP
```

后果：真机旧包无法覆盖安装，用户必须**卸载重装 ⇒ 凭据/登录态全丢**。
动手前必须先备份现 keystore（如 `.bak-<日期>`）并知会用户。
