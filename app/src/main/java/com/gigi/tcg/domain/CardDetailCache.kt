// 卡牌详情解析结果缓存（报告 §8.5）：弹层时代它是应用级单例，升成全屏页后
// 页面 ViewModel 随回退栈销毁，若继续把 LRU 放在 VM 里，每次返回再进都会重新请求。
// 因此上移到 AppContainer，VM 只负责请求/解析/状态机/下载。
//
// key = "<lang>:<entryId>"：切语言后同一张卡是不同文案，不能串。
// 纯 JVM（只有 CardDetail 依赖），JVM 单测直接构造验证 LRU。

package com.gigi.tcg.domain

/** 固定容量 LRU（accessOrder = true：get 也会刷新最近使用顺序） */
class CardDetailCache(private val max: Int = 200) {

    private val entries = object : LinkedHashMap<String, CardDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CardDetail>?) =
            size > max
    }

    @Synchronized
    fun get(key: String): CardDetail? = entries[key]

    @Synchronized
    fun put(key: String, detail: CardDetail) {
        entries[key] = detail
    }

    /** 仅测试与诊断用：当前缓存条数 */
    @Synchronized
    fun size(): Int = entries.size
}
