package com.stash.data.download.preview

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges NewPipe Extractor's [Downloader] interface onto the shared
 * application [OkHttpClient]. Re-using the shared client inherits TLS
 * config, timeouts, and the connection pool used by every other HTTP
 * surface in the app — no second network stack.
 *
 * Holds no per-request state; safe to share as a `@Singleton`.
 */
@Singleton
class OkHttpNewPipeDownloader @Inject constructor(
    private val client: OkHttpClient,
) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val httpReq = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .apply {
                request.headers().forEach { (name, values) ->
                    values.forEach { addHeader(name, it) }
                }
            }
            .build()
        client.newCall(httpReq).execute().use { resp ->
            // resp.body.string() consumes the body — fine here because
            // NewPipe expects a fully-materialised String anyway, and
            // we never retry a request through this adapter.
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString(),
            )
        }
    }
}
