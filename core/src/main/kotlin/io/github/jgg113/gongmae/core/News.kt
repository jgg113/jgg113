package io.github.jgg113.gongmae.core

import org.w3c.dom.Element
import java.io.IOException
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

data class NewsItem(val title: String, val link: String, val source: String, val published: ZonedDateTime?)

/** 부동산·경매 뉴스 (Google 뉴스 RSS, 키 필요 없음) */
class NewsClient(private val transport: WebTransport = UrlConnectionTransport()) {
    fun search(query: String): List<NewsItem> {
        val url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, "UTF-8") + "&hl=ko&gl=KR&ceid=KR:ko"
        val response = transport.get(url, mapOf("User-Agent" to "Mozilla/5.0 (Android) GongmaeCompare", "Accept" to "application/rss+xml"))
        if (response.status != 200) throw IOException("뉴스를 불러오지 못했습니다 (HTTP ${response.status})")
        return parse(response.body)
    }

    companion object {
        private val rfc: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        /** 요일이 틀린 날짜도 있어 요일을 떼고 다시 읽는다. */
        private fun date(text: String): ZonedDateTime? =
            runCatching { ZonedDateTime.parse(text, rfc) }.getOrNull()
                ?: runCatching { ZonedDateTime.parse(text.substringAfter(", ").trim(), DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss z", java.util.Locale.US)) }.getOrNull()

        fun parse(xml: String): List<NewsItem> {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val doc = runCatching { factory.newDocumentBuilder().parse(xml.trim().byteInputStream(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
            val items = doc.getElementsByTagName("item")
            return (0 until items.length).mapNotNull { i ->
                val item = items.item(i) as? Element ?: return@mapNotNull null
                fun tag(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
                val source = tag("source")
                // 제목 끝의 " - 언론사" 를 떼어 낸다.
                val title = tag("title").let { if (source.isNotEmpty()) it.removeSuffix(" - $source") else it }
                val link = tag("link")
                if (title.isEmpty() || link.isEmpty()) return@mapNotNull null
                NewsItem(title, link, source, date(tag("pubDate")))
            }.sortedByDescending { it.published }
        }
    }
}
