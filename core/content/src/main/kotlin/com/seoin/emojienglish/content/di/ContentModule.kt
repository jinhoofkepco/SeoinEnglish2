package com.seoin.emojienglish.content.di

import com.seoin.emojienglish.content.AssetLessonRepository
import com.seoin.emojienglish.content.LessonRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface ContentModule {

    @Binds
    @Singleton
    fun bindLessonRepository(impl: AssetLessonRepository): LessonRepository
}
