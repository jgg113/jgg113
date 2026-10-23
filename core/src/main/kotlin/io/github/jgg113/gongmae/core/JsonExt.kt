package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 공공데이터포털 응답은 같은 필드가 숫자·문자열·빈 문자열·null 로 섞여 오므로 모든 값을 느슨하게 읽는다.

internal fun JsonObject.str(key: String): String {
    val element = this[key] ?: return ""
    if (element is JsonNull || element !is JsonPrimitive) return ""
    return element.content.trim()
}

internal fun JsonObject.firstStr(vararg keys: String): String =
    keys.asSequence().map { str(it) }.firstOrNull { it.isNotEmpty() } ?: ""

internal fun JsonObject.amount(vararg keys: String): Long? =
    keys.asSequence().mapNotNull { parseAmount(str(it)) }.firstOrNull()

internal fun JsonObject.decimal(vararg keys: String): Double? =
    keys.asSequence().mapNotNull { parseDecimal(str(it)) }.firstOrNull()

/**
 * 목록 필드를 객체 리스트로 편다. `[...]`, `{"item": [...]}`, `{"item": {...}}`, 단일 객체, 빈 문자열 모두 처리한다.
 */
internal fun JsonElement?.objects(innerKey: String? = null): List<JsonObject> = when (this) {
    null, is JsonNull, is JsonPrimitive -> emptyList()
    is JsonArray -> filterIsInstance<JsonObject>()
    is JsonObject -> {
        val inner = innerKey?.let { this[it] }
        when {
            inner != null -> inner.objects()
            innerKey != null && size == 1 && values.first() !is JsonPrimitive -> values.first().objects()
            else -> listOf(this)
        }
    }
}

/** "42,400,000" · "42400000.0" · "4.24E8" → 원 단위 정수. 숫자가 없으면 null. */
fun parseAmount(raw: String?): Long? = parseDecimal(raw)?.let { Math.round(it) }

fun parseDecimal(raw: String?): Double? {
    if (raw.isNullOrBlank()) return null
    raw.trim().toDoubleOrNull()?.let { return it }
    val cleaned = raw.filter { it.isDigit() || it == '.' }
    if (cleaned.isEmpty() || cleaned.count { it == '.' } > 1) return null
    return cleaned.toDoubleOrNull()
}

/**
 * "202609281400" · "2026-09-28 14:00" · "20260928" → 12자리 yyyyMMddHHmm. 해석할 수 없으면 "".
 * 온비드는 일정 미정을 "2999…" 로 보내므로 2900년 이후는 미정("")으로 본다.
 */
fun normalizeDateTime(raw: String?, endOfDay: Boolean = false): String {
    val digits = raw.orEmpty().filter { it.isDigit() }
    if (digits.length >= 4 && digits.substring(0, 4).toInt() >= 2900) return ""
    return when {
        digits.length >= 12 -> digits.substring(0, 12)
        digits.length == 8 -> digits + if (endOfDay) "2359" else "0000"
        else -> ""
    }
}
