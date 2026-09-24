# VERIFY-T2 阶段性验收报告（前 4 模块）

状态: done
只读验收，未修改任何源码；证据来自 Read/Grep 原样摘录。

## ① 用例计数对照表（4 组）

N_web 采用 vitest 逻辑用例数（`it.each` 按展开行数计）。N_kt 为 `@Test` 计数（Grep 校验）。

| 模块 | Web 源 it(/it.each | N_web(逻辑) | Kotlin @Test | N_kt | 判定 |
|---|---|---|---|---|---|
| UidCode | 5×it( + 1×it.each(5行) | 10 | 10 | 10 | PASS (≥) |
| Tier | 2×it( + 1×it.each(12行) | 14 | 14 | 14 | PASS (≥) |
| Opponent | 6×it( | 6 | 6 | 6 | PASS (≥) |
| Percent | 10×it( | 10 | 10 | 10 | PASS (≥) |

N_kt 全部 ≥ N_web，无缺失用例。

## ② 移植保真

### UidCode —— PASS
- 字符表逐字符相等：
  - UidCode.kt:14 `private const val CHARSET = "528XM1TVN6UHQ9DLG4R3CZSYB7W0FPIEOAJK"`
  - code.ts:9 `const CHARSET = '528XM1TVN6UHQ9DLG4R3CZSYB7W0FPIEOAJK';`
- 低位在前、不反转（无 `.reverse()`）：UidCode.kt:19-24
  ```
  while (value > 0) {
      val remainder = (value % 36).toInt()
      result.append(CHARSET[remainder])
      value = floor(value.toDouble() / 36).toLong()
  }
  return "CA0${result}N"
  ```
  对照 code.ts:14-19，逐语句一致（余数取低位、append 尾部、不反转）。
- CA0 前缀 / N 后缀包裹：UidCode.kt:24 `return "CA0${result}N"` == code.ts:19 `return \`CA0${result}N\``。
- JSON 组装 + Base64：UidCode.kt:28-30（US-ASCII，等价 btoa）== code.ts:23-25。
- 行为手算验证 id2code(36)：36%36=0→'5', floor(36/36)=1→1%36=1→'2' → "CA052N"，与测试期望一致。

### Tier —— PASS
阈值链逐档与 tier.ts 一致（upper/tier/stars）：1200黄铜1…2000黄铜5, 2100星银1…2500星银5, 2600赤金1…3000赤金5。
- 常量行：Tier.kt:9-25 == tier.ts:10-26（2100→星银1, 2500→赤金1, 3000→赤金5 边界全对齐）。
- `score < 1` → 空/0：Tier.kt:28-30 == tier.ts:29-31。
- 影幻兜底存在：Tier.kt:36 `return TierStars("影幻", 0)` == tier.ts:37 `{ tier: '影幻', stars: 0 }`。

### Opponent —— PASS
- trans_no 拆分用正则取前两段：Opponent.kt:9,12-15 == opponent.ts:11,15。
- **字符串比较，未改数值比较**：Opponent.kt:17 `return if (first == curUid) second else first` == opponent.ts:17 `return first === curUid ? second : first`。全仓库无 `toLong()`/`toInt()` 参与该判定；curUid/first/second 均为 String。
- 失败→unknown：Opponent.kt:12-13（null / 不匹配）== opponent.ts:8-14。

### Percent —— PASS
- 分母 0/负 → 0：Percent.kt:9 `if (total > 0) part / total * 100 else 0.0` == percent.ts:5。
- 去 `.0` 尾：Percent.kt:20-23（`%.1f` 后 `endsWith(".0")` 截断）== percent.ts:16-19（`toFixed(1)` + `endsWith('.0')` slice）。
- 非法值 → "0%"：Percent.kt:17 `value == null || !value.isFinite()`（覆盖 null/NaN/±Inf）== percent.ts:13（`typeof!=='number' || !Number.isFinite` 覆盖 null/undefined/NaN/Inf）。行为一致。

## ③ 纯净性 —— PASS
Grep `^import android` 于 domain 目录 → No matches（计数 0）。4 文件仅 import `java.util.Base64`/`java.util.Locale`/`kotlin.math.floor`，无 android 依赖。

## ④ 断言质量 —— PASS
抽查无弱断言（无 assertNotNull/assertTrue 凑数）：
- UidCodeTest 全用 assertEquals（含 Base64 具体值）+ 1 处 assertNotEquals 对应 ts 的 `.not.toBe`。
- PercentTest:75-82 移植了 ts 的字典序陷阱对比（含 wrong 分支断言），非仅断主结果。
- 各 it.each 展开为独立 @Test 且断言 TierStars 具体值（data class equals）。

## ⑤ 总体结论: PASS
4 模块用例计数达标、移植逐行保真（字符表/Base64/阈值链/字符串比较/百分比三处行为一致）、domain 无 android import、断言强度对齐。无 FAIL 项。
