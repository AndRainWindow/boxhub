package com.boxhub.app.ui.components

import java.io.File
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessImgTest {

    @Test
    fun guest_placeholder_images_resolve_to_thumbnails() {
        // M2 录制：瀚思彼岸游客视角真实帖子（图片 src=none.gif + file 缩略图直链）
        val f = File("../build/hass_map.html") // Gradle test cwd = app/
        if (!f.exists()) {
            println("SAMPLE MISSING: build/hass_map.html — skip")
            return
        }
        val doc = Jsoup.parse(f.readText(), "https://bbs.hassbian.com/")
        val body = doc.select("[id^=postmessage_]").first()?.html() ?: error("no postmessage")
        println("RAW body has img: " + body.contains("<img"))
        val out = preprocessDiscuzHtml(body, "https://bbs.hassbian.com/")
        println("PROCESSED>>> " + out.take(2000))
        assertTrue("处理后应保留图片", Regex("<img").containsMatchIn(out))
        assertTrue("src 应指向 attachment 缩略图", out.contains("attachment.hasstatic.com"))
    }
}
