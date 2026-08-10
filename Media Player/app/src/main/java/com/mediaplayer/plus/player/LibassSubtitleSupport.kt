package com.mediaplayer.plus.player

import android.util.Log
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory

/**
 * 字幕渲染悬浮组件
 */
@Composable
fun SubtitleOverlay(cues: List<androidx.media3.common.text.Cue>, modifier: Modifier = Modifier, videoW: Int = 0, videoH: Int = 0) {
    if (cues.isEmpty()) return

    val hasBitmapCues = cues.any { it.bitmap != null }
    Log.d("SubTitle", "fullscreen cues=${cues.size} hasBitmap=$hasBitmapCues video=${videoW}x${videoH}")

    if (hasBitmapCues) {
        val sizes = cues.map { c -> c.bitmap?.width to c.bitmap?.height }
        Log.d("LibassCues", "fullscreen sizes=$sizes video=${videoW}x${videoH}")

        val canvasW = if (videoW > 0 && videoH > 0) videoW else cues.maxOfOrNull { it.bitmap?.width ?: 0 } ?: 1
        val canvasH = if (videoW > 0 && videoH > 0) videoH else cues.maxOfOrNull { it.bitmap?.height ?: 0 } ?: 1

        val bitmaps = cues.mapNotNull { c -> c.bitmap?.takeIf { it.width > 0 && it.height > 0 } }
        if (bitmaps.isEmpty()) return

        val combined = android.graphics.Bitmap.createBitmap(canvasW, canvasH, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(combined)
        c.drawColor(android.graphics.Color.TRANSPARENT)

        for (cue in cues) {
            val bm = cue.bitmap ?: continue
            if (bm.width <= 0 || bm.height <= 0) continue
            c.drawBitmap(bm, 0f, 0f, null)
        }

        Image(
            bitmap = combined.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                cues.forEach { cue ->
                    val rawText = cue.text?.toString() ?: ""
                    if (rawText.isNotBlank()) {
                        Text(
                            text = rawText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = 14.sp,
                            modifier = Modifier.graphicsLayer(clip = true),
                            style = TextStyle(
                                shadow = Shadow(
                                    color = Color.Black,
                                    offset = Offset(1f, 1f),
                                    blurRadius = 1f
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Libass 原始数据拦截工厂
 */
@UnstableApi
class LibassRawInterceptorFactory(
    private val defaultFactory: SubtitleParser.Factory,
    private val assHandler: AssHandler,
    var useLibass: Boolean = false
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean {
        val ok = if (useLibass && isAssFormat(format)) true else defaultFactory.supportsFormat(format)
        Log.d("LibassFactory", "supportsFormat mime=${format.sampleMimeType} useLibass=$useLibass result=$ok")
        return ok
    }

    override fun create(format: Format): SubtitleParser {
        if (useLibass && isAssFormat(format)) {
            val patched = if (format.id == null) {
                val counterId = "ext_${(System.nanoTime() and 0xFFFFFFFFL).toString(16)}"
                format.buildUpon()
                    .setId(counterId)
                    .setLabel("external_subtitle")
                    .setLanguage(format.language ?: "zh")
                    .build()
            } else format
            Log.d("LibassFactory", "create id=${patched.id} lang=${patched.language}")
            val libassParser = AssSubtitleParserFactory(assHandler).create(patched)
            return LibassRawInterceptorParser(libassParser)
        }
        return defaultFactory.create(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return if (useLibass && isAssFormat(format)) 1
        else defaultFactory.getCueReplacementBehavior(format)
    }

    private fun isAssFormat(format: Format): Boolean {
        return format.sampleMimeType == MimeTypes.TEXT_SSA || format.sampleMimeType == "text/x-ssa"
    }
}

@UnstableApi
private class LibassRawInterceptorParser(
    private val delegate: SubtitleParser
) : SubtitleParser {
    override fun parse(data: ByteArray, offset: Int, length: Int, outputOptions: SubtitleParser.OutputOptions, output: Consumer<CuesWithTiming>) {
        delegate.parse(data, offset, length, outputOptions, output)
    }
    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior
    override fun reset() = delegate.reset()
}
