package com.example.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.domain.model.SelectedSubtitleFile
import com.example.domain.model.SrtBlock
import com.example.srt.SrtEngine
import com.example.srt.SrtParser
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SavedSubtitleOutput(
  val outputFileName: String,
  val displayPath: String,
  val contentUri: String,
  val localBackupPath: String,
  val fileSizeBytes: Long,
)

class SubtitleFileStorage(private val context: Context) {

  companion object {
    val SRT_PICKER_MIME_TYPES =
      arrayOf(
        "application/x-subrip",
        "application/x-srt",
        "text/srt",
        "text/plain",
        "application/octet-stream",
        "*/*",
      )

    val TEXT_KEY_FILE_MIME_TYPES =
      arrayOf(
        "text/plain",
        "text/*",
        "*/*",
      )
  }

  fun takePersistableReadPermission(uri: Uri) {
    runCatching {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }
  }

  suspend fun readPlainText(uri: Uri): String? =
    withContext(Dispatchers.IO) {
      runCatching {
        context.contentResolver.openInputStream(uri)?.use { SrtParser.readRaw(it) }
      }.getOrNull()
    }

  suspend fun readAndAnalyzeUri(uri: Uri): SelectedSubtitleFile? =
    withContext(Dispatchers.IO) {
      val resolver = context.contentResolver
      var displayName =
        uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "subtitle.srt"
      var reportedSize = -1L

      resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIdx >= 0) {
            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
          }
          val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
          if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
            reportedSize = cursor.getLong(sizeIdx)
          }
        }
      }

      val rawText =
        resolver.openInputStream(uri)?.use { SrtParser.readRaw(it) }
          ?: return@withContext null

      val resolvedSize =
        if (reportedSize > 0L) reportedSize else rawText.toByteArray(Charsets.UTF_8).size.toLong()

      SrtEngine.analyzeFile(
        fileName = displayName,
        fileSizeBytes = resolvedSize,
        uriString = uri.toString(),
        rawContent = rawText,
      )
    }

  suspend fun saveTranslatedSrt(
    outputFileName: String,
    srtContent: String,
  ): SavedSubtitleOutput =
    withContext(Dispatchers.IO) {
      val contentBytes = srtContent.toByteArray(Charsets.UTF_8)

      // 1. Always write an internal backup copy so Share, Preview, and Re-download always work
      val backupDir = File(context.filesDir, "translated_subtitles").apply { mkdirs() }
      val uniqueBackupName = "${System.currentTimeMillis()}_$outputFileName"
      val backupFile = File(backupDir, uniqueBackupName)
      backupFile.writeBytes(contentBytes)

      // 2. Save to public Downloads/BSub Translator/
      var publicUriString = ""
      var displayPath = "Downloads/BSub Translator/$outputFileName"

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/BSub Translator"
        val values =
          ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outputFileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
          }

        val insertedUri =
          resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: resolver.insert(
              MediaStore.Downloads.EXTERNAL_CONTENT_URI,
              ContentValues(values).apply { put(MediaStore.Downloads.MIME_TYPE, "text/plain") },
            )

        if (insertedUri != null) {
          try {
            resolver.openOutputStream(insertedUri)?.use { out ->
              out.write(contentBytes)
              out.flush()
            }
            val publishValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(insertedUri, publishValues, null, null)
            publicUriString = insertedUri.toString()
          } catch (_: Exception) {
            runCatching { resolver.delete(insertedUri, null, null) }
          }
        }
      } else {
        @Suppress("DEPRECATION")
        val publicDir =
          File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BSub Translator",
          )
        try {
          if (publicDir.exists() || publicDir.mkdirs()) {
            val targetFile = resolveUniqueFile(publicDir, outputFileName)
            targetFile.writeBytes(contentBytes)
            publicUriString = Uri.fromFile(targetFile).toString()
            displayPath = "Downloads/BSub Translator/${targetFile.name}"
          }
        } catch (_: Exception) {
          displayPath = "App Storage/translated_subtitles/$outputFileName"
        }
      }

      if (publicUriString.isBlank()) {
        publicUriString =
          FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              backupFile,
            )
            .toString()
      }

      SavedSubtitleOutput(
        outputFileName = outputFileName,
        displayPath = displayPath,
        contentUri = publicUriString,
        localBackupPath = backupFile.absolutePath,
        fileSizeBytes = contentBytes.size.toLong(),
      )
    }

  suspend fun readPreviewText(localBackupPath: String, contentUri: String): String =
    withContext(Dispatchers.IO) {
      val backupFile = File(localBackupPath)
      if (backupFile.exists()) {
        return@withContext backupFile.readText(Charsets.UTF_8)
      }
      if (contentUri.startsWith("content://")) {
        return@withContext runCatching {
          context.contentResolver
            .openInputStream(Uri.parse(contentUri))
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        }.getOrDefault("")
      }
      ""
    }

  suspend fun findContentMatchSnippet(
    localBackupPath: String,
    contentUri: String,
    originalFileName: String,
    query: String,
  ): String? =
    withContext(Dispatchers.IO) {
      val needle = query.trim()
      if (needle.isEmpty()) return@withContext null

      val sources =
        listOf(
          readPreviewText(localBackupPath, contentUri),
          readOriginalSourceBackup(localBackupPath, originalFileName),
        )

      for (raw in sources) {
        if (raw.isBlank()) continue
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val dialogueMatch =
          lines.firstOrNull { line ->
            !line.all { ch -> ch.isDigit() } &&
              !line.contains("-->") &&
              line.contains(needle, ignoreCase = true)
          }
        if (dialogueMatch != null) return@withContext dialogueMatch

        val fallbackMatch = lines.firstOrNull { it.contains(needle, ignoreCase = true) }
        if (fallbackMatch != null) return@withContext fallbackMatch
      }
      null
    }

  suspend fun exportToUri(localBackupPath: String, contentUri: String, targetUri: Uri): Boolean =
    withContext(Dispatchers.IO) {
      val text = readPreviewText(localBackupPath, contentUri)
      if (text.isEmpty()) return@withContext false
      return@withContext runCatching {
        val stream: OutputStream? = context.contentResolver.openOutputStream(targetUri)
        stream?.use {
          it.write(text.toByteArray(Charsets.UTF_8))
          it.flush()
        }
        stream != null
      }.getOrDefault(false)
    }

  suspend fun reSaveToDownloads(
    outputFileName: String,
    localBackupPath: String,
    contentUri: String,
  ): SavedSubtitleOutput? =
    withContext(Dispatchers.IO) {
      val text = readPreviewText(localBackupPath, contentUri)
      if (text.isEmpty()) return@withContext null
      saveTranslatedSrt(outputFileName, text)
    }

  fun createShareIntent(
    outputFileName: String,
    localBackupPath: String,
    contentUri: String,
  ): Intent? {
    val backupFile = File(localBackupPath)
    val shareUri: Uri? =
      when {
        backupFile.exists() ->
          FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            backupFile,
          )
        contentUri.startsWith("content://") -> Uri.parse(contentUri)
        else -> null
      }

    if (shareUri == null) return null

    return Intent(Intent.ACTION_SEND).apply {
      type = "application/x-subrip"
      putExtra(Intent.EXTRA_STREAM, shareUri)
      putExtra(Intent.EXTRA_SUBJECT, outputFileName)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  suspend fun saveOriginalSourceBackup(
    translatedLocalBackupPath: String,
    originalFileName: String,
    blocks: List<SrtBlock>,
  ): String =
    withContext(Dispatchers.IO) {
      if (blocks.isEmpty()) return@withContext ""
      val originalDir = File(context.filesDir, "original_subtitles").apply { mkdirs() }
      val translatedName = File(translatedLocalBackupPath).name
      val sidecarName =
        if (translatedName.isNotBlank()) {
          "$translatedName.orig.srt"
        } else {
          "${System.currentTimeMillis()}_${originalFileName.ifBlank { "source.srt" }}"
        }
      val origFile = File(originalDir, sidecarName)
      origFile.writeText(SrtEngine.formatBlocks(blocks), Charsets.UTF_8)
      origFile.absolutePath
    }

  suspend fun readOriginalSourceBackup(
    translatedLocalBackupPath: String,
    originalFileName: String,
  ): String =
    withContext(Dispatchers.IO) {
      val originalDir = File(context.filesDir, "original_subtitles")
      if (!originalDir.exists()) return@withContext ""
      val translatedName = File(translatedLocalBackupPath).name
      if (translatedName.isNotBlank()) {
        val exactSidecar = File(originalDir, "$translatedName.orig.srt")
        if (exactSidecar.exists()) {
          return@withContext exactSidecar.readText(Charsets.UTF_8)
        }
      }
      if (originalFileName.isNotBlank()) {
        val fallback =
          originalDir
            .listFiles { file ->
              file.isFile &&
                (file.name.endsWith("_$originalFileName") ||
                  file.name.endsWith("_$originalFileName.orig.srt"))
            }
            ?.maxByOrNull { it.lastModified() }
        if (fallback != null && fallback.exists()) {
          return@withContext fallback.readText(Charsets.UTF_8)
        }
      }
      ""
    }

  suspend fun deleteSavedFiles(localBackupPath: String) =
    withContext(Dispatchers.IO) {
      if (localBackupPath.isNotBlank()) {
        runCatching { File(localBackupPath).delete() }
        val translatedName = File(localBackupPath).name
        if (translatedName.isNotBlank()) {
          val sidecar = File(File(context.filesDir, "original_subtitles"), "$translatedName.orig.srt")
          runCatching { if (sidecar.exists()) sidecar.delete() }
        }
      }
    }

  private fun resolveUniqueFile(dir: File, fileName: String): File {
    val candidate = File(dir, fileName)
    if (!candidate.exists()) return candidate
    val dotIdx = fileName.lastIndexOf('.')
    val base = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
    val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""
    var counter = 1
    while (true) {
      val next = File(dir, "${base}_$counter$ext")
      if (!next.exists()) return next
      counter++
    }
  }
}
