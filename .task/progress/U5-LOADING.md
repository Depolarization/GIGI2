# U5-LOADING 探针

status: done

## 任务
- A. StateViews.kt `LoadingView()`：细弧环（strokeWidth 2.dp、尺寸 32.dp、主色默认）
- B. AppImage.kt `ShimmerPlaceholder()`：暗色对比度改善（不改动画结构/方向/fillMaxSize 契约）

## 环境
- 构建命令：`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug --rerun-tasks`
- 独占文件：StateViews.kt、AppImage.kt（LoginScreen.kt 不碰）

## 进展
- [x] 改前闸门：GATE_OK（两文件改前无未提交 diff）
- [x] A 完成：LoadingView 圆环 → `size(32.dp)` + `strokeWidth = 2.dp`，颜色不显式指定（M3 默认即 primary）；label 逻辑未动；EmptyState/ErrorState 未动；`size` import 已有（L6）
- [x] B 完成：ShimmerPlaceholder highlight `surface` → `onSurface.copy(alpha = 0.07f)`（**方案①**）；动画结构/方向/fillMaxSize 契约/`width > 0f ? 渐变 : SolidColor(base)` 防退化判断/无条件先绘制 painter 结构 全部未动；仅同步更新了函数头顶描述性注释（原注释写"surface 亮带"已失真）
- [ ] 构建通过
- [ ] 提交

## shimmer 配色选型：方案①，理由
亮带实际是叠在占位区下方页面底色（两主题均为 `surface`）上的半透明色，插值合成后：
- 暗色：base=surfaceVariant `#49454F`(73,69,79)；带心 ≈ 0.07×onSurface`#E6E1E5` + 0.93×surface`#1C1B1F` ≈ `#2A292D`(42,41,45)，与 base 三通道差 30~34（对比度约 1.8:1）——扫光清晰可辨。
- 亮色：base=`#E7E0EC`(231,224,236)；带心 ≈ 0.07×onSurface`#1C1B1F` + 0.93×surface`#FFFBFE` ≈ `#EFECEB`(239,235,238)，与 base 差 8~11（约 1.1:1）——亮色下自然克制。
- 旧值问题：highlight=surface 在暗色下与 surfaceVariant 同属深灰系、反差弱，亮带几乎看不见。
- 弃方案②（primary alpha）：primary 带紫/蓝色相，暗色下合成偏色明显、观感像"出错底色"而非高光；且方案①的带心亮度由 onSurface 决定，主题自适应方向正确（暗底带更亮、亮底带更暗的相对反差都由 onSurface/surface 天然拉开）。

## LoadingView 调用点（自动生效）
- ui/screens/rank/RankRoute.kt:74
- ui/screens/home/HomeRoute.kt:98、:114
- ui/screens/cardwiki/CardWikiRoute.kt:125
- ui/screens/cardstats/CardStatsRoute.kt:101
- ui/dialogs/playerdetail/PlayerDetailDialog.kt:81
- ui/dialogs/cardcover/CardCoverSheet.kt:176

