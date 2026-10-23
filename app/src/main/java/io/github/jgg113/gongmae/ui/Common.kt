package io.github.jgg113.gongmae.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.BidWindow
import io.github.jgg113.gongmae.core.ClaudePrompt
import io.github.jgg113.gongmae.core.CourtClient
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.OfficialDoc
import io.github.jgg113.gongmae.core.Property
import java.time.LocalDateTime

object Links {
    private const val ONBID_HOME = "https://www.onbid.co.kr"
    private const val CLAUDE_PACKAGE = "com.anthropic.claude"

    fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "링크를 열 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /** 지도 앱(geo:)으로 열고, 지도 앱이 없으면 카카오맵 웹으로 연다. */
    fun openMap(context: Context, address: String) {
        if (address.isBlank()) {
            Toast.makeText(context, "주소 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))
        } catch (e: ActivityNotFoundException) {
            openUrl(context, "https://map.kakao.com/link/search/" + Uri.encode(address))
        }
    }

    /** 온비드 물건 상세를 연다. 식별자가 모자라면 물건관리번호를 복사해 두고 홈을 연다. */
    fun openOnbid(context: Context, property: Property) {
        val url = property.onbidUrl
        if (url != null) {
            openUrl(context, url)
            return
        }
        copy(context, "물건관리번호", property.id)
        Toast.makeText(context, "물건관리번호 ${property.id} 를 복사했습니다. 온비드 검색창에 붙여넣으세요.", Toast.LENGTH_LONG).show()
        openUrl(context, ONBID_HOME)
    }

    /** 법원경매정보는 물건별 주소가 없어 사건번호를 복사해 두고 검색 화면을 연다. */
    fun openCourt(context: Context, property: Property) {
        copy(context, "사건번호", property.caseNo)
        Toast.makeText(context, "사건번호 ${property.caseNo} 를 복사했습니다. 법원경매정보 검색창에 붙여넣으세요.", Toast.LENGTH_LONG).show()
        openUrl(context, CourtClient.ENTRY_URL)
    }

    /** 앱 전용 주소(intent)를 먼저 열고, 앱이 없으면 웹 주소로 연다. */
    private fun openAppOrWeb(context: Context, appUrl: String, webUrl: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appUrl)))
        } catch (e: ActivityNotFoundException) {
            openUrl(context, webUrl)
        }
    }

    /** 카카오맵 앱으로 위치를 연다 (키 필요 없음). */
    fun openKakaoMap(context: Context, name: String, position: LatLng?, query: String) {
        if (position == null) {
            openUrl(context, "https://map.kakao.com/link/search/" + Uri.encode(query))
            return
        }
        openAppOrWeb(
            context,
            "kakaomap://look?p=${position.lat},${position.lng}",
            "https://map.kakao.com/link/map/" + Uri.encode(name.take(30)) + "," + position.lat + "," + position.lng,
        )
    }

    /** 카카오맵 로드뷰 (앱이 없으면 웹) */
    fun openRoadview(context: Context, position: LatLng) {
        openAppOrWeb(
            context,
            "kakaomap://roadView?p=${position.lat},${position.lng}",
            "https://map.kakao.com/link/roadview/${position.lat},${position.lng}",
        )
    }

    /** 네이버지도 앱으로 위치를 연다 (앱이 없으면 웹 검색). */
    fun openNaverMap(context: Context, name: String, position: LatLng?, query: String) {
        val web = "https://map.naver.com/p/search/" + Uri.encode(query)
        if (position == null) {
            openUrl(context, web)
            return
        }
        openAppOrWeb(
            context,
            "nmap://place?lat=${position.lat}&lng=${position.lng}&name=" + Uri.encode(name.take(30)) + "&appname=io.github.jgg113.gongmae",
            web,
        )
    }

    /**
     * 분석 질문을 Claude 앱 채팅으로 넘긴다. 사용자의 Claude 구독으로 실시간 분석하므로 이 앱은 AI 비용을 쓰지 않는다.
     * 앱이 공유 글을 입력창에 못 넣는 경우를 대비해 질문은 늘 클립보드에도 복사해 둔다.
     */
    fun askClaude(context: Context, prompt: String) {
        copy(context, "Claude 분석 요청", prompt)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, prompt)
            .setPackage(CLAUDE_PACKAGE)
        try {
            context.startActivity(send)
            Toast.makeText(context, "Claude 앱으로 보냈습니다. 입력창이 비어 있으면 길게 눌러 붙여넣으세요.", Toast.LENGTH_LONG).show()
            return
        } catch (e: ActivityNotFoundException) {
            // 공유 받기를 지원하지 않거나 설치되지 않았다.
        }
        val launch = context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE)
        if (launch != null) {
            context.startActivity(launch)
            Toast.makeText(context, "질문을 복사했습니다. Claude 새 채팅 입력창에 붙여넣으세요.", Toast.LENGTH_LONG).show()
        } else {
            openUrl(context, ClaudePrompt.webUrl(prompt))
            Toast.makeText(context, "Claude 웹을 엽니다. 전체 질문은 복사해 두었습니다.", Toast.LENGTH_LONG).show()
        }
    }

    /** 서류 사이트는 물건 주소로 바로 가는 주소가 없어 주소를 복사해 두고 사이트를 연다. 로그인·결제는 그 사이트에서 직접 한다. */
    fun openOfficialDoc(context: Context, doc: OfficialDoc, property: Property) {
        val address = OfficialDoc.searchAddress(property)
        if (address.isNotBlank()) {
            copy(context, "주소", address)
            Toast.makeText(context, "주소를 복사했습니다. ${doc.site} 검색창에 붙여넣으세요.\n$address", Toast.LENGTH_LONG).show()
        }
        openUrl(context, doc.url)
    }

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun share(context: Context, p: Property, now: LocalDateTime) {
        val text = buildString {
            appendLine("[${p.source.label}] ${p.name.ifBlank { p.usageLabel }}")
            appendLine(p.displayAddress)
            appendLine("감정가 ${Format.won(p.appraisalPrice)} / 최저입찰가 ${Format.won(p.minBidPrice)} (${Format.percent(p.appraisalRatio)})")
            appendLine("유찰 ${p.failCount}회 · ${p.bidWindow(now).label} ${Format.dDay(p, now)}".trimEnd())
            appendLine("입찰기간 ${Format.period(p.bidStart, p.bidEnd)}")
            if (p.isCourt) {
                append("${p.organization} ${p.caseNo} (법원경매정보에서 검색)")
            } else {
                append("온비드 물건관리번호 ${p.id}")
                p.onbidUrl?.let { append("\n").append(it) }
            }
        }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "공유하기"))
    }
}

@Composable
fun Tag(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = content,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun WindowTag(window: BidWindow) {
    val colors = MaterialTheme.colorScheme
    when (window) {
        BidWindow.OPEN -> Tag(window.label, colors.primary, colors.onPrimary)
        BidWindow.UPCOMING -> Tag(window.label, colors.secondaryContainer, colors.onSecondaryContainer)
        BidWindow.ENDED -> Tag(window.label, colors.surfaceVariant, colors.onSurfaceVariant)
        BidWindow.UNKNOWN -> Tag(window.label, colors.surfaceVariant, colors.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value.ifBlank { "-" }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
