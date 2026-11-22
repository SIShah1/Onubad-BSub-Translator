package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val ADAPTIVE_GRID_SIZE = 108f
private const val COLOR_TEAL_BG_HEX = 0xFF1B687E.toInt()
private const val COLOR_YELLOW_FG_HEX = 0xFFFFD54F.toInt()

/**
 * Renders the full layered Android Adaptive App Icon inside Jetpack Compose:
 * - Edge-to-edge solid deep teal background (#1B687E)
 * - Foreground artwork centered strictly inside the inner 66% adaptive safe zone
 * - Cleanly knocked-out "অ" and "A" glyphs letting the background layer show through
 */
@Composable
fun AppIconGraphic(modifier: Modifier = Modifier) {
  Canvas(
    modifier = modifier.aspectRatio(1f),
  ) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    // 1. Solid deep teal background edge-to-edge (no borders, no margins, no rounded corners)
    drawBackgroundLayer(nativeCanvas, size.width, size.height)

    // 2. Isolate foreground in an offscreen layer so PorterDuff.Mode.CLEAR punches
    // cleanly down to the background layer instead of clearing the Compose surface itself.
    val layerId = nativeCanvas.saveLayer(0f, 0f, size.width, size.height, null)
    try {
      drawForegroundLayer(nativeCanvas, size.width, size.height)
    } finally {
      nativeCanvas.restoreToCount(layerId)
    }
  }
}

/**
 * Renders and saves a lossless [sizePx] x [sizePx] PNG (ARGB_8888) containing only the
 * foreground artwork on a transparent canvas, strictly centered inside the 66% safe zone.
 */
fun exportForegroundPng(outputFile: File, sizePx: Int = 1024) {
  require(sizePx > 0) { "sizePx must be positive, was $sizePx" }
  val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  try {
    bitmap.eraseColor(AndroidColor.TRANSPARENT)
    val canvas = AndroidCanvas(bitmap)
    drawForegroundLayer(canvas, sizePx.toFloat(), sizePx.toFloat())
    saveBitmapLosslessPng(bitmap, outputFile)
  } finally {
    bitmap.recycle()
  }
}

/**
 * Renders and saves a lossless [sizePx] x [sizePx] PNG (ARGB_8888) filled edge-to-edge
 * with solid deep teal (#1B687E).
 */
fun exportBackgroundPng(outputFile: File, sizePx: Int = 1024) {
  require(sizePx > 0) { "sizePx must be positive, was $sizePx" }
  val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  try {
    val canvas = AndroidCanvas(bitmap)
    drawBackgroundLayer(canvas, sizePx.toFloat(), sizePx.toFloat())
    saveBitmapLosslessPng(bitmap, outputFile)
  } finally {
    bitmap.recycle()
  }
}

/**
 * Verifies launcher safe-zone framing in Android Studio by clipping [AppIconGraphic]
 * inside both a circular launcher mask and a squircle launcher mask.
 */
@Preview(showBackground = true, backgroundColor = 0xFF101820)
@Composable
fun AdaptiveIconPreview() {
  val squircleShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val rx = w / 2f
    val ry = h / 2f
    val exponent = 4.2f
    val steps = 128

    for (i in 0..steps) {
      val theta = (2.0 * Math.PI * i / steps).toFloat()
      val cosT = cos(theta)
      val sinT = sin(theta)
      val x = cx + rx * sign(cosT) * abs(cosT).pow(2f / exponent)
      val y = cy + ry * sign(sinT) * abs(sinT).pow(2f / exponent)
      if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
  }

  Row(
    modifier = Modifier
      .background(Color(0xFF101820))
      .padding(24.dp),
    horizontalArrangement = Arrangement.spacedBy(24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(108.dp)
          .clip(CircleShape),
      ) {
        AppIconGraphic(modifier = Modifier.fillMaxSize())
      }
      Text(text = "Circle", color = Color.White, fontSize = 12.sp)
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(108.dp)
          .clip(squircleShape),
      ) {
        AppIconGraphic(modifier = Modifier.fillMaxSize())
      }
      Text(text = "Squircle", color = Color.White, fontSize = 12.sp)
    }
  }
}

private fun saveBitmapLosslessPng(bitmap: Bitmap, outputFile: File) {
  outputFile.parentFile?.mkdirs()
  FileOutputStream(outputFile).use { stream ->
    val written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    check(written) { "Failed to compress bitmap as PNG to ${outputFile.absolutePath}" }
    stream.flush()
  }
}

private fun drawBackgroundLayer(canvas: AndroidCanvas, width: Float, height: Float) {
  val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOR_TEAL_BG_HEX
    style = Paint.Style.FILL
  }
  canvas.drawRect(0f, 0f, width, height, bgPaint)
}

/**
 * Draws all foreground shapes normalized onto a 108x108 virtual grid so all elements remain
 * strictly inside the central 66% safe zone ([21..87] on both axes).
 */
private fun drawForegroundLayer(canvas: AndroidCanvas, width: Float, height: Float) {
  val scale = min(width, height) / ADAPTIVE_GRID_SIZE
  val offsetX = (width - ADAPTIVE_GRID_SIZE * scale) / 2f
  val offsetY = (height - ADAPTIVE_GRID_SIZE * scale) / 2f

  canvas.save()
  canvas.translate(offsetX, offsetY)
  canvas.scale(scale, scale)

  val yellowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOR_YELLOW_FG_HEX
    style = Paint.Style.FILL
  }

  val yellowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = COLOR_YELLOW_FG_HEX
    style = Paint.Style.STROKE
    strokeWidth = 2.6f
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }

  val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    style = Paint.Style.FILL
    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
  }

  // 1. Upper-left speech bubble ("অ") inside [23..53, 23..53.5]
  val leftBubbleRect = RectF(23f, 23f, 53f, 47.5f)
  canvas.drawRoundRect(leftBubbleRect, 6.2f, 6.2f, yellowFillPaint)
  val leftTail = AndroidPath().apply {
    moveTo(28.5f, 46.8f)
    lineTo(26.2f, 53.2f)
    lineTo(35.0f, 46.8f)
    close()
  }
  canvas.drawPath(leftTail, yellowFillPaint)

  // 2. Lower-right speech bubble ("A") inside [55..85, 37.5..67.5]
  val rightBubbleRect = RectF(55f, 37.5f, 85f, 62f)
  canvas.drawRoundRect(rightBubbleRect, 6.2f, 6.2f, yellowFillPaint)
  val rightTail = AndroidPath().apply {
    moveTo(73.0f, 61.3f)
    lineTo(81.8f, 67.7f)
    lineTo(79.5f, 61.3f)
    close()
  }
  canvas.drawPath(rightTail, yellowFillPaint)

  // 3. Cleanly knock out "অ" and "A" so the background layer shows through
  drawCenteredClearGlyph(
    canvas = canvas,
    glyph = "অ",
    bounds = leftBubbleRect,
    textSize = 17.5f,
    clearPaint = clearPaint,
  )
  drawCenteredClearGlyph(
    canvas = canvas,
    glyph = "A",
    bounds = rightBubbleRect,
    textSize = 16.5f,
    clearPaint = clearPaint,
  )

  // 4. Two smooth curved yellow arrows connecting the bubbles
  // Top-right arrow: from right of top-left bubble toward top of bottom-right bubble
  drawCurvedArrow(
    canvas = canvas,
    startX = 56.8f,
    startY = 27.6f,
    ctrlX = 70.8f,
    ctrlY = 25.0f,
    endX = 73.2f,
    endY = 33.4f,
    strokePaint = yellowStrokePaint,
    headPaint = yellowFillPaint,
  )
  // Bottom-left arrow: from left of bottom-right bubble toward bottom of top-left bubble
  drawCurvedArrow(
    canvas = canvas,
    startX = 51.2f,
    startY = 57.4f,
    ctrlX = 37.2f,
    ctrlY = 60.0f,
    endX = 34.8f,
    endY = 51.6f,
    strokePaint = yellowStrokePaint,
    headPaint = yellowFillPaint,
  )

  // 5. Neat horizontal subtitle bars centered underneath the bubbles (strictly <= 87)
  // Top solid full-width bar
  canvas.drawRoundRect(RectF(26f, 71.5f, 82f, 75.5f), 2f, 2f, yellowFillPaint)
  // Bottom segmented bars (two shorter bars)
  canvas.drawRoundRect(RectF(28f, 79.0f, 56f, 83.0f), 2f, 2f, yellowFillPaint)
  canvas.drawRoundRect(RectF(60f, 79.0f, 80f, 83.0f), 2f, 2f, yellowFillPaint)

  canvas.restore()
}

private fun drawCenteredClearGlyph(
  canvas: AndroidCanvas,
  glyph: String,
  bounds: RectF,
  textSize: Float,
  clearPaint: Paint,
) {
  clearPaint.textSize = textSize
  val textBounds = Rect()
  clearPaint.getTextBounds(glyph, 0, glyph.length, textBounds)
  val x = bounds.centerX()
  val y = bounds.centerY() - textBounds.exactCenterY()
  canvas.drawText(glyph, x, y, clearPaint)
}

private fun drawCurvedArrow(
  canvas: AndroidCanvas,
  startX: Float,
  startY: Float,
  ctrlX: Float,
  ctrlY: Float,
  endX: Float,
  endY: Float,
  strokePaint: Paint,
  headPaint: Paint,
) {
  val shaftPath = AndroidPath().apply {
    moveTo(startX, startY)
    quadTo(ctrlX, ctrlY, endX, endY)
  }
  canvas.drawPath(shaftPath, strokePaint)

  // Tangent at quadratic Bezier t = 1 is (end - ctrl)
  val angle = atan2((endY - ctrlY).toDouble(), (endX - ctrlX).toDouble())
  val headLength = 4.6f
  val spreadRadians = Math.toRadians(34.0)

  val tipX = endX + (1.4f * cos(angle)).toFloat()
  val tipY = endY + (1.4f * sin(angle)).toFloat()
  val leftX = tipX - (headLength * cos(angle - spreadRadians)).toFloat()
  val leftY = tipY - (headLength * sin(angle - spreadRadians)).toFloat()
  val rightX = tipX - (headLength * cos(angle + spreadRadians)).toFloat()
  val rightY = tipY - (headLength * sin(angle + spreadRadians)).toFloat()

  val arrowHead = AndroidPath().apply {
    moveTo(tipX, tipY)
    lineTo(leftX, leftY)
    lineTo(rightX, rightY)
    close()
  }
  canvas.drawPath(arrowHead, headPaint)
}
