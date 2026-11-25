package com.example.srt

import com.example.domain.model.SrtBlock
import java.util.Locale

enum class SrtValidationIssueType {
  EMPTY_CONTENT,
  MISSING_SERIAL,
  DUPLICATE_SERIAL,
  NON_SEQUENTIAL_SERIAL,
  INVALID_TIMING_SYNTAX,
  REVERSED_TIMING_RANGE,
  MISSING_TEXT,
  CUE_COUNT_MISMATCH,
  TIMECODE_DRIFT,
}

data class SrtValidationIssue(
  val blockIndex: Int? = null,
  val lineNumber: Int? = null,
  val type: SrtValidationIssueType,
  val message: String,
)

data class SrtValidationReport(
  val isValid: Boolean,
  val totalBlocks: Int,
  val validTimingBlocks: Int,
  val issues: List<SrtValidationIssue> = emptyList(),
  val normalizedSrt: String? = null,
) {
  val summary: String
    get() =
      if (isValid) {
        "Valid SRT ($totalBlocks cue(s), $validTimingBlocks timing blocks verified)"
      } else {
        val firstMsg = issues.firstOrNull()?.message ?: "Unknown SRT format error"
        "Invalid SRT (${issues.size} issue(s)): $firstMsg"
      }
}

/**
 * Validates translated `.srt` content for strict `HH:MM:SS,mmm --> HH:MM:SS,mmm` timing syntax
 * and structural format integrity before saving the final output file.
 */
object SrtValidator {

  private val STRICT_SINGLE_TIMECODE_REGEX =
    Regex("""^(\d{2,3}):([0-5]\d):([0-5]\d),(\d{3})$""")

  private val STRICT_TIMING_LINE_REGEX =
    Regex(
      """^\s*(\d{2,3}):([0-5]\d):([0-5]\d),(\d{3})\s*-->\s*(\d{2,3}):([0-5]\d):([0-5]\d),(\d{3})(?:\s+(.*))?\s*$"""
    )

  fun isStrictTimecodeToken(token: String): Boolean =
    STRICT_SINGLE_TIMECODE_REGEX.matches(token.trim())

  fun isStrictTimingLine(line: String): Boolean =
    STRICT_TIMING_LINE_REGEX.matches(line)

  fun formatStrictTimestampLine(range: SrtTimecodeRange): String {
    val startFormatted = formatMillisToStrictTimecode(range.startMillis)
    val endFormatted = formatMillisToStrictTimecode(range.endMillis)
    return if (range.trailingMetadata.isBlank()) {
      "$startFormatted --> $endFormatted"
    } else {
      "$startFormatted --> $endFormatted ${range.trailingMetadata.trim()}"
    }
  }

  fun formatMillisToStrictTimecode(totalMillis: Long): String {
    val safeMs = totalMillis.coerceAtLeast(0L)
    val hours = safeMs / 3_600_000L
    val minutes = (safeMs % 3_600_000L) / 60_000L
    val seconds = (safeMs % 60_000L) / 1_000L
    val millis = safeMs % 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
  }

  fun validateSrtContent(
    rawSrtContent: String,
    expectedOriginalBlocks: List<SrtBlock>? = null,
  ): SrtValidationReport {
    val normalizedRaw =
      rawSrtContent
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    if (normalizedRaw.isBlank()) {
      return SrtValidationReport(
        isValid = false,
        totalBlocks = 0,
        validTimingBlocks = 0,
        issues =
          listOf(
            SrtValidationIssue(
              type = SrtValidationIssueType.EMPTY_CONTENT,
              message = "Translated SRT content is empty.",
            )
          ),
        normalizedSrt = null,
      )
    }

    val rawLines = normalizedRaw.lines()
    val chunks = splitIntoCueChunks(rawLines)
    if (chunks.isEmpty()) {
      return SrtValidationReport(
        isValid = false,
        totalBlocks = 0,
        validTimingBlocks = 0,
        issues =
          listOf(
            SrtValidationIssue(
              type = SrtValidationIssueType.EMPTY_CONTENT,
              message = "No subtitle cue blocks detected.",
            )
          ),
        normalizedSrt = null,
      )
    }

    val issues = ArrayList<SrtValidationIssue>()
    val seenSerials = HashSet<Int>(chunks.size)
    val validatedBlocks = ArrayList<SrtBlock>(chunks.size)
    var validTimingCount = 0
    var previousSerial = 0

    for (chunk in chunks) {
      val firstLineNum = chunk.startLineNumber
      val lines = chunk.lines
      val serialCandidate = lines[0].trim()
      val serial = serialCandidate.toIntOrNull()

      if (serial == null || serial <= 0) {
        issues.add(
          SrtValidationIssue(
            blockIndex = null,
            lineNumber = firstLineNum,
            type = SrtValidationIssueType.MISSING_SERIAL,
            message = "Line $firstLineNum: Expected positive cue serial number, found '${lines[0]}'.",
          )
        )
        continue
      }

      if (!seenSerials.add(serial)) {
        issues.add(
          SrtValidationIssue(
            blockIndex = serial,
            lineNumber = firstLineNum,
            type = SrtValidationIssueType.DUPLICATE_SERIAL,
            message = "Cue #$serial (line $firstLineNum): Duplicate cue serial number.",
          )
        )
      }

      if (serial <= previousSerial) {
        issues.add(
          SrtValidationIssue(
            blockIndex = serial,
            lineNumber = firstLineNum,
            type = SrtValidationIssueType.NON_SEQUENTIAL_SERIAL,
            message = "Cue #$serial (line $firstLineNum): Out-of-order serial after #$previousSerial.",
          )
        )
      }
      previousSerial = maxOf(previousSerial, serial)

      if (lines.size < 2) {
        issues.add(
          SrtValidationIssue(
            blockIndex = serial,
            lineNumber = firstLineNum + 1,
            type = SrtValidationIssueType.INVALID_TIMING_SYNTAX,
            message = "Cue #$serial: Missing timing line (expected HH:MM:SS,mmm --> HH:MM:SS,mmm).",
          )
        )
        continue
      }

      val timingLine = lines[1].trim()
      if (!isStrictTimingLine(timingLine)) {
        issues.add(
          SrtValidationIssue(
            blockIndex = serial,
            lineNumber = firstLineNum + 1,
            type = SrtValidationIssueType.INVALID_TIMING_SYNTAX,
            message =
              "Cue #$serial (line ${firstLineNum + 1}): Invalid timing syntax '$timingLine' (expected HH:MM:SS,mmm --> HH:MM:SS,mmm).",
          )
        )
      } else {
        val parsedRange = SrtParser.parseTimecodeRange(timingLine)
        if (parsedRange == null) {
          issues.add(
            SrtValidationIssue(
              blockIndex = serial,
              lineNumber = firstLineNum + 1,
              type = SrtValidationIssueType.INVALID_TIMING_SYNTAX,
              message = "Cue #$serial: Unparseable timing range '$timingLine'.",
            )
          )
        } else if (!parsedRange.isChronologicallyValid) {
          issues.add(
            SrtValidationIssue(
              blockIndex = serial,
              lineNumber = firstLineNum + 1,
              type = SrtValidationIssueType.REVERSED_TIMING_RANGE,
              message =
                "Cue #$serial (line ${firstLineNum + 1}): End time is earlier than start time ('$timingLine').",
            )
          )
        } else {
          validTimingCount++
        }
      }

      val textLines = lines.drop(2).dropLastWhile { it.isBlank() }.map { it.trimEnd() }
      if (textLines.none { it.isNotBlank() }) {
        issues.add(
          SrtValidationIssue(
            blockIndex = serial,
            lineNumber = firstLineNum + 2,
            type = SrtValidationIssueType.MISSING_TEXT,
            message = "Cue #$serial: Subtitle cue contains no dialogue text.",
          )
        )
      }

      validatedBlocks.add(
        SrtBlock(
          index = serial,
          timestamp = timingLine,
          lines = textLines,
        )
      )
    }

    if (expectedOriginalBlocks != null) {
      if (validatedBlocks.size != expectedOriginalBlocks.size) {
        issues.add(
          SrtValidationIssue(
            blockIndex = null,
            lineNumber = null,
            type = SrtValidationIssueType.CUE_COUNT_MISMATCH,
            message =
              "Cue count mismatch: expected ${expectedOriginalBlocks.size} cues, found ${validatedBlocks.size}.",
          )
        )
      } else {
        for (i in expectedOriginalBlocks.indices) {
          val expected = expectedOriginalBlocks[i]
          val actual = validatedBlocks[i]
          if (actual.index != expected.index) {
            issues.add(
              SrtValidationIssue(
                blockIndex = expected.index,
                lineNumber = null,
                type = SrtValidationIssueType.NON_SEQUENTIAL_SERIAL,
                message =
                  "Cue #${expected.index} mismatch at position ${i + 1}: found #${actual.index}.",
              )
            )
          } else {
            val expectedRange = SrtParser.parseTimecodeRange(expected.timestamp)
            val actualRange = SrtParser.parseTimecodeRange(actual.timestamp)
            val matchesTimecode =
              if (expectedRange != null && actualRange != null) {
                expectedRange.startMillis == actualRange.startMillis &&
                  expectedRange.endMillis == actualRange.endMillis
              } else {
                SrtParser.normalizeTimestamp(actual.timestamp) ==
                  SrtParser.normalizeTimestamp(expected.timestamp)
              }
            if (!matchesTimecode) {
              issues.add(
                SrtValidationIssue(
                  blockIndex = expected.index,
                  lineNumber = null,
                  type = SrtValidationIssueType.TIMECODE_DRIFT,
                  message =
                    "Cue #${expected.index} timecode mismatch: expected '${expected.timestamp}', found '${actual.timestamp}'.",
                )
              )
            }
          }
        }
      }
    }

    val isValid = issues.isEmpty() && validatedBlocks.isNotEmpty()
    val canonicalSrt = if (isValid) SrtParser.formatBlocks(validatedBlocks) else null

    return SrtValidationReport(
      isValid = isValid,
      totalBlocks = validatedBlocks.size,
      validTimingBlocks = validTimingCount,
      issues = issues,
      normalizedSrt = canonicalSrt,
    )
  }

  /**
   * Validates translated SRT output and attempts canonical timing normalization (e.g., converting
   * `HH:MM:SS.mmm` or single-digit hours to strict `HH:MM:SS,mmm` and restoring original
   * timecodes when [originalBlocks] are provided) before failing if format integrity is broken.
   */
  fun validateAndRepairBeforeSave(
    rawSrtContent: String,
    originalBlocks: List<SrtBlock>? = null,
  ): SrtValidationReport {
    val initialReport = validateSrtContent(rawSrtContent, expectedOriginalBlocks = originalBlocks)
    if (initialReport.isValid) {
      return initialReport
    }

    val parsedBlocks = SrtParser.parseBlocks(rawSrtContent)
    if (parsedBlocks.isEmpty()) {
      return initialReport
    }

    val repairedBlocks =
      if (originalBlocks != null && originalBlocks.isNotEmpty()) {
        val candidateSource =
          originalBlocks.mapIndexed { idx, orig ->
            val parsedMatch = parsedBlocks.firstOrNull { it.index == orig.index } ?: parsedBlocks.getOrNull(idx)
            val origRange = SrtParser.parseTimecodeRange(orig.timestamp)
            val strictTs =
              if (origRange != null && origRange.isChronologicallyValid) {
                formatStrictTimestampLine(origRange)
              } else {
                orig.timestamp.trim()
              }
            val lines =
              if (parsedMatch != null && parsedMatch.lines.any { it.isNotBlank() }) {
                parsedMatch.lines
              } else {
                orig.lines
              }
            SrtBlock(index = orig.index, timestamp = strictTs, lines = lines)
          }
        candidateSource
      } else {
        parsedBlocks.map { block ->
          val range = SrtParser.parseTimecodeRange(block.timestamp)
          val strictTs =
            if (range != null && range.isChronologicallyValid) {
              formatStrictTimestampLine(range)
            } else {
              block.timestamp.trim()
            }
          block.copy(timestamp = strictTs)
        }
      }

    val repairedSrt = SrtParser.formatBlocks(repairedBlocks)
    val repairedReport = validateSrtContent(repairedSrt, expectedOriginalBlocks = originalBlocks)
    return if (repairedReport.isValid) repairedReport else initialReport
  }

  private data class RawCueChunk(
    val startLineNumber: Int,
    val lines: List<String>,
  )

  private fun splitIntoCueChunks(lines: List<String>): List<RawCueChunk> {
    val chunks = ArrayList<RawCueChunk>()
    var idx = 0
    while (idx < lines.size) {
      while (idx < lines.size && lines[idx].isBlank()) {
        idx++
      }
      if (idx >= lines.size) break
      val startLineNum = idx + 1
      val chunkLines = ArrayList<String>()
      while (idx < lines.size && lines[idx].isNotBlank()) {
        chunkLines.add(lines[idx])
        idx++
      }
      chunks.add(RawCueChunk(startLineNumber = startLineNum, lines = chunkLines))
    }
    return chunks
  }
}
