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

object RequestType {
    const val SCRIPT = 1
    const val IMAGE = 2
    const val STYLE = 4
    const val XHR = 8
    const val SUBDOC = 16
    const val MEDIA = 32
    const val FONT = 64
    const val OTHER = 128

    fun infer(path: String, headers: Map<String, String>?): Int {
        val dest = headers?.entries?.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        when (dest) {
            "script" -> return SCRIPT
            "image" -> return IMAGE
            "style" -> return STYLE
            "iframe", "frame" -> return SUBDOC
            "font" -> return FONT
            "audio", "video", "track" -> return MEDIA
            "empty" -> return XHR
        }
        when (path.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> return SCRIPT
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> return IMAGE
            "css" -> return STYLE
            "woff", "woff2", "ttf", "otf" -> return FONT
            "mp4", "webm", "mp3", "m4a", "ogg", "m3u8", "ts" -> return MEDIA
            "html", "htm" -> return SUBDOC
        }
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
        return when {
            accept.contains("text/css") -> STYLE
            accept.startsWith("image/") -> IMAGE
            accept.contains("text/html") -> SUBDOC
            headers?.keys?.any { it.equals("X-Requested-With", true) } == true -> XHR
            else -> OTHER
        }
    }
}

private class NetworkRule(
    private val pattern: String,
    val thirdParty: Boolean?,
    val typeMask: Int,
    val excludedTypes: Int,
    val domains: Set<String>,
    val excludedDomains: Set<String>,
    val important: Boolean
) {
    // Regex compilation is deferred: most rules never get looked at on a given page.
    private val regex: Regex by lazy(LazyThreadSafetyMode.PUBLICATION) { compile(pattern) }

    fun matches(url: String, pageHost: String?, thirdPartyReq: Boolean, type: Int): Boolean {
        if (thirdParty != null && thirdParty != thirdPartyReq) return false
        if (typeMask != 0 && typeMask and type == 0) return false
        if (excludedTypes and type != 0) return false
        if (domains.isNotEmpty() || excludedDomains.isNotEmpty()) {
            if (pageHost == null) return false
            if (excludedDomains.any { hostMatches(pageHost, it) }) return false
            if (domains.isNotEmpty() && domains.none { hostMatches(pageHost, it) }) return false
        }
        return regex.containsMatchIn(url)
    }

    private fun compile(p: String): Regex {
        var s = p
        val sb = StringBuilder()
        when {
            s.startsWith("||") -> { sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?"); s = s.substring(2) }
            s.startsWith("|") -> { sb.append('^'); s = s.substring(1) }
        }
        val endAnchor = s.endsWith("|")
        if (endAnchor) s = s.dropLast(1)
        for (c in s) {
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-zA-Z0-9_.%-]|$)")
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        if (endAnchor) sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}

internal fun hostMatches(host: String, domain: String): Boolean =
    host == domain || host.endsWith(".$domain")

private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "edu", "ac")

internal fun registrableDomain(host: String): String {
    val parts = host.split('.')
    if (parts.size <= 2) return host
    val take = if (parts.last().length == 2 && parts[parts.size - 2] in SECOND_LEVEL) 3 else 2
    return parts.takeLast(take).joinToString(".")
}

class FilterEngine private constructor(
    private val blockHosts: Set<String>,
    private val allowHosts: Set<String>,
    private val blockIndex: Map<String, List<NetworkRule>>,
    private val allowIndex: Map<String, List<NetworkRule>>,
    private val blockFallback: List<NetworkRule>,
    private val allowFallback: List<NetworkRule>,
    private val genericSelectors: List<List<String>>,
    private val domainSelectors: Map<String, List<String>>,
    val ruleCount: Int
) {
    private val tokenRegex = Regex("[a-z0-9%]{3,}")

    fun shouldBlock(url: String, host: String, pageHost: String?, type: Int): Boolean {
        val lower = url.lowercase()
        val thirdParty = pageHost != null && registrableDomain(pageHost) != registrableDomain(host)

        val blocked = hostListed(blockHosts, host) ||
            matchIndex(blockIndex, blockFallback, lower, pageHost, thirdParty, type, onlyImportant = false)
        if (!blocked) return false

        if (hostListed(allowHosts, host)) return false
        return !matchIndex(allowIndex, allowFallback, lower, pageHost, thirdParty, type, onlyImportant = false)
    }

    private fun hostListed(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun matchIndex(
        index: Map<String, List<NetworkRule>>,
        fallback: List<NetworkRule>,
        url: String,
        pageHost: String?,
        thirdParty: Boolean,
        type: Int,
        onlyImportant: Boolean
    ): Boolean {
        for (m in tokenRegex.findAll(url)) {
            val bucket = index[m.value] ?: continue
            for (r in bucket) {
                if ((!onlyImportant || r.important) && r.matches(url, pageHost, thirdParty, type)) return true
            }
        }
        for (r in fallback) {
            if ((!onlyImportant || r.important) && r.matches(url, pageHost, thirdParty, type)) return true
        }
        return false
    }

    fun cosmeticScript(host: String): String? {
        val groups = ArrayList<List<String>>(genericSelectors)
        var h = host
        while (true) {
            domainSelectors[h]?.let { groups.add(it) }
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        if (groups.isEmpty()) return null
        val sb = StringBuilder("(function(){if(document.getElementById('aab-adblock'))return;")
        sb.append("var s=document.createElement('style');s.id='aab-adblock';")
        sb.append("(document.head||document.documentElement).appendChild(s);var sh=s.sheet;")
        sb.append("var g=[")
        groups.forEachIndexed { i, grp ->
            if (i > 0) sb.append(',')
            sb.append('[')
            grp.forEachIndexed { j, sel ->
                if (j > 0) sb.append(',')
                sb.append('"').append(sel.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            }
            sb.append(']')
        }
        sb.append("];var D='{display:none!important}';")
        sb.append("for(var i=0;i<g.length;i++){try{sh.insertRule(g[i].join(',')+D,sh.cssRules.length)}")
        sb.append("catch(e){for(var j=0;j<g[i].length;j++){try{sh.insertRule(g[i][j]+D,sh.cssRules.length)}catch(e2){}}}}")
        sb.append("})();")
        return sb.toString()
    }

    class Builder {
        private val blockHosts = HashSet<String>()
        private val allowHosts = HashSet<String>()
        private val blockIndex = HashMap<String, MutableList<NetworkRule>>()
        private val allowIndex = HashMap<String, MutableList<NetworkRule>>()
        private val blockFallback = ArrayList<NetworkRule>()
        private val allowFallback = ArrayList<NetworkRule>()
        private val generic = LinkedHashSet<String>()
        private val domainSel = HashMap<String, MutableList<String>>()
        private var count = 0

        private val hostOnly = Regex("^\\|\\|[a-z0-9][a-z0-9.-]*\\^?$")
        private val tokenRegex = Regex("[a-z0-9%]{3,}")
        private val unsupportedCosmetic = listOf(
            ":has-text(", ":xpath(", ":-abp-", ":matches-", ":upward(", ":remove(", ":style(",
            ":contains(", ":watch-attr(", ":min-text-length(", ":others(", ":nth-ancestor(", ":matches-path("
        )
        private val safeSelector = Regex("^[a-zA-Z0-9#.\\[\\]=\"'_\\-:>+~*^$|() %/]+$")

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
            val cosmeticIdx = line.indexOf("##")
            if (cosmeticIdx >= 0 && !line.contains("#@#") && !line.contains("$")) {
                addCosmetic(line.substring(0, cosmeticIdx), line.substring(cosmeticIdx + 2))
                return
            }
            if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#")) return
            addNetwork(line)
        }

        private fun addCosmetic(domains: String, selector: String) {
            if (selector.isEmpty() || selector.startsWith("+js(") || selector.contains(',')) return
            if (unsupportedCosmetic.any { selector.contains(it) } || !safeSelector.matches(selector)) return
            if (domains.isEmpty()) {
                generic.add(selector)
                count++
                return
            }
            val positives = domains.split(',').filter { it.isNotEmpty() && !it.startsWith("~") && !it.contains('*') }
            for (d in positives) {
                domainSel.getOrPut(d.lowercase()) { ArrayList() }.add(selector)
                count++
            }
        }

        private fun addNetwork(line: String) {
            var rule = line
            val exception = rule.startsWith("@@")
            if (exception) rule = rule.substring(2)
            if (rule.length >= 2 && rule.startsWith("/") && rule.endsWith("/")) return // regex rules unsupported

            var thirdParty: Boolean? = null
            var types = 0
            var excluded = 0
            var important = false
            val incDomains = HashSet<String>()
            val excDomains = HashSet<String>()

            val dollar = rule.lastIndexOf('$')
            if (dollar >= 0) {
                val opts = rule.substring(dollar + 1)
                rule = rule.substring(0, dollar)
                for (opt in opts.split(',')) {
                    val negated = opt.startsWith("~")
                    val name = opt.removePrefix("~").substringBefore('=')
                    val bit = when (name) {
                        "script" -> RequestType.SCRIPT
                        "image" -> RequestType.IMAGE
                        "stylesheet", "css" -> RequestType.STYLE
                        "xmlhttprequest", "xhr" -> RequestType.XHR
                        "subdocument", "frame" -> RequestType.SUBDOC
                        "media" -> RequestType.MEDIA
                        "font" -> RequestType.FONT
                        "other", "ping", "websocket" -> RequestType.OTHER
                        else -> 0
                    }
                    when {
                        bit != 0 -> if (negated) excluded = excluded or bit else types = types or bit
                        name == "third-party" || name == "3p" -> thirdParty = !negated
                        name == "first-party" || name == "1p" -> thirdParty = negated
                        name == "important" -> important = true
                        name == "match-case" || name == "all" -> {}
                        name == "domain" -> {
                            for (d in opt.substringAfter('=').split('|')) {
                                if (d.startsWith("~")) excDomains.add(d.substring(1).lowercase())
                                else if (d.isNotEmpty()) incDomains.add(d.lowercase())
                            }
                        }
                        else -> return // redirect, csp, removeparam, popup, document... not supported
                    }
                }
            }
            if (rule.isEmpty()) return
            rule = rule.lowercase()
            count++

            val plain = thirdParty == null && types == 0 && excluded == 0 && incDomains.isEmpty() && excDomains.isEmpty()
            if (plain && hostOnly.matches(rule)) {
                (if (exception) allowHosts else blockHosts).add(rule.removePrefix("||").removeSuffix("^"))
                return
            }

            val r = NetworkRule(rule, thirdParty, types, excluded, incDomains, excDomains, important)
            val token = pickToken(rule)
            if (token == null) {
                (if (exception) allowFallback else blockFallback).add(r)
            } else {
                (if (exception) allowIndex else blockIndex).getOrPut(token) { ArrayList() }.add(r)
            }
        }

        /** Longest alphanumeric run that is guaranteed to appear as a whole token in any matching URL. */
        private fun pickToken(pattern: String): String? {
            val anchoredStart = pattern.startsWith("|")
            val anchoredEnd = pattern.endsWith("|")
            var best: String? = null
            for (m in tokenRegex.findAll(pattern)) {
                val s = m.range.first
                val e = m.range.last + 1
                val before = if (s == 0) null else pattern[s - 1]
                val after = if (e >= pattern.length) null else pattern[e]
                if (before == '*' || after == '*') continue
                if (before == null && !anchoredStart) continue
                if (after == null && !anchoredEnd) continue
                if (best == null || m.value.length > best.length) best = m.value
            }
            return best
        }

        fun build(): FilterEngine {
            val groups = generic.chunked(64)
            return FilterEngine(
                blockHosts, allowHosts, blockIndex, allowIndex, blockFallback, allowFallback,
                groups, domainSel, count
            )
        }
    }
}
