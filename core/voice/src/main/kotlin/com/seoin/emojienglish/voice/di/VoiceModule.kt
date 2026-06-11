package com.seoin.emojienglish.voice.di

import com.seoin.emojienglish.voice.DefaultVoiceController
import com.seoin.emojienglish.voice.MockVoiceGateway
import com.seoin.emojienglish.voice.VoiceController
import com.seoin.emojienglish.voice.VoiceGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the current voice implementations. Swapping the gateway (Mock → Intent →
 * Realtime, §9.2 / Appendix A) is a one-line change here — no caller is touched.
 */
@Module
@InstallIn(SingletonComponent::class)
interface VoiceModule {

    @Binds
    @Singleton
    fun bindVoiceGateway(impl: MockVoiceGateway): VoiceGateway

    @Binds
    @Singleton
    fun bindVoiceController(impl: DefaultVoiceController): VoiceController
}
