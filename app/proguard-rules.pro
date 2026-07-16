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

# libhortaywebm.so (minimal ffmpeg VP9+alpha decoder) reaches back into Kotlin via JNI:
# nativeDecode() is resolved by symbol name (kept by the default native-methods rule), but it
# instantiates WebmAlphaNative$Raw through FindClass("…/WebmAlphaNative$Raw") +
# GetMethodID("<init>","([I[IIII)V"). R8 never sees that native instantiation, so without an
# explicit keep it renames Raw (and may drop the @JvmField fields / the ctor's descriptor); the
# JNI lookup then misses and the decoder fail-softs to null — every WebM video sticker & animated
# custom emoji silently falls back to its STATIC thumb in minified builds only (debug looks fine).
-keep class dev.lyo.hortay.webm.** { *; }

# jLaTeXMath (ru.noties:jlatexmath-android) — the rich-message math renderer. The engine loads
# its TeX macro handlers, symbol tables and font descriptors REFLECTIVELY from XML resources
# bundled in the AAR assets (TeXFormulaSettings.xml, GlueSettings.xml, DefaultTeXFont maps →
# handler class + method names resolved at runtime, never referenced from bytecode). R8 can't
# see those call sites, so a minified build strips the handler classes / methods and the app
# crashes (ParseException / NoSuchMethodError) the first time it renders a formula using a
# stripped macro — release-only, invisible in debug. The AAR ships NO consumer-proguard rules,
# so keep the whole engine wholesale. Cost is a fraction of the ~2.1 MB the fonts already add.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }

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
# accessor that the Android driver invokes at create-or-migrate time. Keep both
# generated DB packages wholesale as a precaution — each is small (WebDatabase +
# ArchiveDatabase, with their query interfaces) — so a release build can never
# strip a Schema member R8 didn't see referenced through the AndroidSqliteDriver
# factory and crash the first query with NoSuchFieldError/NoSuchMethodError. Both
# databases get the symmetric rule; dropping either requires re-verifying the full
# capture + read flow in a minified build.
-keep class dev.lyo.hortay.data.web.db.** { *; }
-keep class dev.lyo.hortay.data.archive.db.** { *; }
-keep class app.cash.sqldelight.** { *; }
