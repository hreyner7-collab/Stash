package com.stash.core.media.streaming

import com.stash.core.data.streaming.StreamAvailabilityChecker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings that wire `core/media`-side streaming implementations to
 * the interfaces declared in `core/data`. The worker
 * ([com.stash.core.data.sync.workers.AvailabilityCheckWorker]) only knows
 * the interface — Hilt resolves the binding at construction time and
 * supplies the [KennyyStreamAvailabilityChecker] implementation that
 * delegates to `core/media`'s [KennyyStreamResolver].
 *
 * Kept separate from [StreamCacheModule] (object + `@Provides`) because
 * `@Binds` requires an abstract function in an interface or abstract class.
 */
@Module
@InstallIn(SingletonComponent::class)
interface StreamingBindingsModule {
    @Binds
    @Singleton
    fun bindStreamAvailabilityChecker(
        impl: KennyyStreamAvailabilityChecker,
    ): StreamAvailabilityChecker
}
