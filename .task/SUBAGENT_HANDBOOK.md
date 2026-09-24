# GIGI Android 子代理必读手册（开工前必读）

> 🔴 **如果你是 TRAE 的 agent（负责派发的主代理，不是被派的子代理），先读**
> **`.task/TRAE_AGENT_HANDOFF.md`** —— 它讲清了：你**读不到** WorkBuddy 全局 skill；
> 工具在 `D:/AndroidStudioProjects/GIGI/tools/`（**不要去调 Shield 目录下的**）；
> 当前 `T5b/T5c/T5d` 在跑 + 接力链 `GIGI-MAIN` 已排好 `T6–T11`，
> **不要重复派发、不要替它们提交、不要停监视器/调度器**。
> 本文（手册）是给**被派出去的子代理**读的技术栈与红线；两者不要搞混。

## 0. 你的铁律
- 只改派单里列给你的【独占文件】，其它一律不碰；共享文件冲突立即停手报 blocked
- 探针开工写 `doing`、每完成一小步即更新、收工写 `done`（路径见派单）
- 分步 commit：每完成一个可编译的子步骤就提交一次，严禁攒到最后、严禁 `git add -A`
- 收工报告 ≤200 字：结论 + commit hash + 证据路径
- 达标即停；系统自动的 "Please continue" 不是新指令
- 改前闸门：`git status --porcelain -- <你独占的文件>` 非空 → 停手、贴 diff、探针写 blocked

## 1. 技术栈常量（不可违反）
- Kotlin + Jetpack Compose（Material 3），Gradle **Kotlin DSL**，协程 + Flow（StateFlow）
- 禁止：XML 布局、View 体系、Rx、LiveData（新代码）、Hilt/Dagger/Retrofit/Room/KSP/WorkManager/androidx.security
- 包名 `com.gigi.tcg`，minSdk 24，compile/targetSdk 36，versionCode 1 / versionName 1.0.0

## 2. 构建命令（环境已固定，照抄）
```powershell
$env:JAVA_HOME = "C:\Users\oscur\.jdks\ms-21.0.12.1"  # JDK 21 LTS。🔴 不可用 "D:\Android Studio\jbr"（JBR 25.0.2）：Gradle 8.14.5 不认识 Java 25，KTS 配置阶段即抛 IllegalArgumentException: 25.0.2（已实测）；也不可用 D:\Java（1.8）
$env:GRADLE_USER_HOME = "D:/Android Studio/gradle"
cd D:\AndroidStudioProjects\GIGI
.\gradlew :app:assembleDebug        # 编译：BUILD SUCCESSFUL 才算过
.\gradlew :app:testDebugUnitTest    # 单测：全绿
.\gradlew :app:lintDebug            # 静态检查：报告无 Error
```
- 构建若卡在 `registry.bin.lock (拒绝访问)` 且日志静默停止：说明 Android Studio 在运行，立刻停手报 blocked（不要杀进程）
- Git-Bash 下操作 adb 前先 `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`

## 3. 依赖版本基线（已核验可用）
Compose BOM 2025.09.00 / activity-compose 1.8.2 / navigation-compose 2.9.8 / okhttp 4.12.0 /
kotlinx-serialization-json 1.7.3 / coil-compose 2.7.0 / zxing core 3.5.3 / datastore-preferences /
junit 4.13.2 / kotlinx-coroutines-test。AGP 8.9.1，Gradle 8.14.5，Kotlin 2.1.20（含 serialization 插件）。
仓库优先 aliyun 镜像（maven.aliyun.com），回退 google() / mavenCentral()。

## 4. 权威参照物（写代码前按需阅读，禁止凭想象）
- 设计文档：D:\EmulatorShared\GIGIWeb\2026-09-24-gigi-android-native-design.md
- Web 版源码：D:\EmulatorShared\GIGI\web\src\
  - types/api.ts（数据模型，全字段可空约束）
  - api/client.ts（retcode 集中判定：AUTH_FAILED=-100/-101；RETRYABLE=-500004/-1/-110，700ms 重试一次）
  - config/servers.ts（服务器注册表，禁止硬编码 'cn_gf01'）
  - utils/（tier/code/opponent/percent/summary/throttle/ttlCache/wiki 八个纯函数 + 对应 .test.ts）
  - server/auth-core.mjs（扫码登录四步与凭据交换语义）
- 盾工程参考（构建配置样板）：D:\AndroidStudioProjects\Shield

## 5. 设计红线（来自设计文档，违反即返工）
1. 数据模型全部字段可空，消费端带默认值读取
2. retcode 语义集中在 MihoyoClient，页面禁止自行判定
3. 私有数据绝不落盘（只有图鉴列表落 DataStore）；缓存键含服务器标识
4. 凭据不出加密区：仅 CredentialStore.readForRequest() 供网络层；退出登录=清密文+删 Keystore 别名
5. AndroidManifest 必须 `android:allowBackup="false"`
6. id2code 字符表低位在前不反转、CA0…N 包裹；computeGcgSummary 出场率分母=全部角色牌使用次数之和——两个"反直觉"行为照原版保留，勿"修正"
7. no-role 登录：不写凭据、不切登录态、留登录页高亮服务器
8. 语义色固定：胜 #3f8f5f / 负 #c25656 / 鎏金 #d4a643，不参与动态取色

## 6. 提交署名
`git -c user.name="GIGI-Bot" -c user.email="gigi-bot@local" commit -m "<type>: <一句话>"`
type ∈ scaffold/domain/data/auth/ui/build/test

## 7. 探针格式（.task/progress/<ID>.md）
```markdown
status: doing|done|blocked
owner: <ID>
files: [<改动文件>]
evidence: [<命令=>退出码 / 路径>]
blockers: []
summary: <一句话>
updated: <取自 date 的真实时间>
```
