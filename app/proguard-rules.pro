# Readable stack traces from user reports; shrinking and optimisation still run.
-dontobfuscate
-keepattributes Signature,EnclosingMethod,InnerClasses,*Annotation*

# Gson models persisted in MMKV, and every type reachable from their fields. Gson reads fields
# reflectively, so R8 must not strip, merge or inline them. Only these are pinned: the rest of the
# app and the extractor get fully optimised. Gson ships its own rules for TypeToken and
# @SerializedName. Add any new class stored with Gson here, or it'll come back empty in release.
-keep class com.testtube.app.downloader.core.history.DownloadRecord { <fields>; <init>(...); }
-keep enum com.testtube.app.downloader.core.history.DownloadStatus { *; }
-keep enum com.testtube.app.downloader.core.history.DownloadType { *; }
-keep class com.testtube.app.extractor.Delivery { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.DeliveryCatalog { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.FeedClient$CachedChannel { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.FeedClient$RssVideo { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.FeedItem { <fields>; <init>(...); }
-keep enum com.testtube.app.extractor.FeedItem$Kind { *; }
-keep class com.testtube.app.extractor.LocalSubscriptions$Channel { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.PlaybackDetails { <fields>; <init>(...); }
-keep enum com.testtube.app.extractor.PlaybackMode { *; }
-keep class com.testtube.app.extractor.PlaybackPlan { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.RelatedVideo { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.StreamCandidate { <fields>; <init>(...); }
-keep enum com.testtube.app.extractor.StreamCandidateKind { *; }
-keep class com.testtube.app.extractor.StreamCatalog { <fields>; <init>(...); }
-keep class com.testtube.app.extractor.VideoDetails { <fields>; <init>(...); }
-keep class com.testtube.app.filter.BlockedChannel { <fields>; <init>(...); }
-keep class com.testtube.app.history.WatchHistory$Entry { <fields>; <init>(...); }
-keep class com.testtube.app.player.common.PlayerPreferences$Played { <fields>; <init>(...); }
-keep class com.testtube.app.player.common.PlayerPreferences$Progress { <fields>; <init>(...); }
-keep class com.testtube.app.player.queue.QueueItem { <fields>; <init>(...); }

# The NewPipe stream models inside PlaybackDetails (cached and deep-copied with Gson).
-keep enum org.schabi.newpipe.extractor.MediaFormat { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.ItagItem { <fields>; <init>(...); }
-keep enum org.schabi.newpipe.extractor.services.youtube.ItagItem$ItagType { *; }
-keep class org.schabi.newpipe.extractor.stream.AudioStream { <fields>; <init>(...); }
-keep enum org.schabi.newpipe.extractor.stream.AudioTrackType { *; }
-keep enum org.schabi.newpipe.extractor.stream.DeliveryMethod { *; }
-keep class org.schabi.newpipe.extractor.stream.Stream { <fields>; <init>(...); }
-keep class org.schabi.newpipe.extractor.stream.StreamSegment { <fields>; <init>(...); }
-keep enum org.schabi.newpipe.extractor.stream.StreamType { *; }
-keep class org.schabi.newpipe.extractor.stream.SubtitlesStream { <fields>; <init>(...); }
-keep class org.schabi.newpipe.extractor.stream.VideoStream { <fields>; <init>(...); }

# The PoToken page calls back into the app through these.
-keepclassmembers class com.testtube.app.extractor.potoken.PoTokenBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Rhino generates and loads classes at runtime and looks a lot of itself up by name.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }

# Protobuf lite finds message fields by name.
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
