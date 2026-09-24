// 移植 web/src/config/__tests__/servers.test.ts 后 3 个 URL 用例（L64/L72/L81 describe 内 it），
// 断言完整 URL 字符串（web 侧 toContain 处一并升级为全串断言，构造逐字对照 urls.ts）。

package com.gigi.tcg.data.api

import com.gigi.tcg.data.ServerId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlBuilderTest {

    @Test
    fun `赛事接口 badge_region 与 game_biz 随服务器变化 域名恒为国服`() {
        assertEquals(
            "https://hk4e-api.mihoyo.com/event/geniusinvokationtcg/get_game_records" +
                "?badge_uid=123456789&badge_region=cn_qd01&game_biz=hk4e_cn&lang=zh-cn",
            gameRecordsUrl("123456789", ServerId.Channel),
        )
        assertTrue(gameRecordsUrl("123456789", ServerId.Official).contains("badge_region=cn_gf01"))
    }

    @Test
    fun `游戏记录接口 server 与 role_id 取所选服务器`() {
        val gf01 = cardListUrl("123456789", ServerId.Official)
        assertTrue(gf01.contains("server=cn_gf01"))
        assertTrue(gf01.contains("role_id=123456789"))
        assertEquals(
            "https://api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/cardList" +
                "?limit=999&offset=0&server=cn_qd01&role_id=123456789" +
                "&need_action=true&need_avatar=true&need_stats=true",
            cardListUrl("123456789", ServerId.Channel),
        )
    }

    @Test
    fun `登录态与其它赛事接口同样带上服务器参数`() {
        assertEquals(
            "https://api-takumi.mihoyo.com/common/badge/v1/login/info?game_biz=hk4e_cn&lang=zh-cn",
            userInfoUrl(ServerId.Official),
        )
        assertEquals(
            "https://hk4e-api.mihoyo.com/event/geniusinvokationtcg/peak_rank" +
                "?page_size=999&page_token=&badge_uid=123456789&badge_region=cn_gf01&game_biz=hk4e_cn&lang=zh-cn",
            peakRankUrl("123456789", ServerId.Official),
        )
        assertEquals(
            "https://hk4e-api.mihoyo.com/event/geniusinvokationtcg/other_home_page" +
                "?code=abc&badge_uid=123456789&badge_region=cn_gf01&game_biz=hk4e_cn&lang=zh-cn",
            otherHomePageUrl("abc", "123456789", ServerId.Official),
        )
    }
}
