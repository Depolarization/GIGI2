package com.gigi.tcg.i18n

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import java.lang.ref.WeakReference

/**
 * 非 Compose 上下文（数据层 / ViewModel）取本地化字符串的桥。
 * Compose 侧优先用 `stringResource(id)`；本桥只服务于拿不到 Context 的层。
 * 解析每次走 application resources 的当前 Configuration ⇒ 跟随系统语言变化。
 */
@SuppressLint("StaticFieldLeak") // 仅存 applicationContext 的 WeakReference，无 Activity 泄漏面
object LocaleStrings {

    /** 解析器：@StringRes id → 当前语言文案；解析不出返回 null。可整体替换（单测无需 Android 环境） */
    private var resolver: ((Int) -> String?)? = null

    private var contextRef: WeakReference<Context>? = null

    /**
     * 注册 Context（GigiApp.onCreate 传 this，即 Application；**不要传 Activity**，防泄漏）。
     * 传 null 解除注册（测试用）。
     */
    fun attach(appContext: Context?) {
        contextRef = appContext?.let { WeakReference(it) }
        resolver = appContext?.let { context -> { id: Int -> resolveFrom(context.resources, id) } }
    }

    /** 注入自定义解析器（单测/特殊宿主用）；传 null 解除 */
    fun installResolverForTest(resolver: ((Int) -> String?)?) {
        this.resolver = resolver
    }

    /** 已注册的 Context；未 attach 为 null。自检用 */
    val attachedContext: Context? get() = contextRef?.get()

    /**
     * 当前界面语言（数据层接口 lang 参数用）：每次读 attach 时的 Context 当前 Configuration ⇒ 跟随系统语言。
     * 未 attach（纯 JVM 单测）/ Configuration 读取异常 ⇒ 回落简中，与 URL 构造函数的默认参数一致。
     */
    fun currentLanguage(): AppLanguage =
        attachedContext?.let { ctx -> runCatching { currentAppLanguage(ctx) }.getOrNull() }
            ?: AppLanguage.SimplifiedChinese

    /** 解析器是否就绪（attach 或注入过）；未就绪时调用方应回落字面量而非哨兵 */
    val resolved: Boolean get() = resolver != null

    /** 按 @StringRes id 取当前系统语言下的字符串；未注册时返回哨兵，便于排查漏接 */
    fun get(@StringRes id: Int): String = runCatching { resolver?.invoke(id) }.getOrNull() ?: "[missing:$id]"

    /** 带参数版本（资源里用 `%1$s` 等占位符）；解析失败同样返回哨兵 */
    fun get(@StringRes id: Int, vararg args: Any): String {
        val template = runCatching { resolver?.invoke(id) }.getOrNull() ?: return "[missing:$id]"
        return runCatching { String.format(template, *args) }.getOrDefault(template)
    }

    /** 解析不出（未注册/id 非法/解析抛错）时回落到调用方字面量 —— 数据层离线单测的默认文案通道 */
    fun getOrDefault(@StringRes id: Int, default: String): String =
        runCatching { resolver?.invoke(id) }.getOrNull() ?: default

    /**
     * 带参数的 [getOrDefault]：模板取不到时用 `default` 作为格式化模板。
     * 供**纯函数**（导出表头/徽章这类要在无 Android 环境的 JVM 单测里直接调用的代码）使用，
     * 让旧调用点与测试断言都不必改。
     */
    fun getOrDefault(@StringRes id: Int, default: String, vararg args: Any): String {
        val template = runCatching { resolver?.invoke(id) }.getOrNull() ?: default
        return runCatching { String.format(template, *args) }.getOrDefault(template)
    }

    private fun resolveFrom(resources: Resources?, @StringRes id: Int): String? =
        runCatching { resources?.getString(id) }.getOrNull()
}
