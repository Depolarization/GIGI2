# Add project specific ProGuard rules here.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gigi.tcg.domain.**$$serializer { *; }
-keepclassmembers class com.gigi.tcg.domain.** {
    *** Companion;
}
-keepclasseswithmembers class com.gigi.tcg.domain.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.gigi.tcg.data.model.**$$serializer { *; }
-keepclassmembers class com.gigi.tcg.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.gigi.tcg.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coil
-keep class coil.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ZXing
-keep class com.google.zxing.** { *; }
