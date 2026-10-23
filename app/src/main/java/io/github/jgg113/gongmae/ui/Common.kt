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
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.Property
import java.time.LocalDateTime

object Links {
    private const val ONBID_HOME = "https://www.onbid.co.kr"

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

    /** 온비드는 물건 주소를 외부에서 바로 열 수 없어 물건관리번호를 복사해 두고 홈을 연다. */
    fun openOnbid(context: Context, id: String) {
        copy(context, "물건관리번호", id)
        Toast.makeText(context, "물건관리번호 $id 를 복사했습니다. 온비드 검색창에 붙여넣으세요.", Toast.LENGTH_LONG).show()
        openUrl(context, ONBID_HOME)
    }

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun share(context: Context, p: Property, now: LocalDateTime) {
        val text = buildString {
            appendLine("[공매] ${p.name.ifBlank { p.usageLabel }}")
            appendLine(p.displayAddress)
            appendLine("감정가 ${Format.won(p.appraisalPrice)} / 최저입찰가 ${Format.won(p.minBidPrice)} (${Format.percent(p.appraisalRatio)})")
            appendLine("유찰 ${p.failCount}회 · ${p.bidWindow(now).label} ${Format.dDay(p, now)}".trimEnd())
            appendLine("입찰기간 ${Format.period(p.bidStart, p.bidEnd)}")
            append("온비드 물건관리번호 ${p.id}")
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
