# U2A-IMAGE 探针

状态: blocked（唯一阻断项 = 真机门槛④：App 全新安装、无凭据，停在登录页，**需用户扫码**；代码修复与静态门槛①②③已全部完成并提交）
开工: 2026-09-25
P0: AppImage 网络图永远停在 shimmer，永不显示真图

## 根因（主代理字节码取证，采信不复议）
- AsyncImagePainter.drawSize 初值 Size.Zero；默认 SizeResolver 挂起等待 drawSize 变正；
  drawSize 只在 onDraw 中更新；而 AppImage 改成仅 Success 分支才 `Image(painter=...)` ⇒ 自锁死循环。

## 修复方案
- 无条件先绘制 painter（让 onDraw 触发、drawSize 变正），再按 state 叠加占位层（仅非 Success）。
- request 构建保持不变（不加 .size()）；函数签名不变。
- ShimmerPlaceholder 在未拿到尺寸时回落纯底色（顺带项，非主因）。

## 步骤
- [x] 改前闸门 `git diff --quiet -- AppImage.kt` → exit 0（文件干净）
- [x] 改 AppImage.kt：Image(painter) 无条件绘制在 when 之前；Success 不叠占位；Error→失败图；else→shimmer
- [x] 设备核验: boot_id=0e07370d-841d-4ce7-af39-c151824f9dc5 一致；但 com.gigi.tcg **未安装**（54 包无 gigi）→ 装机后大概率停在登录页（T11 已清凭据）
- [x] ① compileDebugKotlin BUILD SUCCESSFUL（class 16:53:03 反汇编确认含新代码：SolidColor 常量 + 新行号映射）
- [x] ② testDebugUnitTest BUILD SUCCESSFUL，19 套件/121 用例，failures=0
- [x] ③ grep "Image(" → line 58（在 when@64 之前，无条件绘制）；Success 分支仅 Unit
- [x] ④a assembleDebug BUILD SUCCESSFUL；④b install -r Success
- [ ] ④c ✘ **blocked：停在登录页**。证据 `.task/evidence/U2A/00-启动首屏.png`（登录页二维码 + "请用米游社或云·原神扫码并确认登录"）
      原因：装机前 `pm list packages` 无 com.gigi.tcg（此前已被卸载，凭据随 app data 一并清除）；未清数据、未反复重装，仅装了 1 次。
      已核查无绕行路径：仅 MainActivity 一个 Activity；AppImage 仅 3 个调用点（Avatar.kt:39 / CardCoverSheet.kt:211 / CardWikiRoute.kt:388），全部在登录后页面；登录页不含 AppImage。
      解除条件：用户在当前 MuMu 屏（QR 仍显示）扫码 → 重派本单第④步，补 01/02 两张真图截图即可闭环。
- [x] ⑤ logcat FATAL EXCEPTION 计数 = 0（`adb logcat -d | grep -ci "FATAL EXCEPTION"` → 0）
- [x] 提交：`df3f502 fix: load network images in AppImage`（仅 AppImage.kt）+ 本探针与证据目录（本次 task: 提交）

## 证据
- .task/evidence/U2A/00-启动首屏.png（登录页现状）
- 缺：01-图鉴真图.png / 02-排行榜头像真图.png（被登录页阻断）

## 备注
- 设备: 127.0.0.1:7555 (MuMu, Android12, 900x1600/320)
- 共享工作树并发提示: git status 中 AboutDialog.kt / GigiNavHost.kt / CardWikiRoute.kt / RankRoute.kt / 任务清单-main.md 的改动**非本单**（疑似兄弟子代理在写），本单未 add 未 commit 这些文件；先前一次 `compileDebugKotlin UP-TO-DATE` 亦系并发构建先行编译所致（class 反汇编确认含本单新代码后放行）
- 本单 commit 范围（显式路径）: AppImage.kt + 本探针 + .task/evidence/U2A/
