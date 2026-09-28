# 「我的」页面与新接口功能融合设计

日期：2026-09-28
状态：待用户复核
范围：把 4 个「已验证存在但工程未接」的米游社 GCG 接口融合进现有 App

---

## 1. 背景

`mihoyo-api-sdk` 报告确认国服七圣召唤共有 8 个 GCG 端点（`api-takumi-record.mihoyo.com/game_record/app/genshin/api/gcg/*`），
业务代号是 `GCG`（不是 invokation/tcg/七圣，这也是早期 grep 假阴性的原因）。

工程现状（已接入 2 个）：

| 端点 | 用途 | 状态 |
|---|---|---|
| `gcg/cardList` | 角色牌/行动牌已收集数、use_count、熟练度 | ✅ 已接入 |
| `gcg/basicInfo` | 收集总数 `*_card_num_total`（147 / 941） | ✅ 已接入（V28-A5） |
| `gcg/deckList` | 我的卡组（牌组数、组成卡牌） | ❌ 未接 |
| `gcg/cardBackList` | 已收集卡背 | ❌ 未接 |
| `gcg/matchList` | 最近对局 + **收藏对局** `favourite_matches` | ❌ 未接 |
| `gcg/challenge/schedule` | 胜冠之试各旬（每半月）列表 | ❌ 未接 |
| `gcg/challenge/record` | 单旬战绩 + **该旬所用卡组**（内联 `deck_list`） | ❌ 未接 |
| `gcg/getGCGCovers` | 对局封面 | ❌ 未接（暂不需要） |

四个待接端点与已接的 `cardList` **同主机、同鉴权口径（Cookie）、同 GET 形态**，
工程 `MihoyoClient` 已自动注入 Cookie ⇒ **接入无结构性障碍**。

### 核心未知项

SDK 的 TypeSpec 里，这四个端点的**顶层字段有名字，但元素级结构是 `Record<unknown>`**：

- `deck_list[]` 元素字段（牌组 id / 名称 / 卡牌数组）未展开
- `card_back_list[]` 元素字段未展开
- `favourite_matches[]` 标为 `unknown[]`
- `challenge/record` 的 `deck_list[]` 同为 `unknown[]`

**已确认（curl 实证）**：`deckList` 端点真实存在（返回结构化参数校验错误而非 404）、
`role_id` 必填且 > 0、`server` 必填、需登录 Cookie（`retcode=10001`）。
国际服（`api-os.hoyolab.com`）**无**此端点群。

---

## 2. 已确认的产品决策

| 决策点 | 结论 |
|---|---|
| 四项新功能的归属 | **全部归入新的「我的」页面**，不拆散 |
| 页面内部结构 | **分区分组卡片列表**（单条可滚长列表），不用嵌套 Tab |
| 账号管理深度 | **完整管理**：列表 + 点选切换 + 登出 + 添加 |
| 标题栏改造 | **只去掉个人 ID**（昵称/UID 移入「我的」）；**不做大标题改造**（避免挤压信息与滑动空间） |
| 首页语义 | **固定为最近对局**，不承载账号/身份信息 |
| 字段未知的处理 | **先抓样本，再写代码**：先做 UI 骸架，接口层留空；拿到真实响应后填字段 |
| 账号切换 | 工程有真多账号且切换有坑 ⇒ 需重建全局状态（本设计已覆盖） |

---

## 3. 信息架构

### 3.1 导航骨架

底部导航从 4 项变 5 项（宽屏 ≥840dp 的 NavigationRail 同步）：

```
首页(最近对局) │ 排行 │ 统计 │ 图鉴 │ 我的
```

`GigiNavHost.kt` 增 `ROUTE_MY = "my"` + 一个 `GigiDestination`（图标建议 `Icons.Outlined.Person`）。

### 3.2 「我的」页分区

单条 `LazyColumn`，四个分区卡片，自上而下：

| # | 分区 | 内容 | 数据来源 |
|---|---|---|---|
| ① | 账号管理 | 账号列表（头像/昵称/UID/区服/当前标记），点选切换、菜单登出、添加账号入口 | `AppContainer.accounts` / `activateAccount` |
| ② | 个人信息 | 昵称、游戏 UID、等级 | `gcg/basicInfo`（已有）+ `cardList.stats` |
| ③ | 卡牌资产 | 我的卡组（组数）、卡背图鉴（已收集/总数） | `gcg/deckList`、`gcg/cardBackList` |
| ④ | 对局记录 | 收藏对局（条数）、胜冠之试（按旬入口） | `gcg/matchList`、`gcg/challenge/schedule` |

**为什么用分区列表而不是嵌套 Tab**：360dp 宽度下 Tab 套 Tab 会被挤爆，
且四项功能的**内容量差异极大**（卡背是网格、旬战绩是时间线、卡组是卡面列表），
塞进同页 Tab 会导致每页都很浅。一级页只做**导航枢纽**（带数值摘要），
重内容一律进二级页。

### 3.3 二级页

| 二级页 | 承载内容 | 复杂度 |
|---|---|---|
| 我的卡组 | 牌组列表 → 牌组详情（组成卡牌、卡面） | 高（`deck_list` 字段未知） |
| 卡背图鉴 | 已收集卡背网格，未收集置灰 | 低（`card_back_list` 字段最单一） |
| 收藏对局 | 收藏的逐局记录列表 | 中 |
| 胜冠之试 | 旬列表 → 单旬战绩（含该旬卡组） | 最高（两个端点串联） |

### 3.4 标题栏

保留现有 `TopAppBar`（**不做 LargeTopAppBar**，用户判断大标题会挤压信息与滑动空间）。
改动仅一处：**移除标题栏里的个人 ID 展示**（现由首页/统计页的标题栏承担），
昵称与 UID 改由「我的」页分区②承载。

---

## 4. 技术设计

### 4.1 分层

严格照抄现有四层（照 `fetchGcgCardListCached` 的形状）：

```
data/api/UrlBuilder.kt      + deckListUrl / cardBackListUrl / matchListUrl
                             + challengeScheduleUrl / challengeRecordUrl
data/model/ApiModels.kt     + GcgDeckListData / GcgCardBackListData / GcgMatchListData
                             + GcgChallengeScheduleData / GcgChallengeRecordData
                             ⚠️ 元素级字段待样本确定，先用 JsonObject 宽容解析
data/repo/GigiRepository.kt + fetchGcgDeckList / fetchGcgCardBackList / fetchGcgMatchList
                             + fetchGcgChallengeSchedule / fetchGcgChallengeRecord
                             （全部 5min TTL 内存缓存，异常吞掉返回空，绝不影响主链路）
ui/screens/my/             + MyRoute / MyViewModel
                             + deck/ cardback/ favorites/ challenge/ 四个二级页
```

**UID 口径警告**（本项目已踩过）：`role_id` 必须是**原神游戏 9 位 UID**；
签名接口的 `uid` 要的是**米游社社区 UID**。两者命名空间不同，混用会拿到占位数据且不报错。

### 4.2 账号切换的状态重建（关键风险点）

`AppGate.switchAccount(uid)` 已调 `container.activateAccount()` + `repository.clearPrivateCache()`，
但 **ViewModel 层不会自动重建** —— 各页 VM 的 `viewModel(key = "cardStats")` 等在 NavHost 作用域，
切账号后可能仍持有旧账号数据。

方案：让「我的」页的切换动作走一条显式链路：

```
MyViewModel.switchAccount(uid)
  → AppContainer.activateAccount(uid)     // 换凭据 + 清私有缓存
  → 通知宿主（回调 onAccountSwitched）
  → 宿主对 NavHost 内所有数据页 VM 调 clear/refresh
  → 跳回首页并重建导航栈
```

**待实现时确认**：现有四页 VM 是否已有可复用的 `clear()`；若无则补一个
（`CardStatsViewModel` 有 `generation` 计数机制，可作参照）。

### 4.3 分阶段实施

| 阶段 | 内容 | 前置条件 |
|---|---|---|
| **P0 骸架** | 导航加「我的」+ 四分区 UI + 四个二级页占位 + 账号管理（复用现有多账号底座）+ 标题栏去个人 ID | 无 |
| **P1 抓样本** | 装 P0 包 → 真机登录态跑四个接口 → 拿到真实 JSON 样本 | P0 |
| **P2 接数据** | 按样本定型元素级模型 → 填充四个二级页真实内容 | P1 |
| **P3 账号切换** | 切账号时重建全局状态 | P0 |

**P0 与 P1/P2 解耦**：即使样本一直没拿到，「我的」页的账号管理与个人信息也能独立交付。

---

## 5. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 元素级字段名猜错 | 数据不显示，静默失败 | P1 先抓样本；接口层宽容解析 + 临时日志 |
| 服务端对某些端点强制校验 DS | 该端点 4003/-101 | 已有 `cardList` 无 DS 跑通先例；真出问题时按 SDK `runtime/ds.ts` v2 补（salt 需自备） |
| 切账号后旧数据残留 | 显示上一个账号的统计 | P3 显式重建链路 |
| 国际服无此端点群 | 国际服用户功能不可用 | 需确认工程是否支持国际服；若支持，功能入口按 server 条件显示 |
| 魔物牌无 total 字段 | 分母口径不全 | 已记录在 A5 遗留项 |

---

## 6. 验收标准

- P0：底部导航 5 项；「我的」页四分区可滚动、账号可切换/登出/添加；标题栏无个人 ID；单测全绿
- P2：四个二级页展示真实数据；空态/错误态有明确文案；切换账号后数据正确刷新
