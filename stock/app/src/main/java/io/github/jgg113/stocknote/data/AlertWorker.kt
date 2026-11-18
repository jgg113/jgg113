package io.github.jgg113.stocknote.data

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
import io.github.jgg113.stocknote.MainActivity
import io.github.jgg113.stocknote.R
import io.github.jgg113.stocknote.core.BacktestConfig
import io.github.jgg113.stocknote.core.Backtester
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.MarketData
import io.github.jgg113.stocknote.core.PriceData
import io.github.jgg113.stocknote.core.SignalScanner
import io.github.jgg113.stocknote.core.Strategies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 백그라운드에서 가격 알림·종목 신호 알림·매매신호 알림을 확인한다.
 * 안드로이드는 15분보다 자주 돌릴 수 없고, 배터리 절약 상태에서는 늦어질 수 있다.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = AppStore(applicationContext)
        // 앱이 꺼져 있어도 AI 연구소에서 저장한 조합 전략의 신호를 볼 수 있게 등록한다.
        if (Strategies.custom.isEmpty()) Strategies.register(store.evolved.map { it.toStrategy() })
        if (!store.alertSettings.enabled) return@withContext Result.success()
        val data = MarketData(store.keys, FileCache(applicationContext), store.tokenStore)
        val fees = store.fees
        val messages = ArrayList<Pair<String, String>>()

        // 1) 가격 알림
        val alerts = store.priceAlerts
        if (alerts.isNotEmpty()) {
            val quotes = runCatching { data.quotes(alerts.map { it.asset }, maxAgeSec = 60) }.getOrDefault(emptyMap())
            val now = System.currentTimeMillis() / 1000
            val updated = alerts.map { a ->
                val q = quotes[a.asset.key] ?: return@map a
                val cur = a.asset.market.currency
                val hit = when {
                    a.above != null && q.price >= a.above -> "${Fmt.priceWithUnit(q.price, cur)} — 목표가 ${Fmt.priceWithUnit(a.above, cur)} 이상"
                    a.below != null && q.price <= a.below -> "${Fmt.priceWithUnit(q.price, cur)} — 기준가 ${Fmt.priceWithUnit(a.below, cur)} 이하"
                    a.movePct != null && kotlin.math.abs(q.changeRate) >= a.movePct -> "오늘 ${Fmt.signedRate(q.changeRate)} 움직였습니다"
                    else -> null
                }
                // 같은 알림을 하루에 여러 번 보내지 않는다 (6시간 간격)
                if (hit != null && now - a.lastFired > 6 * 3600) {
                    messages += "${a.asset.name} 가격 알림" to hit
                    a.copy(lastFired = now)
                } else a
            }
            store.priceAlerts = updated
        }

        // 2) 종목 + 전략 신호 알림
        val signalAlerts = store.signalAlerts
        if (signalAlerts.isNotEmpty()) {
            val updated = signalAlerts.map { s ->
                val strategy = Strategies.find(s.strategyId) ?: return@map s
                val candles = runCatching { data.daily(s.asset, 400) }.getOrNull() ?: return@map s
                val r = Backtester.run(strategy, PriceData(candles), BacktestConfig(fees.roundTripPct(s.asset.market)))
                val idx = r.lastSignalIndex
                val signal = r.lastSignal ?: return@map s
                if (idx < 0) return@map s
                val t = candles[idx].time
                if (t > s.lastSignalTime && idx >= candles.size - 2) {
                    if (s.lastSignalTime > 0 || idx == candles.size - 1) {
                        messages += "${s.asset.name} ${signal.label} 신호" to "${strategy.name} · ${Fmt.date(t)} 종가 ${Fmt.priceWithUnit(candles[idx].close, s.asset.market.currency)}"
                    }
                    s.copy(lastSignalTime = t)
                } else if (s.lastSignalTime == 0L) s.copy(lastSignalTime = t) else s
            }
            store.signalAlerts = updated
        }

        // 3) 매매신호 알림 (관심종목 + 대표 종목에서 새로 생긴 매수 신호)
        val scans = store.scanAlerts
        if (scans.isNotEmpty()) {
            val watch = store.watchlists.flatMap { it.assets }
            val updated = scans.map { scan ->
                val strategies = scan.strategyIds.mapNotNull { Strategies.find(it) }.ifEmpty { return@map scan }
                val universe = runCatching { data.universe(scan.market, scan.size, watch) }.getOrDefault(emptyList())
                if (universe.isEmpty()) return@map scan
                val result = SignalScanner.scan(data.dailyMany(universe, 300), strategies, { BacktestConfig(fees.roundTripPct(it.market)) }, lookback = 1, minTrades = 2, portfolioBars = 0)
                val keys = result.hits.map { "${it.asset.key}|${it.strategy.id}|${it.time}" }
                val fresh = result.hits.filter { "${it.asset.key}|${it.strategy.id}|${it.time}" !in scan.notified }
                if (fresh.isNotEmpty()) {
                    messages += "새 매수 신호 ${fresh.size}건 (${scan.market.label})" to fresh.take(5).joinToString(", ") { "${it.asset.name}·${it.strategy.name}" }
                }
                scan.copy(notified = (scan.notified + keys).takeLast(300))
            }
            store.scanAlerts = updated
        }

        messages.forEachIndexed { i, (title, text) -> notify(applicationContext, 100 + i, title, text) }
        Result.success()
    }

    companion object {
        private const val WORK = "stocknote-alerts"
        private const val CHANNEL = "alerts"

        fun schedule(context: Context, settings: AlertSettings) {
            val wm = WorkManager.getInstance(context)
            if (!settings.enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<AlertWorker>(settings.intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun notify(context: Context, id: Int, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "가격·신호 알림", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, n) }
        }
    }
}
