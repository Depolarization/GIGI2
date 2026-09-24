// 米游社 Wiki 卡牌图鉴的字段解析：逐字对照 Web 版 utils/wiki.ts。
// ch_ext / ext 均为"JSON 字符串里再嵌 JSON 字符串"，需二次解析；
// 任何一步解析失败都按空数据处理（字段缺失须容错，不抛异常）。

package com.gigi.tcg.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val WIKI_JSON = Json { ignoreUnknownKeys = true }

@Serializable
data class WikiFilterChild(val label: String? = null)

@Serializable
data class WikiFilterDef(
    val label: String? = null,
    val children: List<WikiFilterChild> = emptyList(),
)

private val TITLE_SUFFIX_PATTERN = Regex("【[^】]+】$")

/**
 * 频道筛选定义：ch_ext 中 attribute_key=="filter" 项的 value（JSON 字符串）再解析
 * → [{label, children:[{label}]}]
 */
fun parseFilterDefs(chExt: String?): List<WikiFilterDef> {
    if (chExt.isNullOrEmpty()) return emptyList()
    return try {
        val attrs = WIKI_JSON.parseToJsonElement(chExt).jsonArray
        for (attr in attrs) {
            val obj = attr.jsonObject
            val key = obj["attribute_key"]?.jsonPrimitive?.contentOrNull
            val value = obj["value"]?.jsonPrimitive?.contentOrNull
            if (key == "filter" && !value.isNullOrEmpty()) {
                val parsed = WIKI_JSON.parseToJsonElement(value)
                return if (parsed is JsonArray) {
                    WIKI_JSON.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(WikiFilterDef.serializer()),
                        parsed,
                    )
                } else {
                    emptyList()
                }
            }
        }
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 移除图鉴标题尾部的分类后缀（如"杜林【角色牌】"→"杜林"）。
 * 后缀为 Wiki 编辑产物，分类已由所在频道表达，展示与搜索均使用去后缀标题。
 */
fun stripTitleSuffix(title: String): String = TITLE_SUFFIX_PATTERN.replace(title, "")

/**
 * 卡牌归属标签：ext → {"c_{233|234|235}": {"filter": {"text": "[\"分类/子项\", ...]"}}}，
 * filter.text 需再次 JSON 解析为字符串数组。
 */
fun parseCardFilters(ext: String?, extKey: String): List<String> {
    if (ext.isNullOrEmpty()) return emptyList()
    return try {
        val root = WIKI_JSON.parseToJsonElement(ext).jsonObject
        val filter = (root[extKey] as? JsonObject)?.get("filter") as? JsonObject
        val text = filter?.get("text")?.jsonPrimitive?.contentOrNull
        if (text.isNullOrEmpty()) return emptyList()
        val arr = WIKI_JSON.parseToJsonElement(text)
        if (arr is JsonArray) {
            // TS: arr.filter(x => typeof x === 'string')——非字符串元素剔除
            arr.filter { it.jsonPrimitive.isString }.map { it.jsonPrimitive.content }
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}
