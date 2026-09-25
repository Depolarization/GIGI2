package com.gigi.tcg.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/** 全局动画令牌：时长、缓动与常用进出场，业务页面统一引用、不要各自手写魔法数字。 */
object Motion {
    /** 快反馈（按压、勾选、图标切换） */
    const val Fast: Int = 150

    /** 强调过渡（页面进出、卡片展开），默认时长 */
    const val Emphasized: Int = 300

    /** 柔和入场（首屏、大图、列表初次出现） */
    const val Gentle: Int = 450

    /** 快反馈 tween */
    fun <T> fast() = tween<T>(durationMillis = Fast, easing = FastOutSlowInEasing)

    /** 强调过渡 tween */
    fun <T> emphasized() = tween<T>(durationMillis = Emphasized, easing = FastOutSlowInEasing)

    /** 柔和入场 tween */
    fun <T> gentle() = tween<T>(durationMillis = Gentle, easing = FastOutSlowInEasing)

    /** 中等回弹 spring：拖拽释放、展开收起等需要物理感的场景 */
    fun <T> emphasizedSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** 内容进场：淡入 + 下方 1/4 高度上滑 */
    val ContentEnter: EnterTransition = fadeIn(tween(Emphasized, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(Emphasized, easing = FastOutSlowInEasing)) { it / 4 }

    /** 内容退场：淡出 + 向下滑出 1/4 高度 */
    val ContentExit: ExitTransition = fadeOut(tween(Emphasized, easing = FastOutSlowInEasing)) +
        slideOutVertically(tween(Emphasized, easing = FastOutSlowInEasing)) { it / 4 }
}
