---
task: V6I-D-REGRESS
status: doing
---

# V6I-D-REGRESS A1 图片自锁防回归测试

改前闸门 `git diff --quiet -- app/src/main/java/com/gigi/tcg/ui/components/AppImage.kt` → 退出码 0，GATE_PASS，放行。

## 计划
新建 `app/src/test/java/com/gigi/tcg/ui/components/AppImageDrawOrderTest.kt`（纯 JVM，JUnit4，
源码文本断言范式对齐 HomeReloadModeTest.kt），锁死 AppImage 的"painter 无条件先绘制"不变量：

1. `painter = painter` 位置 < `when (painter.state)` 位置（绘制早于状态分支）。
2. `Image(` 行前导空格(8) 严格小于 `is AsyncImagePainter.State.Error ->` 行前导空格(12)（绘制不在分支内）。
3. `is AsyncImagePainter.State.Success` 行 trim 后以 `-> Unit` 结尾（Success 分支不绘制）。
4. `Regex("(?<![A-Za-z])Image\\(")` 全文件匹配数 == 1（只有一处 Image 组件调用）。
5. 源码含 `drawSize` 与 `无条件`（成因注释保留）。

## 进度
- [x] 读源码（AppImage.kt L55-69：Box 内先 Image(painter=painter,…)，后 when(painter.state){Success->Unit; Error->…; else->…}，锚点与任务描述一致）
- [ ] 写测试
- [ ] 变异 A（挪进 Success 分支）→ 预期红
- [ ] 还原 → 变异 B（Error 分支再插 Image）→ 预期红
- [ ] 还原 → 全量测试绿 → done
