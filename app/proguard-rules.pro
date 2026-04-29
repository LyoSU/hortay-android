# TDLib uses JNI to access Java fields and call constructors / methods reflectively
# from native code. Without these keeps the release build crashes on first td.send()
# with NoSuchMethodError or NoSuchFieldError. The TdApi.* nested classes carry our
# request/response payloads — keep their fields and zero-arg constructors.
-keep class org.drinkless.tdlib.** { *; }
-keep class org.drinkless.td.libcore.** { *; }
-keepclassmembers class org.drinkless.tdlib.TdApi$* {
    <fields>;
    <init>(...);
}

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
