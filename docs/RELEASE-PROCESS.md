# GIGI 发版标准流程（V55）

本文是**唯一**的发版操作口径，取代散落在会话里的临时步骤。每次发正式版按 §1 → §7 顺序执行，§8 是排障，§9 是可复制清单。

前置阅读：[RELEASE-SIGNING.md](RELEASE-SIGNING.md)（签名口令、keystore、换密钥后果）。

> 🔴 三条铁律
> 1. **先提交、后打包**：release APK 必须能从某个 commit 复现，禁止用带未提交改动的工作区出正式包。
> 2. **不复用版本号**：包已发布就只加版本，不替换资产、不删 Release。
> 3. **镜像缓存要主动清**：旧版客户端（≤1.0.2）只认第一个成功镜像，jsDelivr 不清缓存就会误报「已是最新」。

---

## 0. 一次性前提

| 项 | 检查命令 | 期望 |
|---|---|---|
| release 签名口令 | `"$env:GIGI_STORE_PASSWORD" -ne $null` | True（设置方法见 RELEASE-SIGNING.md） |
| gh 凭据含 workflow scope | `gh auth status` | `Token scopes` 含 `repo`、`workflow` |
| 能访问 github.com | `git ls-remote --heads origin` | 能列出分支 |

本机需要代理时，git / gh / curl 都要显式带环境变量（git 不读 Windows 系统代理）：

```powershell
$env:HTTPS_PROXY='http://127.0.0.1:7897'; $env:HTTP_PROXY='http://127.0.0.1:7897'
```

---

## 1. 冻结工作区并验证

```powershell
git status --short          # 只允许出现工具产物（如 .trae-html-share-packages/）
$env:JAVA_HOME="C:\Users\oscur\.jdks\ms-21.0.12.1"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

- 单测/构建通过后，把待发布改动**全部提交并推送**。
- 未跟踪的工具产物不要入库；确认它们不在 `git add` 范围内。

## 2. 升版本号

只改 `app/build.gradle.kts` 两行：

```kotlin
versionCode = <上一版 + 1>
versionName = "<x.y.z>"
```

单独一个提交，例如 `V54：版本号升至 1.0.4（versionCode 5）`，推送后再打包。
`versionCode` 必须严格递增：客户端「已是最新」判定和系统覆盖安装都依赖它。

## 3. 构建正式包

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

产物：`app\build\outputs\apk\release\app-release.apk`

两道自检：

```powershell
# 1) 签名必须是 release 证书（不是 debug）
& "$((Get-ChildItem 'D:\AndroidSDK\build-tools' -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName)\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk
# 期望：CN=GIGI；SHA-256 与 RELEASE-SIGNING.md 表格一致

# 2) 记录摘要（发布后与 update.json 对照）
(Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256).Hash.ToLower()
```

> 🔴 签名不一致（debug keystore）说明构建环境没读到口令或走了 debug 变体，**不得发布**。

## 4. 创建 GitHub Release

```powershell
gh release create vX.Y.Z "app\build\outputs\apk\release\app-release.apk" --title "vX.Y.Z" --notes "<更新说明>"
```

- **tag 必须等于 `v` + `versionName`**：workflow 会校验，不一致直接失败。
- `--notes` 的内容会被 workflow 原样写进 `update.json` 的 `body`，最终显示在客户端更新弹窗。
  写用户能看懂的新增/改进/修复即可，不要贴内部排障过程。
- **不要**加 `--prerelease`：workflow 明确跳过 prerelease。
- `gh release create` 会在当前默认分支 HEAD 打 tag，所以 §1/§2 的提交必须先推上去。

## 5. 等清单自动同步

```powershell
gh run list --limit 3               # 期望 update-manifest 为 success（约 8–15s）
git fetch origin
git show origin/main:update.json
```

逐项核对：

| 字段 | 期望 |
|---|---|
| `versionName` / `versionCode` | 与本次构建一致 |
| `downloadUrl` | `https://github.com/Depolarization/GIGI2/releases/download/vX.Y.Z/app-release.apk` |
| `sha256` | 与 §3 的 `Get-FileHash` 完全一致 |
| `size` | 与 APK 字节数一致 |
| `publishedAt` | 本次发布时间（非空，多镜像择优依赖它） |

同步本地：`git pull --rebase origin main`。

> 若 workflow 失败：先看 run 日志。最常见原因是 tag 与 `versionName` 不一致、
> Release 里没有 APK 资产、或 main 分支保护禁止 bot 直推。

## 6. 清 jsDelivr 缓存（必做）

```powershell
curl.exe -sS "https://purge.jsdelivr.net/gh/Depolarization/GIGI2@main/update.json"
curl.exe -sS https://cdn.jsdelivr.net/gh/Depolarization/GIGI2@main/update.json   # 复验已是新版本
```

原因：jsDelivr 对 `@main` 缓存最长 12h。新版客户端会并行拉全部镜像、按 `publishedAt` 择优，不受影响；
但 **1.0.2 及更早的客户端是串行「第一个成功即返回」**，jsDelivr 返回旧清单就会直接显示「已是最新」。

> gh-proxy 曾出现对 `update.json` 返回 404 的情况（缓存了文件尚不存在时期的响应），
> 属镜像侧缓存问题，不需要改代码；以 jsDelivr purge 后的结果和 ghfast.top 为准。

## 7. 真机验证（升级路径）

在**上一版**（例如 1.0.3 / vc4）的机器上操作：

1. 「关于」→「检查更新」：2–4s 内显示「当前 A → 最新 B」，来源应为 `mirror:*`（不是 `api:github`）。
2. 冷启动应用：确认**没有**任何检查更新请求（`adb logcat -s GIGI.UpdateChecker` 应为空）。
3. 点「去下载」：
   - 系统下载器走 `ghfast.top` 镜像（日志 `候选=1/3 url=https://ghfast.top/...`）；
   - 4MB 包应在 10s 内下完；
   - 完成后日志出现 `安装包校验通过 sha256=...`，与清单一致；
   - 设备端独立核对：`adb shell sha256sum /sdcard/Download/GIGI-<版本>.apk`。
4. 校验失败路径无需每次演练；改动下载/校验代码时才需要。

> 调试包的签名与 release 包不同：把 release APK 装到 debug 包上会
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，属预期。验证「下载 + 校验」即可，不要据此判断发版失败。

## 8. 常见故障对照

| 现象 | 原因 | 处理 |
|---|---|---|
| workflow 失败：tag 与 versionName 不一致 | 版本号没提交就发布 | 补提交升版本，换新版本号重发（不可复用） |
| workflow 失败：no APK asset | `gh release create` 没带 APK | 删除该 Release，重新按 §4 创建 |
| workflow push 被拒 | main 分支保护 | 调整保护规则或改用 PR 流程 |
| 检查更新显示 `api:github` | 三个镜像都失败（404/超时/未 purge） | 先跑 §6 purge，再查 `update.json` 是否存在 |
| 下载 30s 无进度后失败 | 直链没套镜像（清单缺 sha256 或旧版客户端） | 确认 `update.json` 有 sha256；旧版客户端只能引导手动下载 |
| `api.github.com` 403 | 未认证限流 | 只影响兜底路径；等待或降低检查频率 |
| `git push` 拒绝 workflow 文件 | 凭据缺 `workflow` scope | `gh auth refresh --hostname github.com -s workflow` |

## 9. 发版清单（复制勾选）

- [ ] 工作区干净，改动已提交推送
- [ ] `testDebugUnitTest` + `assembleDebug` 通过
- [ ] `versionCode` 递增、`versionName` 更新并单独提交
- [ ] `assembleRelease` 成功，签名证书 `CN=GIGI`
- [ ] 记录 APK 的 sha256 与 size
- [ ] `gh release create` 的 tag 与 versionName 一致、非 prerelease
- [ ] `update-manifest` success，`update.json` 五字段核对通过
- [ ] jsDelivr purge 完成且复验为新版本
- [ ] 旧版本真机检出更新（来源 `mirror:*`），下载校验通过
- [ ] 冷启动确认无自检请求
