@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.BuildConfig

private val guideSteps = listOf(
    "공공데이터포털(data.go.kr)에 회원가입하고 로그인합니다.",
    "'차세대 온비드 부동산 물건목록 조회서비스'를 검색해 [활용신청]을 누릅니다. 무료이고 보통 자동 승인됩니다.",
    "권리·임대차 정보까지 보려면 '차세대 온비드 부동산 물건상세 조회서비스'도 활용신청합니다.",
    "마이페이지 → 데이터활용 → Open API → 활용신청 현황에서 '일반 인증키'를 복사해 위 칸에 붙여넣고 저장합니다. (Encoding·Decoding 아무거나 됩니다)",
    "신청 직후에는 키가 반영되기까지 1~2시간 걸릴 수 있습니다. [연결 테스트]로 확인하세요.",
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
                    "공매(온비드) 물건은 공공데이터포털 오픈API로 불러오므로 인증키가 필요합니다. 법원경매·지도·주변 시설은 키 없이 됩니다. 인증키는 이 휴대폰에만 저장됩니다.",
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

            SectionCard("공공데이터포털 인증키 받는 방법") {
                guideSteps.forEachIndexed { index, step ->
                    Row {
                        Text("${index + 1}.", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Links.openUrl(context, "https://www.data.go.kr/tcs/dss/selectDataSetList.do?keyword=" + Uri.encode("차세대 온비드 부동산"))
                    }) { Text("data.go.kr 에서 찾기") }
                    OutlinedButton(onClick = { Links.openUrl(context, "https://www.onbid.co.kr") }) { Text("온비드") }
                }
            }

            SectionCard("앱 정보") {
                InfoRow("버전", BuildConfig.VERSION_NAME)
                InfoRow("공매", "한국자산관리공사 온비드 (공공데이터포털 오픈API)")
                InfoRow("경매", "대법원 법원경매정보 (공개 화면)")
                InfoRow("지도", "OpenStreetMap · CARTO · Esri")
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
