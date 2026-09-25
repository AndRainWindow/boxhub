package com.boxhub.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import com.boxhub.app.core.network.RefererInterceptor
import com.boxhub.app.core.network.SharedCookieStore
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class BoxHubApp : Application() {

    /** 共享 cookie（登录态），与各站网关、WebView 采集同源 */
    @javax.inject.Inject
    lateinit var cookieStore: SharedCookieStore

    override fun onCreate() {
        super.onCreate()
        // 全局图片加载器：补 Referer 过各站防盗链（正文内联图 + 附件 + 头像共用）
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieStore) // 登录后图片携带认证 cookie
            .addInterceptor(RefererInterceptor())
            .build()
        Coil.setImageLoader {
            ImageLoader.Builder(this)
                .callFactory { client }
                .crossfade(false)
                .build()
        }
    }
}
