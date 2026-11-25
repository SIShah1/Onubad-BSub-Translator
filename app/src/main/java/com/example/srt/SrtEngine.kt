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
    val matchedBlocks: Map<Int, SrtBlock> = emptyMap(),
    val missingBlocks: List<SrtBlock> = emptyList(),
  ) : BatchReconciliationResult()
}

object SrtEngine {

  private val CODE_FENCE_BLOCK_REGEX =
    Regex("""```(?:[a-zA-Z0-9_-]+)?\s*\n([\s\S]*?)```""")
  private val CODE_FENCE_OPEN_REGEX = Regex("""^```[a-zA-Z0-9_-]*\s*""")
  private val CODE_FENCE_CLOSE_REGEX = Regex("""\s*```$""")
  private val THINK_BLOCK_REGEX = Regex("""(?is)<think>.*?</think>""")
  private val UNCLOSED_THINK_REGEX = Regex("""(?is)<think>.*$""")
  private val LENIENT_SERIAL_REGEX =
    Regex("""^\s*(?:cue|block|#)?\s*[\[\(*`]*(\d{1,7})[\]\)*`.:]*\s*$""", RegexOption.IGNORE_CASE)
  private val LENIENT_TIMECODE_LINE_REGEX =
    Regex(
      """^\s*[*`]*(\d{1,3})\s*:\s*([0-5]?\d)\s*:\s*([0-5]?\d)\s*[,.:\s]\s*(\d{1,4})\s*(?:--+>|==+>|—+>|–+>|->)\s*(\d{1,3})\s*:\s*([0-5]?\d)\s*:\s*([0-5]?\d)\s*[,.:\s]\s*(\d{1,4})(?:\s+([^`*]+?))?[*`]*\s*$"""
    )

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

  fun parseModelOutput(
    rawResponse: String,
    expectedBatch: List<SrtBlock> = emptyList(),
  ): List<SrtBlock> {
    var cleaned =
      rawResponse
        .trim()
        .removePrefix("\uFEFF")
        .replace(THINK_BLOCK_REGEX, "")
        .replace(UNCLOSED_THINK_REGEX, "")
        .trim()

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

    val strictParsed = parse(cleaned)
    if (expectedBatch.isEmpty()) {
      return if (strictParsed.isNotEmpty()) strictParsed else parseLenientModelBlocks(cleaned, expectedBatch)
    }

    if (strictParsed.size >= expectedBatch.size) {
      return strictParsed
    }

    val lenientParsed = parseLenientModelBlocks(cleaned, expectedBatch)
    return if (lenientParsed.size > strictParsed.size) lenientParsed else strictParsed
  }

  private fun parseLenientModelBlocks(
    cleanedText: String,
    expectedBatch: List<SrtBlock>,
  ): List<SrtBlock> {
    val normalized = cleanedText.replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.isBlank()) return emptyList()

    val lines = normalized.lines()
    val anchors = ArrayList<Triple<Int, Int, String>>()

    for (lineIdx in 1 until lines.size) {
      val normalizedTiming = parseLenientTimingLine(lines[lineIdx]) ?: continue
      val serial = parseLenientSerial(lines[lineIdx - 1]) ?: continue
      anchors.add(Triple(lineIdx, serial, normalizedTiming))
    }

    if (anchors.isNotEmpty()) {
      val blocks = ArrayList<SrtBlock>(anchors.size)
      for (pos in anchors.indices) {
        val (timeIdx, serial, normalizedTs) = anchors[pos]
        val nextTimeIdx = anchors.getOrNull(pos + 1)?.first
        val contentEnd = if (nextTimeIdx != null) nextTimeIdx - 1 else lines.size
        val rawContentLines =
          if (timeIdx + 1 < contentEnd) {
            lines.subList(timeIdx + 1, contentEnd)
          } else {
            emptyList()
          }
        val cleanContent =
          rawContentLines
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .map { it.trimEnd() }
        blocks.add(
          SrtBlock(
            index = serial,
            timestamp = normalizedTs,
            lines = cleanContent,
          )
        )
      }
      return blocks
    }

    // Fallback: if LLM omitted timestamp lines and emitted `index\ntext` blocks while expectedBatch is known
    if (expectedBatch.isNotEmpty()) {
      val expectedByIndex = expectedBatch.associateBy { it.index }
      val rawChunks = normalized.split(Regex("""\n\s*\n"""))
      val recovered = ArrayList<SrtBlock>(rawChunks.size)
      for ((chunkIdx, rawChunk) in rawChunks.withIndex()) {
        val chunkLines = rawChunk.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (chunkLines.size < 2) continue
        val serial = parseLenientSerial(chunkLines[0]) ?: continue
        val timingFromSecond = parseLenientTimingLine(chunkLines[1])
        if (timingFromSecond != null && chunkLines.size >= 3) {
          recovered.add(
            SrtBlock(
              index = serial,
              timestamp = timingFromSecond,
              lines = chunkLines.drop(2),
            )
          )
        } else {
          val expected = expectedByIndex[serial] ?: expectedBatch.getOrNull(chunkIdx)
          if (expected != null) {
            recovered.add(
              SrtBlock(
                index = expected.index,
                timestamp = expected.timestamp,
                lines = chunkLines.drop(1),
              )
            )
          }
        }
      }
      if (recovered.isNotEmpty()) {
        return recovered
      }
    }

    return emptyList()
  }

  private fun parseLenientSerial(line: String): Int? {
    val clean = line.removePrefix("\uFEFF").trim()
    clean.toIntOrNull()?.let { return it }
    val match = LENIENT_SERIAL_REGEX.matchEntire(clean) ?: return null
    return match.groupValues[1].toIntOrNull()
  }

  private fun parseLenientTimingLine(line: String): String? {
    val match = LENIENT_TIMECODE_LINE_REGEX.matchEntire(line.trim()) ?: return null
    val g = match.groupValues
    val sh = g[1].padStart(2, '0')
    val sm = g[2].padStart(2, '0')
    val ss = g[3].padStart(2, '0')
    val sms = normalizeMillisToken(g[4])
    val eh = g[5].padStart(2, '0')
    val em = g[6].padStart(2, '0')
    val es = g[7].padStart(2, '0')
    val ems = normalizeMillisToken(g[8])
    val trailing = g[9].trim()
    val base = "$sh:$sm:$ss,$sms --> $eh:$em:$es,$ems"
    return if (trailing.isBlank()) base else "$base $trailing"
  }

  private fun normalizeMillisToken(token: String): String {
    val digits = token.trim()
    return when {
      digits.length == 3 -> digits
      digits.length < 3 -> digits.padEnd(3, '0')
      else -> digits.take(3)
    }
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
   * 2. Otherwise aligns translated blocks by cue index, normalized timestamp, relative batch numbering
   *    (`1..N`), or exact batch size position while keeping every original index and timestamp verbatim.
   * 3. Accepts when >= 80% of blocks matched cleanly (preserving original lines for rare dropped/merged cues);
   *    returns [BatchReconciliationResult.TooIncomplete] carrying [matchedBlocks] and [missingBlocks] so
   *    already-translated cues are never discarded during adaptive recovery.
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
            matchedBlocks = emptyMap(),
            missingBlocks = originalBatch,
          )
        }

        val consumedPos = BooleanArray(translatedBlocks.size)
        val byIndex = LinkedHashMap<Int, MutableList<Int>>(translatedBlocks.size)
        val byTimestamp = LinkedHashMap<String, MutableList<Int>>(translatedBlocks.size)
        for (pos in translatedBlocks.indices) {
          val block = translatedBlocks[pos]
          byIndex.getOrPut(block.index) { ArrayList(2) }.add(pos)
          val normTs = SrtParser.normalizeTimestamp(block.timestamp)
          if (normTs.isNotBlank()) {
            byTimestamp.getOrPut(normTs) { ArrayList(2) }.add(pos)
          }
        }

        fun takeFirstUnconsumed(positions: List<Int>?): Int? {
          if (positions == null) return null
          return positions.firstOrNull { !consumedPos[it] }
        }

        val aligned = arrayOfNulls<SrtBlock>(originalBatch.size)

        // Pass 1: Match by exact original cue index
        for (i in originalBatch.indices) {
          val original = originalBatch[i]
          val pos = takeFirstUnconsumed(byIndex[original.index]) ?: continue
          val candidate = translatedBlocks[pos]
          if (candidate.lines.any { it.isNotBlank() }) {
            consumedPos[pos] = true
            aligned[i] =
              SrtBlock(
                index = original.index,
                timestamp = original.timestamp,
                lines = candidate.lines,
              )
          }
        }

        // Pass 2: Match remaining blocks by normalized timestamp
        for (i in originalBatch.indices) {
          if (aligned[i] != null) continue
          val original = originalBatch[i]
          val normOrigTs = SrtParser.normalizeTimestamp(original.timestamp)
          val pos = takeFirstUnconsumed(byTimestamp[normOrigTs]) ?: continue
          val candidate = translatedBlocks[pos]
          if (candidate.lines.any { it.isNotBlank() }) {
            consumedPos[pos] = true
            aligned[i] =
              SrtBlock(
                index = original.index,
                timestamp = original.timestamp,
                lines = candidate.lines,
              )
          }
        }

        // Pass 3: Relative re-numbering fallback (LLM numbered batch output 1..M instead of K..K+M-1)
        val firstOrigIdx = originalBatch.first().index
        if (firstOrigIdx > 1) {
          for (i in originalBatch.indices) {
            if (aligned[i] != null) continue
            val relativeSerial = i + 1
            val pos = takeFirstUnconsumed(byIndex[relativeSerial]) ?: continue
            val candidate = translatedBlocks[pos]
            if (candidate.lines.any { it.isNotBlank() }) {
              consumedPos[pos] = true
              aligned[i] =
                SrtBlock(
                  index = originalBatch[i].index,
                  timestamp = originalBatch[i].timestamp,
                  lines = candidate.lines,
                )
            }
          }
        }

        // Pass 4: Exact batch-size positional fallback
        if (translatedBlocks.size == originalBatch.size) {
          for (i in originalBatch.indices) {
            if (aligned[i] != null || consumedPos[i]) continue
            val candidate = translatedBlocks[i]
            if (candidate.lines.any { it.isNotBlank() }) {
              consumedPos[i] = true
              aligned[i] =
                SrtBlock(
                  index = originalBatch[i].index,
                  timestamp = originalBatch[i].timestamp,
                  lines = candidate.lines,
                )
            }
          }
        }

        var matchedCount = 0
        val matchedMap = LinkedHashMap<Int, SrtBlock>(originalBatch.size)
        val missingList = ArrayList<SrtBlock>()
        val reconciled = ArrayList<SrtBlock>(originalBatch.size)

        for (i in originalBatch.indices) {
          val resolvedBlock = aligned[i]
          val original = originalBatch[i]
          if (resolvedBlock != null) {
            matchedCount++
            matchedMap[original.index] = resolvedBlock
            reconciled.add(resolvedBlock)
          } else {
            missingList.add(original)
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
            matchedBlocks = matchedMap,
            missingBlocks = missingList,
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
