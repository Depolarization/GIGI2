# U2A2-RANKTOP 探针

status: blocked（代码+编译+单测 全绿；设备可视化截图需用户扫码登录）
start: 2026-09-25 16:46
end: 2026-09-25 16:57
owner: U2A2-RANKTOP 子代理
scope: A2 RankRoute.kt 行距收紧 + A3 GigiNavHost.kt 顶栏 actions 顺序

## 结论

- A2 ✅ 完成：RankRow 数字↔头像间距由 spacedBy(12dp)+36dp 固定槽 收紧为
  spacedBy(4.dp) + widthIn(min = 28.dp) + textAlign Center；头像↔昵称区补
  padding(start = 8.dp)。触发行 vertical padding 8dp 未动（行高 ≥ 48dp），
  Avatar 44dp 未动。编译+单测通过。
- A3 ✅ 完成：顶栏 actions 顺序改为 账户名入口(TextButton+DropdownMenu) →
  玩家查询(PersonSearch) → 关于(Info)。DropdownMenu 全部逻辑零改动，仅移位。
- 设备实证 ⛔ blocked：装机启动成功、无崩溃，但停在**扫码登录页**（凭据失效）。
  按指令未清数据、未反复重装。排行榜行距截图(01)与顶栏截图(02)无法在本轮产出，
  需用户扫码后由用户/后续棒次补拍。topbar 未登录不渲染，非本棒代码问题。

## 步骤日志

- [x] 改前闸门：`git diff --quiet` → exit 0（两独占文件无未提交改动）
- [x] A2 编辑 RankRoute.kt（RankRoute.kt:142/149/152）：
      spacedBy(12.dp)→spacedBy(4.dp)；Text 数字 width(36.dp)→widthIn(min = 28.dp)
      + textAlign = TextAlign.Center；Column(weight 1f)→weight(1f).padding(start = 8.dp)；
      import width→widthIn。vertical padding 8dp、Avatar 44dp 均未动。
- [x] A3 编辑 GigiNavHost.kt：账户 Box(TextButton+DropdownMenu, 原 147-195 行) 整块
      上移至 PersonSearch IconButton 之前，逻辑与内容一字未改。
- [x] 结构自查 grep 输出：
      RankRoute.kt: 15:import ...widthIn / 142:spacedBy(4.dp) / 149:widthIn(min = 28.dp)
        / 152:padding(start = 8.dp)（width(36.dp) 已消失）
      GigiNavHost.kt: 145:TextButton(账户名) / 194:PersonSearch / 197:Info
        → 账户名行号 145 < PersonSearch 行号 194 ✓
- [x] compileDebugKotlin → BUILD SUCCESSFUL in 48s（16:50）
- [x] testDebugUnitTest → BUILD SUCCESSFUL，聚合 121 tests / 0 failures / 0 errors（16:51）
- [~] 外部干扰：16:53 首次 assembleDebug 失败，错误全部位于并行代理正在编辑的
      cardwiki/CardWikiRoute.kt（Unresolved heightIn/VerticalDivider/GRID_CARD_MIN_WIDTH），
      非本棒文件。等待其落盘（mtime 16:54:01 稳定）后重试 → assembleDebug
      BUILD SUCCESSFUL（APK 16:54:36，Gradle 哈希级 UP-TO-DATE 确认含本棒改动）。
- [x] 装机：adb -s 127.0.0.1:7555（boot_id=0e07370d-...）install -r → Success
- [x] 启动：monkey 1 次 + 等 12s → MainActivity 前台，topResumedActivity 正确
- [~] 界面现状：停在扫码登录页 → 见 .task/evidence/U2A2/00-启动现状.png，
      **需用户扫码**后方可继续 01/02 截图
- [x] logcat FATAL 检查：0 处 FATAL EXCEPTION，无崩溃（见 logcat_crash_check.txt）

## 证据

- .task/evidence/U2A2/00-启动现状.png —— 启动后现状（扫码登录页，阻塞点）
- .task/evidence/U2A2/logcat_crash_check.txt —— FATAL count=0 + com.gigi.tcg 启动日志
- 缺：01-排行榜行距.png、02-顶栏账户名在查询图标左侧.png（blocked：需扫码）

## 提交

- A2: （见 git log）
- A3: （见 git log）
- 证据+探针: （见 git log）
