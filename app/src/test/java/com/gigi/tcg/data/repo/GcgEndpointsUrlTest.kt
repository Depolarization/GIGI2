// 「我的」页 4 组 GCG 端点的 URL 构造钉 + 模型解析钉（纯 JVM，无 Android 依赖）。
//
// URL 侧：钉住 host/path/server/role_id/schedule_id 与「多余参数一律不传」的实测口径。
// 模型侧：把 .task/p1-gcg-samples/raw/*.json 的真实片段内联进来当回归钉——
// 这些字段的形态是接口"拼错也别改"级别的事实（game_id 是字符串、linups 少个 e），
// 只靠注释挡不住重构手滑，故用真值断言。

package com.gigi.tcg.data.repo

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.gcgCardBackListUrl
import com.gigi.tcg.data.api.gcgChallengeRecordUrl
import com.gigi.tcg.data.api.gcgChallengeScheduleUrl
import com.gigi.tcg.data.api.gcgDeckListUrl
import com.gigi.tcg.data.api.gcgMatchListUrl
import com.gigi.tcg.data.model.GcgCardBackListData
import com.gigi.tcg.data.model.GcgChallengeRecordData
import com.gigi.tcg.data.model.GcgChallengeScheduleData
import com.gigi.tcg.data.model.GcgDeckListData
import com.gigi.tcg.data.model.GcgMatchListData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GcgEndpointsUrlTest {

    // 与 di.AppContainer 生产配置一致：未建模的多余键必须被忽略（deckList 卡牌实测 20 键，只建模 10）
    private val json = Json { ignoreUnknownKeys = true }

    private val uid = "157777921"

    // ---- URL ----

    @Test
    fun `deckList URL 用 record 域同主机同前缀且只带 server 与 role_id`() {
        val url = gcgDeckListUrl(uid, ServerId.Official)
        assertEquals(
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/deckList" +
                "?server=cn_gf01&role_id=$uid",
            url,
        )
        // 实测 need_deck_detail / need_avatar / need_action 无效 ⇒ 不该出现
        assertFalse(url.contains("need_"))
        assertTrue(gcgDeckListUrl(uid, ServerId.Channel).contains("server=cn_qd01"))
    }

    @Test
    fun `cardBackList 与 matchList URL 不带分页参数`() {
        val back = gcgCardBackListUrl(uid, ServerId.Official)
        assertEquals(
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/cardBackList" +
                "?server=cn_gf01&role_id=$uid",
            back,
        )
        val match = gcgMatchListUrl(uid, ServerId.Official)
        assertEquals(
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/matchList" +
                "?server=cn_gf01&role_id=$uid",
            match,
        )
        // 实测 limit / offset / need_favourite 无效 ⇒ 不该出现
        listOf(back, match).forEach {
            assertFalse(it.contains("limit="))
            assertFalse(it.contains("offset="))
        }
    }

    @Test
    fun `challenge schedule 与 record 走同一 gcg 前缀`() {
        assertEquals(
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/challenge/schedule" +
                "?server=cn_gf01&role_id=$uid",
            gcgChallengeScheduleUrl(uid, ServerId.Official),
        )
        assertEquals(
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/challenge/record" +
                "?server=cn_gf01&role_id=$uid&schedule_id=83",
            gcgChallengeRecordUrl(uid, ServerId.Official, 83),
        )
    }

    @Test
    fun `单旬战绩参数名是 schedule_id 而不是 season_id`() {
        val url = gcgChallengeRecordUrl(uid, ServerId.Channel, 86)
        assertTrue(url.contains("schedule_id=86"))
        assertFalse(url.contains("season_id"))
    }

    @Test
    fun `注册表未命中时用 DEFAULT 兜底不崩且不会拼出 null`() {
        // from() 对非注册表标识符返回 null（不做兜底），调用方兜底到 DEFAULT 后仍能构造 URL
        assertNull(ServerId.from("cn_unknown"))
        listOf(
            gcgDeckListUrl(uid, ServerId.DEFAULT),
            gcgCardBackListUrl(uid, ServerId.DEFAULT),
            gcgMatchListUrl(uid, ServerId.DEFAULT),
            gcgChallengeScheduleUrl(uid, ServerId.DEFAULT),
            gcgChallengeRecordUrl(uid, ServerId.DEFAULT, 1),
        ).forEach {
            assertTrue(it, it.contains("server=${ServerId.DEFAULT.id}"))
            assertTrue(it, it.contains("role_id=$uid"))
            assertFalse(it, it.contains("null"))
        }
    }

    // ---- 模型解析钉（真机片段） ----

    /** 真实片段的裁剪版：保留 1 张角色牌 + 1 张行动牌，并保留未建模键（desc/tags/card_skills） */
    private val deckListJson =
        """
        {
          "deck_list": [
            {
              "id": 1, "name": "我的牌组", "is_valid": true,
              "share_code": "A1IBhikbGQLBoV0NByFCw5ANCTFhzY4UCnIROJwXCmLRaa4XDILRC78RFKJiEDcRGfEB",
              "avatar_cards": [
                {
                  "id": 1407, "name": "雷电将军", "num": 1, "hp": 10, "proficiency": 0, "use_count": 0,
                  "card_type": "CardTypeCharacter", "category": "GCGCardCategoryAvatarCard",
                  "action_cost": [], "tags": [], "desc": "", "card_skills": [],
                  "image": "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f6fc/4b113f4284c6db5af8f4d30385341f6c.png?x-resource-process=image/resize,m_fixed,h_275,w_160"
                }
              ],
              "action_cards": [
                {
                  "id": 214071, "name": "万千的愿望", "num": 1, "hp": 0,
                  "card_type": "CardTypeModify", "category": "GCGCardCategoryActionCard",
                  "action_cost": [
                    {"cost_type": "CostTypeElectro", "cost_value": 3},
                    {"cost_type": "CostTypeEnergy", "cost_value": 2}
                  ],
                  "image": "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f6fc/0e7ca69dc7e50fd34ffece4e4e367795.png?x-resource-process=image/resize,m_fixed,h_275,w_160"
                }
              ]
            }
          ],
          "role_id": "157777921", "level": 60, "nickname": "墨邪"
        }
        """

    @Test
    fun `deckList 解析出 share_code 卡面图与元素骰消耗且未建模键不致命`() {
        val data = json.decodeFromString(GcgDeckListData.serializer(), deckListJson)
        assertEquals(60, data.level)
        assertEquals("墨邪", data.nickname)
        assertEquals("157777921", data.roleId)
        val deck = data.deckList!!.single()
        assertEquals(1, deck.id)
        assertEquals(true, deck.isValid)
        assertEquals(
            "A1IBhikbGQLBoV0NByFCw5ANCTFhzY4UCnIROJwXCmLRaa4XDILRC78RFKJiEDcRGfEB",
            deck.shareCode,
        )
        val avatar = deck.avatarCards!!.single()
        assertEquals(1407, avatar.id)
        assertEquals(10, avatar.hp)
        assertTrue(avatar.image!!.startsWith("https://act-webstatic.mihoyo.com/"))
        // 角色牌实测恒不带骰子消耗
        assertTrue(avatar.actionCost!!.isEmpty())
        assertEquals("CardTypeCharacter", avatar.cardType)
        val action = deck.actionCards!!.single()
        assertEquals(214071, action.id)
        assertEquals(
            listOf("CostTypeElectro" to 3, "CostTypeEnergy" to 2),
            action.actionCost!!.map { it.costType to it.costValue },
        )
    }

    /** 真实片段：game_id 是字符串、阵容键拼成 linups、match_time 是 6 个 int */
    private val matchListJson =
        """
        {
          "recent_matches": [
            {
              "game_id": "10", "is_win": true, "match_type": "胜冠之试",
              "match_time": {"year": 2026, "month": 8, "day": 5, "hour": 21, "minute": 19, "second": 0},
              "self": {
                "name": "墨邪", "is_overflow": false,
                "linups": [
                  "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f716/0601e5060ccfdfadff18eddacd7cb108.png",
                  "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f716/c2e3e1ce9b3082d06559a579784e7acb.png",
                  "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f716/8257437e7581abcda1850eaae8441d15.png"
                ]
              },
              "opposite": {"name": "下路对狙", "is_overflow": false, "linups": ["https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f716/0424a279f2ad3453a598ceee6b79e958.png"]}
            }
          ],
          "favourite_matches": []
        }
        """

    @Test
    fun `matchList 解析 game_id 为字符串且保留接口拼错的 linups`() {
        val data = json.decodeFromString(GcgMatchListData.serializer(), matchListJson)
        assertEquals(0, data.favouriteMatches!!.size)
        val match = data.recentMatches!!.single()
        assertEquals("10", match.gameId)
        assertEquals(true, match.isWin)
        assertEquals("胜冠之试", match.matchType)
        val time = match.matchTime!!
        assertEquals(
            listOf(2026, 8, 5, 21, 19, 0),
            listOf(time.year, time.month, time.day, time.hour, time.minute, time.second),
        )
        assertEquals(3, match.self!!.linups!!.size)
        assertEquals("下路对狙", match.opposite!!.name)
        assertEquals(false, match.opposite!!.isOverflow)
    }

    /** 真实片段：deck 与 deckList 同构 ⇒ 复用同一 GcgDeck；honor_character 实测恒 [] */
    private val challengeRecordJson =
        """
        {
          "basic": {
            "schedule": {
              "id": 83, "name": "26年8月上",
              "begin": {"year": 2026, "month": 8, "day": 1, "hour": 4, "minute": 0, "second": 0},
              "end": {"year": 2026, "month": 8, "day": 16, "hour": 3, "minute": 59, "second": 59}
            },
            "nickname": "墨邪", "uid": "157777921", "win_cnt": 1,
            "medal": "https://act-webstatic.mihoyo.com/game_record/genshin/gcg/challenge_medal_1.png",
            "has_data": true
          },
          "honor_character": [],
          "deck_list": [
            {
              "win_cnt": 1,
              "deck": {
                "id": 2, "name": "", "is_valid": true,
                "share_code": "GCHB9WwjAiBRw3UTBnERN48WGXEQZ5EZGEEhlIIlCiFwibsWC4HQlb0hGPJROIURHBAA",
                "avatar_cards": [
                  {"id": 1608, "name": "娜维娅", "num": 1, "hp": 10, "card_type": "CardTypeCharacter",
                   "category": "GCGCardCategoryAvatarCard", "action_cost": [], "desc": "", "tags": []}
                ],
                "action_cards": []
              }
            }
          ],
          "recommend_url": "https://webstatic.mihoyo.com/ys/event/bbs-lineup-qskp/index.html?mhy_presentation_style=fullscreen"
        }
        """

    @Test
    fun `challengeRecord 复用 GcgDeck 且 honor_character 空数组不崩`() {
        val data = json.decodeFromString(GcgChallengeRecordData.serializer(), challengeRecordJson)
        assertEquals(true, data.basic!!.hasData)
        assertEquals(1, data.basic!!.winCnt)
        assertEquals("157777921", data.basic!!.uid)
        assertEquals(83, data.basic!!.schedule!!.id)
        assertEquals(listOf(2026, 8, 16, 3, 59, 59), data.basic!!.schedule!!.end!!.let {
            listOf(it.year, it.month, it.day, it.hour, it.minute, it.second)
        })
        val entry = data.deckList!!.single()
        assertEquals(1, entry.winCnt)
        // 与 deckList 同构 ⇒ GcgChallengeDeck.deck 就是 GcgDeck，能直接喂给牌组列表的渲染路径
        val deck: com.gigi.tcg.data.model.GcgDeck = entry.deck!!
        assertEquals(2, deck.id)
        assertEquals("", deck.name)
        assertEquals(1608, deck.avatarCards!!.single().id)
        assertEquals(emptyList<Any>(), data.honorCharacter)
        assertTrue(data.recommendUrl!!.startsWith("https://webstatic.mihoyo.com/"))
    }

    @Test
    fun `cardBackList 解析未收集项 has_obtained 为 false`() {
        val data = json.decodeFromString(GcgCardBackListData.serializer(), cardBackListJson)
        val backs = data.cardBackList!!
        assertEquals(3, backs.size)
        assertEquals(0, backs[0].id)
        assertEquals(true, backs[0].hasObtained)
        val missing = backs.filter { it.hasObtained == false }.map { it.id }
        assertEquals(listOf(142, 143), missing)
        // image 不可靠（实测有项为空串），image_v2 全有 ⇒ 消费端取 v2
        assertEquals("", backs[1].image)
        assertTrue(backs[1].imageV2!!.endsWith(".png"))
        assertEquals("CGCCardCategoryCardBack", backs[2].category)
    }

    private val cardBackListJson =
        """
        {
          "card_back_list": [
            {
              "id": 0, "has_obtained": true, "category": "CGCCardCategoryCardBack",
              "image": "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_gcg_item_icon_u8f59e/bbede6fe24af17f51c542b5ef02fef69.png",
              "image_v2": "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/67c7f729/6745d847f1148bb2cb57a1eb083afa46.png"
            },
            {
              "id": 142, "has_obtained": false, "category": "CGCCardCategoryCardBack", "image": "",
              "image_v2": "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/699e348a/477eaae21f6e8b79711d8c960c4b53f5.png"
            },
            {
              "id": 143, "has_obtained": false, "category": "CGCCardCategoryCardBack", "image": "",
              "image_v2": "https://act-webstatic.mihoyo.com/hk4e/e20200928calculate/item_icon/6a7ba983/5b1440dd8b0737f2850a9a1b7b2bae43.png"
            }
          ]
        }
        """

    @Test
    fun `schedule 列表解析出旬 id 可用于 record 入参`() {
        val data = json.decodeFromString(
            GcgChallengeScheduleData.serializer(),
            """
            {"schedule_list":[
              {"id":86,"name":"26年9月下",
               "begin":{"year":2026,"month":9,"day":16,"hour":4,"minute":0,"second":0},
               "end":{"year":2026,"month":10,"day":1,"hour":3,"minute":59,"second":59}},
              {"id":85,"name":"26年9月上","begin":{"year":2026,"month":9,"day":1},"end":{"year":2026,"month":9,"day":16}}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(86, 85), data.scheduleList!!.map { it.id })
        assertEquals("26年9月下", data.scheduleList!!.first().name)
        // GcgTime 字段可空：缺 hour/minute/second 也不能崩
        assertNull(data.scheduleList!![1].begin!!.hour)
        assertTrue(gcgChallengeRecordUrl(uid, ServerId.DEFAULT, data.scheduleList!!.first().id!!).contains("schedule_id=86"))
    }
}
