package io.github.jgg113.stocknote

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
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

    /** 화면 밖에 있어도 누를 수 있게, 터치 대신 누르기 동작을 직접 부른다. */
    private fun press(text: String) {
        compose.onAllNodesWithText(text)[0].performSemanticsAction(SemanticsActions.OnClick)
        repeat(5) { pump() }
    }

    private fun click(text: String) {
        compose.onAllNodesWithText(text)[0].performClick()
        repeat(5) { pump() }
    }

    @Test
    fun mainScreens() {
        compose.mainClock.autoAdvance = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        // KRONOS_DIR 에 가중치 파일이 있으면 내려받은 것처럼 넣어 두고 Kronos 화면도 확인한다.
        val kronosSrc = System.getenv("KRONOS_DIR")?.let { File(it) }?.takeIf { it.isDirectory }
        kronosSrc?.listFiles()?.forEach { f -> f.copyTo(File(File(app.filesDir, "models").apply { mkdirs() }, f.name), overwrite = true) }
        val vm = AppViewModel(app)
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
        if (vm.kronos.installed) {
            vm.runKronos()
            waitFor(300, "kronos") { !vm.kronos.running && (vm.kronos.result != null || vm.kronos.error != null) }
            println("Kronos: " + (vm.kronos.error ?: vm.kronos.result?.let { "상승확률 ${it.upProb} 예상 ${it.expectedReturn} ${it.seconds}초" }))
            waitFor(120, "ai short again") { vm.ai[AiHorizon.SHORT]?.result != null }
        }
        shot("03_ai_short_top")
        swipe(); shot("03_ai_short_2")
        swipe(); shot("03_ai_short_3")
        swipe(); shot("03_ai_short_4")
        swipe(); shot("03_ai_short_5")
        swipe(); shot("03_ai_short_6")
        if (vm.kronos.installed) {
            vm.validateKronos()
            waitFor(600, "kronos validation") { !vm.kronos.validating }
            println("Kronos 검증: " + (vm.kronos.error ?: vm.kronosValidation(Market.CRYPTO)?.let { "AUC ${it.auc} ±${it.se} ${it.windows}건 bundled=${it.bundled}" }))
            waitFor(120, "ai short after validation") { vm.ai[AiHorizon.SHORT]?.result != null }
            swipeDown(); swipe(); swipe(); shot("03_ai_short_kronos_validated")
        }
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

    /**
     * 키를 넣기만 해도 저장되고, [연결 점검]이 그 키로 실제 서버에 물어 원인과 해결 방법을 보여 주는지 확인한다.
     * (가짜 키를 쓰므로 서버가 거절하는 것이 정상이다.)
     */
    @Test
    fun settingsKeysSaveAsTypedAndCheckExplains() {
        compose.mainClock.autoAdvance = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        vm.setHomeMarket(Market.CRYPTO)
        vm.tab = Tab.SETTINGS
        compose.setContent { StockTheme { AppRoot(vm) } }
        repeat(5) { pump() }

        // 줄바꿈·빈칸이 섞인 채 붙여넣어도, [저장]을 누르지 않아도 정리되어 저장된다.
        compose.onNodeWithText("공공데이터포털 인증키").performTextInput("fake key\n0123456789abcdef0123456789abcdef0123456789abcdef")
        repeat(5) { pump() }
        val expected = "fakekey0123456789abcdef0123456789abcdef0123456789abcdef"
        check(vm.keys.dataGoKr == expected) { "화면 상태에 키가 반영되지 않았다: ${vm.keys.dataGoKr}" }
        check(io.github.jgg113.stocknote.data.AppStore(app).keys.dataGoKr == expected) { "키가 저장되지 않았다" }
        compose.onNodeWithText("한국투자증권 앱키").performTextInput("P".repeat(36))
        repeat(5) { pump() }
        shot("20_settings_keys_typed")

        press("연결 점검")
        waitFor(120, "checks") { vm.checks != null }
        val rows = vm.checks ?: error("연결 점검이 끝나지 않았다")
        rows.forEach { println("check ${if (it.ok) "OK" else if (it.unused) "--" else "XX"} ${it.name}: ${it.detail}") }
        val stock = rows.first { it.name == "공공데이터포털 주식시세" }
        check(!stock.ok && !stock.unused && stock.help.contains("활용신청")) { "가짜 키인데 안내가 없다: $stock" }
        check(rows.first { it.name == "한국투자증권 (실시간)" }.detail.contains("둘 다")) { "앱시크릿이 빠진 것을 알려 주지 않는다" }
        check(!vm.checksText().contains(expected)) { "복사하는 점검 결과에 키가 들어가면 안 된다" }
        swipe(); shot("21_settings_check_result")
        swipe(); shot("21_settings_check_result_2")

        // AI: 고르는 즉시·넣는 즉시 저장되고, [AI 연결 테스트]가 결과를 보여 준다.
        press("Gemini (무료 API 키)")
        check(io.github.jgg113.stocknote.data.AppStore(app).aiProvider == io.github.jgg113.stocknote.core.AiProvider.GEMINI_API) { "AI 선택이 저장되지 않았다" }
        compose.onNodeWithText("Gemini API 키").performTextInput("AIza fake key 0123456789")
        repeat(5) { pump() }
        check(io.github.jgg113.stocknote.data.AppStore(app).aiKey(io.github.jgg113.stocknote.core.AiProvider.GEMINI_API) == "AIzafakekey0123456789") { "AI 키가 저장되지 않았다" }
        press("AI 연결 테스트")
        waitFor(60, "ai test") { vm.aiTest?.loading == false }
        println("ai test: ${vm.aiTest}")
        check(vm.aiTest?.error?.contains("키가 맞지 않습니다") == true) { "가짜 키인데 알려 주지 않는다: ${vm.aiTest}" }
        shot("22_settings_ai_test")

        // 앱으로 넘기기: 앱이 없으면 웹으로 넘어가고 죽지 않는다.
        press("Gemini 앱으로 넘기기")
        press("넘기기 테스트")
        shot("23_settings_ai_app")
    }

    /** 종합 점수·시장 심리·히트맵·김치 프리미엄·포트폴리오 진단 화면을 실제 업비트 시세로 그려 본다. */
    @Test
    fun insightScreens() {
        compose.mainClock.autoAdvance = false
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = AppViewModel(app)
        vm.setHomeMarket(Market.CRYPTO)
        compose.setContent { StockTheme { AppRoot(vm) } }
        waitFor(90, "home") { !vm.home.loading && vm.home.featured.isNotEmpty() }
        val h = vm.home
        println("mood ${h.mood?.score} ${h.mood?.label} parts=${h.mood?.parts?.map { it.name + "=" + it.score }} heat=${h.heat.size} fng=${h.fearGreed?.value} kimchi=${h.kimchi.size} median=${io.github.jgg113.stocknote.core.Kimchi.median(h.kimchi)} via ${h.kimchiSource}")
        check(h.mood != null) { "시장 심리가 계산되지 않았다" }
        check(h.heat.size >= 10) { "히트맵 종목이 없다" }
        check(h.fearGreed != null) { "공포·탐욕 지수를 받지 못했다" }
        check(h.kimchi.size >= 10) { "김치 프리미엄을 계산하지 못했다" }
        swipe(); shot("30_home_mood_heatmap")
        swipe(); shot("31_home_kimchi_brief")

        val btc = Asset(Market.CRYPTO, "KRW-BTC", "비트코인", "UPBIT")
        val eth = Asset(Market.CRYPTO, "KRW-ETH", "이더리움", "UPBIT")
        vm.openAsset(btc)
        waitFor(120, "detail") { vm.detail?.report != null }
        click("종합")
        waitFor(60, "summary") { vm.detail?.extraLoaded == true }
        println("premium ${vm.detail?.premium?.premiumPct} via ${vm.detail?.premiumSource}")
        check(vm.detail?.premium != null) { "코인 종합 탭에 김치 프리미엄이 없다" }
        shot("32_detail_summary")
        swipe(); shot("33_detail_summary_2")
        click("이슈"); waitFor(60, "issues") { vm.detail?.issuesLoading == false && vm.detail?.news?.isNotEmpty() == true }
        shot("34_detail_sentiment")
        vm.back()

        val paper = vm.accounts.first { it.type == io.github.jgg113.stocknote.core.AccountType.PAPER }
        vm.deposit(paper.id, io.github.jgg113.stocknote.core.Currency.KRW, 50_000_000.0)
        val prices = vm.data.quotes(listOf(btc, eth))
        check(vm.trade(paper.id, btc, true, 0.2, prices[btc.key]!!.price)) { "모의 매수 실패" }
        check(vm.trade(paper.id, eth, true, 1.0, prices[eth.key]!!.price)) { "모의 매수 실패" }
        vm.tab = Tab.INVEST
        repeat(5) { pump() }
        vm.diagnose(paper.id)
        waitFor(120, "diagnose") { vm.diagnoses[paper.id]?.loading == false }
        val d = vm.diagnoses[paper.id]
        println("diagnosis ${d?.result?.let { "${it.score} ${it.grade} vol=${it.volatilityPct} corr=${it.avgCorrelation} findings=${it.findings.map { f -> f.text }}" } ?: d?.error}")
        check(d?.result != null) { "포트폴리오 진단 실패: ${d?.error}" }
        swipe(); shot("35_invest_diagnosis")
        swipe(); shot("36_invest_diagnosis_2")
    }
}
