/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.web.adblock

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Adblock-Plus-syntax filter engine (the same list format Brave uses: EasyList, EasyPrivacy,
 * uBlock/Brave lists). Supports network rules ($third-party, resource types, $domain) and
 * simple cosmetic element hiding. Advanced uBO/Brave procedural filters are skipped.
 */
object AdBlocker {
    private const val TAG = "AdBlocker"
    private const val REFRESH_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000

    val FILTER_LISTS = listOf(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "ublock-filters.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "ublock-privacy.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "brave-specific.txt" to "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt"
    )

    @Volatile private var engine: FilterEngine? = null
    @Volatile private var started = false

    val ruleCount: Int get() = engine?.ruleCount ?: 0

    /** Loads cached lists (and refreshes them from the network when stale) in the background. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        thread(name = "adblock-init", isDaemon = true) {
            val dir = File(appContext.filesDir, "adblock").apply { mkdirs() }
            rebuild(dir)
            val stale = FILTER_LISTS.any { (name, _) ->
                val f = File(dir, name)
                !f.exists() || System.currentTimeMillis() - f.lastModified() > REFRESH_INTERVAL_MS
            }
            if (stale && download(dir)) rebuild(dir)
        }
    }

    private fun rebuild(dir: File) {
        try {
            val builder = FilterEngine.Builder()
            for ((name, _) in FILTER_LISTS) {
                val f = File(dir, name)
                if (f.exists()) f.bufferedReader().useLines { lines -> lines.forEach(builder::addLine) }
            }
            engine = builder.build()
            Log.i(TAG, "Loaded ${engine?.ruleCount} rules")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to build filter engine", t)
        }
    }

    private fun download(dir: File): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        var anyOk = false
        for ((name, url) in FILTER_LISTS) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val body = resp.body
                    if (resp.isSuccessful) {
                        val tmp = File(dir, "$name.tmp")
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        if (tmp.length() > 0 && tmp.renameTo(File(dir, name))) anyOk = true else tmp.delete()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Download failed for $url: ${t.message}")
            }
        }
        return anyOk
    }

    /** True if this sub-resource request should be blocked. Safe to call from any thread. */
    fun shouldBlock(request: WebResourceRequest, pageUrl: String?): Boolean {
        if (request.isForMainFrame) return false
        val e = engine ?: return false
        val url = request.url ?: return false
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val headers = request.requestHeaders
        val type = RequestType.infer(url.path.orEmpty(), headers)
        val pageHost = pageUrl?.let { android.net.Uri.parse(it).host?.lowercase() }
        // YouTube is handled by the document-start script; network rules there break playback.
        val host = url.host?.lowercase().orEmpty()
        if (isYouTubeHost(host) || (pageHost != null && isYouTubeHost(pageHost))) return false
        return e.shouldBlock(url.toString(), url.host?.lowercase().orEmpty(), pageHost, type)
    }

    private fun isYouTubeHost(h: String): Boolean =
        listOf("youtube.com", "googlevideo.com", "ytimg.com", "youtube-nocookie.com", "ggpht.com")
            .any { h == it || h.endsWith(".$it") }

    /** JavaScript that hides ad elements for [pageUrl], or null if there is nothing to inject. */
    fun cosmeticScript(pageUrl: String?): String? {
        val e = engine ?: return null
        val host = pageUrl?.let { android.net.Uri.parse(it).host?.lowercase() } ?: return null
        return e.cosmeticScript(host)
    }
}
