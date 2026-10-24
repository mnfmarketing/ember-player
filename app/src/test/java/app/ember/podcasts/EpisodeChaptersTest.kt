package app.ember.podcasts

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Podcast chapters: the JSON chapters format, and CHAP frames in real MP3s made by ffmpeg (ci/tag-test.sh). */
class EpisodeChaptersTest {

    private val dir = System.getenv("TEST_M4B_DIR")?.let { File(it) }

    @Test fun jsonChapters() {
        val json = """
            {"version":"1.2.0","chapters":[
              {"startTime":0,"title":"Intro"},
              {"startTime":95.5,"title":"The interview","img":"https://example.com/a.jpg","url":"https://example.com"},
              {"startTime":60,"title":"Hidden ad","toc":false},
              {"startTime":1800.25,"title":""}
            ]}
        """.trimIndent()
        val ch = EpisodeChapters.parseJson(json)
        assertEquals(listOf(0L, 95_500L, 1_800_250L), ch.map { it.startMs })
        assertEquals(listOf("Intro", "The interview", "Chapter 3"), ch.map { it.title })
        assertEquals("https://example.com/a.jpg", ch[1].imageUrl)
    }

    private fun checkMp3(name: String) {
        val f = dir?.let { File(it, name) }
        assumeTrue("$name not generated", f != null && f.exists())
        val bytes = f!!.readBytes()
        val len = EpisodeChapters.id3TagLength(bytes.copyOf(10))
        assumeTrue("$name has no ID3 tag", len > 0)
        val ch = EpisodeChapters.parseId3(bytes.copyOf(len))
        assertEquals("$name chapter starts", listOf(0L, 40_000L, 75_000L), ch.map { it.startMs })
        assertEquals("$name chapter titles", listOf("Cold open", "Héllo — main story", "Outro"), ch.map { it.title })
        println("$name: ${ch.size} chapters read from ID3 ($len byte tag)")
    }

    @Test fun mp3Id3v23() = checkMp3("episode-v3.mp3")

    @Test fun mp3Id3v24() = checkMp3("episode-v4.mp3")
}
