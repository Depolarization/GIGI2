---
task: V6I-D-REGRESS
status: done
---

# V6I-D-REGRESS A1 图片自锁防回归测试

改前闸门 `git diff --quiet -- app/src/main/java/com/gigi/tcg/ui/components/AppImage.kt` → 退出码 0，GATE_PASS，放行。

## 产出
新建 `app/src/test/java/com/gigi/tcg/ui/components/AppImageDrawOrderTest.kt`（纯 JVM、JUnit4、
源码文本断言范式对齐 HomeReloadModeTest.kt；读 `src/main/java/com/gigi/tcg/ui/components/AppImage.kt`）。
AppImage.kt **零改动**（仅变异验证期间临时改、每步 git checkout 还原，提交前后闸门均 exit=0）。

## 5 条断言各锁什么
1. `painterIsDrawnBeforeStateBranch` — `painter = painter` 的位置 < `when (painter.state)` 的位置：绘制早于状态分支。
2. `painterImageIsNotInsideAnyBranch` — `Image(` 行前导空格（当前 8）严格小于 `is AsyncImagePainter.State.Error ->` 行前导空格（当前 12）：无条件绘制是 Box 直接子级，不在任何分支体内。
3. `successBranchDrawsNothing` — Success 分支行 trim 后以 `-> Unit` 结尾：成功后不再二次绘制。
4. `exactlyOneImageCallInFile` — `Regex("(?<![A-Za-z])Image\\(")` 全文件匹配数 == 1：任何分支里再塞一个 Image 都会红（`rememberAsyncImagePainter(` 被负向断言排除）。
5. `deadlockCauseCommentIsKept` — 源码保留 `drawSize` 与 `无条件` 两个关键词：成因注释不被删。

## 变异验证（核心交付）

### 变异 A：把 Image(painter=painter,…) 整块挪进 Success 分支（Success -> { Image(...) }）
预期 1/2/3 至少一条红 → **实际三条全红**（4、5 保持绿，符合"各自只守自己"）：
```
AppImageDrawOrderTest > painterIsDrawnBeforeStateBranch FAILED
  java.lang.AssertionError: 绘制必须早于状态分支（A1 死锁回归：painter@2295, when@2177）
AppImageDrawOrderTest > painterImageIsNotInsideAnyBranch FAILED
  java.lang.AssertionError: Image( 行缩进(16)必须严格小于分支行缩进(12)，否则绘制被关进了某个状态分支（A1 死锁会复发）
AppImageDrawOrderTest > successBranchDrawsNothing FAILED
  java.lang.AssertionError: Success 分支应为 -> Unit（占位叠加由无条件绘制承担）: is AsyncImagePainter.State.Success -> {
```
还原：`git checkout -- AppImage.kt` → `git diff --quiet` exit=0 ✅

### 变异 B：在 Error 分支内再插一个 `Image(painter = painter, …)`
预期仅断言 4 红 → **实际仅 4 红**：
```
AppImageDrawOrderTest > exactlyOneImageCallInFile FAILED
  java.lang.AssertionError: 全文件应恰有 1 处 Image( 组件调用，实际=2
```
还原：`git checkout -- AppImage.kt` → `git diff --quiet` exit=0 ✅

### 恢复后全量绿
`JAVA_HOME=ms-21.0.12.1 ./gradlew :app:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL**。
`TEST-com.gigi.tcg.ui.components.AppImageDrawOrderTest.xml`：**tests="5" skipped="0" failures="0" errors="0"**。

## 验收对照
- [x] 全量 testDebugUnitTest BUILD SUCCESSFUL（--rerun-tasks，JDK 21）
- [x] 本类 XML tests=5 ≥ 5，failures=0，errors=0
- [x] `git status --short` 无 AppImage.kt；提交后 `git diff --quiet` 再验 exit=0
- [x] commit：a98e222 `test: lock unconditional painter draw order in AppImage (A1 regression)`（署名 GIGI-Bot，只含本测试 + 本探针两个文件）

## 备注
- 变异 A 首轮跑出过一版"缩进=全行空格数"的读数（16 vs 15，结论仍正确），随即把 helper 收紧为
  `takeWhile { it == ' ' }.length` 只数前导空格，重跑得到上面 16 vs 12 的干净读数。
- 并发构建按 ENV-NOTES 一律 `--rerun-tasks`；本次未受其他棒干扰（编译错误仅出现在我自己的测试文件，已修）。
