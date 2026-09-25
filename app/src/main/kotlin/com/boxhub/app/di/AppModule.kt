package com.boxhub.app.di

import com.boxhub.app.data.site.SiteConfig
import com.boxhub.app.data.site.SiteRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSites(): List<SiteConfig> = SiteRegistry.all
}
