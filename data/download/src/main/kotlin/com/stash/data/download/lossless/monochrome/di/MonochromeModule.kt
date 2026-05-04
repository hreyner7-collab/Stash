package com.stash.data.download.lossless.monochrome.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.monochrome.MonochromeSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt wiring for the Tidal-via-Monochrome lossless source.
 *
 * Binds [MonochromeSource] into the `Set<LosslessSource>` multibinding so
 * [com.stash.data.download.lossless.LosslessSourceRegistry] picks it up
 * alongside the existing [com.stash.data.download.lossless.qobuz.QobuzSource].
 *
 * No credentials wiring — `api.monochrome.tf` is operator-credentialed
 * (the operator runs uimaxbai/hifi-api with their own paid Tidal account).
 * End users supply nothing.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MonochromeModule {

    @Binds
    @IntoSet
    abstract fun bindMonochromeAsLosslessSource(impl: MonochromeSource): LosslessSource
}
