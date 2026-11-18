package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiki 富文本清洗：先抽 data-name 注释、再洗正文，顺序错了会把注释连标签一起丢掉。
 * 样本取自官方 entry_page（法尔伽 510291 / 重光临晨 510307 / 炽热骗骗花 502960）的真实 HTML。
 */
class WikiRichTextTest {

    @Test
    fun `抽取 data-name 注释并从正文移除详情锚点`() {
        val html = "造成2点<strong><u>物理伤害</u></strong>" +
            "<span data-type=\"详情\" data-name=\"&lt;p&gt;&lt;strong&gt;物理伤害&lt;/strong&gt;：&lt;/p&gt;" +
            "&lt;p&gt;物理伤害不会附着元素，也不会发生元素反应。&lt;/p&gt;\" class=\"wiki-note-text\">" +
            "<sup class=\"wiki-sup\">[详情]</sup></span>。"

        val result = WikiRichTextParser.parse(html)

        assertEquals("造成2点物理伤害。", result.text)
        assertEquals(1, result.notes.size)
        assertEquals("物理伤害", result.notes[0].title)
        assertEquals("物理伤害不会附着元素，也不会发生元素反应。", result.notes[0].body)
    }

    @Test
    fun `魔物牌 data-name 里的未转义 p 标签不截断开标签`() {
        // 真机复现（炽热骗骗花 502960）：data-name 值里是**未转义**的 <p style=&quot;…&quot;>，
        // 旧写法 <span[^>]*> 会在 data-name 内第一个 `>` 处提前结束，正文残留
        // `" class="wiki-note-text">[详情]` 这类脏文本。
        val html = "<p>造成3点<strong><span style=\"color: rgb(207, 19, 34)\"><u>火元素伤害</u></span>" +
            "</strong><span data-type=\"详情\" data-name=\"<p style=&quot;white-space: pre-wrap;&quot;>" +
            "火元素伤害：</p><p style=&quot;white-space: pre-wrap;&quot;>附着火元素，可发生元素反应：</p>" +
            "<p style=&quot;white-space: pre-wrap;&quot;><strong>融化</strong>：本伤害+2</p>\" " +
            "class=\"wiki-note-text\"><sup class=\"wiki-sup\">[详情]</sup></span>。</p>"

        val result = WikiRichTextParser.parse(html)

        assertEquals("造成3点火元素伤害。", result.text)
        assertEquals(1, result.notes.size)
        assertEquals("火元素伤害", result.notes[0].title)
        assertTrue(result.notes[0].body.contains("附着火元素，可发生元素反应："))
        assertTrue(result.notes[0].body.contains("融化：本伤害+2"))
        assertFalse(result.text.contains("[详情]"))
        assertFalse(result.text.contains("class="))
    }

    @Test
    fun `魔物牌注释内的加粗与方括号原样保留`() {
        // 同一张卡的「燃烧」分支：注释正文里既有 <strong> 又有 [燃烧烈焰] 方括号
        val html = "<p>造成7点火元素伤害<span data-type=\"详情\" data-name=\"<p style=&quot;white-space: " +
            "pre-wrap;&quot;>火元素伤害：</p><p style=&quot;white-space: pre-wrap;&quot;><strong>燃烧</strong>：" +
            "本伤害+1，生成回合结束时造成1点火伤害的[<strong>燃烧烈焰</strong>]（可生效1次，最多叠加到2次）" +
            "</p>\" class=\"wiki-note-text\"><sup class=\"wiki-sup\">[详情]</sup></span>。</p>"

        val result = WikiRichTextParser.parse(html)

        assertEquals("造成7点火元素伤害。", result.text)
        assertEquals("火元素伤害", result.notes[0].title)
        assertEquals(
            "燃烧：本伤害+1，生成回合结束时造成1点火伤害的[燃烧烈焰]（可生效1次，最多叠加到2次）",
            result.notes[0].body,
        )
    }

    @Test
    fun `注释里嵌套的双层编码详情锚点不泄漏属性文本`() {
        // 真机复现（伊法 506919·空天疾护 的「援护射击」注释）：
        // 外层 data-name 里又嵌了一个 data-type="详情" 的 span，内层 data-name 是双层实体编码。
        // 旧实现只解一层就洗标签，裸引号会把内层开标签截断，正文残留
        // `" class="wiki-note-text">[详情]`。
        val note = "&lt;p&gt;&lt;strong&gt;援护射击：&lt;/strong&gt;&lt;/p&gt;" +
            "&lt;p&gt;&lt;strong&gt;特技&lt;/strong&gt;&lt;/p&gt;" +
            "&lt;p&gt;&lt;strong&gt;费用：2&lt;/strong&gt;&lt;/p&gt;" +
            "&lt;p&gt;消耗1点&lt;strong&gt;「夜魂值」&lt;/strong&gt;，对上一个敌方角色" +
            "&lt;span style=&#34;color: rgb(0, 0, 0)&#34;&gt;造成1点&lt;/span&gt;" +
            "&lt;strong&gt;&lt;span style=&#34;color: rgb(54, 207, 201)&#34;&gt;&lt;u&gt;风元素伤害&lt;/u&gt;" +
            "&lt;/span&gt;&lt;/strong&gt;" +
            "&lt;span data-type=&#34;详情&#34; data-name=&#34;&amp;lt;p style=&#34;&gt;风元素伤害：&lt;/p&gt;" +
            "&amp;#34; class=&amp;#34;wiki-note-text&amp;#34;&amp;gt;&lt;sup class=&#34;wiki-sup&#34;&gt;" +
            "[详情]&lt;/sup&gt;&lt;/span&gt;" +
            "&lt;span style=&#34;color: rgb(0, 0, 0)&#34;&gt;，并治疗我方受伤最多的角色2点。&lt;/span&gt;&lt;/p&gt;"
        val html = "<p>造成1点风元素伤害<span data-type=\"详情\" data-name=\"$note\" " +
            "class=\"wiki-note-text\"><sup class=\"wiki-sup\">[详情]</sup></span>。</p>"

        val result = WikiRichTextParser.parse(html)

        assertEquals("造成1点风元素伤害。", result.text)
        assertEquals(1, result.notes.size)
        assertEquals("援护射击", result.notes[0].title)
        val body = result.notes[0].body
        assertTrue(body.contains("费用：2"))
        assertTrue(body.contains("并治疗我方受伤最多的角色2点。"))
        assertFalse(body.contains("wiki-note-text"))
        assertFalse(body.contains("[详情]"))
        assertFalse(body.contains("&amp;"))
    }

    @Test
    fun `注释属性顺序颠倒也能抽到`() {
        val html = "<span data-name=\"&lt;p&gt;狂飙突进：状态说明&lt;/p&gt;\" data-type=\"详情\">[详情]</span>"

        val result = WikiRichTextParser.parse(html)

        assertEquals(1, result.notes.size)
        assertEquals("狂飙突进", result.notes[0].title)
        assertEquals("状态说明", result.notes[0].body)
    }

    @Test
    fun `块级标签转行并去掉 html 外壳`() {
        val html = "<html><head></head><body><p>第一段</p><p>第二段</p></body></html>"

        val result = WikiRichTextParser.parse(html)

        assertEquals("第一段\n第二段", result.text)
    }

    @Test
    fun `实体解码且不把转义标签当标签`() {
        val result = WikiRichTextParser.parse("<p>造成 &lt;b&gt;2&lt;/b&gt; 点&nbsp;伤害</p>")

        assertEquals("造成 <b>2</b> 点 伤害", result.text)
    }

    @Test
    fun `同名注释去重且保持首次出现顺序`() {
        val html = "<p>物理伤害" +
            "<span data-type=\"详情\" data-name=\"&lt;p&gt;物理伤害：第一次&lt;/p&gt;\">[详情]</span>" +
            "与物理伤害" +
            "<span data-type=\"详情\" data-name=\"&lt;p&gt;物理伤害：第二次&lt;/p&gt;\">[详情]</span></p>"

        val result = WikiRichTextParser.parse(html)

        assertEquals(1, result.notes.size)
        assertEquals("第一次", result.notes[0].body)
    }

    @Test
    fun `无冒号注释取前十二字作标题且正文保留全文`() {
        val html = "<span data-type=\"详情\" data-name=\"&lt;p&gt;召唤物规则说明&lt;/p&gt;\">[详情]</span>"

        val result = WikiRichTextParser.parse(html)

        assertEquals("召唤物规则说明", result.notes[0].title)
        assertEquals("召唤物规则说明", result.notes[0].body)
    }

    @Test
    fun `空白规范压缩但不吞段落`() {
        val result = WikiRichTextParser.parse("<p> 造成   2 点\n伤害 </p><p>  结束 </p>")

        assertEquals("造成 2 点\n伤害\n结束", result.text)
    }

    @Test
    fun `残缺 HTML 不抛异常`() {
        val result = WikiRichTextParser.parse("<span data-type=\"详情\" data-name=\"未闭合")

        assertNotNull(result)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `空输入返回空对象`() {
        assertEquals(WikiRichText(""), WikiRichTextParser.parse(null))
        assertEquals(WikiRichText(""), WikiRichTextParser.parse("   "))
    }
}
