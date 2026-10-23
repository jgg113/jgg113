@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jgg113.gongmae.BuildConfig
import io.github.jgg113.gongmae.core.AiProvider
import io.github.jgg113.gongmae.core.AiText
import io.github.jgg113.gongmae.core.ApiHealth

private val guideSteps = listOf(
    "공공데이터포털(data.go.kr)에 회원가입하고 로그인합니다.",
    "필요한 API 를 검색해 [활용신청]을 누릅니다. 모두 무료이고 보통 바로 자동 승인됩니다. (아래 '무료 API 안내' 참고)",
    "마이페이지 → 데이터활용 → Open API → 활용신청 현황에서 '일반 인증키'를 복사해 위 칸에 붙여넣고 저장합니다. 키 하나로 신청한 모든 API 를 씁니다.",
    "신청 직후에는 반영까지 1~2시간 걸릴 수 있습니다. [API 연결 점검]으로 어떤 API 가 되는지 확인하세요.",
)

/** 앱이 쓰는 무료 공공 API·키 (이름, 설명, 주소) */
private val freeApis = listOf(
    Triple("건축HUB 건축물대장정보 (추천)", "건축물대장 표제부: 사용승인일(노후도)·용도(근생빌라 판별)·세대수·주차. 공공데이터포털 같은 키", "https://www.data.go.kr/data/15134735/openapi.do"),
    Triple("브이월드 인증키 (선택)", "지적도(필지 경계)·브이월드 지도·항공사진. vworld.kr 회원가입 → 오픈API → 인증키 발급, 서비스 URL 에 http://localhost", "https://www.vworld.kr/dev/v4dv_apikey_s001.do"),
    Triple("Google AI Studio (Gemini)", "AI 분석·비교. 결제 등록 없이 쓰면 한도를 넘어도 멈출 뿐 요금 없음", "https://aistudio.google.com/apikey"),
    Triple("Groq (ChatGPT 계열 공개모델 gpt-oss)", "AI 비교용. 카드 등록 없는 무료 요금제", "https://console.groq.com/keys"),
    Triple("OpenRouter (무료 모델만)", "AI 비교용. 크레딧 충전 안 하면 :free 모델만 쓰이고 요금 없음", "https://openrouter.ai/keys"),
)

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf(vm.serviceKey) }
    var reveal by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("설정", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("공공데이터포털 인증키") {
                Text(
                    "공매(온비드)·실거래가·전월세·건축물대장은 공공데이터포털 오픈API 로 불러오므로 인증키가 필요합니다. 법원경매·지도·공시지가는 키 없이 됩니다. 인증키는 이 휴대폰에만 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("일반 인증키") },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "숨기기" else "보기") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.saveServiceKey(input)
                        input = vm.serviceKey
                    }) { Text("저장") }
                    OutlinedButton(
                        onClick = {
                            vm.saveServiceKey(input)
                            input = vm.serviceKey
                            vm.testServiceKey()
                        },
                        enabled = vm.keyTest !is KeyTestState.Running,
                    ) { Text("연결 테스트") }
                }
                val saved = vm.serviceKey
                Text(
                    if (saved.isBlank()) "저장된 인증키가 없습니다." else "저장된 키: ${saved.take(6)}…${saved.takeLast(4)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (val test = vm.keyTest) {
                    KeyTestState.Idle -> Unit
                    KeyTestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("확인 중…", style = MaterialTheme.typography.bodyMedium)
                    }
                    is KeyTestState.Ok -> Text(
                        "연결 성공! 서울 압류재산 공매 ${test.totalCount?.let { "%,d".format(it) } ?: "-"}건이 조회됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    is KeyTestState.Failed -> Text(
                        test.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ApiCheckCard(vm)
            AiSettingsCard(vm)
            LookupAndAlertCard(vm)
            MapKeyCard(vm)

            SectionCard("무료 API 안내 (유료 전환 없음)") {
                Text(
                    "아래는 모두 무료입니다. 결제 수단을 등록하지 않으면 한도를 넘어도 그날 멈출 뿐 요금이 나가지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                freeApis.forEach { (name, desc, url) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { Links.openUrl(context, url) }) { Text("열기") }
                    }
                }
            }

            SectionCard("공공데이터포털 인증키 받는 방법") {
                guideSteps.forEachIndexed { index, step ->
                    Row {
                        Text("${index + 1}.", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Links.openUrl(context, "https://www.data.go.kr/tcs/dss/selectDataSetList.do?keyword=" + Uri.encode("차세대 온비드"))
                    }) { Text("data.go.kr 에서 찾기") }
                    OutlinedButton(onClick = { Links.openUrl(context, "https://www.onbid.co.kr") }) { Text("온비드") }
                }
            }

            SectionCard("앱 정보") {
                InfoRow("버전", BuildConfig.VERSION_NAME)
                InfoRow("공매", "한국자산관리공사 온비드 (공공데이터포털 오픈API)")
                InfoRow("경매", "대법원 법원경매정보 (공개 화면)")
                InfoRow("시세", "국토교통부 실거래가·전월세 (공공데이터포털)")
                InfoRow("공시가격", "부동산공시가격알리미 (공개 조회)")
                InfoRow("지도", "OpenStreetMap · Esri · 브이월드 (osmdroid)")
                Text(
                    "이 앱은 공매·경매 정보를 조회하고 비교만 합니다. 입찰은 온비드·법원에서 직접 하세요. AI 분석(위험도·투자매력·종합점수)은 공개 정보를 정해진 기준으로 점수화한 참고 자료입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.fillMaxWidth())
        }
    }
}

/** 활용신청한 API 가 실제로 되는지 하나씩 확인 */
@Composable
private fun ApiCheckCard(vm: AppViewModel) {
    val context = LocalContext.current
    SectionCard("API 연결 점검") {
        Text(
            "온비드·실거래가·전월세·건축물대장 API 를 1건씩만 불러 활용신청 상태를 확인합니다 (하루 한도를 거의 쓰지 않습니다).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = vm::runApiCheck, enabled = vm.apiChecks !is Load.Loading) { Text("API 연결 점검") }
        when (val state = vm.apiChecks) {
            null -> Unit
            Load.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(vm.apiCheckProgress.ifBlank { "확인 중…" }, style = MaterialTheme.typography.bodySmall)
            }
            is Load.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is Load.Done -> {
                val ok = state.value.count { it.health == ApiHealth.OK }
                Text("정상 $ok / ${state.value.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                state.value.forEach { check ->
                    val color = when (check.health) {
                        ApiHealth.OK -> Color(0xFF2E7D32)
                        ApiHealth.NEEDS_APPROVAL -> Color(0xFFEF6C00)
                        else -> MaterialTheme.colorScheme.error
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(check.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(check.health.label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
                        }
                        Text(check.detail.take(140), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (check.health == ApiHealth.NEEDS_APPROVAL) {
                            TextButton(onClick = { Links.openUrl(context, check.pageUrl) }) { Text("활용신청 페이지 열기") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** 'AI 실시간 분석' 버튼이 쓸 AI 고르기, 무료 API 키, 'AI 비교'에 넣을 AI */
@Composable
private fun AiSettingsCard(vm: AppViewModel) {
    val context = LocalContext.current
    SectionCard("AI 분석 연결") {
        Text(
            "물건 상세 화면의 'AI 실시간 요약·상세 분석' 버튼이 어떤 AI 를 쓸지 고릅니다. 모두 무료로 쓸 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        AiProvider.entries.forEach { provider ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = vm.aiProvider == provider, onClick = { vm.updateAiProvider(provider) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = vm.aiProvider == provider, onClick = { vm.updateAiProvider(provider) })
                Column(Modifier.weight(1f)) {
                    Text(provider.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(provider.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pkg = provider.packageName
                    if (pkg != null) {
                        val installed = remember(pkg) { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                        Text(
                            if (installed) "설치됨" else "설치 안 됨 — 웹으로 열립니다",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    if (provider.isApi) {
                        Text(
                            if (vm.keyOf(provider).isBlank()) "키 없음 — 아래에서 넣으세요" else "키 저장됨",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (vm.keyOf(provider).isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        HorizontalDivider()
        Text("무료 API 키", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        AiProvider.apis.forEach { provider -> AiKeyField(vm, provider) }
        when (val test = vm.geminiTest) {
            AiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("확인하는 중…", style = MaterialTheme.typography.bodySmall)
            }
            is AiState.Done -> Text("연결 성공 (${test.source}): ${AiText.plain(test.text).take(120)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is AiState.Error -> Text(test.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
        Text(
            "키 받는 법: 각 '키 발급' 버튼으로 사이트를 열어 로그인 → API 키 만들기 → 복사해 붙여넣기. 결제 수단을 등록하지 않으면 무료 한도 안에서만 쓰이고 요금이 나가지 않습니다. 키는 이 휴대폰에만 저장됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("'AI 비교'에 넣을 AI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        (listOf(AiProvider.BUILT_IN) + AiProvider.apis).forEach { provider ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = provider in vm.compareProviders, onCheckedChange = { vm.toggleCompareProvider(provider) })
                Text(provider.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (provider.isApi && vm.keyOf(provider).isBlank()) Text("키 필요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "상세 화면의 [AI 비교]를 누르면 고른 AI 에 같은 질문을 동시에 보내 결론(추천·조건부·비추천)을 나란히 보여 줍니다. ChatGPT 는 무료 API 가 없어 Groq 의 OpenAI 공개모델(gpt-oss-120b)로 대신 비교합니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiKeyField(vm: AppViewModel, provider: AiProvider) {
    val context = LocalContext.current
    var key by rememberSaveable(provider.name) { mutableStateOf(vm.keyOf(provider)) }
    var reveal by rememberSaveable(provider.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("${provider.short} API 키") },
            singleLine = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.saveAiKey(provider, key) }) { Text("저장") }
            TextButton(onClick = {
                vm.saveAiKey(provider, key)
                vm.testAi(provider)
            }, enabled = key.isNotBlank()) { Text("테스트") }
            TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "숨기기" else "보기") }
            provider.keyPage?.let { page -> TextButton(onClick = { Links.openUrl(context, page) }) { Text("키 발급") } }
        }
    }
}

/** 실거래가·공시가격 자동 조회, 새 물건·변동 알림 */
@Composable
private fun LookupAndAlertCard(vm: AppViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setAlerts(granted)
    }
    SectionCard("자동 조회·알림") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("실거래가·전월세·공시가격 자동 조회", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "검색 결과 위쪽 물건부터 주변 실거래가·전월세(국토부)·공시지가·건축물대장을 자동으로 불러와 AI 점수에 반영합니다. 같은 동네·종류는 한 번만 부릅니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.autoLookup, onCheckedChange = vm::changeAutoLookup)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("새 물건·관심 물건 변동 알림", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "하루 두 번 지금 검색 조건(지역·필터)으로 공매를 다시 확인해 새 물건과 관심 물건의 가격 인하·유찰·일정 변경을 알려 줍니다. 법원경매는 사이트 차단을 피하려고 앱을 열었을 때만 확인합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = vm.alertsEnabled,
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setAlerts(on)
                    }
                },
            )
        }
    }
}

/** 브이월드 인증키 (지적도·브이월드 지도) */
@Composable
private fun MapKeyCard(vm: AppViewModel) {
    val context = LocalContext.current
    var key by rememberSaveable { mutableStateOf(vm.vworldKey) }
    SectionCard("지도 (지적도)") {
        Text(
            "기본 지도(OpenStreetMap·Esri)는 키 없이 됩니다. 브이월드 인증키(무료)를 넣으면 지도에 '지적'(필지 경계)·'브이월드'·'항공' 버튼이 생깁니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("브이월드 인증키") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveVworldKey(key) }) { Text("저장") }
            OutlinedButton(onClick = { Links.openUrl(context, "https://www.vworld.kr/dev/v4dv_apikey_s001.do") }) { Text("키 발급") }
        }
        Text(
            "발급: vworld.kr 회원가입 → 오픈API → 인증키 발급 → 서비스 분류 '모바일 앱'(없으면 '기타'), 서비스 URL 은 http://localhost 로 입력 → 활용 API 에서 2D 지도(WMTS)·WMS 선택.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
