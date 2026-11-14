package io.github.jgg113.stocknote

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import io.github.jgg113.stocknote.core.AiHorizon
import io.github.jgg113.stocknote.ui.Screen
import androidx.test.core.app.ApplicationProvider
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.ui.AppRoot
import io.github.jgg113.stocknote.ui.AppViewModel
import io.github.jgg113.stocknote.ui.Tab
import io.github.jgg113.stocknote.ui.theme.StockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.io.File

/**
 * 실제 업비트 시세로 주요 화면을 그려 보고 스크린샷을 남긴다 (화면이 죽지 않는지 확인용).
 * 인터넷이 필요해 기본 빌드에서는 돌지 않는다: ./gradlew :app:testDebugUnitTest -PscreenTests
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1100dp-xhdpi")
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun pump(ms: Long = 50) {
        compose.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun shot(name: String) {
        repeat(10) { pump(); Thread.sleep(20) }
        // captureToImage 는 무한 애니메이션 때문에 기다리다 멈추므로 화면 뷰를 직접 그린다.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot $name")
    }

    /** 로딩 표시(무한 애니메이션) 때문에 자동 대기를 쓰지 않고, 시계를 직접 움직이며 조건을 본다. */
    private fun waitFor(seconds: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            pump()
            if (condition()) { pump(); return }
            Thread.sleep(50)
        }
        println("기다림 시간 초과: $what")
    }

    /** 화면 가운데를 손가락으로 밀어 내용을 스크롤한다 (탭 줄을 누르지 않게 가운데에서 시작). */
    private fun swipe() {
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.75f, endY = height * 0.25f, durationMillis = 400) }
        repeat(20) { pump() }
    }

    private fun swipeDown() {
        repeat(5) { compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.85f, durationMillis = 300) } }
        repeat(20) { pump() }
    }

    private fun click(text: String) {
        compose.onAllNodesWithText(text)[0].performClick()
        repeat(5) { pump() }
    }

    @Test
    fun mainScreens() {
        compose.mainClock.autoAdvance = false
        val vm = AppViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.setHomeMarket(Market.CRYPTO)
        compose.setContent { StockTheme { AppRoot(vm) } }
        waitFor(60, "home") { !vm.home.loading && vm.home.featured.isNotEmpty() }
        shot("01_home_crypto")

        val btc = Asset(Market.CRYPTO, "KRW-BTC", "비트코인", "UPBIT")
        vm.toggleWatch(btc)
        vm.openAsset(btc)
        waitFor(120, "detail") { vm.detail?.report != null && vm.detail?.forecast != null }
        shot("02_detail_chart")
        click("AI 예측")
        waitFor(300, "ai short") { vm.ai[AiHorizon.SHORT]?.let { it.result != null || it.error != null } == true }
        println("AI 단기: " + (vm.ai[AiHorizon.SHORT]?.error ?: vm.ai[AiHorizon.SHORT]?.result?.let { "${it.opinion.label} ${it.reliability.label}" }))
        shot("03_ai_short_top")
        swipe(); shot("03_ai_short_mid")
        swipe(); shot("03_ai_short_models")
        swipe(); shot("03_ai_short_patterns")
        vm.loadAi(AiHorizon.LONG)
        waitFor(300, "ai long") { vm.ai[AiHorizon.LONG]?.let { it.result != null || it.error != null } == true }
        swipeDown(); shot("03_ai_long_top")
        click("지표분석"); swipeDown(); shot("04_strategy_summary")
        swipe(); shot("04_strategy_summary_rank")
        click("전체결과"); shot("04_strategy_results")
        val first = vm.detail?.report?.byReturn?.first()
        if (first != null) {
            vm.open(Screen.StrategyDetail(btc, first.strategy.id))
            shot("05_strategy_detail")
            swipe(); shot("05_strategy_trades")
            vm.back()
        }
        vm.trade(vm.accounts.first().id, btc, true, 0.05, vm.detail?.quote?.price ?: 1.0)
        vm.back()
        compose.waitForIdle()

        vm.tab = Tab.DISCOVER
        vm.updateSignals(vm.signals.copy(market = Market.CRYPTO, size = 20))
        vm.runSignals()
        waitFor(300, "signals") { !vm.signals.running && vm.signals.result != null }
        shot("08_signals")
        vm.updateSignals(vm.signals.copy(side = io.github.jgg113.stocknote.core.Signal.BUY)); repeat(5) { pump() }; shot("08_signals_buy")
        click("AI 순위"); vm.updateAiRank(vm.aiRank.copy(market = Market.CRYPTO, size = 20)); vm.runAiRank()
        waitFor(300, "ai rank") { !vm.aiRank.running }
        shot("09_ai_rank")
        click("특징종목"); vm.loadFeatured(Market.CRYPTO); waitFor(30, "featured") { !vm.featured.loading }; shot("09_featured")
        click("테마"); vm.loadThemes(Market.CRYPTO); waitFor(30, "themes") { !vm.themes.loading }; shot("10_themes")
        click("종목필터"); vm.updateScreener(vm.screener.copy(market = Market.CRYPTO)); vm.runScreener(); waitFor(30, "screener") { !vm.screener.running }; shot("11_screener")

        vm.tab = Tab.WATCH
        waitFor(20, "watch") { vm.watchQuotes.isNotEmpty() }
        shot("12_watch")
        vm.tab = Tab.INVEST
        waitFor(20, "accounts") { vm.accountPrices.isNotEmpty() }
        shot("13_invest")
        click("차트게임"); vm.setGameMarket(Market.CRYPTO); vm.newGame(); waitFor(60, "game") { vm.game.game != null || vm.game.error != null }
        vm.game.game?.let { g -> vm.updateGame(g.buy().next(8)) }
        shot("14_game")
        vm.tab = Tab.SETTINGS
        shot("15_settings")
        vm.tab = Tab.HOME
        vm.setHomeMarket(Market.KR)
        waitFor(30, "home kr") { !vm.home.loading }
        shot("16_home_kr_without_keys")
    }
}
