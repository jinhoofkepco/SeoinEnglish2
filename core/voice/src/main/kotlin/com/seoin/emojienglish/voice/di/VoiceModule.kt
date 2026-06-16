package com.seoin.emojienglish.voice.di

import com.seoin.emojienglish.voice.DefaultVoiceController
import com.seoin.emojienglish.voice.DefaultVoiceSession
import com.seoin.emojienglish.voice.PictureController
import com.seoin.emojienglish.voice.VoiceController
import com.seoin.emojienglish.voice.VoiceGateway
import com.seoin.emojienglish.voice.VoiceSession
import com.seoin.emojienglish.voice.WebViewPictureGateway
import com.seoin.emojienglish.voice.WebViewVoiceGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the current voice implementations. Swapping the gateway (Mock → WebView →
 * Realtime, §9.2 / Appendix A) is a one-line change here — no caller is touched.
 *
 * Production now uses the real [WebViewVoiceGateway]; `MockVoiceGateway` /
 * `FakeVoiceGateway` remain for step-api `@Preview` and unit tests.
 */
@Module
@InstallIn(SingletonComponent::class)
interface VoiceModule {

    @Binds
    @Singleton
    fun bindVoiceGateway(impl: WebViewVoiceGateway): VoiceGateway

    @Binds
    @Singleton
    fun bindVoiceSession(impl: DefaultVoiceSession): VoiceSession

    @Binds
    @Singleton
    fun bindVoiceController(impl: DefaultVoiceController): VoiceController

    @Binds
    @Singleton
    fun bindPictureController(impl: WebViewPictureGateway): PictureController
}
