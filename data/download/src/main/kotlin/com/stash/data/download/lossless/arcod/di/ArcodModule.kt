package com.stash.data.download.lossless.arcod.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.arcod.ArcodSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt wiring for the ARCOD (arcod.xyz) Qobuz-DL lossless source.
 *
 * Binds [ArcodSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it
 * up alongside the squid.wtf and kennyy.com.br Qobuz sources.
 *
 * No interceptor multibinding here — ARCOD's per-user Supabase auth is
 * attached inside [com.stash.data.download.lossless.arcod.ArcodClient]'s
 * derived OkHttp client (via ArcodAuthInterceptor), not the shared
 * download client. Only the [LosslessSource] binding belongs in this
 * module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ArcodModule {

    @Binds
    @IntoSet
    abstract fun bindArcodAsLosslessSource(impl: ArcodSource): LosslessSource
}
