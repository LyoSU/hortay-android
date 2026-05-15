# TDLib uses JNI to access Java fields and call constructors / methods reflectively
# from native code. Without these keeps the release build crashes on first td.send()
# with NoSuchMethodError or NoSuchFieldError. The TdApi.* nested classes carry our
# request/response payloads — keep their fields and zero-arg constructors.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.TdApi$* {
    <fields>;
    <init>(...);
}

# DEX layout optimization: collapse every obfuscated class into the empty package
# so R8 can drop the per-class package-name strings from the DEX string pool.
# Typical saving: 100–300 KB DEX on apps of this size. Doesn't affect any class
# we explicitly -keep above (their original package names are preserved). The
# trade-off is that stack traces in Play Console show "a.b.c" by default — Play
# auto-deobfuscates via the bundled mapping.txt, so symbolicated traces still
# read normally end-to-end. Direct ADB logcat reads need the mapping.txt applied
# manually via retrace.
-repackageclasses ''

# kotlinx.serialization annotation processing relies on companion-object accessors
# and synthetic methods that R8 strips by default.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose stability inference reads @Stable / @Immutable at runtime when generating
# skippable lambdas. Strip the annotations and PostCard recomposes on every update.
-keep @androidx.compose.runtime.Stable class *
-keep @androidx.compose.runtime.Immutable class *

# Coroutines: keep volatile fields + Continuation symbol names so release stack traces
# remain readable in crash reports.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlin.coroutines.Continuation

# SQLDelight 2.x generates `<DatabaseName>.Schema` as a Companion-object Schema
# accessor that the Android driver invokes via reflection at create-or-migrate
# time. R8 doesn't see the call (it goes through the AndroidSqliteDriver factory)
# and may strip the Schema field on release, crashing the very first SQLDelight
# query with NoSuchFieldError. Keep all generated DB classes wholesale — the
# package is small (one `WebDatabase` + its query interfaces).
-keep class dev.lyo.hortay.data.web.db.** { *; }
-keep class app.cash.sqldelight.** { *; }
