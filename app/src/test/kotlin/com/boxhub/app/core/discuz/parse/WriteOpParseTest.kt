package com.boxhub.app.core.discuz.parse

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M4 写操作：表单解析 / 响应判读 / 上传响应解析 */
class WriteOpParseTest {

    // ---------- 表单解析 ----------

    private val replyFormHtml = """
        <html><body>
        <form id="postform" action="forum.php?mod=post&action=reply">
          <input type="hidden" name="formhash" value="abc123def" />
          <input type="hidden" name="posttime" value="1759000000" />
          <input type="hidden" name="hash" value="uploadtoken789" />
          <input type="hidden" name="noticeauthor" value="成哥哥呀" />
          <input type="hidden" name="noticetrimstr" value="WR632AX做CPE很稳" />
          <textarea name="message">[quote]成哥哥呀:原帖内容[/quote]
        </textarea>
          <script>updateseccode('sec_x9k2');</script>
          <input type="text" name="seccodeverify" id="sec" />
          var swfconfig = { file_size_limit : "2048" };
          var imgexts = 'jpg,jpeg,png,gif';
          <a href="home.php?mod=space&uid=685610">我</a>
        </form>
        </body></html>
    """.trimIndent()

    @Test
    fun `reply form parses all fields`() {
        val doc = Jsoup.parse(replyFormHtml)
        val ctx = DiscuzParsers.parseReplyForm(doc, "176", "8469778")
        assertNotNull(ctx)
        ctx!!
        assertEquals("abc123def", ctx.formhash)
        assertEquals("1759000000", ctx.posttime)
        assertEquals("uploadtoken789", ctx.uploadHash)
        assertEquals("685610", ctx.uid)
        assertEquals("成哥哥呀", ctx.noticeAuthor)
        assertTrue(ctx.prefillMessage.contains("[quote]"))
        assertEquals("sec_x9k2", ctx.seccodeIdhash)
        assertEquals("seccodeverify", ctx.seccodeField)
        assertEquals(2048, ctx.maxImageSizeKb)
        assertEquals(listOf("jpg", "jpeg", "png", "gif"), ctx.allowedImageExts)
        assertNotNull(ctx.seccodeImageUrl("https://www.right.com.cn/forum/"))
    }

    @Test
    fun `form without posttime is invalid`() {
        val doc = Jsoup.parse("""<input name="formhash" value="x" />""")
        assertNull(DiscuzParsers.parseReplyForm(doc, "1", "2"))
    }

    // ---------- 响应判读 ----------

    @Test
    fun `succeedhandle is success with pid`() {
        val html = """<script>succeedhandle_post('forum.php?mod=viewthread&tid=1&pid=987654', '回复发布成功', 'type:reply');"""
        val r = DiscuzParsers.interpretWriteResponse(html)
        assertTrue(r is DiscuzParsers.WriteOutcome.Success)
        assertEquals("987654", (r as DiscuzParsers.WriteOutcome.Success).pid)
    }

    @Test
    fun `plain success text without script`() {
        val r = DiscuzParsers.interpretWriteResponse("恭喜，回复发布成功。")
        assertTrue(r is DiscuzParsers.WriteOutcome.Success)
    }

    @Test
    fun `cooldown error maps correctly`() {
        val r = DiscuzParsers.interpretWriteResponse(
            """<div class="alert_error">两次发表间隔太短，请 8 秒后再试</div>""",
        )
        assertTrue("got $r", r is DiscuzParsers.WriteOutcome.Fail)
        assertEquals(
            com.boxhub.app.core.discuz.result.ErrorKind.Cooldown,
            (r as DiscuzParsers.WriteOutcome.Fail).kind,
        )
    }

    @Test
    fun `captcha error maps to NeedsCaptcha`() {
        val r = DiscuzParsers.interpretWriteResponse(
            """<div class="messagetext">验证码错误，请重新输入</div>""",
        )
        assertTrue("got $r", r is DiscuzParsers.WriteOutcome.NeedsCaptcha)
    }

    @Test
    fun `mobile binding maps to enshan quirk`() {
        val r = DiscuzParsers.interpretWriteResponse(
            """<div class="alert_error">请先进行手机绑定后才能操作</div>""",
        )
        assertEquals(
            com.boxhub.app.core.discuz.result.ErrorKind.MobileBindRequired,
            (r as DiscuzParsers.WriteOutcome.Fail).kind,
        )
    }

    @Test
    fun `login required maps to SessionExpired`() {
        val r = DiscuzParsers.interpretWriteResponse(
            """showDialog('您需要先登录才能继续本操作')""",
        )
        assertTrue("got $r", r is DiscuzParsers.WriteOutcome.SessionExpired)
    }

    // ---------- 上传响应 ----------

    @Test
    fun `upload success parses aid from DISCUZUPLOAD`() {
        val (aid, err) = DiscuzParsers.parseUploadResponse("DISCUZUPLOAD|0|71055|2048|0|0")
        assertEquals("71055", aid)
        assertNull(err)
    }

    @Test
    fun `upload failure status surfaces error`() {
        val (aid, err) = DiscuzParsers.parseUploadResponse("DISCUZUPLOAD|2||")
        assertNull(aid)
        assertNotNull(err)
    }

    @Test
    fun `upload falls back to json aid`() {
        val (aid, _) = DiscuzParsers.parseUploadResponse("""{"code":0,"aid": "3456"}""")
        assertEquals("3456", aid)
    }

    @Test
    fun `upload falls back to attachment path`() {
        val (aid, path) = DiscuzParsers.parseUploadResponse(
            """<img src="data/attachment/forum/202609/abc.jpg">""",
        )
        assertNull(aid)
        assertTrue(path!!.startsWith("PATH:"))
        assertTrue(path.contains("data/attachment/forum/202609/abc.jpg"))
    }
}
