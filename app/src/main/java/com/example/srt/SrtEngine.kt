package com.example.srt

import com.example.domain.model.AppSettings
import com.example.domain.model.NamingMode
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock

sealed class ValidationResult {
  data class Valid(val blocks: List<SrtBlock>) : ValidationResult()

  data class Invalid(val reason: String) : ValidationResult()
}

sealed class BatchReconciliationResult {
  data class Resolved(
    val blocks: List<SrtBlock>,
    val exactMatch: Boolean,
    val matchedCount: Int,
  ) : BatchReconciliationResult()

  data class TooIncomplete(
    val matchedCount: Int,
    val expectedCount: Int,
    val reason: String,
  ) : BatchReconciliationResult()
}

object SrtEngine {

  private val CODE_FENCE_BLOCK_REGEX =
    Regex("""```(?:[a-zA-Z0-9_-]+)?\s*\n([\s\S]*?)```""")
  private val CODE_FENCE_OPEN_REGEX = Regex("""^```[a-zA-Z0-9_-]*\s*""")
  private val CODE_FENCE_CLOSE_REGEX = Regex("""\s*```$""")

  fun analyzeFile(
    fileName: String,
    fileSizeBytes: Long,
    uriString: String,
    rawContent: String,
  ): SelectedSubtitleFile {
    val blocks = parse(rawContent)
    val totalLines = blocks.sumOf { block -> block.lines.count { it.isNotBlank() } }
    return SelectedSubtitleFile(
      uriString = uriString,
      fileName = fileName,
      fileSizeBytes = fileSizeBytes,
      totalBlocks = blocks.size,
      totalLines = totalLines,
      blocks = blocks,
    )
  }

  fun parse(rawContent: String): List<SrtBlock> = SrtParser.parseBlocks(rawContent)

  fun parseModelOutput(rawResponse: String): List<SrtBlock> {
    var cleaned = rawResponse.trim().removePrefix("\uFEFF")
    val fencedMatch = CODE_FENCE_BLOCK_REGEX.find(cleaned)
    if (fencedMatch != null) {
      cleaned = fencedMatch.groupValues[1].trim()
    } else {
      if (cleaned.startsWith("```")) {
        cleaned = cleaned.replaceFirst(CODE_FENCE_OPEN_REGEX, "").trim()
      }
      if (cleaned.endsWith("```")) {
        cleaned = cleaned.replaceFirst(CODE_FENCE_CLOSE_REGEX, "").trim()
      }
    }
    return parse(cleaned)
  }

  fun validateBatch(
    originalBatch: List<SrtBlock>,
    translatedBlocks: List<SrtBlock>,
  ): ValidationResult {
    if (translatedBlocks.isEmpty()) {
      return ValidationResult.Invalid("Empty or unparseable SRT output from AI")
    }

    if (translatedBlocks.size != originalBatch.size) {
      return ValidationResult.Invalid(
        "Block count mismatch: expected ${originalBatch.size}, received ${translatedBlocks.size}"
      )
    }

    val seenIndices = HashSet<Int>(translatedBlocks.size)
    val sanitizedBlocks = ArrayList<SrtBlock>(originalBatch.size)

    for (i in originalBatch.indices) {
      val original = originalBatch[i]
      val translated = translatedBlocks[i]

      if (!seenIndices.add(translated.index)) {
        return ValidationResult.Invalid("Duplicate serial number #${translated.index} detected")
      }

      if (translated.index != original.index) {
        return ValidationResult.Invalid(
          "Serial mismatch at position ${i + 1}: expected #${original.index}, got #${translated.index}"
        )
      }

      if (
        SrtParser.normalizeTimestamp(translated.timestamp) !=
          SrtParser.normalizeTimestamp(original.timestamp)
      ) {
        return ValidationResult.Invalid(
          "Timestamp mismatch at block #${original.index}"
        )
      }

      val hasOriginalText = original.lines.any { it.isNotBlank() }
      val hasTranslatedText = translated.lines.any { it.isNotBlank() }
      if (hasOriginalText && !hasTranslatedText) {
        return ValidationResult.Invalid(
          "Missing translated text at block #${original.index}"
        )
      }

      // Preserve the exact original timestamp string verbatim to guarantee zero drift
      sanitizedBlocks.add(
        SrtBlock(
          index = original.index,
          timestamp = original.timestamp,
          lines = translated.lines,
        )
      )
    }

    return ValidationResult.Valid(sanitizedBlocks)
  }

  /**
   * Resilient reconciliation for LLM subtitle responses:
   * 1. Returns strict [ValidationResult.Valid] blocks immediately when exact 1:1 integrity holds.
   * 2. Otherwise aligns translated blocks by cue index, normalized timestamp, or exact batch size
   *    position while keeping every original index and timestamp verbatim.
   * 3. Accepts when >= 80% of blocks matched cleanly (preserving original lines for rare dropped/merged cues);
   *    returns [BatchReconciliationResult.TooIncomplete] on truncated/garbled output so the caller can split.
   */
  fun reconcileBatch(
    originalBatch: List<SrtBlock>,
    translatedBlocks: List<SrtBlock>,
  ): BatchReconciliationResult {
    when (val strict = validateBatch(originalBatch, translatedBlocks)) {
      is ValidationResult.Valid -> {
        return BatchReconciliationResult.Resolved(
          blocks = strict.blocks,
          exactMatch = true,
          matchedCount = strict.blocks.size,
        )
      }
      is ValidationResult.Invalid -> {
        if (originalBatch.isEmpty() || translatedBlocks.isEmpty()) {
          return BatchReconciliationResult.TooIncomplete(
            matchedCount = 0,
            expectedCount = originalBatch.size,
            reason = strict.reason,
          )
        }

        val byIndex = LinkedHashMap<Int, SrtBlock>(translatedBlocks.size)
        val byTimestamp = LinkedHashMap<String, SrtBlock>(translatedBlocks.size)
        for (block in translatedBlocks) {
          byIndex.putIfAbsent(block.index, block)
          val normTs = SrtParser.normalizeTimestamp(block.timestamp)
          if (normTs.isNotBlank()) {
            byTimestamp.putIfAbsent(normTs, block)
          }
        }

        val sameSizeFallback = translatedBlocks.size == originalBatch.size
        var matchedCount = 0
        val reconciled = ArrayList<SrtBlock>(originalBatch.size)

        for (i in originalBatch.indices) {
          val original = originalBatch[i]
          val normOrigTs = SrtParser.normalizeTimestamp(original.timestamp)
          val candidate =
            byIndex[original.index]
              ?: byTimestamp[normOrigTs]
              ?: if (sameSizeFallback) translatedBlocks[i] else null

          val hasTranslatedText =
            candidate != null && candidate.lines.any { line -> line.isNotBlank() }

          if (hasTranslatedText) {
            matchedCount++
            reconciled.add(
              SrtBlock(
                index = original.index,
                timestamp = original.timestamp,
                lines = candidate.lines,
              )
            )
          } else {
            reconciled.add(original)
          }
        }

        val coverage = matchedCount.toDouble() / originalBatch.size.toDouble()
        val acceptable = matchedCount > 0 && coverage >= 0.80

        return if (acceptable) {
          BatchReconciliationResult.Resolved(
            blocks = reconciled,
            exactMatch = false,
            matchedCount = matchedCount,
          )
        } else {
          BatchReconciliationResult.TooIncomplete(
            matchedCount = matchedCount,
            expectedCount = originalBatch.size,
            reason = "${strict.reason} (aligned $matchedCount/${originalBatch.size} cues)",
          )
        }
      }
    }
  }

  fun formatBlocks(blocks: List<SrtBlock>): String = SrtParser.formatBlocks(blocks)

  /**
   * Builds compact surrounding dialogue context (preceding & following lines) around a batch slice
   * `[startIdx, endIdxExclusive)` without SRT timestamps, strictly capped at [maxContextLines] lines.
   */
  fun buildSurroundingContext(
    allBlocks: List<SrtBlock>,
    startIdx: Int,
    endIdxExclusive: Int,
    maxContextLines: Int,
    translatedByIndex: Map<Int, SrtBlock> = emptyMap(),
  ): String {
    if (maxContextLines <= 0 || allBlocks.isEmpty()) return ""
    val safeStart = startIdx.coerceIn(0, allBlocks.size)
    val safeEnd = endIdxExclusive.coerceIn(safeStart, allBlocks.size)
    if (safeStart >= safeEnd) return ""

    fun cleanLinesOf(block: SrtBlock): List<String> =
      block.lines.map { it.trim() }.filter { it.isNotBlank() }

    var availBeforeLines = 0
    for (i in (safeStart - 1) downTo 0) {
      val count = cleanLinesOf(allBlocks[i]).size.coerceAtLeast(1)
      availBeforeLines += count
      if (availBeforeLines >= maxContextLines) break
    }
    var availAfterLines = 0
    for (i in safeEnd until allBlocks.size) {
      val count = cleanLinesOf(allBlocks[i]).size.coerceAtLeast(1)
      availAfterLines += count
      if (availAfterLines >= maxContextLines) break
    }

    if (availBeforeLines == 0 && availAfterLines == 0) return ""

    val half = maxContextLines / 2
    val targetBefore: Int
    val targetAfter: Int
    if (availBeforeLines < half) {
      targetBefore = availBeforeLines
      targetAfter = minOf(availAfterLines, maxContextLines - targetBefore)
    } else if (availAfterLines < (maxContextLines - half)) {
      targetAfter = availAfterLines
      targetBefore = minOf(availBeforeLines, maxContextLines - targetAfter)
    } else {
      targetBefore = half
      targetAfter = maxContextLines - half
    }

    val precedingEntries = ArrayList<String>()
    var beforeUsed = 0
    if (targetBefore > 0) {
      for (i in (safeStart - 1) downTo 0) {
        val remaining = targetBefore - beforeUsed
        if (remaining <= 0) break
        val block = allBlocks[i]
        val lines = cleanLinesOf(block)
        if (lines.isEmpty()) continue
        val slice = if (lines.size <= remaining) lines else lines.takeLast(remaining)
        val dialogue = slice.joinToString(" / ")
        val translated =
          translatedByIndex[block.index]
            ?.lines
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(" / ")
            ?.takeIf { it.isNotBlank() }
        val formatted =
          if (translated != null) {
            "[#${block.index}] \"$dialogue\" (BN: \"$translated\")"
          } else {
            "[#${block.index}] \"$dialogue\""
          }
        precedingEntries.add(formatted)
        beforeUsed += slice.size
      }
      precedingEntries.reverse()
    }

    val followingEntries = ArrayList<String>()
    var afterUsed = 0
    if (targetAfter > 0) {
      for (i in safeEnd until allBlocks.size) {
        val remaining = targetAfter - afterUsed
        if (remaining <= 0) break
        val block = allBlocks[i]
        val lines = cleanLinesOf(block)
        if (lines.isEmpty()) continue
        val slice = if (lines.size <= remaining) lines else lines.take(remaining)
        val dialogue = slice.joinToString(" / ")
        followingEntries.add("[#${block.index}] \"$dialogue\"")
        afterUsed += slice.size
      }
    }

    if (precedingEntries.isEmpty() && followingEntries.isEmpty()) return ""

    return buildString {
      if (precedingEntries.isNotEmpty()) {
        appendLine("PRECEDING LINES (already occurred before this batch):")
        precedingEntries.forEach { appendLine(it) }
      }
      if (followingEntries.isNotEmpty()) {
        if (isNotEmpty()) appendLine()
        appendLine("FOLLOWING LINES (occur after this batch):")
        followingEntries.forEach { appendLine(it) }
      }
    }.trim()
  }

  fun buildOutputFileName(originalFileName: String, settings: AppSettings): String {
    val cleanName = originalFileName.trim().ifBlank { "subtitle.srt" }
    val baseName =
      if (cleanName.endsWith(".srt", ignoreCase = true)) {
        cleanName.dropLast(4)
      } else {
        cleanName
      }

    val rawResult =
      when (settings.namingMode) {
        NamingMode.DEFAULT -> "${baseName}_BSub.srt"
        NamingMode.CUSTOM -> {
          val prefix = sanitizeFileSegment(settings.customPrefix)
          val suffix = sanitizeFileSegment(settings.customSuffix)
          val combined = "${prefix}${baseName}${suffix}"
          if (combined.isBlank()) "${baseName}_BSub.srt" else "$combined.srt"
        }
      }

    return sanitizeFileSegment(rawResult).ifBlank { "${baseName}_BSub.srt" }
  }

  private fun sanitizeFileSegment(input: String): String {
    return input.replace(Regex("""[\\/:*?"<>|]"""), "_")
  }

  fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1.0) return "$bytes B"
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
      String.format(java.util.Locale.US, "%.2f MB", mb)
    } else {
      String.format(java.util.Locale.US, "%.1f KB", kb)
    }
  }
}
