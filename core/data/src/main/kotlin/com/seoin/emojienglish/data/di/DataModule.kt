package com.seoin.emojienglish.data.di

import com.seoin.emojienglish.data.InMemoryMasterModeState
import com.seoin.emojienglish.data.InMemoryPlanRepository
import com.seoin.emojienglish.data.InMemoryProgressRepository
import com.seoin.emojienglish.data.InMemoryTraceRepository
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.data.PlanRepository
import com.seoin.emojienglish.data.ProgressRepository
import com.seoin.emojienglish.data.TraceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds @Singleton
    fun bindTraceRepository(impl: InMemoryTraceRepository): TraceRepository

    @Binds @Singleton
    fun bindProgressRepository(impl: InMemoryProgressRepository): ProgressRepository

    @Binds @Singleton
    fun bindPlanRepository(impl: InMemoryPlanRepository): PlanRepository

    @Binds @Singleton
    fun bindMasterModeState(impl: InMemoryMasterModeState): MasterModeState
}
