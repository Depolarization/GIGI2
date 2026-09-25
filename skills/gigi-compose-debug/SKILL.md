---
name: gigi-compose-debug
description: GIGI 工程（Kotlin + Jetpack Compose）的布局/渲染类缺陷排查方法——用 javap 反编译 Compose 库查内部实现、自定义 Modifier 链的父约束坑（wrapContentSize/requiredWidth/enforceIncoming）、渲染回归的验证手段与盲区、以及本机真机取证的硬限制。当遇到「控件尺寸/位置不对」「indicator/分隔线/背景块宽度异常」「自定义 Modifier 不生效」「要查 material3 某个 API 到底怎么实现的」「改完 UI 怎么验证」时使用。触发词：Compose 布局、Modifier 不生效、wrapContentSize、父约束、constrainWidth、javap material3、indicator 宽度、渲染回归、真机截图黑。
agent_created: true
---

# GIGI：Compose 布局 / 渲染类缺陷排查

> 本文件是 GIGI 工程自包含的。技术栈：Kotlin + Jetpack Compose（BOM 2025.09.00，**material3 1.3.2**）+ Navigation Compose。
> 工程根 `D:/AndroidStudioProjects/GIGI`，构建见 `.task/ENV-NOTES.md`（`JAVA_HOME` 必须是 `ms-21.0.12.1`）。

## 0. 一句话原则

**自定义 `Modifier` 链去替换官方实现时，必须先把官方实现 `javap` 出来逐条对齐。**
不要凭"看起来差不多"就少写几步 —— 官方链里那些看似无关的节点（如 `wrapContentSize`）
往往是**解开父约束的开关**。

---

## 1. 症状 → 怀疑方向对照表

| 症状 | 第一怀疑 | 下一步 |
| --- | --- | --- |
| 自定义宽度/高度**完全不生效**，元素撑满父容器 | **父约束被固定**（`TabRow`/`LazyRow` 等会用 `Constraints.fixed(...)` 测量子项） | 看链上有没有 `wrapContentSize` / `requiredWidth` |
| 元素尺寸比预期**小**（被压缩） | 父约束的 `maxWidth/maxHeight` 限制 | 同上；或父级 `weight`/`fillMaxWidth` 冲突 |
| 元素**位置**偏移不对 | `offset` 语义（lambda 版是**相对原位置**的布局偏移，不是绝对定位） | 确认基准是父左上角还是自身原位置 |
| 只在**动画/手势过程中**出问题 | 插值量的**取值范围**搞错（如 `currentPageOffsetFraction` 是 `[-0.5, 0.5]` 不是 `[0, 1]`） | 打印/查文档确认范围，别想当然 `coerceIn(0f, 1f)` |
| 改了 A 之后 B 坏了 | **回归** —— 去查上一个改动引入的假设 | `git log` 找相关 commit，对照官方实现 |

---

## 2. javap 反编译 Compose 库（核心技能）

### 2.1 找 classes.jar

```bash
# material3（版本目录名即版本号，GIGI 用 1.3.2）
find /c/Users/oscur/.gradle/caches -path "*material3*1.3.2*" -name "*.aar"
# → .../modules-2/files-2.1/androidx.compose.material3/material3-android/1.3.2/<hash>/material3-release.aar

# 解压后的 classes.jar（transforms 目录，构建过一次就有）
find /c/Users/oscur/.gradle/caches/8.14.5/transforms -name "classes.jar" -path "*material3*"
# → .../transforms/7d4994daf6263be70b9e121a8cfbe36c/transformed/material3-release/jars/classes.jar
```

⚠️ `transforms/<hash>` 里的 hash 会变；**用 `find` 现场定位**，不要硬编码。

### 2.2 javap 用法

```bash
JAR="<上面的 classes.jar>"
JAVAP="C:/Users/oscur/.jdks/ms-21.0.12.1/bin/javap.exe"

# 1) 看某个类有哪些成员（含 Kotlin 生成的 $default 桥接、可见性）
"$JAVAP" -classpath "$JAR" androidx.compose.material3.TabRowDefaults

# 2) 看某个类的字节码（找它调用了哪些 Modifier 工厂）
"$JAVAP" -c -classpath "$JAR" androidx.compose.material3.TabRowDefaults

# 3) 只看它调了哪些 foundation/layout 的修饰符（最有用的一招）
"$JAVAP" -c -classpath "$JAR" androidx.compose.material3.TabRowDefaults \
  | grep -oE "androidx/compose/foundation/layout/[A-Za-z]+Kt\.[a-zA-Z$]+" | sort | uniq -c | sort -rn

# 4) 内部类（composed/inlined lambda 的实际实现往往在这里！）
"$JAVAP" -c -classpath "$JAR" 'androidx.compose.material3.TabRowDefaults$tabIndicatorOffset$2'
```

### 2.3 🔴 关键经验

- **`composed { }` / inline lambda 的真实逻辑在 `$<方法名>$<数字>` 内部类里**。
  只反编译外层类会漏掉关键调用 —— 我在查 `tabIndicatorOffset` 时就先漏了，
  因为 `wrapContentSize` 在 `TabRowDefaults$tabIndicatorOffset$2` 里，不在主类。
- **构造器可见性**：`javap` 输出里只有带 `DefaultConstructorMarker` 的合成构造器 ⇒
  该构造器是 Kotlin **`internal`**，**外部无法自己构造**（如 `TabPosition`）。
  这决定了"能不能自己造一个对象传给官方 API"这条路的可行性。
- **Kotlin 的 `Dp` 在字节码里是 `float`**，方法名带 `-D9Ej5fM` 之类的 hash 后缀
  （如 `getLeft-D9Ej5fM`、`width-3ABfNKs`）—— grep 时用 `[a-zA-Z$]+` 兜住。

---

## 3. Modifier 约束坑（本次踩的）

### 3.1 症状与根因

`TabRow` 测量 indicator 时传的是**固定宽度约束**（整行宽）。
`Modifier.width(dp)` 默认 **`enforceIncoming = true`** ⇒ 内部 `constrainWidth` 会把请求宽度
**夹到 [min, max] 区间** ⇒ 自定义宽度失效，indicator 横贯整行。

### 3.2 官方怎么解的

javap 实证 material3 1.3.2 的 `tabIndicatorOffset`：

```
Modifier
    .fillMaxWidth()                                  // SizeKt.fillMaxWidth$default
    .wrapContentSize(Alignment.BottomStart)          // ← 解开父约束的开关
    .offset { IntOffset(left.roundToPx(), 0) }       // OffsetKt.offset（lambda 版）
    .width(width)                                    // SizeKt."width-3ABfNKs"
```

**`wrapContentSize` 测量子内容时用宽松 `Constraints()`**（unbounded），
等于把父约束"解开"，后面的 `.width(w)` 才真正生效。

### 3.3 三种解约束手段

| 手段 | 语义 | 适用 |
| --- | --- | --- |
| `.wrapContentSize(align)` | 自身仍受父约束，但**子内容**用宽松约束测量 | 模仿官方链（首选，语义最轻） |
| `.requiredWidth(w)` | **完全忽略**父约束，强制 w | 确实要突破父约束时（副作用大） |
| `.sizeIn(minWidth, maxWidth)` | 在区间内取 | 只是想放宽上界 |

⚠️ **顺序敏感**：`wrapContentSize` 必须在 `width` **之前**（外层），否则约束照样被夹。
回归测试要断言这个顺序（见 §5）。

---

## 4. 渲染类回归的验证手段与盲区

### 4.1 能做

- **构建 + 全量单测**：`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks`
- **用例计数**：`python .task/count-tests.py`（独立复算，不信子代理自述）
- **源码文本断言**（本工程惯例，见 `AppImageDrawOrderTest` / `RankRowSpacingTest` / `RankTabIndicatorTest`）：
  读 `File("src/main/java/...")` 做字符串/正则断言，**必须做变异验证**（故意改坏 ⇒ 断言必须红 ⇒ 还原 ⇒ 绿）
- **javap 对照官方实现**（§2）—— 这是"证明链写对了"的强证据

### 4.2 🔴 盲区（必须知道）

**源码文本断言只能证明"代码写对了"，证明不了"渲染对了"。**
纯布局/渲染回归（宽度、位置、层级）在单测和逐行 diff 里**完全看不出来** ——
源码看着完全合理。**唯一手段是真机/模拟器上肉眼滑一下。**

⇒ **UI 改动的验收，装机复测这一步不能省。**

---

## 5. 回归测试怎么写（本工程范式）

```kotlin
class XxxTest {
    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/.../Xxx.kt")  // 路径相对 app/
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    /** 取目标函数/块的文本区间 */
    private fun region(): String {
        val start = src.indexOf("private fun Xxx(")
        assertTrue("找不到 Xxx", start >= 0)
        val end = src.indexOf("\n}", start)   // 体内 } 均缩进 ⇒ 列 0 的 \n} 必是收尾
        assertTrue("Xxx 未闭合", end > start)
        return src.substring(start, end)
    }

    @Test fun 不变量名() { /* assertTrue(region().contains(...)) */ }
}
```

**必做**：
1. 断言要**能真红** —— 写完做一次变异（改坏 → 跑 → 必须红 → 还原 → 必须绿），把结果写进探针。
2. **顺序类断言**用 `indexOf` 比较位置（`fill < wrap`），不要只断言存在。
3. 断言**不要命中注释里的字面量**。踩过：注释写 `Modifier.width()` 导致 `.width(` 断言误命中 ⇒
   注释里改措辞（写成"显式宽度修饰符"）而不是放宽断言。
4. 锁住**硬约束**（如本工程 `snapshotFlow { pagerState.settledPage }` 是数据加载语义，不许被顺手改掉）。

---

## 6. 本机真机取证的硬限制（MIUI / Redmi Note 7 `ac9bcc9a`）

| 限制 | 表现 | 对策 |
| --- | --- | --- |
| 拒注入事件 | `input tap` / `input swipe` 报 `INJECT_EVENTS` 权限错 | **无法自动点击/滑动**，交互类复测只能让用户手动做 |
| 截图全黑 | `screencap` 对 Compose 返回纯黑（1080x2340 / 9945 字节 / 100% `(0,0,0)`）；源码无 `FLAG_SECURE` | 真机以 `uiautomator dump` 为第一取证手段 |
| 无语义节点 | `SecondaryIndicator`、`Spacer`、纯 `Box` 装饰**不进 dump** | 这类元素的渲染**无法自动取证** |
| 冷启动 ≠ 稳态 | 装机后首次启动 `Displayed +8s855ms`、`Skipped 172 frames` | 判"卡在 Loading"必须重启后 T+15s/30s/50s **多点取样** |

**adb 路径**：`D:/AndroidSDK/platform-tools/adb.exe`（不在 PATH，写绝对路径）
**Git Bash 路径改写坑**：`/sdcard/x.png` 会被改写成 Windows 路径 ⇒ 用双斜杠 `//sdcard/x.png`

⇒ **结论**：indicator / 分隔线 / 背景块这类"无语义节点的纯渲染"，**自动化验证不了**，
派单验收门槛里必须明确写"需用户真机肉眼复测"。

---

## 7. 标准排查流程（照这个走）

1. **读源码**，别猜（`grep -n` + `Read` 定位实际代码，不要凭记忆或摘要）
2. **找官方对应实现**（§2 javap），逐条对齐调用链，列出差异
3. **判断差异的性质**：是"少了一步开关"还是"语义不同"
4. **写修复**，并保留原方案里**必须保留**的部分（如本工程的"跟手插值"不能退回官方静态 offset）
5. **补回归测试** + **变异验证**（§5）
6. **构建 + 全量单测 + 独立复算用例数**
7. **装机，给用户明确的复测清单**（§6：交互/渲染只能人眼看）
8. **把根因与教训写进 `.task/ENV-NOTES.md`**（供后续棒读）
