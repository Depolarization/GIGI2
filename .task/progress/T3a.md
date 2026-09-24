status: done
owner: T3a
files:
  - app/src/main/java/com/gigi/tcg/data/model/ApiModels.kt
  - app/src/main/java/com/gigi/tcg/data/ServerId.kt
  - app/src/main/java/com/gigi/tcg/data/ServerApi.kt
  - app/src/test/java/com/gigi/tcg/data/ServerRegistryTest.kt
evidence:
  - "git status --porcelain 改前闸门 = 空 (HEAD=67e62fd)"
  - "commit 7485049 data: serializable models and server registry (4 files, +342)"
  - "对照: api.ts L5-143 逐类型; servers.ts L19-66; servers.test.ts L14/23/28/33/42/51"
blockers: []
summary: >
  18+ @Serializable 数据类全字段可空带默认，timestamp 保持 String?、uid 用 JsonPrimitive
  容错 number|string；ServerId sealed(Official/Channel, ALL/DEFAULT/from/isValid)+
  ServerResolution/resolveServerWithFallback/otherServer；ServerApi 6 常量逐字搬；
  ServerRegistryTest 移植前 6 用例。未跑 gradle（交 T3d）。
updated: 2026-09-24 23:46:27
