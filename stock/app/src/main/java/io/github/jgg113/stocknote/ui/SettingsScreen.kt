package io.github.jgg113.stocknote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    var k by remember(vm.keys) { mutableStateOf(vm.keys) }
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
            KeyBlock("공공데이터포털 인증키", "국내 주식 전 종목 시세(전날 종가)·지수. 마이페이지의 일반 인증키를 그대로 붙여넣으세요.", k.dataGoKr, { k = k.copy(dataGoKr = it) }) {
                TextButton(onClick = { openUrl(context, PublicDataClient.STOCK_PAGE) }) { Text("주식시세 신청") }
                TextButton(onClick = { openUrl(context, PublicDataClient.INDEX_PAGE) }) { Text("지수시세 신청") }
            }
            KeyBlock("한국투자증권 앱키", "국내·해외 실시간 시세와 분봉. 한국투자증권 계좌를 만든 뒤 KIS Developers 에서 신청합니다 (무료). 이 앱은 시세만 보고 주문은 하지 않습니다.", k.kisAppKey, { k = k.copy(kisAppKey = it) }) {
                TextButton(onClick = { openUrl(context, KisClient.SIGNUP) }) { Text("KIS Developers") }
            }
            OutlinedTextField(k.kisAppSecret, { k = k.copy(kisAppSecret = it.trim()) }, label = { Text("한국투자증권 앱시크릿") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("모의투자 서버 키", modifier = Modifier.weight(1f))
                Switch(k.kisVirtual, { k = k.copy(kisVirtual = it) })
            }
            SmallLabel("모의투자용으로 받은 앱키면 켜세요. 모의 서버는 초당 호출이 적어 느리고 실시간 순위는 볼 수 없습니다.")
            KeyBlock("DART 인증키", "국내 공시·재무제표. 회원가입 뒤 바로 받습니다.", k.dart, { k = k.copy(dart = it) }) {
                TextButton(onClick = { openUrl(context, DartClient.KEY_PAGE) }) { Text("DART 키 받기") }
            }
            KeyBlock("Twelve Data API 키", "한국투자증권이 없을 때 미국 주식 시세·일봉 (무료 하루 800회).", k.twelveData, { k = k.copy(twelveData = it) }) {
                TextButton(onClick = { openUrl(context, TwelveDataClient.KEY_PAGE) }) { Text("키 받기") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { vm.saveKeys(k.copy(dataGoKr = k.dataGoKr.trim(), kisAppKey = k.kisAppKey.trim(), dart = k.dart.trim(), twelveData = k.twelveData.trim())) }) { Text("저장") }
                OutlinedButton(onClick = { vm.runChecks() }, enabled = !vm.checking) { Text(if (vm.checking) "점검 중…" else "연결 점검") }
            }
            vm.checks?.let { list ->
                Spacer(Modifier.height(8.dp))
                list.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).let { m -> s.page?.let { p -> m.clickable { openUrl(context, p) } } ?: m }, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (s.ok) "✓" else "✕", color = if (s.ok) PriceColors.up else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyMedium)
                            SmallLabel(s.detail + if (!s.ok && s.page != null) " (눌러서 신청 페이지 열기)" else "")
                        }
                    }
                }
            }
        }

        SectionCard("AI 해설") {
            var provider by remember { mutableStateOf(vm.store.aiProvider) }
            var key by remember(provider) { mutableStateOf(vm.aiKey(provider)) }
            AiProvider.entries.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { provider = p; vm.store.aiProvider = p }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(provider == p, { provider = p; vm.store.aiProvider = p })
                    Column(Modifier.weight(1f)) {
                        Text(p.label)
                        SmallLabel(p.description)
                    }
                }
            }
            if (provider.isApi) {
                OutlinedTextField(key, { key = it }, label = { Text("${provider.label} 키") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { vm.store.saveAiKey(provider, key); vm.message("AI 키를 저장했습니다.") }) { Text("키 저장") }
                    provider.keyPage?.let { page -> TextButton(onClick = { openUrl(context, page) }) { Text("무료 키 받기") } }
                }
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

@Composable
private fun KeyBlock(title: String, help: String, value: String, onChange: (String) -> Unit, links: @Composable () -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        OutlinedTextField(value, { onChange(it.trim()) }, label = { Text(title) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SmallLabel(help)
        Row { links() }
    }
}

@Suppress("unused")
private fun DataKeys.count() = listOf(hasPublic, hasKis, hasDart, hasTwelve).count { it }
