package com.gigi.tcg.data.api

/** 凭据明文仅经此接口流向网络层；业务层拿不到 */
interface CredentialSource {
    /** 拼好的 Cookie 头（如 ltoken_v2=…; cookie_token_v2=…; e_hk4e_token=…）；无凭据返回 null */
    fun cookieHeader(): String?
}
