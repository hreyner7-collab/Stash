package com.stash.core.data.sync.classifier

import android.util.Log
import com.stash.core.model.DownloadFailureType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure classifier that maps a [FailureContext] to a [DownloadFailureType]
 * bucket. Patterns are ordered — first match wins. Add new patterns to the
 * top of the chain unless the new pattern is strictly more specific than
 * an existing one.
 *
 * Every UNKNOWN bucket assignment logs the raw context to logcat so we can
 * iterate the pattern table release-over-release.
 */
@Singleton
class DownloadFailureClassifier @Inject constructor() {

    fun classify(ctx: FailureContext): DownloadFailureType {
        val text = ctx.errorText?.lowercase().orEmpty()
        val causes = ctx.causeChain

        // 1. Auth (status takes priority over text)
        if (ctx.httpStatus in 401..403) return DownloadFailureType.AUTH_EXPIRED
        if (AUTH_TEXT.any { it in text }) return DownloadFailureType.AUTH_EXPIRED

        // 2. Provider unavailability
        if (PROVIDER_TEXT.any { it in text }) return DownloadFailureType.PROVIDER_UNAVAILABLE

        // 3. Network
        if (NETWORK_TEXT.any { it in text }) return DownloadFailureType.NETWORK
        if (causes.any { it in NETWORK_CAUSES }) return DownloadFailureType.NETWORK

        // 4. ffmpeg — text patterns are specific enough that phase gating
        // isn't needed (worker can't distinguish sub-phases anyway).
        if (FFMPEG_TEXT.any { it in text }) return DownloadFailureType.FFMPEG_ERROR

        // 5. Storage
        if (ctx.phase == DownloadPhase.STORAGE) return DownloadFailureType.STORAGE_ERROR
        if (STORAGE_TEXT.any { it in text }) return DownloadFailureType.STORAGE_ERROR

        // 6. Catchall
        Log.w(TAG, "UNKNOWN failure: phase=${ctx.phase} status=${ctx.httpStatus} text=${ctx.errorText}")
        return DownloadFailureType.UNKNOWN
    }

    private companion object {
        const val TAG = "FailureClassifier"

        val AUTH_TEXT = listOf(
            "login required", "sign in", "captcha", "403 forbidden",
            "unauthorized", "401",
        )
        val PROVIDER_TEXT = listOf(
            "video unavailable", "unable to extract", "private video",
            "this video has been removed", "not available in your country",
            "copyright", "region",
        )
        val NETWORK_TEXT = listOf(
            "timed out", "timeout", "unable to resolve host", "no address",
            "connection reset", "connection refused", "unreachable",
        )
        val NETWORK_CAUSES = setOf(
            "SocketTimeoutException", "UnknownHostException",
            "ConnectException", "SSLException",
        )
        val FFMPEG_TEXT = listOf(
            "ffmpeg", "exit code", "muxer", "codec",
        )
        val STORAGE_TEXT = listOf(
            "enospc", "no space left", "permission denied",
            "saf", "no such file", "eacces",
        )
    }
}
