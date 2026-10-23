package io.github.jgg113.gongmae.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jgg113.gongmae.MainActivity
import io.github.jgg113.gongmae.core.Analyzer
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.Changes
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.OnbidClient
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.SearchRequest
import io.github.jgg113.gongmae.core.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 하루 두 번 저장된 검색 조건으로 공매 물건을 다시 받아 새 물건과 관심 물건 변동을 알린다.
 * 법원경매는 사이트 차단 위험이 있어 배경에서는 부르지 않는다.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        if (!store.alertsEnabled) return Result.success()
        val key = store.serviceKey
        val settings = store.loadSettings()
        if (key.isBlank() || Source.ONBID !in settings.sources) return Result.success()
        val found: List<Property> = withContext(Dispatchers.IO) {
            runCatching {
                Regions.splitSigungu(settings.sigungu).ifEmpty { listOf("") }.flatMap { sigungu ->
                    val request = SearchRequest(
                        sido = settings.sido,
                        sigungu = sigungu,
                        assetTypes = AssetType.entries.filter { it in settings.assetTypes },
                        includePrivateContract = settings.includePrivateContract,
                    )
                    OnbidClient(key).search(request, LocalDateTime.now()).properties
                }
            }.getOrNull()
        } ?: return Result.retry()

        val now = LocalDateTime.now()
        val matching = store.loadFilter().apply(found, now, Analyzer.analyzeAll(found))
        val seen = store.seenIds
        val fresh = if (seen.isEmpty()) emptyList() else matching.filter { it.id !in seen }
        store.seenIds = seen + found.map { it.id }

        val byId = found.associateBy { it.id }
        val favorites = store.loadFavorites()
        val log = store.favoriteChanges.toMutableMap()
        val changedNames = mutableListOf<String>()
        val updated = favorites.map { old ->
            val new = byId[old.id] ?: return@map old
            val diff = Changes.between(old, new)
            if (diff.isNotEmpty()) {
                log[old.id] = (diff + log[old.id].orEmpty()).distinct().take(6)
                changedNames += "${old.name.take(18)}: ${diff.first()}"
            }
            new
        }
        if (updated != favorites) store.saveFavorites(updated)
        if (changedNames.isNotEmpty()) store.favoriteChanges = log

        if (fresh.isNotEmpty()) {
            val region = Regions.label(settings.sido, settings.sigungu)
            val lines = fresh.take(4).map { "${it.name.take(20)} · ${Format.wonShort(it.minBidPrice)}" }
            notify(1, "새 공매 물건 ${fresh.size}건 ($region)", lines)
        }
        if (changedNames.isNotEmpty()) notify(2, "관심 물건 변동 ${changedNames.size}건", changedNames.take(4))
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(id: Int, title: String, lines: List<String>) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "물건 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle().also { inbox -> lines.forEach { inbox.addLine(it) } }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        private const val CHANNEL = "alerts"
        private const val WORK = "property-alerts"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlertWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
