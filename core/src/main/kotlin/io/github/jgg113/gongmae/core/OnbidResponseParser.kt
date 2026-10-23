package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class OnbidApiException(val code: String, message: String) : Exception(message) {
    /** 다른 재산유형을 계속 조회해도 같은 오류가 날 오류(인증키·한도·네트워크). */
    val isFatal: Boolean get() = code in FATAL_CODES || code.startsWith("HTTP4") || code == NETWORK

    companion object {
        const val NETWORK = "NETWORK"
        private val FATAL_CODES = setOf("20", "21", "22", "30", "31", "32", "HTTP401", "HTTP403", "HTTP429")
    }
}

data class OnbidPage(val items: List<JsonObject>, val totalCount: Int?)

object OnbidResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val xmlTag = { tag: String -> Regex("<$tag>\\s*(.*?)\\s*</$tag>", RegexOption.DOT_MATCHES_ALL) }

    fun parse(body: String): OnbidPage {
        val text = body.trim().removePrefix("﻿")
        if (text.isEmpty()) throw OnbidApiException("EMPTY", "서버 응답이 비어 있습니다. 잠시 후 다시 시도하세요.")
        if (text.startsWith("<")) return parseXml(text)

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw OnbidApiException("PARSE", "응답을 해석하지 못했습니다: ${text.take(80)}")
        val container = root["response"] as? JsonObject ?: root
        val header = (container["header"] ?: container["result"] ?: container["cmmMsgHeader"]) as? JsonObject
        val code = header?.firstStr("resultCode", "returnReasonCode").orEmpty()
        val message = header?.firstStr("resultMsg", "returnAuthMsg", "errMsg").orEmpty()
        checkResult(code, message)?.let { return it }

        val bodyObj = container["body"] as? JsonObject
        return OnbidPage(
            items = bodyObj?.get("items").objects("item"),
            totalCount = bodyObj?.str("totalCount")?.toIntOrNull(),
        )
    }

    private fun parseXml(text: String): OnbidPage {
        fun tag(name: String) = xmlTag(name).find(text)?.groupValues?.get(1).orEmpty()
        val code = tag("returnReasonCode").ifEmpty { tag("resultCode") }
        val message = tag("returnAuthMsg").ifEmpty { tag("resultMsg") }.ifEmpty { tag("errMsg") }
        checkResult(code, message)?.let { return it }
        // 정상 XML 응답은 resultType=json 을 보내므로 오지 않는다. 오면 해석 불가로 처리.
        throw OnbidApiException(code.ifEmpty { "PARSE" }, describe(code, message.ifEmpty { "XML 응답" }))
    }

    /** 정상이면 null, 데이터 없음이면 빈 페이지, 그 밖에는 예외. */
    private fun checkResult(code: String, message: String): OnbidPage? {
        if (code == "03" || message.contains("NODATA", ignoreCase = true)) return OnbidPage(emptyList(), 0)
        // 데이터가 없는 재산유형은 DB_ERROR 로 응답하는 경우가 있다.
        if (code == "02" || message.contains("DB_ERROR", ignoreCase = true)) return OnbidPage(emptyList(), 0)
        if (code.isEmpty() || code == "00" || code == "0") return null
        throw OnbidApiException(code, describe(code, message))
    }

    fun describe(code: String, message: String): String = when (code) {
        "10", "11" -> "요청 값이 올바르지 않습니다. ($message)"
        "12" -> "해당 API 서비스를 찾을 수 없습니다. data.go.kr 에서 활용신청한 서비스가 맞는지 확인하세요."
        "20" -> "서비스 접근이 거부되었습니다. data.go.kr 에서 이 서비스의 활용신청 승인 여부를 확인하세요."
        "21" -> "인증키가 일시적으로 사용 중지되었습니다. 잠시 후 다시 시도하세요."
        "22" -> "오늘 요청 한도를 넘었습니다(개발계정 하루 1,000건). 내일 다시 시도하세요."
        "30" -> "등록되지 않은 인증키입니다. 설정에서 인증키를 확인하세요. 활용신청 직후라면 반영까지 1~2시간 걸릴 수 있습니다."
        "31" -> "인증키 활용기간이 끝났습니다. data.go.kr 에서 연장 신청하세요."
        "32" -> "등록되지 않은 IP 에서 요청했습니다."
        "01", "04", "05", "99" -> "공공데이터포털 서버 오류입니다. 잠시 후 다시 시도하세요. ($message)"
        else -> "오류 $code: $message"
    }

    fun describeHttp(status: Int): String = when (status) {
        401 -> "인증키가 올바르지 않거나 아직 활용신청이 반영되지 않았습니다. 설정에서 인증키를 확인하세요."
        403 -> "접근이 거부되었습니다. data.go.kr 활용신청 상태를 확인하세요."
        404 -> "API 주소를 찾을 수 없습니다."
        429 -> "요청이 너무 많습니다. 잠시 후 다시 시도하세요."
        in 500..599 -> "공공데이터포털 서버 오류(HTTP $status)입니다. 잠시 후 다시 시도하세요."
        else -> "HTTP 오류 $status"
    }
}
