# VERIFY-T2-B (status: done)

只读验收 T2 后 5 模块：Summary / Format / Throttle / TtlCache / WikiParser。日期 2026-09-24。

## 1. 用例计数表（TS vitest 展开数 vs Kotlin @Test 数）

| 模块 | TS it() 数 | Kotlin @Test 数 | ≥? |
|---|---|---|---|
| summary | 7 (summary.test.ts L17,25,44,49,53,63,76) | 7 (SummaryTest.kt) | ✅ |
| throttle+format | 5 (throttle.test.ts L6,19,25,30,37；含 format 4 例) | 5 (ThrottleTest.kt；format 用例同样内嵌于 ThrottleTest，组织方式与 TS 一致) | ✅ |
| ttlCache | 3 (ttlCache.test.ts L10,18,25) | 3 (TtlCacheTest.kt) | ✅ |
| wiki | 4 (wiki.test.ts L5,22,31,40) | 4 (WikiParserTest.kt) | ✅ |

TS 侧 wiki 未测 stripTitleSuffix，Kotlin 侧同样未测 → 对称。

## 2. Summary 保真 — PASS
- floor(Σuse/3)：Kotlin Summary.kt:91 `floor(lists.charTotalUse.toDouble() / 3)` ↔ TS summary.ts:69 `Math.floor(lists.charTotalUse / 3)`。
- 出场率分母=全部角色牌 use_count 之和：Kotlin 分母累加 Summary.kt:50 `charTotalUse += card.useCount ?: 0`（仅 CardTypeCharacter 分支 L48）；winRate 用 `calcPercent(winGames, totalGames)`（L93），三类占比分母用 actionTotalUse（L111-115）↔ TS summary.ts:28 同分支累加、L71/L89-93 同基数。头注 L2-3 保留"文案称÷场次、代码实为÷Σ角色use"口径差异，与 TS L2-3 逐字一致。
- 分类：CardTypeCharacter→charCards，其余（Modify/Assist/Event）→actionCards，按 useCount 降序稳定排序（Kotlin sortByDescending L57-58 ↔ TS byUseDesc L34-36）。字段名 snake_case→camelCase 映射正确（avatar_card_num_gained→avatarCardNumGained 等）。

## 3. Format — PASS
- formatRecordTime：TS format.ts L5-6 `Number()` 转换 + `!Number.isFinite → ''` ↔ Kotlin Format.kt:16-23 `toDoubleOrNull() ?: return ""` + `!isFinite → ""`；非 number/string（null/bool）TS Number() 亦兜底为 ""，Kotlin else→""，非法值行为一致。输出 `MM-DD HH:mm` 本地时区一致（TS padStart 补零 L13-16 ↔ Kotlin ofPattern "MM-dd HH:mm" L12）。注：TS 注释"现代化为 MM-DD HH:mm"笔误多空格，Kotlin 已修正注释，行为不变。
- formatScoreChange：Kotlin `"($change)"`（Format.kt:32）↔ TS `` `(${change})` ``（format.ts L22），正数无前缀、负数带负号，双方注释示例 "(+45)" 均为文档笔误、实际实现都不加 "+"，一致。

## 4. Throttle — PASS
- Throttle.kt:13-20 `current - last < delayMs → false; else last=current → true` ↔ throttle.ts L8-13 逐行同构；last 初始 0、首次放行、窗口内拒绝、边界（+999 拒 / +1000 放）测试断言与 TS 完全对应（ThrottleTest.kt:22-26 ↔ throttle.test.ts L9-13）。可注入 `now` 替代 vi.useFakeTimers，合理等价。

## 5. TtlCache — PASS
- 过期判定：TtlCache.kt:17 `expiresAt <= now()` → 删除并返回 null ↔ ttlCache.ts L9 同 `<=` 判定+delete。
- 私有前缀清理：Kotlin PRIVATE_PREFIX="gigi:private:"（L7,L33）↔ TS L24 同字面量。
- 纯内存层：文件零 Android import（Grep `^import android` 于 domain/ 全目录 No matches），DataStore 磁盘层明确留给 T3（TtlCache.kt 头注 L3）。

## 6. WikiParser — PASS
- JSON 套 JSON：parseFilterDefs 先 parse chExt、取 attribute_key=="filter" 的 value 字符串再二次解析（WikiParser.kt:36-42）↔ wiki.ts L23-26；parseCardFilters ext→filter.text→三次解析为数组（L72-76）↔ wiki.ts L51-54。
- 容错：两侧均 try/catch → emptyList/[]（Kotlin L54-56,83-85 ↔ TS L31-33,56-58），空/null 入参提前返回（L34,70 ↔ L21,49）；测试断言 4 种坏输入均不抛崩（WikiParserTest.kt:29-32,51-54，TS 侧 undefined 在 Kotlin 传 null 同路径）。
- 类型过滤：非字符串元素剔除 Kotlin L79 `isString` ↔ TS L55 `typeof x === 'string'`。
- 默认值：WikiFilterDef children 默认 emptyList（L24）↔ TS 缺字段即 undefined 透传；此为两处仅有的细微差异——若 value JSON 里对象缺 label/children，kotlinx 会因非空默认值兜底而不抛错，TS 则原样返回 undefined；测试数据未覆盖该畸形场景，视为合理强化，不构成 FAIL。

## 7. 纯净性 — PASS
`Grep ^import android`（app/src/main/java/com/gigi/tcg/domain 全目录）→ No matches found（覆盖 Summary/Format/Throttle/TtlCache/WikiParser 5 文件）。

## 8. 断言质量 — PASS
- 5 个测试文件 Grep `assertNotNull` → No matches，无凑数断言。
- 关键断言抽样：
  - SummaryTest.kt:53 `assertEquals(5L, s.totalGames)` / :54 `assertEquals(3L, s.winGames)`（floor/3 口径）。
  - SummaryTest.kt:66 `assertEquals("45.5%", s.modifyPercent) // 10/22`（分母=行动牌合计）。
  - TtlCacheTest.kt:45-46 私有键 assertNull + 公开键 assertEquals("public")（前缀清理不误伤）。

## 总体结论：PASS
8/8 项通过。非阻塞小差异 2 条：① Format 两侧注释 "(+45)"/"MM-DD HH:mm" 笔误同源保留或已修正，行为一致；② WikiParser kotlinx 默认值对缺字段畸形数据比 TS 更宽容，测试未覆盖，建议知悉即可。
