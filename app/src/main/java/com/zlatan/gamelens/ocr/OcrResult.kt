package com.zlatan.gamelens.ocr

import android.graphics.Rect
import com.zlatan.gamelens.overlay.SampledColorScheme

data class OcrLine(
    val text: String,
    val boundingBox: Rect,
    var translatedText: String? = null
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float? = null,
    val lines: List<OcrLine> = emptyList(),
    var translatedText: String? = null,
    var colorScheme: SampledColorScheme? = null
)
