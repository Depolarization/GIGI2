# GIGI Android 原生重写 — 唯一权威协调文件

> 规则：事件总表只追加、文件占用锁行不删（🔒→🔓）、任务区块含完成标准。
> 设计文档：D:\EmulatorShared\GIGIWeb\2026-09-24-gigi-android-native-design.md（唯一权威设计）
> 参照实现：D:\EmulatorShared\GIGI\web（React 19 + TS，6158 行）
> 子代理手册：.task/SUBAGENT_HANDBOOK.md（每个子代理开工必读）

## 总完成标准（全部满足才算完成）
1. `./gradlew :app:assembleDebug` 退出码 0；`:app:lintDebug` 无 Error；`:app:testDebugUnitTest` 全绿
2. Web 版 79 个 Vitest 用例逐字移植为 JVM 单测且全绿（含两个"反直觉"行为：id2code 低位在前不反转、computeGcgSummary 出场率分母口径）
3. 四页面 + 登录链路 + 三弹窗功能 1:1 对齐 Web 版
4. 真机/模拟器 adb 取证：登录全链路、四页三态、换服隔离、卡面下载落相册、退出后私有缓存清空，截图落盘
5. 全阶段 git 分步提交

## 任务区块

### T1 脚手架 🔓gradle 脚本群
- 范围：git init；settings.gradle.kts / 根 build.gradle.kts / app/build.gradle.kts；gradle wrapper（8.14.5，复用 Shield）；AndroidManifest（allowBackup=false）；GigiApp/MainActivity；theme（动态取色+固定语义色 #3f8f5f/#c25656/#d4a643）；4 页占位 NavHost（NavigationBar，宽屏 NavigationRail）；AppContainer 空壳
- 完成标准：`assembleDebug` 退出码 0
- 状态：✅ 完成（8ae8853/e3af84c/d46fbd2，主代理独立复验通过：build 重跑 exit 0 + APK 19.4MB + diff 逐行审）
- 已裁决偏离：① JAVA_HOME 改 JDK 21 `C:\Users\oscur\.jdks\ms-21.0.12.1`（JBR 25.0.2 被 Gradle 8.14.5 拒识，KTS 配置阶段炸，主代理实测复现确认；手册 §2 已更正）② Kotlin 保持 2.1.20 ③ datastore-preferences 钉 1.1.1（BOM 不管辖）

### T2 domain 层 🔓domain/ + test/
- 范围：Tier/UidCode/Opponent/Percent/Summary/Throttle/TtlCache(纯Kotlin)/WikiParser + 8 个测试文件移植
- 完成标准：`testDebugUnitTest` 全绿，用例数对齐 Web 版 79 个
- 依赖：T1；状态：✅ 完成（217f2b0..67e62fd 共 8 commit；主代理复跑 test exit 0/报告59用例0失败/domain纯净0）
- 口径裁决：文档"79用例/8文件"有误——8 utils 文件展开=59 全量移植；其余 20（retcode5/servers9/storage4+2）属 T3+，由 T3 承接其 JVM 可移植部分
- VERIFY-T2（前4模块）+VERIFY-T2-B（后5模块）只读验收均 PASS；新增 Format.kt 经核实合理（throttle.test.ts 含 format 用例）

### T3 data 层 🔒data/
- 范围：@Serializable 模型（全字段可空）、MihoyoClient（retcode 集中判定/Cookie 注入/1s 节流）、CredentialStore（Keystore+AES-GCM）、双层缓存、GigiRepository、ServerId 注册表、AppContainer 填充
- 完成标准：assembleDebug 0 + 可单测部分全绿
- 依赖：T1、T2；状态：待派

### T4 登录链路 🔒data/auth/ + ui/login/
- 范围：QR 生成/轮询(Flow+delay 2500)/凭据交换(§3.2 四步)/no-role 拒绝半登录；登录全屏页
- 完成标准：assembleDebug 0；交换逻辑单测覆盖
- 依赖：T3；状态：待派

### T5 共享组件+弹窗 🔒ui/components/ + ui/dialogs/
- 范围：三态视图/Avatar/Toast；PlayerDetailDialog(Dialog)/CardCoverDialog(ModalBottomSheet)/AboutDialog(Dialog)
- 完成标准：assembleDebug 0
- 依赖：T1、T3；状态：待派

### T6-T9 四页面（并行，页面包级独占）🔒ui/screens/<各自>/
- Home(T6) / Rank(T7) / CardStats(T8) / CardWiki(T9)：各 Screen+ViewModel，MVI sealed UiState，rememberSaveable 筛选态，筛选变更滚动到顶
- 完成标准：assembleDebug 0；路由接线由 T10 统一做
- 依赖：T3、T5；状态：待派

### T10 集成接线 🔒navigation 文件
- 范围：NavHost 占位替换为真实页面；全量构建 + lint
- 完成标准：assembleDebug 0 + lintDebug 无 Error
- 依赖：T6-T9；状态：待派

### T11 真机取证
- 范围：MuMu 127.0.0.1:16384 adb 逐页截图比对 Web 版语义；§5.2 五个取证点
- 完成标准：截图落盘 .task/evidence/，逐点 PASS
- 依赖：T10；状态：待派

## 事件总表（只追加）
| 时间 | ID | 事件 | hash |
|---|---|---|---|
| 2026-09-24 22:14 | main | 协调网络建立；用户确认 applicationId=com.gigi.tcg | - |
| 2026-09-24 22:17 | T1 | 后台派发（Qwen3.8-Flash，job-2b256ce7）；T2 派单已预写 | - |
| 2026-09-24 22:45 | T1 | ✅ 验收通过（主代理重跑 build exit 0）；裁决 JAVA_HOME→JDK21、datastore 钉 1.1.1；手册已更正 | 8ae8853..d46fbd2 |
| 2026-09-24 23:01 | T2 | 后台派发（Qwen3.8-Flash，job-d5c3da74）；监视器已 ensure@8787，--wait-done T2 已登记 | - |
| 2026-09-24 23:12 | VERIFY-T2 | 只读验收代理后台派发（job-f798f971），验收已产出的前 4 模块；--wait-done T2,VERIFY-T2 | - |
| 2026-09-24 23:12 | main | 用户确立常设纪律：本轮验收通过后自动派下一轮，不再询问；只读验收由非实现者执行 | - |
| 2026-09-24 23:40 | T2 | ✅ 正式闭环：VERIFY-T2/T2-B 均 PASS + 主代理复验 | - |
| 2026-09-24 23:42 | T3a/b/c | 三路并行派发（models+registry / api client / credential store），T3d 派单已预写；--wait-done 已登记 | - |
| 2026-09-25 00:05 | T3a/b/c | ✅ 主代理核实：6 commit（f85b49c..be4ab50）、源码树干净、产物在盘、探针 done；throttled 显式化裁决知悉 | - |
| 2026-09-25 00:06 | T3d | 集成收尾后台派发（job-523d5704）；--wait-done T3d | - |
| 2026-09-25 00:25 | T3 | ✅ 全量验收通过：build 0、83 测试 0 失败（主代理复跑核实）、APK 在盘 | e124d19..bf5ebeb |
| 2026-09-25 00:27 | T4a/T4LINT | 并行派发：登录核心 AuthManager / domain 层 lint 7 Error 修复；--wait-done 已登记 | - |
| 2026-09-25 01:10 | T4a/T4LINT | ✅ 主代理验收：95 测试 0 失败、lint Error 0、build 0（5 commit：32bb430..c5bdba5） | - |
| 2026-09-25 01:12 | T4b | 登录全屏页+AppGate 门控后台派发（job-9a2d5eac） | - |
| 2026-09-25 01:14 | T5a | 共享组件后台派发（job-0f3490d2），与 T4b 并行；T5b/c/d 派单预写 | - |
| 2026-09-25 01:40 | T4b/T5a | ✅ 主代理验收：3 commit（627c2a8/ b53bc35/1977c74）、assemble 0、树干净 | - |
| 2026-09-25 01:43 | GIGI-MAIN | 接力链启动：T5b(15656)/T5c(36116)/T5d(560) 已派发 | .task/chains/GIGI-MAIN.json |
| 2026-09-25 01:46 | GIGI-MAIN | 调度器切换计划任务模式：pid=38128 parent=svchost（修复相对路径坑：链条改绝对路径）；三棒 wait_only；监视器 8787 在 | - |

## 文件占用锁
| 文件/目录 | 状态 | 占用者 |
|---|---|---|
| gradle 脚本群/AndroidManifest | 🔓 | -（T1 完成释放） |
| app/src/main/java/com/gigi/tcg/domain/ | � | -（T2 完成释放） |
| app/src/test/ | �→�� | T3 各组按需新增测试 |
| app/src/main/java/com/gigi/tcg/data/model + ServerId/ServerApi | � | T3 完成 |
| app/src/main/java/com/gigi/tcg/data/api/ | 🔓 | T3 完成 |
| app/src/main/java/com/gigi/tcg/data/auth/ QrSession/AuthManager | 🔓 | T4a 完成 |
| app/src/main/java/com/gigi/tcg/domain/ | 🔓 | T4LINT 完成 |
| app/src/main/java/com/gigi/tcg/ui/login/ + MainActivity + di/AppContainer | 🔒 | T4b |
| app/src/main/java/com/gigi/tcg/ui/components/ | 🔒 | T5a |
| app/src/main/java/com/gigi/tcg/ui/dialogs/{playerdetail,cardcover,about} | 🔓 | T5b/c/d 待派 |
| app/src/main/java/com/gigi/tcg/data/cache + repo | 🔓 | T3 完成 |
| app/src/main/java/com/gigi/tcg/ui/ | 🔓 | - |

## Git 历史
（待 T1 git init 后登记）
