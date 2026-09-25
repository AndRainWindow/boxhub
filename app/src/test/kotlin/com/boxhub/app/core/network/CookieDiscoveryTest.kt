package com.boxhub.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五站 cookie 头样例 → auth 前缀发现 / auth 识别 */
class CookieDiscoveryTest {

    @Test
    fun discover_prefix_from_real_samples() {
        // 瀚思彼岸（Set-Cookie 实证前缀）
        assertEquals(
            "gXRl_2132",
            SharedCookieStore.discoverAuthPrefix(
                "gXRl_2132_saltkey=U2IYF6ry; gXRl_2132_auth=abc123def%7D; PHPSESSID=x",
            ),
        )
        // 开心电视
        assertEquals(
            "ok8J_2132",
            SharedCookieStore.discoverAuthPrefix("ok8J_2132_auth=zz; ok8J_2132_saltkey=k"),
        )
        // 恩山（未知前缀，登录后由这里发现）
        assertEquals(
            "rHEX_2132",
            SharedCookieStore.discoverAuthPrefix("rHEX_2132_auth=deadbeef; rHEX_2132_saltkey=s"),
        )
        // 非 2132 段的历史前缀（飞客 3192 等形态）
        assertEquals(
            "cu3z_3192",
            SharedCookieStore.discoverAuthPrefix("cu3z_3192_auth=ff; cu3z_3192_saltkey=s"),
        )
    }

    @Test
    fun discover_returns_null_without_auth() {
        assertNull(SharedCookieStore.discoverAuthPrefix("acw_tc=abc; other=1"))
        assertNull(SharedCookieStore.discoverAuthPrefix(""))
    }

    @Test
    fun auth_cookie_name_pattern() {
        assertTrue(SharedCookieStore.AUTH_COOKIE.matches("rHEX_2132_auth"))
        assertTrue(SharedCookieStore.AUTH_COOKIE.matches("gXRl_2132_auth"))
        assertTrue(!SharedCookieStore.AUTH_COOKIE.matches("gXRl_2132_saltkey"))
        assertTrue(!SharedCookieStore.AUTH_COOKIE.matches("auth"))
    }
}
