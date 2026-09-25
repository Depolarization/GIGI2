// A1 回归锁：AppImage 必须"无条件先绘制 painter，再按 state 叠加占位"。
// 历史缺陷：只在 State.Success 分支绘制 → coil 默认 SizeResolver 挂起等正尺寸、
// drawSize 只在 onDraw 更新 → 请求与绘制互相死锁，图片永远停在 shimmer。
// 本测试对真实源码做文本断言，防止后人"顺手优化"把 Image 挪进分支里复发死锁。
package com.gigi.tcg.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppImageDrawOrderTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/components/AppImage.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    private fun lineIndexOf(prefix: String): Int {
        val line = src.lines().firstOrNull { it.trim().startsWith(prefix) }
        assertTrue("源码中找不到以 '$prefix' 开头的行", line != null)
        return line!!.takeWhile { c -> c == ' ' }.length
    }

    /** 不变量 1：painter 的绘制（Image(painter = painter)）出现在状态分支 when (painter.state) 之前 */
    @Test
    fun painterIsDrawnBeforeStateBranch() {
        val drawPos = src.indexOf("painter = painter")
        val branchPos = src.indexOf("when (painter.state)")
        assertTrue("应存在 painter = painter 绘制", drawPos >= 0)
        assertTrue("应存在 when (painter.state) 状态分支", branchPos >= 0)
        assertTrue(
            "绘制必须早于状态分支（A1 死锁回归：painter@$drawPos, when@$branchPos）",
            drawPos < branchPos,
        )
    }

    /** 不变量 2：Image(...) 调用是 Box 的直接子级，不在任何 when 分支体内（比较缩进深度） */
    @Test
    fun painterImageIsNotInsideAnyBranch() {
        val imageIndent = lineIndexOf("Image(")
        val errorBranchIndent = lineIndexOf("is AsyncImagePainter.State.Error ->")
        assertTrue(
            "Image( 行缩进($imageIndent)必须严格小于分支行缩进($errorBranchIndent)，" +
                "否则绘制被关进了某个状态分支（A1 死锁会复发）",
            imageIndent < errorBranchIndent,
        )
    }

    /** 不变量 3：Success 分支体是 Unit，不再绘制（全工程只画那一次无条件绘制） */
    @Test
    fun successBranchDrawsNothing() {
        val successLine = src.lines().firstOrNull { it.trim().startsWith("is AsyncImagePainter.State.Success") }
        assertTrue("应存在 Success 分支行", successLine != null)
        assertTrue(
            "Success 分支应为 -> Unit（占位叠加由无条件绘制承担）: ${successLine!!.trim()}",
            successLine.trim().endsWith("-> Unit"),
        )
    }

    /** 不变量 4：全文件恰有一处 Image 组件调用，防止有人在某个分支里再放一个 Image */
    @Test
    fun exactlyOneImageCallInFile() {
        val count = Regex("(?<![A-Za-z])Image\\(").findAll(src).count()
        assertTrue("全文件应恰有 1 处 Image( 组件调用，实际=$count", count == 1)
    }

    /** 不变量 5：成因注释保留（drawSize / 无条件），删注释等于拆掉防回归的路标 */
    @Test
    fun deadlockCauseCommentIsKept() {
        assertTrue("注释应说明 drawSize 只在 onDraw 更新", src.contains("drawSize"))
        assertTrue("注释应说明必须无条件绘制", src.contains("无条件"))
    }
}
