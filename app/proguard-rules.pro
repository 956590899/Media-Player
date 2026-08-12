# ProGuard rules for Media Player Plus

# Keep Application class (declared in AndroidManifest.xml)
-keep class com.mediaplayer.plus.MediaPlayerApp { *; }

# Keep MediaPlayer player state class
-keep class com.mediaplayer.plus.player.PlayerState { *; }
-keep class com.mediaplayer.plus.player.PlayerState$Status { *; }

# Keep MusicServiceManager (static sharedAlbumArtBytes)
-keep class com.mediaplayer.plus.MusicServiceManager { *; }

# Keep MusicService
-keep class com.mediaplayer.plus.MusicService { *; }

# Keep MediaMetadataRetriever reflection
-dontwarn android.media.MediaMetadataRetriever

# Keep Media3 ID3 classes
-keep class androidx.media3.extractor.metadata.id3.** { *; }
-keep class androidx.media3.common.Metadata { *; }
-keep class androidx.media3.common.MediaMetadata { *; }

# slf4j optional dependency (no binding at runtime)
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Bouncy Castle rules for SMB NTLM MD4 support
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# jcifs-ng rules
-keep class jcifs.** { *; }
-dontwarn jcifs.**
