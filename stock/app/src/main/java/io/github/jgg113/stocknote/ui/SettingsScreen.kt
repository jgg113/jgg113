package io.github.jgg113.stocknote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import io.github.jgg113.stocknote.core.AiTest
import io.github.jgg113.stocknote.core.KeyText
import io.github.jgg113.stocknote.core.SourceStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.AiProvider
import io.github.jgg113.stocknote.core.DartClient
import io.github.jgg113.stocknote.core.DataKeys
import io.github.jgg113.stocknote.core.FeeConfig
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.KisClient
import io.github.jgg113.stocknote.core.PublicDataClient
import io.github.jgg113.stocknote.core.Strategies
import io.github.jgg113.stocknote.core.TwelveDataClient
import io.github.jgg113.stocknote.ui.theme.PriceColors

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val k = vm.keys
    // 화면을 떠날 때, 바뀐 키로 시세를 다시 받는다.
    DisposableEffect(Unit) { onDispose { vm.applyKeyChanges() } }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportBackup().toByteArray()) } != null }.getOrDefault(false)
            vm.message(if (ok) "백업 파일을 저장했습니다." else "백업 파일을 저장하지 못했습니다.")
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null) vm.message("백업 파일을 열지 못했습니다.") else vm.importBackup(text)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))

        SectionCard("시세·자료 연결 (모두 무료)") {
            Text("코인(업비트)·뉴스·환율은 키 없이 됩니다. 아래 키는 넣은 것만 씁니다.", style = MaterialTheme.typography.bodySmall)
            Notice("키는 넣는 즉시 이 휴대폰에 저장됩니다. 넣은 뒤 맨 아래 [연결 점검]을 눌러 실제로 되는지 확인하세요.")
            KeyField(
                "공공데이터포털 인증키", k.dataGoKr, { vm.updateKeys(vm.keys.copy(dataGoKr = it)) },
                "국내 주식 전 종목 시세(전날 종가)·지수. 마이페이지의 일반 인증키를 그대로 붙여넣으세요. 다른 용도로 받아 둔 키가 있어도, 아래 두 API 를 각각 '활용신청'해야 이 앱에서 열립니다 (키는 같은 것을 씁니다).",
                k.hintDataGoKr(),
            ) {
                TextButton(onClick = { openUrl(context, PublicDataClient.STOCK_PAGE) }) { Text("주식시세 신청") }
                TextButton(onClick = { openUrl(context, PublicDataClient.INDEX_PAGE) }) { Text("지수시세 신청") }
            }
            KeyField(
                "한국투자증권 앱키", k.kisAppKey, { vm.updateKeys(vm.keys.copy(kisAppKey = it)) },
                "국내·해외 실시간 시세와 분봉. 한국투자증권 계좌를 만든 뒤 KIS Developers 에서 신청합니다 (무료). 이 앱은 시세만 보고 주문은 하지 않습니다.",
                k.hintKisKey(),
            ) {
                TextButton(onClick = { openUrl(context, KisClient.SIGNUP) }) { Text("KIS Developers") }
            }
            KeyField("한국투자증권 앱시크릿", k.kisAppSecret, { vm.updateKeys(vm.keys.copy(kisAppSecret = it)) }, "앱키와 함께 받는 긴 글자(약 180자)입니다.", k.hintKisSecret(), secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("모의투자 서버 키", modifier = Modifier.weight(1f))
                Switch(k.kisVirtual, { vm.updateKeys(vm.keys.copy(kisVirtual = it)) })
            }
            SmallLabel("모의투자용으로 받은 앱키면 켜세요. 잘 모르겠으면 그대로 두고 [연결 점검]을 누르면 어느 쪽 키인지 알아서 맞춥니다. 모의 서버는 초당 호출이 적어 느리고 실시간 순위는 볼 수 없습니다.")
            KeyField("DART 인증키", k.dart, { vm.updateKeys(vm.keys.copy(dart = it)) }, "국내 공시·재무제표. 회원가입 뒤 바로 받습니다.", k.hintDart()) {
                TextButton(onClick = { openUrl(context, DartClient.KEY_PAGE) }) { Text("DART 키 받기") }
            }
            KeyField("Twelve Data API 키", k.twelveData, { vm.updateKeys(vm.keys.copy(twelveData = it)) }, "한국투자증권이 없을 때 미국 주식 시세·일봉 (무료 하루 800회).", null) {
                TextButton(onClick = { openUrl(context, TwelveDataClient.KEY_PAGE) }) { Text("키 받기") }
            }
            Button(onClick = { vm.runChecks() }, enabled = !vm.checking, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(if (vm.checking) "점검 중… (길면 1분쯤 걸립니다)" else "연결 점검")
            }
            vm.checks?.let { list ->
                Spacer(Modifier.height(8.dp))
                val failed = list.count { !it.ok && !it.unused }
                Text(
                    if (failed == 0) "넣은 키는 모두 정상입니다." else "${failed}곳이 연결되지 않았습니다. 아래 설명대로 해 보세요.",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = if (failed == 0) OkGreen else MaterialTheme.colorScheme.error,
                )
                list.forEach { CheckRow(it) }
                OutlinedButton(onClick = { copyText(context, vm.checksText()); vm.message("점검 결과를 복사했습니다. 키는 들어 있지 않습니다.") }) { Text("점검 결과 복사") }
                SmallLabel("그래도 안 되면 복사한 점검 결과를 그대로 알려 주세요. 원인을 바로 찾을 수 있습니다.")
            }
        }

        SectionCard("AI 해설") {
            val provider = vm.aiProvider
            SmallLabel("고르는 즉시 저장됩니다. 고른 방법으로 종목 화면의 AI 해설·질문이 나갑니다.")
            AiProvider.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { vm.chooseAiProvider(p) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(provider == p, { vm.chooseAiProvider(p) })
                    Column(Modifier.weight(1f)) {
                        Text(p.label)
                        SmallLabel(p.description)
                    }
                }
            }
            if (provider.isApi) {
                val key = vm.aiKey(provider)
                KeyField("${provider.label.substringBefore(" (")} API 키", key, { vm.updateAiKey(provider, it) }, "", provider.keyHint(key), secret = true) {
                    provider.keyPage?.let { page -> TextButton(onClick = { openUrl(context, page) }) { Text("무료 키 받기") } }
                }
                val t = vm.aiTest
                Button(onClick = { vm.testAi() }, enabled = t?.loading != true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(if (t?.loading == true) "테스트 중…" else "AI 연결 테스트")
                }
                when {
                    t == null -> Unit
                    t.loading -> SmallLabel(t.step, Modifier.padding(top = 6.dp))
                    t.ok -> {
                        Text("✓ 연결되었습니다 (${t.model})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = OkGreen, modifier = Modifier.padding(top = 6.dp))
                        SmallLabel("시험 답변: ${t.text}")
                    }
                    else -> Text("✕ ${t.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (provider.isAppHandoff) {
                val installed = remember(provider) { aiAppInstalled(context, provider) }
                Text(
                    if (installed) "✓ 이 휴대폰에 앱이 설치되어 있습니다." else "이 휴대폰에서 앱을 찾지 못했습니다. 웹으로 열립니다.",
                    style = MaterialTheme.typography.bodySmall, color = if (installed) OkGreen else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
                )
                OutlinedButton(onClick = { handoffToAi(context, provider, AiTest.PROMPT) }) { Text("넘기기 테스트") }
                SmallLabel("질문은 항상 복사해 둡니다. 앱이 열렸는데 입력창이 비어 있으면 입력창을 길게 눌러 붙여넣으세요. 답은 그 앱에서 봅니다 (키가 필요 없습니다).")
            }
            SmallLabel("'AI 예측' 점수는 앱 안에서 통계로 계산하므로 AI 키가 없어도 나옵니다. AI 해설은 그 숫자를 글로 풀어 주는 기능입니다.")
        }

        SectionCard("알림") {
            val a = vm.alertSettings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("가격·신호 알림 켜기", modifier = Modifier.weight(1f))
                Switch(a.enabled, { vm.updateAlertSettings(a.copy(enabled = it)) })
            }
            Text("확인 간격", style = MaterialTheme.typography.bodySmall)
            ChipRow(listOf(15, 30, 60, 180), { it == a.intervalMinutes }, { if (it < 60) "${it}분" else "${it / 60}시간" }) { vm.updateAlertSettings(a.copy(intervalMinutes = it)) }
            SmallLabel("안드로이드는 15분보다 자주 확인할 수 없고, 배터리 절약 중에는 늦어질 수 있습니다. 가격 알림 ${vm.priceAlerts.size}개 · 종목 신호 알림 ${vm.signalAlerts.size}개 · 매매신호 알림 ${vm.scanAlerts.size}개")
            vm.scanAlerts.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallLabel("${s.market.label} ${s.size}종목 · 전략 ${s.strategyIds.size}개 (${s.strategyIds.mapNotNull { Strategies.find(it)?.name }.take(2).joinToString()}…)", Modifier.weight(1f))
                    TextButton(onClick = { vm.removeScanAlert(s) }) { Text("끄기") }
                }
            }
        }

        SectionCard("수수료·세금 (모의투자·백테스트)") {
            var fees by remember { mutableStateOf(vm.store.fees) }
            fun str(v: Double) = v.toString()
            var krBuy by remember { mutableStateOf(str(fees.krBuyPct)) }
            var krSell by remember { mutableStateOf(str(fees.krSellPct)) }
            var krTax by remember { mutableStateOf(str(fees.krTaxPct)) }
            var us by remember { mutableStateOf(str(fees.usPct)) }
            var coin by remember { mutableStateOf(str(fees.cryptoPct)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("국내 매수", krBuy, { krBuy = it }, Modifier.weight(1f), "%")
                NumberField("국내 매도", krSell, { krSell = it }, Modifier.weight(1f), "%")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("거래세", krTax, { krTax = it }, Modifier.weight(1f), "%")
                NumberField("해외 (편도)", us, { us = it }, Modifier.weight(1f), "%")
                NumberField("코인 (편도)", coin, { coin = it }, Modifier.weight(1f), "%")
            }
            TextButton(onClick = {
                fees = FeeConfig(krBuy.toDoubleOrNull() ?: 0.015, krSell.toDoubleOrNull() ?: 0.015, krTax.toDoubleOrNull() ?: 0.2, us.toDoubleOrNull() ?: 0.25, coin.toDoubleOrNull() ?: 0.05)
                vm.store.fees = fees
                vm.message("수수료를 저장했습니다. 지표분석은 다시 열면 반영됩니다.")
            }) { Text("저장") }
            SmallLabel("증권사·이벤트에 따라 다릅니다. 국내 거래세는 시장과 연도에 따라 바뀌니 맞게 고치세요.")
        }

        SectionCard("백업·복원") {
            Text("키·관심종목·내 테마·모의계좌·보유 종목·알림·보조지표 설정을 파일 하나로 저장했다가 되돌립니다. 앱을 지우고 다시 설치하거나 휴대폰을 바꿀 때 쓰세요.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { saveBackup.launch("stocknote-backup.json") }) { Text("백업 파일 저장") }
                OutlinedButton(onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("백업에서 복원") }
            }
            SmallLabel("백업 파일에는 넣어 둔 인증키도 그대로 들어갑니다. 다른 사람에게 보내지 마세요. 복원하면 지금 내용 위에 덮어씁니다.")
        }

        SectionCard("AI 모델 파일") {
            val kb = vm.kronosFileBytes()
            Text(if (vm.kronos.installed) "Kronos-mini (미리 학습된 모델) ${Fmt.decimal(kb / 1024.0 / 1024.0)}MB" else "Kronos-mini: 받지 않음", style = MaterialTheme.typography.bodyMedium)
            SmallLabel("종목 화면 → AI 예측 → [단기]에서 내려받고 실행합니다. 다른 AI 모델은 앱 안에서 그날그날 학습하므로 파일이 없습니다.")
            Row {
                if (vm.kronos.installed) TextButton(onClick = { vm.deleteKronos() }) { Text("모델 파일 지우기") }
                TextButton(onClick = { openUrl(context, io.github.jgg113.stocknote.core.KronosModel.PAGE) }) { Text("Kronos 소개") }
            }
        }

        SectionCard("저장 공간") {
            Text("저장해 둔 시세 ${Fmt.decimal(vm.cacheSize() / 1024.0 / 1024.0)}MB", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { vm.clearCache() }) { Text("저장한 시세 지우기") }
            SmallLabel("키·관심종목·계좌·알림은 지워지지 않습니다. 모든 정보는 이 휴대폰에만 저장됩니다.")
        }

        SectionCard("이 앱에 대해") {
            SmallLabel("버전 ${io.github.jgg113.stocknote.BuildConfig.VERSION_NAME}")
            Text("주식노트는 공개된 무료 시세·공시 자료로 차트·지표·전략 신호·통계 예측·모의투자를 하는 개인용 분석 앱입니다.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("• 실제 주문·결제·로그인 기능은 없습니다.\n• 전략 성과와 예측은 과거 자료로 계산한 참고값이며 미래 수익을 보장하지 않습니다.\n• 국내 무료 시세(공공데이터포털)는 전날 종가 기준입니다.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SmallLabel("자료 출처: 업비트 공개 API, 공공데이터포털(금융위원회), 한국투자증권 Open API, Twelve Data, 금융감독원 DART, Google 뉴스, ExchangeRate-API")
            SmallLabel("AI 모델: Kronos (Shi 외, 2025, MIT 라이선스, Hugging Face NeoQuasar/Kronos-mini) — 추론 코드는 이 앱에서 직접 구현했습니다.")
        }
        Spacer(Modifier.height(40.dp))
    }
}

private val OkGreen = Color(0xFF43A047)

/**
 * 키 입력 칸. 넣는 즉시 [onChange] 로 저장되고, 저장된 길이를 보여 줘 제대로 들어갔는지 알 수 있다.
 * 붙여넣다 섞이는 빈칸·줄바꿈은 걷어 낸다.
 */
@Composable
private fun KeyField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    help: String,
    hint: String?,
    secret: Boolean = false,
    links: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(!secret) }
    Column(Modifier.padding(top = 10.dp)) {
        OutlinedTextField(
            value, { onChange(KeyText.clean(it)) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (help.isNotEmpty()) SmallLabel(help)
        Text(
            if (value.isEmpty()) "아직 넣지 않았습니다." else "저장됨 · " + if (show) KeyText.masked(value) else "${value.length}자",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
            color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else OkGreen,
        )
        if (hint != null) Text("확인해 보세요: $hint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val t = KeyText.clean(pasteText(context))
                if (t.isEmpty()) showToast(context, "복사해 둔 키가 없습니다. 키를 먼저 복사하세요.") else onChange(t)
            }) { Text("붙여넣기") }
            if (value.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("지우기") }
            if (secret) TextButton(onClick = { show = !show }) { Text(if (show) "가리기" else "보기") }
            links()
        }
    }
}

/** 연결 점검 한 줄: 상태, 서버가 한 말, 고치는 방법, 신청 페이지 버튼 */
@Composable
private fun CheckRow(s: SourceStatus) {
    val context = LocalContext.current
    val failed = !s.ok && !s.unused
    val color = if (s.ok) OkGreen else if (s.unused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(if (s.ok) "정상" else if (s.unused) "키 없음" else "연결 안 됨", color)
        }
        Text(
            s.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
            color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failed && s.help.isNotEmpty()) {
            Text("이렇게 해 보세요", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            Text(s.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        val page = s.page
        if (failed && page != null) FilledTonalButton(onClick = { openUrl(context, page) }, modifier = Modifier.padding(top = 8.dp)) { Text(s.pageLabel) }
    }
}
