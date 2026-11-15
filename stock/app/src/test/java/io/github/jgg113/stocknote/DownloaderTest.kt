package io.github.jgg113.stocknote

import io.github.jgg113.stocknote.core.KronosModel
import io.github.jgg113.stocknote.core.SafeTensors
import io.github.jgg113.stocknote.data.Downloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 실제 배포처에서 모델 파일을 끝까지 받는지 확인한다 (LIVE=1 일 때만, 약 16MB 를 받는다). */
class DownloaderTest {
    @Test
    fun downloadsTokenizerFromOfficialHost() {
        assumeTrue(System.getenv("LIVE") == "1")
        val dir = File(System.getProperty("java.io.tmpdir"), "stocknote-dl-test").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, KronosModel.TOKENIZER_FILE)
        var last = 0L
        Downloader.download(KronosModel.TOKENIZER_URL, target, KronosModel.TOKENIZER_BYTES) { last = it }
        assertEquals(KronosModel.TOKENIZER_BYTES, target.length())
        assertEquals(KronosModel.TOKENIZER_BYTES, last)
        assertTrue(SafeTensors.open(target).has("embed.weight"))
        assertTrue("임시 파일이 남았습니다", dir.listFiles()!!.none { it.name.endsWith(".part") })
        dir.deleteRecursively()
    }
}
