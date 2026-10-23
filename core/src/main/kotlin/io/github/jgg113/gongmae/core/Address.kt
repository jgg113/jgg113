package io.github.jgg113.gongmae.core

/**
 * 온비드는 좌표를 주지 않고, 물건목록에는 전체 주소 필드(zadrNm·cltrRadr)도 없다.
 * 지번은 지번PNU(ltnoPnu) 나 물건명("… 연남동 245-42 삼정도나빌 제2층 제204호")에서 뽑는다.
 */
object AddressParser {
    /** 지번 뒤의 "외 2필지" 는 지오코딩을 깨뜨린다. 건물명·층·호는 카카오가 흡수하므로 남겨도 된다. */
    private val multiLotTrailer = Regex("\\s*외\\s*\\d+\\s*필(지)?.*$")
    private val lotPattern = Regex("^(산\\s*)?(\\d+)(?:\\s*-\\s*(\\d+))?")

    /**
     * 지번PNU 19자리(법정동코드10 + 산여부1 + 본번4 + 부번4) → "산12-3" 같은 지번.
     * 온비드 PNU 는 산여부 자리에 0(일반)/1(산) 을 쓴다. 본번이 0 이면 믿을 수 없어 버린다.
     */
    fun lotFromPnu(pnu: String): String? {
        if (pnu.length != 19 || !pnu.all { it.isDigit() }) return null
        val main = pnu.substring(11, 15).toInt()
        if (main == 0) return null
        val sub = pnu.substring(15, 19).toInt()
        val mountain = if (pnu[10] == '1') "산" else ""
        return mountain + main + if (sub > 0) "-$sub" else ""
    }

    /** 물건명에서 읍면동 바로 뒤의 지번을 뽑는다. */
    fun lotFromName(name: String, dong: String): String? {
        if (dong.isBlank() || !name.contains(dong)) return null
        val tail = name.substringAfter(dong).trim()
        val match = lotPattern.find(tail) ?: return null
        val mountain = if (match.groupValues[1].isNotBlank()) "산" else ""
        val sub = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.takeIf { it > 0 }
        return mountain + match.groupValues[2].toInt() + if (sub != null) "-$sub" else ""
    }

    fun stripTrailers(address: String): String = multiLotTrailer.replace(address, "").trim()

    fun join(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" ")
}

/** 온비드 원문 상세 페이지. 식별자 4개가 모두 있어야 열린다(하나라도 빠지면 HTTP 500). */
object OnbidLinks {
    private const val DETAIL = "https://www.onbid.co.kr/op/cltrpbancinf/cltrdtl/CltrDtlController/mvmnCltrDtl.do"

    fun detailUrl(p: Property): String? {
        val ids = listOf("onbidCltrno" to p.onbidCltrNo, "onbidPbancNo" to p.onbidPbancNo, "pbctNo" to p.pbctNo, "pbctCdtnNo" to p.conditionNo)
        if (ids.any { it.second.isBlank() }) return null
        return DETAIL + "?" + ids.joinToString("&") { (k, v) -> "$k=$v" }
    }

    private const val IMAGE = "https://www.onbid.co.kr/op/cm/syc/filemng/filemngprcs/FileMngPrcsController/dnldImgFile.do"
    private val attachmentList = Regex("atchFileLstNo=(\\d+)")

    /**
     * 썸네일 URL 의 첨부목록번호로 큰 사진들(atchSn 2부터)을 만든다. 실제 장수는 알 수 없어 없는 번호는 화면에서 건너뛴다.
     */
    fun photoUrls(thumbnailUrl: String, count: Int = 6): List<String> {
        val list = attachmentList.find(thumbnailUrl)?.groupValues?.get(1) ?: return emptyList()
        return (2 until 2 + count).map { sn ->
            "$IMAGE?atchFileLstNo=$list&atchSn=$sn&thnImgDownloadFlag=false&downloadImageKind=CLG_FILE_NM"
        }
    }
}

data class LatLng(val lat: Double, val lng: Double)
