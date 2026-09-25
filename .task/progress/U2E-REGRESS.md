# U2E-REGRESS 真机回归取证

状态: done
开始: 2026-09-25  收工: 2026-09-25 17:23

## 设备
- adb: D:/AndroidSDK/platform-tools/adb.exe
- 设备: 127.0.0.1:7555 (MuMu Android12 900x1600 density320)
- boot_id: 0e07370d-841d-4ce7-af39-c151824f9dc5 (已核对一致，全程未改端口)
- 登录态: Oscuro / UID 261958214 (未清数据、未登出，credentials 完好)

## DNS 归因（开工即测，设备侧）
- hoyobbs.hoyoverse.com → unknown host
- hoyohub.hoyoverse.com → unknown host
- hoyoapi.mihoyo.com   → unknown host
- api-takumi.mihoyo.com → 解析正常(203.107.60.77, 28ms)  ← 对照组
⇒ 三个图片域名为设备 DNS 解析失败，非 App 缺陷。本轮图片全部正常显示，无受限项。

## 结果：PASS 10 / FAIL 0 / 受限 0

- [x] 1 图片真实显示（复拍确认）— PASS — 01-图鉴.png
- [x] 2 首页对局可见且无频繁误报 — PASS — 02-首页对局.png / 03-首页滚动.png
- [x] 3 排行榜行距收紧 + 无引导文本 — PASS — 04-排行榜.png
- [x] 4 顶栏账户名在查询图标左侧 — PASS — 05-顶栏.png（630 < 748 < 844）
- [x] 5 统计四列对齐 — PASS — 06-卡牌统计.png
- [x] 6 图鉴 2列/省略号/下拉筛选/无刷新 — PASS
      - 6a 2列 + 单行 — PASS — 07（最长名 10 字 225x32px 单行；数据集内无更长名，故「…」真机不可触发，代码已就位）
      - 6b 下拉按维度分组 + 组内横滑 + 入口计数 — PASS — 08
      - 6c 筛选生效 — PASS — 09（标签=料理）
      - 6d 无刷新按钮 — PASS — 07/09
      - 6e 搜索框留白对称 — PASS — 上下各 24px
- [x] 7 卡面弹窗拖拽 + 按钮可见 — PASS — 10 / 11（y800→384 展开）
- [x] 8 关于对话框新文案与两按钮 — PASS — 12 / 13（性质=0、Dexphase=0、反馈拉起 Chrome）
- [x] 9 账户菜单 + 退出二次确认 — PASS — 14 / 15（已点取消，登录态保留）
- [x] 10 对手详情无查询码 + 段位在头部 — PASS — 16 / 17（本人详情仍有查询码）

## 崩溃
- FATAL EXCEPTION = 0（收工复查；com.gigi.tcg 相关亦为 0）

## 产出
- 报告: .task/evidence/U2E/回归报告.md
- 截图: .task/evidence/U2E/00..17（18 张，全部现场实截并逐张 Read 确认）
