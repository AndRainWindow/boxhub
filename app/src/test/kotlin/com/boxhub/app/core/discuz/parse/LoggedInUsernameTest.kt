package com.boxhub.app.core.discuz.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoggedInUsernameTest {

    @Test
    fun `vwmy link yields username`() {
        val html = """
            <div id="umenu" style="display:none">
              <a href="home.php?mod=space&uid=685610" class="vwmy">成哥搞基</a>
            </div>
        """.trimIndent()
        assertEquals("成哥搞基", DiscuzParsers.loggedInUsername(Jsoup.parse(html)))
    }

    @Test
    fun `umenu space link yields username`() {
        val html = """<div id="umenu"><a href="home.php?mod=space&uid=42">tester42</a></div>"""
        assertEquals("tester42", DiscuzParsers.loggedInUsername(Jsoup.parse(html)))
    }

    @Test
    fun `enshan strong-vwmy layout yields username`() {
        // 恩山实测结构：class=vwmy 在 strong 上，a 在里面
        val html = """
            <div id="um">
              <p><strong class="vwmy qq"><a href="space-uid-957938.html" title="访问我的空间">AndRainWindow</a></strong>
              <span class="pipe">|</span><a href="home.php?mod=spacecp">设置</a></p>
            </div>
        """.trimIndent()
        assertEquals("AndRainWindow", DiscuzParsers.loggedInUsername(Jsoup.parse(html)))
    }

    @Test
    fun `guest page returns null`() {
        val html = """<div id="umenu"><a href="member.php?mod=logging&action=login">登录</a></div>"""
        assertNull(DiscuzParsers.loggedInUsername(Jsoup.parse(html)))
        assertNull(DiscuzParsers.loggedInUsername(Jsoup.parse("<html><body>游客</body></html>")))
    }
}
