package io.github.jgg113.stocknote.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * 국내 종목으로 AI 모델을 만드는 연구 (개발자가 돌려 결과를 앱에 넣는다).
 * RESEARCH_DIR 에 종목별 CSV(첫 줄 이름, 다음 줄부터 시각,시가,고가,저가,종가,거래량,거래대금)를 두고 돌린다:
 *   RESEARCH_DIR=... RESEARCH_OUT=.../kr_models.json ./gradlew :core:test --tests '*KrResearch*'
 */
class KrResearch {
    private fun load(dir: File): List<Pair<Asset, List<Candle>>> = dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
        val lines = f.readLines()
        val name = lines.first().replace("&amp;", "&").trim()
        val candles = lines.drop(1).mapNotNull { l ->
            val p = l.split(',')
            if (p.size < 7) null else Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toDouble())
        }
        // 최근 1년 하루 평균 거래대금이 30억 원 이상인 종목만 (실제로 사고팔 수 있는 종목)
        val value = candles.takeLast(250).map { it.value }.average()
        if (candles.size < 1500 || value < 3e9) null else Asset(Market.KR, f.name.removeSuffix(".csv"), name, "") to candles
    }

    @Test
    fun buildKoreanModels() {
        val dir = System.getenv("RESEARCH_DIR")?.let(::File) ?: return
        val all = load(dir)
        val shuffled = all.shuffled(Random(42))
        val holdN = all.size * 3 / 10
        val holdout = shuffled.take(holdN)
        val search = shuffled.drop(holdN)
        println("종목 ${all.size}개: 찾기용 ${search.size}개, 따로 남긴 종목 ${holdout.size}개 (${holdout.take(8).joinToString { it.first.name }} …)")
        val cost = BacktestConfig.defaultCost(Market.KR)
        val perRun = (System.getenv("RESEARCH_EVALS") ?: "40000").toLong()
        val seeds = listOf(11, 23, 37)

        val found = LinkedHashMap<String, EvolvedStrategy>()
        var evals = 0L
        var sims = 0L
        var cuts: LongArray? = null
        var lastPbo = Double.NaN
        val t0 = System.currentTimeMillis()
        for (seed in seeds) {
            val lab = EvoLab(search, Market.KR, cost, seed = seed, cuts = cuts)
            cuts = lab.cutDates
            val r = lab.run(maxEvaluations = perRun, population = 200, keep = 40)
            evals += r.evaluations; sims += r.simulations; lastPbo = r.pbo
            r.best.forEach { found.putIfAbsent(it.genome.key, it) }
            println("  탐색 씨앗 $seed: 조합 ${r.evaluations}개, 모의투자 ${r.simulations}번, 과최적화 확률 ${EvoLab.pboText(r.pbo)}, ${(System.currentTimeMillis() - t0) / 1000}초")
        }
        val lab = EvoLab(search, Market.KR, cost, seed = 5, cuts = cuts)
        val hold = EvoLab(holdout, Market.KR, cost, seed = 5, cuts = cuts)
        println("기간: ${lab.periodText()}")

        // 고르기는 학습+검증 성적으로만 한다 (시험 기간·남긴 종목은 보지 않는다). 비슷한 조합은 하나만.
        val ranked = found.values.sortedByDescending { minOf(it.train.score, it.valid.score) }
        val picked = ArrayList<EvolvedStrategy>()
        for (e in ranked) {
            val m = e.genome.members.toSet()
            if (picked.any { p -> val o = p.genome.members.toSet(); (m intersect o).size.toDouble() / (m union o).size > 0.5 }) continue
            picked += e
            if (picked.size == 7) break
        }
        val pbo = lab.pbo(ranked.take(40).map { it.genome })
        val randomTest = lab.randomTestScores()
        fun luck(score: Double) = randomTest.count { it < score } * 100.0 / randomTest.size
        fun st(p: PeriodStats) = "연 ${Fmt.signedRate(p.annReturnPct)} (보유 ${Fmt.signedRate(p.buyHoldAnnPct)}) 승률 ${"%.0f".format(p.winRatePct)}% 거래 ${p.trades} 낙폭 -${"%.0f".format(p.maxDrawdownPct)}% 보유일 ${"%.0f".format(p.exposurePct)}% 번종목 ${"%.0f".format(p.positiveSharePct)}%"

        val now = System.currentTimeMillis() / 1000
        val periods = lab.periodText()
        val models = ArrayList<EvolvedStrategy>()

        // AI 종합 모델: 고른 조합 7개의 투표 (4개 이상이 보유면 보유)
        val parts = picked.map { it.genome }
        val need = parts.size / 2 + 1
        val ensTest = lab.evaluateEnsemble(parts, need, EvoLab.Period.TEST)
        val ens = EvolvedStrategy(
            id = "ai_kr_ensemble", name = "AI 종합 모델 (국내)", genome = parts.first(), market = Market.KR,
            train = lab.evaluateEnsemble(parts, need, EvoLab.Period.TRAIN), valid = lab.evaluateEnsemble(parts, need, EvoLab.Period.VALID), test = ensTest,
            luckPercentile = luck(ensTest.score), pbo = pbo, avgHoldBars = lab.ensembleAvgHold(parts, need), poolSize = lab.poolSize,
            evaluations = evals, simulations = sims, periods = periods, createdAt = now, parts = parts, partsNeed = need,
            holdout = hold.evaluateEnsemble(parts, need, EvoLab.Period.TEST), holdoutSize = hold.poolSize, builtIn = true,
            note = "국내 ${lab.poolSize}종목으로 조합 ${evals / 1000}천 개를 시험해 고른 조합 ${parts.size}개의 투표 모델입니다.",
        )
        models += ens
        picked.forEachIndexed { i, e ->
            models += e.copy(
                id = "ai_kr_${i + 1}", name = "AI 모델 ${i + 1} (국내)", luckPercentile = luck(e.test.score), pbo = pbo, evaluations = evals, simulations = sims,
                holdout = hold.evaluate(e.genome, EvoLab.Period.TEST), holdoutSize = hold.poolSize, builtIn = true, createdAt = now,
                note = "국내 ${lab.poolSize}종목으로 조합 ${evals / 1000}천 개를 시험해 찾은 조합입니다.",
            )
        }

        println("\n총 조합 ${evals}개 · 모의투자 ${sims}번 · ${(System.currentTimeMillis() - t0) / 1000}초 · 과최적화 확률 ${EvoLab.pboText(pbo)} (마지막 탐색 ${EvoLab.pboText(lastPbo)})")
        models.forEach { m ->
            println("- ${m.name}: ${m.describe()} [평균 보유 ${"%.0f".format(m.avgHoldBars)}일]")
            if (!m.isEnsemble) println("    ${m.genome.describe()}")
            println("    학습 ${st(m.train)}")
            println("    검증 ${st(m.valid)}")
            println("    시험 ${st(m.test)} · ${EvoLab.luckText(m.luckPercentile)} · 통과=${m.passedTest}")
            m.holdout?.let { println("    남긴 종목 ${m.holdoutSize}개 시험 기간: ${st(it)}") }
        }
        System.getenv("RESEARCH_OUT")?.let { out ->
            File(out).writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(ListSerializer(EvolvedStrategy.serializer()), models))
            println("저장: $out")
        }
        if (all.isEmpty()) error("자료 없음")
    }
}
