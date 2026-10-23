package io.github.jgg113.gongmae.core

import java.time.LocalDateTime
import java.util.Locale

object Format {
    /** 424_000_000 → "4억 2,400만원", 42_400_000 → "4,240만원", 5_000 → "5,000원". */
    fun won(value: Long?): String {
        if (value == null) return "-"
        if (value < 0) return "-" + won(-value)
        val eok = value / 100_000_000
        val man = (value % 100_000_000) / 10_000
        return when {
            eok > 0 && man > 0 -> "${eok.grouped()}억 ${man.grouped()}만원"
            eok > 0 -> "${eok.grouped()}억원"
            man > 0 -> "${man.grouped()}만원"
            else -> "${value.grouped()}원"
        }
    }

    /** 카드용 짧은 표기: 424_000_000 → "4.24억", 42_400_000 → "4,240만". */
    fun wonShort(value: Long?): String {
        if (value == null) return "-"
        return when {
            value >= 100_000_000 -> trimZeros(String.format(Locale.US, "%.2f", value / 100_000_000.0)) + "억"
            value >= 10_000 -> (value / 10_000).grouped() + "만"
            else -> value.grouped() + "원"
        }
    }

    fun percent(value: Double?): String =
        if (value == null) "-" else trimZeros(String.format(Locale.US, "%.1f", value)) + "%"

    fun area(sqm: Double?): String {
        if (sqm == null) return "-"
        val pyeong = sqm / Property.SQM_PER_PYEONG
        return trimZeros(String.format(Locale.US, "%.2f", sqm)) + "㎡ (" + trimZeros(String.format(Locale.US, "%.1f", pyeong)) + "평)"
    }

    /** "202609281400" → "2026.09.28 14:00" */
    fun dateTime(key: String): String {
        if (key.length != 12) return "-"
        return "${key.substring(0, 4)}.${key.substring(4, 6)}.${key.substring(6, 8)} ${key.substring(8, 10)}:${key.substring(10, 12)}"
    }

    /** "202609281400" → "09.28 14:00" */
    fun shortDateTime(key: String): String {
        if (key.length != 12) return "-"
        return "${key.substring(4, 6)}.${key.substring(6, 8)} ${key.substring(8, 10)}:${key.substring(10, 12)}"
    }

    fun period(start: String, end: String): String =
        if (start.isEmpty() && end.isEmpty()) "-" else "${shortDateTime(start)} ~ ${shortDateTime(end)}"

    fun dDay(property: Property, now: LocalDateTime): String {
        val days = property.daysUntilEnd(now) ?: return ""
        return when (property.bidWindow(now)) {
            BidWindow.ENDED -> "마감"
            BidWindow.UNKNOWN -> ""
            else -> if (days <= 0) "D-DAY" else "D-$days"
        }
    }

    private fun Long.grouped(): String = String.format(Locale.US, "%,d", this)

    private fun trimZeros(text: String): String =
        if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}
