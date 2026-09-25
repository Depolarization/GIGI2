# V6I-B-OVERFLOW — 文本溢出/截断修复

status: doing

## 计划
- HomeRoute.kt: RecordItem 对手昵称补 Ellipsis;ProfileCard UID/段位行补 maxLines=1
- RankRoute.kt: RankRow 积分/UID 行补 maxLines=1 + Ellipsis
- PlayerDetailDialog.kt: competitionName/competitionResult 补截断;角色标签 padding 与 Spacer 对齐 4dp 网格
- 构建 :app:assembleDebug --rerun-tasks

## 闸门
- `git diff --quiet --` 三个独占文件 → 退出码 0 (GATE_OK),放行

## 进度
- ✅ HomeRoute.kt: L288 昵称补 overflow；L200/L205 UID/段位行补 maxLines=1+Ellipsis
- ✅ RankRoute.kt: L240 积分/UID 行补 maxLines=1+Ellipsis
- ✅ PlayerDetailDialog.kt: competitionName/competitionResult 补截断（result 加 weight(1f)）；标签 padding 6→4dp；Spacer 6→4dp

## 逐条改动证据（commit 3e39694，行号为改后）
### HomeRoute.kt（3 处）
- L293 RecordItem 对手昵称 Text：`maxLines = 1` → `maxLines = 1, overflow = TextOverflow.Ellipsis`（审查 P2 L288）
- L204-205 ProfileCard UID 行 Text：无 → `maxLines = 1, overflow = TextOverflow.Ellipsis`（审查 P3 L193）
- L211-212 ProfileCard 段位行 Text：无 → 同上
### RankRoute.kt（1 处）
- L244-245 RankRow 积分/UID 行 Text：无 → `maxLines = 1, overflow = TextOverflow.Ellipsis`（审查 P3 L230）
### PlayerDetailDialog.kt（4 处）
- L248-249 competitionName Text：无 → `maxLines = 1, overflow = TextOverflow.Ellipsis`（weight(1f) 保留，审查 P2 L245）
- L256-258 competitionResult Text：无 → `weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis`（不再挤压右侧积分区，审查 P3 L250）
- L217 角色标签 Column padding：vertical 6.dp → 4.dp（4dp 网格）
- L275 SectionTitle Spacer：width 6.dp → 4.dp
- 未动：AssistChip 替换（超范围）、主题/颜色、布局结构

## 验收
- `:app:assembleDebug --rerun-tasks` → BUILD SUCCESSFUL in 35s（35 tasks executed）
- 改前闸门 GATE_OK；commit 3e39694 仅含 3 个独占文件（14+/2-）
- 工作区其余 kt 改动属并发棒（V6I-A/V6I-C 等），未裹挟

status: done
