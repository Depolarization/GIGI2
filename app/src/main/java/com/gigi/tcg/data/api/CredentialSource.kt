package com.gigi.tcg.data.api

/** 凭据明文仅经此接口流向网络层；业务层拿不到 */
interface CredentialSource {
    /** 拼好的 Cookie 头（如 ltoken_v2=…; cookie_token_v2=…; e_hk4e_token=…）；无凭据返回 null */
    fun cookieHeader(): String?

    /**
     * 指定账户（uid）的 Cookie 头（V37-I：私有数据请求必须带**目标账户自己**的凭据，
     * 而不是永远取激活账户）；uid 为 null/空白 = 激活账户，与无参版同义。
     * 默认实现忽略 uid 回退无参版 ⇒ 既有实现方（含测试桩）不感知；CredentialStore 覆写为按 uid 解密。
     */
    fun cookieHeader(uid: String?): String? = cookieHeader()
}
