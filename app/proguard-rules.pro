# Pulsar MVP keeps default R8 rules until release dependencies are finalized.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.pulsar.**$$serializer { *; }
-keepclassmembers class com.example.pulsar.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.pulsar.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.slf4j.**
# Ktor 的 IDE 调试检测代码引用了标准 JDK 的 management 类，安卓上不存在但也不会被走到
-dontwarn java.lang.management.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# DataStore
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
