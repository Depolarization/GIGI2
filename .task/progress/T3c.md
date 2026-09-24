# T3c 探针

status: done
owner: T3c
files: [app/src/main/java/com/gigi/tcg/data/auth/CredentialStore.kt]
evidence: [git status --porcelain -- app/.../data/auth=空(改前闸门), git commit=>f85b49c 1 file 104 insertions]
blockers: []
summary: Keystore+AES-GCM 凭据存储完成并提交 f85b49c。

## 验收细节
- **算法参数**：AndroidKeyStore 提供者；AES-256（setKeySize(256)）；
  KeyGenParameterSpec(别名 `gigi_credentials_key`, PURPOSE_ENCRYPT|PURPOSE_DECRYPT,
  BLOCK_MODES_GCM, ENCRYPTION_PADDINGS_NONE, setRandomizedEncryptionRequired(true))；
  变换 `AES/GCM/NoPadding`，GCMParameterSpec(128, iv)。
- **随机 IV**：save 每次 SecureRandom 生成 12B IV；加密后以 `cipher.iv` 回读实际 IV
  （Keystore 可能重生成，官方推荐做法），避免解密 IV 失配。
- **存储结构**：SharedPreferences 文件 `gigi_credentials`、key `ciphertext`、
  值=Base64(NO_WRAP)(IV[12B] + GCM密文[含16B tag])。
- **解密容错**：无密文/Base64坏/长度≤12B/GeneralSecurityException（tag校验失败、密钥丢失）
  一律返回 null，不抛出。
- **clear 双重清除**：`prefs.edit().clear().apply()` + `containsAlias` 判断后 `deleteEntry`，
  满足设计红线 4「清密文+删 Keystore 别名」。
- **与 CredentialSource 关系**：`class CredentialStore(context) : CredentialSource`，
  `override fun cookieHeader(): String?` 即网络层唯一取用口；明文仅出现在 save 参数与
  cookieHeader 返回值，加密细节（obtainKey/keyStore/companion 常量）全 private。
- 未引入 androidx.security；无日志输出；未跑 gradle（编译归 T3d）。
