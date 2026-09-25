# U2B-WIKIABOUT — 图鉴页 B1/B2/B3 + 关于对话框 B4

status: blocked（代码/编译/单测全通过；真机目视验收被登录页挡住，需用户扫码）
branch: main
started: 2026-09-25

## 范围（独占文件）
1. `app/src/main/java/com/gigi/tcg/ui/screens/cardwiki/CardWikiRoute.kt` （B1/B2/B3）
2. `app/src/main/java/com/gigi/tcg/ui/dialogs/about/AboutDialog.kt` （B4）
只读：`CardWikiViewModel.kt`、`AppImage.kt`（均未改动）

## 进度
- [x] 改前闸门 `git diff --quiet` → exit 0（两文件干净，可改）
- [x] B1 搜索框上下边距统一：`.height(48.dp)` 固定高度（清空按钮的 48dp 触摸目标不再撑高文本框，
      有无内容等高）；FilterBar `vertical = 8.dp` + `Arrangement.spacedBy(8.dp)`，上下留白与行距对称
- [x] B2 筛选条件下拉菜单化：入口 `OutlinedButton`（未选="筛选"，已选="筛选 · 已选 N 项"）
      → `DropdownMenu`；每个维度一组（组标题 Text = def.label，组内 Row+horizontalScroll，
      横向滚动 scope 只在组内），组间距 8dp；选中后**保持展开**（便于连续调多维度，代码注释已写明）；
      FilterChip 保留 selected 语义；未用 ExposedDropdownMenu（避免 Experimental API 版本耦合），
      菜单宽度用 BoxWithConstraints 对齐入口按钮宽度
- [x] B3 网格 `GridCells.Fixed(2)`，`GRID_CARD_MIN_WIDTH` 常量与注释已删；
      WikiCard 标题核对：**原已** `maxLines = 1` + `TextOverflow.Ellipsis`，padding(horizontal 8/vertical 6) 未动
- [x] 编译 `:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- [x] 单测 `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**（0 失败）
- [x] B4 关于对话框：删「性质」Fact 行；反馈段删 B 站空间链接、只留"若在使用中遇到任何异常错误，欢迎反馈。"；
      `appendLink` 保留（原理与致谢段仍在用）；按钮改 dismissButton「关闭」onClose + confirmButton「反馈」
      ACTION_VIEW 打开 FEEDBACK_URL，`runCatching` 静默吞 ActivityNotFoundException（无 Toast 牵连其它文件）；
      「使用要点」「数据来源与权限」「原理与致谢」「版本」文案未动
- [ ] 真机目视验收 d/e/f（2 列网格、长名省略、下拉分组、关于双按钮）→ **blocked：需用户扫码**
- [x] logcat FATAL EXCEPTION 检查 → 0 命中（未崩）

## 验收门槛记录
① 编译：
```
$ ./gradlew.bat :app:compileDebugKotlin --console=plain
> Task :app:compileDebugKotlin UP-TO-DATE
BUILD SUCCESSFUL in 2s
```
（UP-TO-DATE 因并发构建已用同一份源码编译过；已核验产物内容：
 `app/build/tmp/kotlin-classes/debug/.../CardWikiRouteKt$FilterMenu$2$2.class` 含"筛选"/"已选"，
 `AboutDialogKt.class` 含 `space.bilibili.com/560719483`，about 包 lambda 类含"反馈"/"关闭"，
  且 Dexphase/性质 命中 0；APK `app-debug.apk` 16:54:36 > 源码最后改动 16:54:23，classes5.dex 含 FEEDBACK_URL）

② 单测：
```
$ ./gradlew.bat :app:testDebugUnitTest --console=plain
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 4s
22 actionable tasks: 4 executed, 18 up-to-date
```

③ 文案/结构自查：
```
$ grep -n "性质\|Dexphase\|FEEDBACK_URL\|dismissButton\|confirmButton\|ACTION_VIEW" AboutDialog.kt
28:private const val FEEDBACK_URL = "https://space.bilibili.com/560719483"
100:        confirmButton = {
105:                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL)))
110:        dismissButton = {
$ grep -c "性质\|Dexphase" AboutDialog.kt            → 0   ✅
$ grep -n "GridCells.Fixed(2)\|GridCells.Adaptive\|GRID_CARD_MIN_WIDTH" CardWikiRoute.kt
357:        columns = GridCells.Fixed(2),
$ grep -c "GridCells.Adaptive\|GRID_CARD_MIN_WIDTH" CardWikiRoute.kt → 0   ✅
```

④ 真机（-s 127.0.0.1:7555，MuMu 900x1600/320dpi/Android12）：
```
$ adb -s 127.0.0.1:7555 install -r app-debug.apk     → Success
$ adb -s 127.0.0.1:7555 shell monkey -p com.gigi.tcg 1 → Events injected: 1，等 12s
$ dumpsys activity activities | grep topResumedActivity
    topResumedActivity=ActivityRecord{... com.gigi.tcg/.MainActivity t124}
```
→ 停在**登录页（二维码）**：`AppGate` 登录闸门包住整个 UI，
  `CardWikiRoute`（GigiNavHost.kt:255）与 `AboutDialog`（GigiNavHost.kt:278）都在登录后的 Scaffold 内，
  不扫码无法进入。已按要求：不清数据、不反复重装，截图 + UI dump 记录现状。
  登录页文案证据（UI dump）：GIGI×1 / 扫码×2 / 登录×2 / 米游社×2
```
$ adb -s 127.0.0.1:7555 logcat -d | grep -ci "FATAL EXCEPTION"   → 0
$ adb -s 127.0.0.1:7555 shell pidof com.gigi.tcg                  → 3133（进程存活）
```
⇒ d/e/f（图鉴 2 列网格与下拉菜单、关于对话框双按钮）**本棒无法目视验收**，blocked 待用户扫码登录后复跑。

## 证据
- `.task/evidence/U2B/00-登录页-需扫码.png`（当前实测界面：二维码登录页）
- `.task/evidence/U2B/00-登录页-ui-dump.xml`（同上，机器可读）
- 预期但未产出（blocked）：01-图鉴搜索框与两列网格.png / 02-筛选下拉菜单.png / 03-筛选生效.png /
  04-关于新文案与两按钮.png

## 备注 / 阻塞
- 阻塞点：MuMu 内凭据已失效（同仓库早前 .task/evidence 里的登录后截图属更早会话），
  需用户用米游社/云·原神扫 MuMu 屏幕上的二维码完成登录后，d/e/f 目视项才能补验。
- 代码侧无已知问题；危险动作规避：未清数据、未改端口、未动其它文件、未清 build。
