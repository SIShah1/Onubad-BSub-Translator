package com.example.srt

import android.content.Context
import android.net.Uri
import com.example.domain.model.SrtBlock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

data class SrtTimecodeRange(
  val startTime: String,
  val endTime: String,
  val startMillis: Long,
  val endMillis: Long,
  val trailingMetadata: String = "",
) {
  val durationMillis: Long
    get() = (endMillis - startMillis).coerceAtLeast(0L)

  val isChronologicallyValid: Boolean
    get() = endMillis >= startMillis

  fun normalized(): String {
    val base = "${startTime.replace('.', ',')} --> ${endTime.replace('.', ',')}"
    return if (trailingMetadata.isBlank()) base else "$base ${trailingMetadata.trim()}"
  }
}

data class SrtItem(
  val index: Int,
  val timestamp: String,
  val content: String,
) {
  val lines: List<String>
    get() = if (content.isEmpty()) emptyList() else content.split('\n')

  fun toSrtBlock(): SrtBlock =
    SrtBlock(
      index = index,
      timestamp = timestamp,
      lines = lines,
    )

  fun withTranslatedText(bengaliText: String): SrtItem {
    val cleanLines =
      bengaliText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .dropLastWhile { it.isBlank() }
        .map { it.trimEnd() }
    return copy(content = cleanLines.joinToString("\n"))
  }

  fun withTranslatedLines(bengaliLines: List<String>): SrtItem {
    val cleanLines =
      bengaliLines
        .dropLastWhile { it.isBlank() }
        .map { it.trimEnd() }
    return copy(content = cleanLines.joinToString("\n"))
  }
}

data class ParsedSrtDocument(
  val items: List<SrtItem>,
  val blocks: List<SrtBlock>,
  val isValid: Boolean,
  val totalBlocks: Int = items.size,
  val totalLines: Int = items.sumOf { item -> item.lines.count { it.isNotBlank() } },
)

class SrtFileParser {
  fun parseText(rawContent: String): List<SrtItem> = SrtParser.parse(rawContent)

  fun parseFile(file: File): List<SrtItem> = SrtParser.parse(file)

  fun parsePath(filePath: String): List<SrtItem> = SrtParser.parse(File(filePath))

  fun parseStream(inputStream: InputStream): List<SrtItem> = SrtParser.parse(inputStream)

  fun parseUri(context: Context, uri: Uri): List<SrtItem> = SrtParser.parse(context, uri)

  fun parseDocument(rawContent: String): ParsedSrtDocument = SrtParser.parseDocument(rawContent)

  fun parseDocument(file: File): ParsedSrtDocument = SrtParser.parseDocument(file)

  fun toBlocks(items: List<SrtItem>): List<SrtBlock> = items.map { it.toSrtBlock() }

  fun reconstruct(
    originalItems: List<SrtItem>,
    bengaliTranslations: Map<Int, String>,
    fallbackToOriginal: Boolean = true,
  ): String = SrtParser.reconstruct(originalItems, bengaliTranslations, fallbackToOriginal)

  fun format(items: List<SrtItem>): String = SrtParser.format(items)
}

object SrtParser {

  private val TIMECODE_LINE_REGEX =
    Regex(
      """^\s*(\d{1,3}):([0-5]\d):([0-5]\d)([,.])(\d{3})\s*-->\s*(\d{1,3}):([0-5]\d):([0-5]\d)([,.])(\d{3})(?:\s+(.*))?\s*$"""
    )

  private val TIMESTAMP_ARROW_REGEX = Regex("""\s*-->\s*""")

  fun readRaw(inputStream: InputStream): String {
    val bytes = inputStream.use { it.readBytes() }
    return decodeBytesWithBom(bytes)
  }

  fun readRaw(file: File): String {
    val bytes = file.readBytes()
    return decodeBytesWithBom(bytes)
  }

  fun readRaw(context: Context, uri: Uri): String? {
    return context.contentResolver.openInputStream(uri)?.use { readRaw(it) }
  }

  fun parse(inputStream: InputStream): List<SrtItem> {
    return parse(readRaw(inputStream))
  }

  fun parse(file: File): List<SrtItem> {
    if (!file.exists() || !file.isFile) return emptyList()
    return parse(readRaw(file))
  }

  fun parse(context: Context, uri: Uri): List<SrtItem> {
    val rawText = readRaw(context, uri) ?: return emptyList()
    return parse(rawText)
  }

  fun parse(rawContent: String): List<SrtItem> {
    val normalized =
      rawContent
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    if (normalized.isBlank()) return emptyList()

    val lines = normalized.lines()
    val anchors = ArrayList<Pair<Int, Int>>()

    for (lineIdx in 1 until lines.size) {
      if (!isValidTimecodeLine(lines[lineIdx])) continue
      val serialCandidate = lines[lineIdx - 1].trim().removePrefix("\uFEFF")
      val serial = serialCandidate.toIntOrNull() ?: continue
      anchors.add(lineIdx to serial)
    }

    if (anchors.isEmpty()) return emptyList()

    val items = ArrayList<SrtItem>(anchors.size)
    for (anchorPos in anchors.indices) {
      val (timecodeLineIdx, serial) = anchors[anchorPos]
      val timestamp = lines[timecodeLineIdx].trim()

      val nextAnchorTimecodeIdx = anchors.getOrNull(anchorPos + 1)?.first
      val contentEndExclusive =
        if (nextAnchorTimecodeIdx != null) nextAnchorTimecodeIdx - 1 else lines.size

      val rawContentLines =
        if (timecodeLineIdx + 1 < contentEndExclusive) {
          lines.subList(timecodeLineIdx + 1, contentEndExclusive)
        } else {
          emptyList()
        }

      val trimmedTrailing = rawContentLines.dropLastWhile { it.isBlank() }
      val content = trimmedTrailing.joinToString("\n") { it.trimEnd() }

      items.add(
        SrtItem(
          index = serial,
          timestamp = timestamp,
          content = content,
        )
      )
    }

    return items
  }

  fun parseBlocks(rawContent: String): List<SrtBlock> {
    return parse(rawContent).map { it.toSrtBlock() }
  }

  fun parseDocument(rawContent: String): ParsedSrtDocument {
    val items = parse(rawContent)
    return ParsedSrtDocument(
      items = items,
      blocks = items.map { it.toSrtBlock() },
      isValid = items.isNotEmpty(),
    )
  }

  fun parseDocument(file: File): ParsedSrtDocument {
    val items = parse(file)
    return ParsedSrtDocument(
      items = items,
      blocks = items.map { it.toSrtBlock() },
      isValid = items.isNotEmpty(),
    )
  }

  fun isValidTimecodeLine(timestampLine: String): Boolean {
    return TIMECODE_LINE_REGEX.matches(timestampLine)
  }

  fun parseTimecodeRange(timestampLine: String): SrtTimecodeRange? {
    val match = TIMECODE_LINE_REGEX.matchEntire(timestampLine) ?: return null
    val g = match.groupValues
    val startHours = g[1].toLongOrNull() ?: return null
    val startMinutes = g[2].toLongOrNull() ?: return null
    val startSeconds = g[3].toLongOrNull() ?: return null
    val startMillisPart = g[5].toLongOrNull() ?: return null

    val endHours = g[6].toLongOrNull() ?: return null
    val endMinutes = g[7].toLongOrNull() ?: return null
    val endSeconds = g[8].toLongOrNull() ?: return null
    val endMillisPart = g[10].toLongOrNull() ?: return null

    val startMs = ((startHours * 3600L) + (startMinutes * 60L) + startSeconds) * 1000L + startMillisPart
    val endMs = ((endHours * 3600L) + (endMinutes * 60L) + endSeconds) * 1000L + endMillisPart

    val startRaw = "${g[1]}:${g[2]}:${g[3]}${g[4]}${g[5]}"
    val endRaw = "${g[6]}:${g[7]}:${g[8]}${g[9]}${g[10]}"
    val metadata = g.getOrElse(11) { "" }.trim()

    return SrtTimecodeRange(
      startTime = startRaw,
      endTime = endRaw,
      startMillis = startMs,
      endMillis = endMs,
      trailingMetadata = metadata,
    )
  }

  fun normalizeTimestamp(timestamp: String): String {
    val parsed = parseTimecodeRange(timestamp)
    if (parsed != null) return parsed.normalized()
    return timestamp
      .trim()
      .replace(TIMESTAMP_ARROW_REGEX, " --> ")
      .replace('.', ',')
  }

  /**
   * Replaces subtitle text in [originalItems] with Bengali translations mapped by cue index,
   * preserving exact cue indices and timecodes verbatim.
   */
  fun replaceTranslations(
    originalItems: List<SrtItem>,
    bengaliTranslations: Map<Int, String>,
    fallbackToOriginal: Boolean = true,
  ): List<SrtItem> {
    return originalItems.map { original ->
      val translated = bengaliTranslations[original.index]
      if (translated == null || (translated.isBlank() && fallbackToOriginal && original.content.isNotBlank())) {
        original
      } else {
        original.withTranslatedText(translated)
      }
    }
  }

  /**
   * Applies translated [SrtBlock] entries onto [originalItems] while guaranteeing that original cue
   * serial numbers and timecodes remain strictly unchanged.
   */
  fun applyTranslatedBlocks(
    originalItems: List<SrtItem>,
    translatedBlocks: List<SrtBlock>,
    strictTimecodeCheck: Boolean = true,
    fallbackToOriginal: Boolean = true,
  ): List<SrtItem> {
    val translatedByIndex = translatedBlocks.associateBy { it.index }
    return originalItems.map { original ->
      val candidate = translatedByIndex[original.index]
      if (candidate == null) {
        if (fallbackToOriginal) {
          original
        } else {
          throw IllegalArgumentException("Missing translated block for cue #${original.index}")
        }
      } else {
        if (
          strictTimecodeCheck &&
            normalizeTimestamp(candidate.timestamp) != normalizeTimestamp(original.timestamp)
        ) {
          throw IllegalArgumentException(
            "Timecode integrity violation at cue #${original.index}: expected '${original.timestamp}', got '${candidate.timestamp}'"
          )
        }
        val hasTranslatedText = candidate.lines.any { it.isNotBlank() }
        if (!hasTranslatedText && fallbackToOriginal && original.content.isNotBlank()) {
          original
        } else {
          original.withTranslatedLines(candidate.lines)
        }
      }
    }
  }

  fun replaceBlockTranslations(
    originalBlocks: List<SrtBlock>,
    translatedBlocks: List<SrtBlock>,
    strictTimecodeCheck: Boolean = true,
    fallbackToOriginal: Boolean = true,
  ): List<SrtBlock> {
    val originalItems =
      originalBlocks.map { block ->
        SrtItem(
          index = block.index,
          timestamp = block.timestamp,
          content = block.lines.joinToString("\n"),
        )
      }
    return applyTranslatedBlocks(
        originalItems = originalItems,
        translatedBlocks = translatedBlocks,
        strictTimecodeCheck = strictTimecodeCheck,
        fallbackToOriginal = fallbackToOriginal,
      )
      .map { it.toSrtBlock() }
  }

  fun reconstruct(
    originalItems: List<SrtItem>,
    bengaliTranslations: Map<Int, String>,
    fallbackToOriginal: Boolean = true,
  ): String {
    return format(replaceTranslations(originalItems, bengaliTranslations, fallbackToOriginal))
  }

  fun reconstruct(
    originalRawSrt: String,
    bengaliTranslations: Map<Int, String>,
    fallbackToOriginal: Boolean = true,
  ): String {
    val originalItems = parse(originalRawSrt)
    return reconstruct(originalItems, bengaliTranslations, fallbackToOriginal)
  }

  fun reconstructFromBlocks(
    originalBlocks: List<SrtBlock>,
    translatedBlocks: List<SrtBlock>,
    strictTimecodeCheck: Boolean = true,
    fallbackToOriginal: Boolean = true,
  ): String {
    return formatBlocks(
      replaceBlockTranslations(
        originalBlocks = originalBlocks,
        translatedBlocks = translatedBlocks,
        strictTimecodeCheck = strictTimecodeCheck,
        fallbackToOriginal = fallbackToOriginal,
      )
    )
  }

  fun format(items: List<SrtItem>): String {
    if (items.isEmpty()) return ""
    return items.joinToString("\n\n") { item ->
      buildString {
        append(item.index).append('\n')
        append(item.timestamp.trim())
        if (item.content.isNotEmpty()) {
          append('\n').append(item.content)
        }
      }
    } + "\n"
  }

  fun formatBlocks(blocks: List<SrtBlock>): String {
    if (blocks.isEmpty()) return ""
    val sb = StringBuilder(blocks.size * 64)
    blocks.forEachIndexed { idx, block ->
      sb.append(block.index).append('\n')
      sb.append(block.timestamp.trim()).append('\n')
      if (block.lines.isEmpty()) {
        sb.append('\n')
      } else {
        block.lines.forEach { line ->
          sb.append(line.trimEnd()).append('\n')
        }
      }
      if (idx < blocks.lastIndex) {
        sb.append('\n')
      }
    }
    return sb.toString()
  }

  fun write(
    outputStream: OutputStream,
    items: List<SrtItem>,
    includeUtf8Bom: Boolean = false,
  ) {
    val text = format(items)
    outputStream.use { out ->
      if (includeUtf8Bom) {
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
      }
      out.write(text.toByteArray(Charsets.UTF_8))
      out.flush()
    }
  }

  fun write(
    file: File,
    items: List<SrtItem>,
    includeUtf8Bom: Boolean = false,
  ) {
    file.parentFile?.mkdirs()
    file.outputStream().use { out ->
      if (includeUtf8Bom) {
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
      }
      out.write(format(items).toByteArray(Charsets.UTF_8))
      out.flush()
    }
  }

  private fun decodeBytesWithBom(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    return when {
      bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte() ->
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

      bytes.size >= 2 &&
        bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xFE.toByte() ->
        String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))

      bytes.size >= 2 &&
        bytes[0] == 0xFE.toByte() &&
        bytes[1] == 0xFF.toByte() ->
        String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))

      else -> String(bytes, Charsets.UTF_8)
    }
  }
}
