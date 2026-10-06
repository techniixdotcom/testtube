# Readable stack traces from user reports; shrinking and optimisation still run.
-dontobfuscate
-keepattributes Signature,EnclosingMethod,InnerClasses,*Annotation*

# Gson models persisted in MMKV (Gson ships its own rules for TypeToken and @SerializedName).
-keep class com.testtube.app.downloader.core.history.** { *; }
-keep class com.testtube.app.extractor.** { *; }
-keep class com.testtube.app.filter.** { *; }
-keep class com.testtube.app.history.** { *; }
-keep class com.testtube.app.player.common.PlayerPreferences$* { *; }
-keep class com.testtube.app.player.queue.QueueItem { *; }

# NewPipe Extractor: stream models are cached with Gson, timeago patterns are loaded reflectively,
# and Rhino generates and loads classes at runtime.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# isoparser instantiates its box classes reflectively.
-keep class com.googlecode.mp4parser.** { *; }
-keep class com.coremedia.iso.** { *; }
-keep class com.mp4parser.** { *; }

# Optional classes referenced by dependencies but never used on Android.
-dontwarn com.google.common.collect.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.re2j.**
-dontwarn com.grack.nanojson.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn javax.imageio.**
-dontwarn javax.money.**
-dontwarn javax.script.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.javamoney.moneta.**
-dontwarn org.joda.time.**
-dontwarn org.jsoup.**
-dontwarn org.jspecify.annotations.**
-dontwarn org.mozilla.classfile.**
-dontwarn org.mozilla.javascript.**
-dontwarn springfox.documentation.**
