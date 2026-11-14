package io.github.jgg113.stocknote.data

import io.github.jgg113.stocknote.core.DataException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** 큰 파일(AI 모델 가중치)을 내려받는다. 받다 끊기면 임시 파일만 남고 본 파일은 건드리지 않는다. */
object Downloader {
    fun download(url: String, target: File, expectedBytes: Long, onProgress: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        var current = url
        try {
            repeat(6) {
                val conn = URI(current).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "StockNote")
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        // 다른 서버(저장소)로 넘기는 응답은 직접 따라간다.
                        val next = conn.getHeaderField("Location") ?: throw DataException("모델 내려받기 주소를 찾지 못했습니다.", "DOWNLOAD")
                        current = URI(current).resolve(next).toString()
                        return@repeat
                    }
                    if (code != 200) throw DataException("모델을 내려받지 못했습니다 (HTTP $code).", "DOWNLOAD", code >= 500)
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                onProgress(total)
                            }
                        }
                    }
                    if (tmp.length() != expectedBytes) throw DataException("내려받은 파일 크기가 다릅니다 (${tmp.length()} / $expectedBytes). 다시 시도하세요.", "DOWNLOAD", true)
                    if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw DataException("모델 파일을 저장하지 못했습니다.", "DOWNLOAD") }
                    return
                } finally {
                    conn.disconnect()
                }
            }
            throw DataException("모델 내려받기 주소가 너무 여러 번 바뀝니다.", "DOWNLOAD")
        } catch (e: IOException) {
            throw DataException("모델을 내려받는 중 연결이 끊겼습니다. (${e.message ?: e.javaClass.simpleName})", "NETWORK", true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
