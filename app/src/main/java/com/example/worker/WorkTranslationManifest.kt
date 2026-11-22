package com.example.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.domain.model.InputMode
import com.example.domain.model.TranslationJobRequest
import com.example.srt.SrtEngine
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@JsonClass(generateAdapter = true)
data class WorkTranslationManifest(
  @Json(name = "jobUuid") val jobUuid: String,
  @Json(name = "inputMode") val inputMode: String,
  @Json(name = "title") val title: String,
  @Json(name = "secondaryMeta") val secondaryMeta: String,
  @Json(name = "files") val files: List<WorkSubtitleEntry>,
)

@JsonClass(generateAdapter = true)
data class WorkSubtitleEntry(
  @Json(name = "id") val id: String,
  @Json(name = "fileName") val fileName: String,
  @Json(name = "uriString") val uriString: String,
  @Json(name = "fileSizeBytes") val fileSizeBytes: Long,
  @Json(name = "localSrtPath") val localSrtPath: String,
  @Json(name = "roomJobId") val roomJobId: String,
)

object WorkManifestCodec {
  private val moshi: Moshi by lazy { Moshi.Builder().build() }
  private val adapter by lazy { moshi.adapter(WorkTranslationManifest::class.java) }

  fun toJson(manifest: WorkTranslationManifest): String = adapter.toJson(manifest)

  fun fromJson(json: String): WorkTranslationManifest? =
    runCatching { adapter.fromJson(json) }.getOrNull()
}

object TranslationWorkScheduler {
  const val UNIQUE_WORK_NAME = "bsub_subtitle_translation_work"
  const val TAG_SUBTITLE_TRANSLATION = "bsub_subtitle_translation"
  private const val WORK_JOBS_DIR = "work_jobs"

  suspend fun enqueueTranslation(
    context: Context,
    request: TranslationJobRequest,
  ): UUID =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val jobUuid = UUID.randomUUID().toString()
      val jobsDir = File(appContext.filesDir, WORK_JOBS_DIR).apply { mkdirs() }

      // Remove stale job cache files before writing new batch manifest
      cleanupStaleFiles(jobsDir)

      val entries =
        request.files.mapIndexed { index, file ->
          val srtCacheFile = File(jobsDir, "${jobUuid}_$index.srt")
          val formatted = SrtEngine.formatBlocks(file.blocks)
          srtCacheFile.writeText(formatted, Charsets.UTF_8)

          WorkSubtitleEntry(
            id = file.id,
            fileName = file.fileName,
            uriString = file.uriString,
            fileSizeBytes = file.fileSizeBytes,
            localSrtPath = srtCacheFile.absolutePath,
            roomJobId = "${jobUuid}_$index",
          )
        }

      val manifest =
        WorkTranslationManifest(
          jobUuid = jobUuid,
          inputMode = request.inputMode.name,
          title = request.title,
          secondaryMeta = request.secondaryMeta,
          files = entries,
        )

      val manifestFile = File(jobsDir, "$jobUuid.json")
      manifestFile.writeText(WorkManifestCodec.toJson(manifest), Charsets.UTF_8)

      val constraints =
        Constraints.Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .build()

      val workRequest =
        OneTimeWorkRequestBuilder<SubtitleTranslationWorker>()
          .setConstraints(constraints)
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
          .addTag(TAG_SUBTITLE_TRANSLATION)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_JOB_CONFIG_PATH to manifestFile.absolutePath,
              SubtitleTranslationWorker.KEY_INPUT_MODE to
                (if (request.inputMode == InputMode.MOVIE) InputMode.MOVIE.name else InputMode.SERIES.name),
            )
          )
          .build()

      WorkManager.getInstance(appContext)
        .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)

      workRequest.id
    }

  suspend fun enqueueSingleFileTranslation(
    context: Context,
    rawSrtContent: String,
    fileName: String,
    inputMode: InputMode = InputMode.MOVIE,
    title: String = "",
    secondaryMeta: String = "",
    roomJobId: String = UUID.randomUUID().toString(),
  ): UUID =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val jobsDir = File(appContext.filesDir, WORK_JOBS_DIR).apply { mkdirs() }
      val cachedFile = File(jobsDir, "${roomJobId}.srt")
      cachedFile.writeText(rawSrtContent, Charsets.UTF_8)

      val constraints =
        Constraints.Builder()
          .setRequiredNetworkType(NetworkType.CONNECTED)
          .build()

      val workRequest =
        OneTimeWorkRequestBuilder<SubtitleTranslationWorker>()
          .setConstraints(constraints)
          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
          .addTag(TAG_SUBTITLE_TRANSLATION)
          .setInputData(
            workDataOf(
              SubtitleTranslationWorker.KEY_SRT_FILE_PATH to cachedFile.absolutePath,
              SubtitleTranslationWorker.KEY_FILE_NAME to fileName,
              SubtitleTranslationWorker.KEY_INPUT_MODE to inputMode.name,
              SubtitleTranslationWorker.KEY_TITLE to title,
              SubtitleTranslationWorker.KEY_SECONDARY_META to secondaryMeta,
              SubtitleTranslationWorker.KEY_ROOM_JOB_ID to roomJobId,
            )
          )
          .build()

      WorkManager.getInstance(appContext)
        .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)

      workRequest.id
    }

  fun cancelTranslation(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
  }

  fun observeWorkInfo(context: Context): Flow<List<WorkInfo>> {
    return WorkManager.getInstance(context.applicationContext)
      .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
  }

  private fun cleanupStaleFiles(jobsDir: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    jobsDir.listFiles()?.forEach { file ->
      if (file.lastModified() < cutoff) {
        runCatching { file.delete() }
      }
    }
  }
}
