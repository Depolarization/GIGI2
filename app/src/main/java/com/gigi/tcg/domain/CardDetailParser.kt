// 卡牌详情解析（JSON-first）：官方 entry_page 的 modules[].components[].data 是 JSON 字符串，
// 技能名/类型/图标/费用/卡名/卡图/属性都是结构化字段；只有 desc.value / rich_text 是 HTML。
// 本文件只做「选模块 → 反序列化 → 调 WikiRichTextParser」，纯 JVM、无 Android 依赖。

package com.gigi.tcg.domain

import com.gigi.tcg.data.model.CardBasicInfo
import com.gigi.tcg.data.model.CardEffectData
import com.gigi.tcg.data.model.CardSkillData
import com.gigi.tcg.data.model.CardSkillListData
import com.gigi.tcg.data.model.EntryPageData
import com.gigi.tcg.data.model.EntryPageModule
import kotlinx.serialization.json.Json

/** 卡牌元素：取自基础信息 attr「元素：」，决定元素骰底色与正文关键词颜色 */
enum class CardElement {
    Wind,
    Water,
    Fire,
    Thunder,
    Ice,
    Rock,
    Grass,
    Physical,
    Unknown,
    ;

    companion object {
        fun fromName(value: String?): CardElement {
            val text = value.orEmpty()
            return when {
                text.contains("风") -> Wind
                text.contains("水") -> Water
                text.contains("火") -> Fire
                text.contains("雷") -> Thunder
                text.contains("冰") -> Ice
                text.contains("岩") -> Rock
                text.contains("草") -> Grass
                text.contains("物理") -> Physical
                else -> Unknown
            }
        }
    }
}

/** 费用类型：元素骰 / 无色骰 / 充能 / 中性（未知技能类型） */
enum class CostKind { Element, Untyped, Energy, Neutral }

data class SkillCost(val kind: CostKind, val amount: Int)

/** 一个可展示技能：费用由 life/energy + skill_type 推断，描述已洗成纯文本 */
data class CardSkill(
    val name: String,
    val type: String,
    val iconUrl: String?,
    val costs: List<SkillCost>,
    val descLabel: String,
    val description: String,
    val notes: List<RichNote>,
)

/** 行动牌的单个效果块（rich_text） */
data class CardEffect(
    val label: String,
    val description: String,
    val notes: List<RichNote>,
)

/**
 * 卡牌详情。`skills` 与 `effect` 都可能为空（Wiki 缺模块），
 * 但 `info` 一定存在——基础信息都解析不出时 [CardDetailParser.parse] 返回 null，由上层进 Error。
 */
data class CardDetail(
    val info: CardBasicInfo,
    val element: CardElement,
    val skills: List<CardSkill>,
    val effect: CardEffect?,
) {
    /** 无技能、只有效果 ⇒ 行动牌；Tab 文案换成「效果」 */
    val isAction: Boolean get() = skills.isEmpty() && effect != null
}

object CardDetailParser {

    const val BASIC_INFO_MODULE = "基础信息"
    const val SKILL_MODULE = "卡牌技能"
    const val EFFECT_MODULE = "效果描述"

    /**
     * 解析 entry_page：基础信息失败返回 null（现有 Error 态）；
     * 技能/效果失败只退化空列表，不阻断卡面 Tab。
     */
    fun parse(json: Json, data: EntryPageData): CardDetail? {
        val modules = data.page?.modules.orEmpty()
        val info = modules
            .firstOrNull { it.name == BASIC_INFO_MODULE }
            ?.components
            ?.firstOrNull()
            ?.data
            ?.let { decode(json, it, CardBasicInfo.serializer()) }
            ?: return null

        val skills = parseSkills(json, modules)
        val effect = parseEffect(json, modules)
        return CardDetail(
            info = info,
            element = elementOf(info),
            skills = skills,
            effect = effect,
        )
    }

    /**
     * 取第一个 `卡牌技能` 模块（甘雨等变体卡同页有两个模块，第一个才是基础卡）。
     * 技能按 (name,type) 去重；无名项丢弃。
     */
    private fun parseSkills(
        json: Json,
        modules: List<EntryPageModule>,
    ): List<CardSkill> {
        val raw = modules
            .firstOrNull { it.name == SKILL_MODULE }
            ?.components
            ?.firstOrNull()
            ?.data
            ?: return emptyList()
        val list = decode(json, raw, CardSkillListData.serializer())?.list.orEmpty()
        val seen = mutableSetOf<String>()
        return list.mapNotNull { item ->
            val skill = toSkill(item) ?: return@mapNotNull null
            if (!seen.add("${skill.name}|${skill.type}")) return@mapNotNull null
            skill
        }
    }

    private fun toSkill(item: CardSkillData): CardSkill? {
        val name = item.name?.trim().orEmpty()
        if (name.isEmpty()) return null
        val type = item.type?.trim().orEmpty()
        val parsed = mergeRichText(item.desc?.value.orEmpty())
        return CardSkill(
            name = name,
            type = type,
            iconUrl = item.icon?.takeIf { it.isNotBlank() },
            costs = costsFor(type, item.life, item.energy),
            descLabel = item.desc?.key?.trim().orEmpty(),
            description = parsed.text,
            notes = parsed.notes,
        )
    }

    /** 行动牌：第一个非空 `效果描述` 模块（同页还有空的占位模块） */
    private fun parseEffect(
        json: Json,
        modules: List<EntryPageModule>,
    ): CardEffect? {
        for (module in modules) {
            if (module.name != EFFECT_MODULE) continue
            val raw = module.components?.firstOrNull()?.data ?: continue
            val richText = decode(json, raw, CardEffectData.serializer())?.richText ?: continue
            val parsed = WikiRichTextParser.parse(richText)
            if (parsed.text.isBlank()) continue
            return CardEffect(
                label = module.name.orEmpty(),
                description = parsed.text,
                notes = parsed.notes,
            )
        }
        return null
    }

    /** 多个 desc.value 片段按空行拼接，注释按 title 去重 */
    private fun mergeRichText(html: List<String>): WikiRichText {
        val texts = mutableListOf<String>()
        val notes = mutableListOf<RichNote>()
        for (fragment in html) {
            val parsed = WikiRichTextParser.parse(fragment)
            if (parsed.text.isNotBlank()) texts += parsed.text
            notes += parsed.notes
        }
        return WikiRichText(
            text = texts.joinToString("\n\n"),
            notes = notes.distinctBy { it.title },
        )
    }

    /**
     * 费用推断（报告 07 风险表口径）：
     * - 普通攻击：life=元素骰、energy=无色骰
     * - 元素战技：life=元素骰，energy 忽略（实际恒 0）
     * - 元素爆发：life=元素骰、energy=充能
     * - 未知类型：life 走中性徽章，避免把 energy 误标成充能
     */
    fun costsFor(type: String?, life: Int?, energy: Int?): List<SkillCost> {
        val elementCost = life ?: 0
        val extraCost = energy ?: 0
        val typeText = type.orEmpty()
        val known = typeText.contains("普通攻击") ||
            typeText.contains("元素战技") ||
            typeText.contains("元素爆发")
        val result = mutableListOf<SkillCost>()
        if (elementCost > 0) {
            result += SkillCost(
                kind = if (known) CostKind.Element else CostKind.Neutral,
                amount = elementCost,
            )
        }
        when {
            typeText.contains("普通攻击") && extraCost > 0 ->
                result += SkillCost(CostKind.Untyped, extraCost)

            typeText.contains("元素爆发") && extraCost > 0 ->
                result += SkillCost(CostKind.Energy, extraCost)
        }
        return result
    }

    /** 基础信息 attr「元素：」→ [CardElement] */
    fun elementOf(info: CardBasicInfo): CardElement {
        val values = info.attr.orEmpty()
            .firstOrNull { normalizeKey(it.key) == "元素" }
            ?.value
        return CardElement.fromName(values?.firstOrNull()?.let { normalizeAttrValue(it) })
    }

    fun normalizeKey(key: String?): String = key
        ?.trim()
        ?.trimEnd('：', ':')
        ?.trim()
        .orEmpty()

    /**
     * attr 的 key / value 冒号位置官方并不统一（实测 502960 是 key 带冒号、value 干净；
     * 502488 是 key 无冒号、value 前缀「：」），读取时必须双向归一，否则芯片上屏「：水元素」。
     * 只去掉首尾的全/半角冒号，并折叠连续空白；中间冒号（如「角色邀请·友好对局」）保持原样。
     */
    fun normalizeAttrValue(raw: String?): String = raw
        ?.trim()
        ?.trimStart('：', ':')
        ?.trimEnd('：', ':')
        ?.trim()
        ?.replace(Regex("[\\t\\u00A0 ]+"), " ")
        .orEmpty()

    private fun <T> decode(
        json: Json,
        raw: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T? = try {
        json.decodeFromString(serializer, raw)
    } catch (e: Exception) {
        null
    }
}
