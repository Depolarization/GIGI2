status: done
owner: P1a-UI
files:
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/StatsFormat.kt
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt
  - app/src/test/java/com/gigi/tcg/ui/screens/cardstats/StatsFormatTest.kt
evidence:
  - ./gradlew :app:assembleDebug => BUILD SUCCESSFUL (0)
  - ./gradlew :app:testDebugUnitTest => 全绿（StatsFormatTest tests=4 failures=0 errors=0）
  - ./gradlew :app:lintDebug => BUILD SUCCESSFUL，lint-results-debug.xml severity="Error" 计数=0
  - commits: 6ffd994（formatter+单测）, 4ba57d4（route 动画/列对齐/细节）
blockers: []
summary: >-
  Motion 令牌接入（Tab 用 emphasizedSpring 横向滑动、详情面板 expand/shrink 用 emphasized）；
  卡牌统计右侧出场/出场率/胜率/胜场四列改为固定列宽+右对齐+FontFamily.Monospace，
  百分比统一 1 位小数（含 .0），新增本屏纯函数 formatStatPercent + JVM 单测。
notes_14:
  - 列表行垂直间距统一为 8.dp（原 Char 6/Action 4 不一致）
  - 行动牌行数值同样等宽右对齐并补"出场"标签，与角色表观感一致
  - 类型筛选结果为空时新增 NoMatchHint（"没有匹配的行动牌/角色牌"，对齐 web 文案）
  - 去掉原全角空格拼串，改为真·列对齐
  - PlayerInfoCard 的总胜率/详情占比字段属 domain 数据口径，按红线未改动
closed_by_12: "cardstats 唯一网络图为 Avatar，内部已走 AppImage；本屏无直接 coil 调用，无需改动"
