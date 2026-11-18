// Wiki 富文本清洗：官方 `desc.value[]` / `rich_text` 是 HTML 片段，
// 其中「[详情]」是 <span data-type="详情" data-name="<转义 HTML>"> 锚点。
// 步骤必须是「先抽注释，再洗正文」——否则 data-name 会随标签一起被丢掉。
// 纯 JVM、无 Android 依赖，任何异常都退化为空文本，绝不让详情页报错。

package com.gigi.tcg.domain

/** 一条关键词注释：title 取自注释正文第一个冒号前，body 是冒号后的说明 */
data class RichNote(val title: String, val body: String)

/** 清洗结果：可展示正文 + 关键词注释（按 title 去重，保持出现顺序） */
data class WikiRichText(
    val text: String,
    val notes: List<RichNote> = emptyList(),
)

object WikiRichTextParser {

    /**
     * 匹配 span 开标签，属性值里的 `>` 不能截断标签。
     * 魔物牌实测 data-name 里带未转义的 `<p style="...">`（引号是 &quot;、尖括号是裸的），
     * 旧写法 `<span[^>]*>` 会在 data-name 的第一个 `>` 处提前结束，
     * 留下 `" class="wiki-note-text">[详情]` 这类脏文本。
     */
    private val DETAIL_SPAN_OPEN = Regex(
        "<span\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>",
        RegexOption.IGNORE_CASE,
    )
    private val DATA_TYPE_DETAIL = Regex("""data-type\s*=\s*["']详情["']""")
    private val DATA_NAME = Regex("""data-name\s*=\s*["']([^"']*)["']""")

    /** 块级标签转行：官方正文用 <p> 分段，注释里还有 h1/h3 */
    private val BLOCK_BREAK = Regex(
        "(?i)<(?:br|p|/p|div|/div|h[1-6]|/h[1-6]|li|/li|tr|/tr|blockquote|/blockquote)\\b[^>]*>",
    )
    private val TAG = Regex("<[^>]+>")

    /**
     * 整段「详情」锚点：从 `<span` 到最近一个 `</span>`，不要求开标签可解析。
     * 注释 data-name 里可能嵌**多层**详情锚点（伊法·援护射击 实测），
     * 内层 data-name 是双层实体编码，解码后属性里出现裸引号与 `>`，
     * `<[^>]+>` 会在第一个 `>` 处截断标签，留下 `" class="wiki-note-text">[详情]`。
     * 这里只认 `<span` / `data-type="详情"` / `</span>` 三个锚点，天然容忍坏属性。
     */
    private val DETAIL_SPAN_WHOLE = Regex(
        "<span\\b(?:(?!</span>)[\\s\\S])*?data-type\\s*=\\s*[\"']详情[\"'][\\s\\S]*?</span>",
        RegexOption.IGNORE_CASE,
    )
    private val NUMERIC_ENTITY = Regex("&#([xX][0-9a-fA-F]+|[0-9]+);")
    private val MULTI_SPACE = Regex("[\\t\\u00A0 ]+")
    private val MULTI_NEWLINE = Regex("\\n{2,}")

    /** 注释最多解 4 层编码（官方实测 2 层，留余量）；超过仍不稳定则按当前文本收尾 */
    private const val MAX_NOTE_DECODE_PASSES = 4

    /**
     * 输入 HTML 片段（可含 `<html><head></head><body>` 外壳），输出纯文本与注释。
     * 解析失败返回空对象——上游据此显示空态，而不是把异常抛给 UI。
     */
    fun parse(html: String?): WikiRichText {
        if (html.isNullOrBlank()) return WikiRichText("")
        return try {
            val (stripped, notes) = extractNotes(html)
            WikiRichText(
                text = normalize(htmlToPlainText(stripped)),
                notes = notes.distinctBy { it.title },
            )
        } catch (e: Exception) {
            WikiRichText("")
        }
    }

    /**
     * 抽出所有 `data-type="详情"` 的 span：把 data-name 解实体后洗成 note，
     * 同时从正文里删除整个 span（含其中的 `[详情]`）。
     */
    private fun extractNotes(html: String): Pair<String, List<RichNote>> {
        val ranges = mutableListOf<IntRange>()
        val notes = mutableListOf<RichNote>()
        var searchFrom = 0
        while (searchFrom < html.length) {
            val open = DETAIL_SPAN_OPEN.find(html, searchFrom) ?: break
            val tag = open.value
            if (!DATA_TYPE_DETAIL.containsMatchIn(tag)) {
                searchFrom = open.range.last + 1
                continue
            }
            val nameMatch = DATA_NAME.find(tag)
            if (nameMatch == null) {
                searchFrom = open.range.last + 1
                continue
            }
            val close = html.indexOf("</span>", open.range.last + 1)
            if (close < 0) {
                searchFrom = open.range.last + 1
                continue
            }
            val end = close + "</span>".length
            ranges += open.range.first until end
            parseNote(decodeEntities(nameMatch.groupValues[1]))?.let { notes += it }
            searchFrom = end
        }

        if (ranges.isEmpty()) return html to notes
        val sb = StringBuilder(html.length)
        var cursor = 0
        for (range in ranges) {
            if (range.first > cursor) sb.append(html, cursor, range.first)
            cursor = range.last + 1
        }
        if (cursor < html.length) sb.append(html, cursor, html.length)
        return sb.toString() to notes
    }

    /** 注释 HTML → title/body：第一个冒号前作标题；无冒号取首句前 12 字，正文保留全文 */
    private fun parseNote(rawHtml: String): RichNote? {
        val text = normalize(notePlainText(rawHtml))
        if (text.isBlank()) return null
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        val colon = firstLine.indexOfFirst { it == '：' || it == ':' }
        val title: String
        val body: String
        if (colon > 0) {
            title = firstLine.substring(0, colon).trim()
            val rest = buildString {
                val sameLine = firstLine.substring(colon + 1).trim()
                if (sameLine.isNotEmpty()) append(sameLine)
                val following = text.lines().drop(1)
                for (line in following) {
                    if (line.isBlank()) continue
                    if (isNotEmpty()) append('\n')
                    append(line.trim())
                }
            }.trim()
            body = rest.ifBlank { text }
        } else {
            title = firstLine.take(12).trim()
            body = text
        }
        if (title.isBlank()) return null
        return RichNote(title = title, body = body)
    }

    private fun htmlToPlainText(html: String): String {
        val withBreaks = BLOCK_BREAK.replace(html, "\n")
        return decodeEntities(TAG.replace(withBreaks, ""))
    }

    /**
     * 注释正文的多层清洗：官方注释 data-name 里可能再嵌一个「详情」锚点，
     * 且内层是「实体编码的 HTML 再被实体编码一次」。单次「洗标签 → 解实体」之后，
     * 内层标签才变成裸标签，而属性里的裸引号会让 `<[^>]+>` 截断，
     * 残留 `" class="wiki-note-text">[详情]`（用户真机截图：伊法·援护射击）。
     *
     * 因此按「移除详情锚点 → 块级/普通标签洗掉 → 解实体」循环到文本稳定；
     * 只用于注释正文（主描述仍需保留转义标签的字面语义，见单测）。
     */
    private fun notePlainText(html: String): String {
        var current = html
        repeat(MAX_NOTE_DECODE_PASSES) {
            val stripped = TAG.replace(
                BLOCK_BREAK.replace(DETAIL_SPAN_WHOLE.replace(current, ""), "\n"),
                "",
            )
            val decoded = decodeEntities(stripped)
            if (decoded == stripped) return normalize(decoded.replace("[详情]", ""))
            current = decoded
        }
        return normalize(
            TAG.replace(BLOCK_BREAK.replace(DETAIL_SPAN_WHOLE.replace(current, ""), "\n"), "")
                .replace("[详情]", ""),
        )
    }

    /** HTML 实体解码；`&amp;` 最后处理，避免 `&amp;lt;` 被二次解码成标签 */
    private fun decodeEntities(input: String): String {
        var out = input
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = NUMERIC_ENTITY.replace(out) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x", ignoreCase = true)) {
                raw.drop(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            if (code != null && code in 0..0x10FFFF) {
                runCatching { String(Character.toChars(code)) }.getOrDefault(match.value)
            } else {
                match.value
            }
        }
        return out.replace("&amp;", "&")
    }

    private fun normalize(input: String): String = input
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .replace(MULTI_SPACE, " ")
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(MULTI_NEWLINE, "\n")
        .trim()
}
