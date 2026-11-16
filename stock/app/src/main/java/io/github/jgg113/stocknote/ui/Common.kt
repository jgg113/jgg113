package io.github.jgg113.stocknote.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.jgg113.stocknote.core.AiProvider
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.NewsItem
import io.github.jgg113.stocknote.core.Quote
import io.github.jgg113.stocknote.ui.theme.PriceColors

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            if (title != null || action != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    action?.invoke(this)
                }
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun <T> ChipRow(items: List<T>, selected: (T) -> Boolean, label: (T) -> String, modifier: Modifier = Modifier, onClick: (T) -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item -> FilterChip(selected = selected(item), onClick = { onClick(item) }, label = { Text(label(item)) }) }
    }
}

@Composable
fun MarketChips(selected: Market, onSelect: (Market) -> Unit, modifier: Modifier = Modifier) =
    ChipRow(Market.entries, { it == selected }, { it.label }, modifier, onSelect)

@Composable
fun ChangeText(rate: Double, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(Fmt.signedRate(rate), color = PriceColors.color(rate), modifier = modifier, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun QuoteRow(asset: Asset, quote: Quote?, note: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(asset.name.ifBlank { quote?.asset?.name?.ifBlank { asset.code } ?: asset.code }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(asset.shortCode, (quote?.asset?.exchange ?: asset.exchange).takeIf { it.isNotBlank() && it != "UPBIT" }, note).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (quote != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.price(quote.price, asset.market.currency), fontWeight = FontWeight.SemiBold, color = PriceColors.color(quote.change))
                ChangeText(quote.changeRate)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun Loading(text: String = "불러오는 중…", progress: Pair<Int, Int>? = null) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress != null && progress.second > 0) {
            LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("$text ${progress.first}/${progress.second}", style = MaterialTheme.typography.bodySmall)
        } else {
            CircularProgressIndicator(Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Notice(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, action: String? = null, onAction: (() -> Unit)? = null) {
    Surface(color = color, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun ErrorNotice(text: String, onRetry: (() -> Unit)? = null) =
    Notice(text, MaterialTheme.colorScheme.errorContainer, if (onRetry != null) "다시" else null, onRetry)

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
fun NewsList(items: List<NewsItem>, context: Context, limit: Int = 30) {
    if (items.isEmpty()) {
        Text("표시할 내용이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        return
    }
    items.take(limit).forEachIndexed { i, n ->
        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().clickable { openUrl(context, n.link) }.padding(vertical = 8.dp)) {
            Text(n.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(n.kind.label, n.source, if (n.published > 0) Fmt.dateTime(n.published).let { if (it.endsWith("00:00")) it.take(10) else it } else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || it == '-' }) },
        label = { Text(label) },
        singleLine = true,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun SmallLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
fun ScoreBar(value: Double, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.fillMaxWidth().height(8.dp)) {
        LinearProgressIndicator(progress = { (value / 100).toFloat().coerceIn(0f, 1f) }, color = color, modifier = Modifier.fillMaxWidth().height(8.dp))
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

/** 다른 앱(메신저 등)으로 글을 보낸다. */
fun shareText(context: Context, text: String) {
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("주식노트", text))
}

/** 클립보드에 있는 글 (없으면 빈 글) */
fun pasteText(context: Context): String = runCatching {
    context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull().orEmpty()

/** AI 앱이 이 휴대폰에 설치되어 있는지 */
fun aiAppInstalled(context: Context, provider: AiProvider): Boolean =
    provider.packageName?.let { runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull() } != null

fun showToast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/**
 * 질문을 AI 앱으로 넘긴다. 질문은 항상 클립보드에도 복사해 둔다.
 * ① 앱이 '공유 받기'를 지원하면 질문을 바로 넘기고 ② 안 되면 앱만 열어 붙여넣게 하고 ③ 앱이 없으면 웹을 연다.
 */
fun handoffToAi(context: Context, provider: AiProvider, prompt: String) {
    val pkg = provider.packageName ?: return
    runCatching { copyText(context, prompt) }
    val name = provider.label.substringBefore(" 앱")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(send)
        showToast(context, "$name 앱으로 보냈습니다. 입력창이 비어 있으면 길게 눌러 붙여넣으세요.")
        return
    } catch (_: Exception) {
        // 공유 받기를 지원하지 않거나 설치되지 않았다.
    }
    val launch = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
    if (launch != null) {
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            showToast(context, "질문을 복사했습니다. $name 새 대화에 길게 눌러 붙여넣으세요.")
            return
        } catch (_: Exception) {
        }
    }
    val web = provider.webLink(prompt)
    if (web != null) {
        openUrl(context, web)
        showToast(context, "$name 앱이 없어 웹으로 엽니다. 질문은 복사해 두었으니 붙여넣으세요.")
    } else {
        showToast(context, "$name 앱을 열지 못했습니다. 질문은 복사해 두었습니다.")
    }
}

fun Double?.orDash(f: (Double) -> String): String = if (this == null || this.isNaN()) "-" else f(this)
