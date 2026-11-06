package com.aurora.music.webserver

import android.content.Context

object WebAssets {

    private var cachedIndexHtml: ByteArray? = null

    /**
     * Loads the browser player HTML/JS/CSS bundle from assets with thread-safe caching.
     */
    fun getIndexHtml(context: Context): ByteArray {
        cachedIndexHtml?.let { return it }
        return synchronized(this) {
            cachedIndexHtml?.let { return it }
            try {
                context.assets.open("web/index.html").use { input ->
                    input.readBytes().also { cachedIndexHtml = it }
                }
            } catch (_: Exception) {
                "<!DOCTYPE html><html><body><h1>Aurora Music Server</h1><p>Web player assets not found in package.</p></body></html>"
                    .toByteArray(Charsets.UTF_8)
            }
        }
    }
}
