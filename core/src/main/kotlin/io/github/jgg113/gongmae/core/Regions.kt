package io.github.jgg113.gongmae.core

/** 시도·시군구 목록. 시도는 온비드 API 가 요구하는 정식 명칭(lctnSdnm)으로 둔다. */
object Regions {
    val sidoList: List<String> = listOf(
        "서울특별시", "부산광역시", "대구광역시", "인천광역시", "광주광역시", "대전광역시", "울산광역시", "세종특별자치시",
        "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전라남도", "경상북도", "경상남도", "제주특별자치도",
    )

    private val shortNames = mapOf(
        "서울" to "서울특별시", "부산" to "부산광역시", "대구" to "대구광역시", "인천" to "인천광역시",
        "광주" to "광주광역시", "대전" to "대전광역시", "울산" to "울산광역시", "세종" to "세종특별자치시",
        "경기" to "경기도", "강원" to "강원특별자치도", "충북" to "충청북도", "충남" to "충청남도",
        "전북" to "전북특별자치도", "전남" to "전라남도", "경북" to "경상북도", "경남" to "경상남도", "제주" to "제주특별자치도",
    )

    private val sigunguBySido: Map<String, List<String>> = mapOf(
        "서울특별시" to "종로구 중구 용산구 성동구 광진구 동대문구 중랑구 성북구 강북구 도봉구 노원구 은평구 서대문구 마포구 양천구 강서구 구로구 금천구 영등포구 동작구 관악구 서초구 강남구 송파구 강동구",
        "부산광역시" to "중구 서구 동구 영도구 부산진구 동래구 남구 북구 해운대구 사하구 금정구 강서구 연제구 수영구 사상구 기장군",
        "대구광역시" to "중구 동구 서구 남구 북구 수성구 달서구 달성군 군위군",
        "인천광역시" to "제물포구 영종구 미추홀구 연수구 남동구 부평구 계양구 서해구 검단구 중구 동구 서구 강화군 옹진군",
        "광주광역시" to "동구 서구 남구 북구 광산구",
        "대전광역시" to "동구 중구 서구 유성구 대덕구",
        "울산광역시" to "중구 남구 동구 북구 울주군",
        "세종특별자치시" to "",
        "경기도" to "수원시 성남시 의정부시 안양시 부천시 광명시 평택시 동두천시 안산시 고양시 과천시 구리시 남양주시 오산시 시흥시 군포시 의왕시 하남시 용인시 파주시 이천시 안성시 김포시 화성시 광주시 양주시 포천시 여주시 연천군 가평군 양평군",
        "강원특별자치도" to "춘천시 원주시 강릉시 동해시 태백시 속초시 삼척시 홍천군 횡성군 영월군 평창군 정선군 철원군 화천군 양구군 인제군 고성군 양양군",
        "충청북도" to "청주시 충주시 제천시 보은군 옥천군 영동군 증평군 진천군 괴산군 음성군 단양군",
        "충청남도" to "천안시 공주시 보령시 아산시 서산시 논산시 계룡시 당진시 금산군 부여군 서천군 청양군 홍성군 예산군 태안군",
        "전북특별자치도" to "전주시 군산시 익산시 정읍시 남원시 김제시 완주군 진안군 무주군 장수군 임실군 순창군 고창군 부안군",
        "전라남도" to "목포시 여수시 순천시 나주시 광양시 담양군 곡성군 구례군 고흥군 보성군 화순군 장흥군 강진군 해남군 영암군 무안군 함평군 영광군 장성군 완도군 진도군 신안군",
        "경상북도" to "포항시 경주시 김천시 안동시 구미시 영주시 영천시 상주시 문경시 경산시 의성군 청송군 영양군 영덕군 청도군 고령군 성주군 칠곡군 예천군 봉화군 울진군 울릉군",
        "경상남도" to "창원시 진주시 통영시 사천시 김해시 밀양시 거제시 양산시 의령군 함안군 창녕군 고성군 남해군 하동군 산청군 함양군 거창군 합천군",
        "제주특별자치도" to "제주시 서귀포시",
    ).mapValues { (_, names) -> names.split(' ').filter { it.isNotBlank() } }

    fun sigunguOf(sido: String): List<String> = sigunguBySido[sido].orEmpty()

    /** 검색 지역의 시도가 빈 문자열이면 전국이다. */
    const val NATIONWIDE = ""

    /** 시군구 여러 개는 쉼표로 이어 저장한다 ("강남구,서초구"). */
    fun splitSigungu(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun joinSigungu(values: Collection<String>): String = values.filter { it.isNotBlank() }.distinct().joinToString(",")

    /** 화면 표시: "전국", "서울 전체", "서울 강남구", "서울 강남구 외 2" */
    fun label(sido: String, sigungu: String): String {
        if (sido.isBlank()) return "전국"
        val list = splitSigungu(sigungu)
        return when (list.size) {
            0 -> "${shortName(sido)} 전체"
            1 -> "${shortName(sido)} ${list[0]}"
            else -> "${shortName(sido)} ${list[0]} 외 ${list.size - 1}"
        }
    }

    /**
     * 주소 문자열(휴대폰 지오코더의 "대한민국 경기도 수원시 장안구 …" 등)에서 시도·시군구를 찾는다.
     * 시군구가 목록에 없으면 빈 문자열.
     */
    fun fromAddress(text: String): Pair<String, String>? {
        val tokens = text.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
        val sido = tokens.firstNotNullOfOrNull { token -> resolveSido(token).takeIf { it in sidoList } } ?: return null
        val sigungu = tokens.firstOrNull { it in sigunguOf(sido) }.orEmpty()
        return sido to sigungu
    }

    /** "서울특별시" → "서울" (화면 표시용). */
    fun shortName(sido: String): String = shortNames.entries.firstOrNull { it.value == sido }?.key ?: sido

    /** "서울"·"서울시" → "서울특별시". 모르는 이름은 그대로 돌려준다. */
    fun resolveSido(name: String): String {
        val trimmed = name.trim()
        if (trimmed in sidoList) return trimmed
        return shortNames.entries.firstOrNull { trimmed.startsWith(it.key) }?.value ?: trimmed
    }

    /**
     * API 의 시군구(lctnSggnm) 또는 주소가 선택한 시군구와 같은지.
     * "서구"가 "강서구"에 걸리지 않도록 부분 문자열이 아니라 낱말 단위로 비교한다.
     */
    fun matchesSigungu(apiSigungu: String, address: String, wanted: String): Boolean {
        if (wanted.isBlank()) return true
        val target = wanted.trim()
        val apiTokens = apiSigungu.split(' ').filter { it.isNotBlank() }
        if (apiTokens.isNotEmpty() && (apiSigungu == target || apiTokens.first() == target)) return true
        return address.split(' ').any { it == target }
    }
}
