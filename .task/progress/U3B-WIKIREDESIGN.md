# U3B-WIKIREDESIGN 探针

status: blocked（代码改动完成且编译/单测/结构自查全过；仅真机目视验收因无登录态未做）
开始时间: 2026-09-25

## 任务
CardWikiRoute.kt：筛选改「四维一列」每维度独立原生下拉框（选中项特殊背景色、选项只显示选项值），网格改固定三列。筛选栏维持吸顶。

## 步骤记录
- [x] 改前闸门：git diff --quiet CardWikiRoute.kt → exit=0（干净，可改）
- [x] 依赖 API 核查：compose-bom 2025.09.00 → material3 1.3.2（查 pom 确认）。
      1.3.2 里【没有 DropdownMenuItemDefaults】类（1.4.0 也没有），MenuItemColors 只有文本/图标色，无 containerColor。
      → 按探针允许的"以实际依赖签名为准"适配：选中项高亮用
        DropdownMenuItem(modifier = Modifier.background(secondaryContainer), colors = MenuDefaults.itemColors(textColor = onSecondaryContainer))。
        DropdownMenuItem 实为无底色 Row（已 javap 确认 DropdownMenuItemContent 无 Surface），modifier 上的背景可全宽显示。
      1.3.2 确认可用：ExposedDropdownMenuBox(expanded,onExpandedChange,content)、MenuAnchorType.PrimaryNotEditable、
        scope.menuAnchor(type,enabled)、ExposedDropdownMenu(.., matchTextFieldWidth=true 默认对齐锚点宽度)、ExposedDropdownMenuDefaults.TrailingIcon。
- [x] 改写 CardWikiRoute.kt：
      · FilterBar：搜索框一行 + 按 filterDefs 动态渲染每维度一个 FilterDropdown（纵向一列，isNullOrEmpty 过滤空 label），吸顶结构未动
      · 旧 FilterMenu/FilterMenuGroup（OutlinedButton+FilterChip+横向滚动）整体删除；FILTER_ENTRY 常量删除；FILTER_ALL 值改"不限"
      · FilterDropdown：ExposedDropdownMenuBox + OutlinedTextField(readOnly, label=维度名, 值=选中值/不限) + menuAnchor(PrimaryNotEditable)
      · FilterOption：选项文本只取 child label / FILTER_ALL（无"维度名：值"拼接）；选中项 Modifier.background(secondaryContainer) + itemColors(textColor=onSecondaryContainer)；点击后关菜单
      · 网格改 GridCells.Fixed(3)
      · 失效 import 清理：horizontalScroll/rememberScrollState/BoxWithConstraints/KeyboardArrowDown/FilterChip/OutlinedButton/DropdownMenu，新增 M3 下拉相关 6 个 import
- [x] 编译 :app:compileDebugKotlin → BUILD SUCCESSFUL（10s，未调 menuAnchor 签名）
- [x] 单测 :app:testDebugUnitTest → BUILD SUCCESSFUL，19 个测试类 121 个用例 failures=0
- [x] 结构自查 grep：GridCells.Fixed(3) 命中 / Fixed(2) 0 命中；ExposedDropdownMenuBox、ExposedDropdownMenu、
      secondaryContainer、FILTER_ALL 均命中；FilterChip/FilterMenuGroup 0 命中（exit=1）；选项文本仅来自 child label 与 FILTER_ALL
- [x] import 复查：旧实现相关符号 0 命中，无失效 import 残留
- [x] APK：:app:assembleDebug → BUILD SUCCESSFUL；adb -s ac9bcc9a install -r → Success；monkey 启动成功，App 进程存活（pid 10737）
- [x] logcat：logcat -c 清缓冲后 FATAL EXCEPTION 计数 0（清前唯一一条为 09-24 21:14 UiAutomationService 重复注册，非 GIGI 进程，属陈旧缓冲）

## 🔴 blocked：真机无登录态，未做真机目视验收
- 真机 ac9bcc9a（Redmi Note 7 / Android 10）初始为息屏（mWakefulness=Asleep，screencap 全白）；
  用 `svc power stayon true` 唤醒后截图正常（未清数据、未重装、未绕过登录；MIUI 拒绝 `input keyevent` 注入：
  SecurityException INJECT_EVENTS，故未做任何点击/滑动操作）。
- App 停在【登录页】（符合探针预期）：真机无凭据，且该机当前无法连通 passport-api.miyoushe.com
  （页面显示"生成失败：failed to connect to passport-api.miyoushe.com/203.107.60.62 (port 443) from /192.168.1.100
  (port 37723) after 10000ms"），二维码未生成，无法扫码。
- 按探针指令立刻停手，未做 01/02/03 三张目视验收截图。
- 待用户联网+扫码登录后复验：三列网格、四维纵向下拉、下拉选项纯值文本（不限/…）、选中项 secondaryContainer 高亮。
- 证据：.task/evidence/V3B/00-真机登录页.png（登录页实拍；前期两张全白息屏截图为自建中间产物，已删除）
