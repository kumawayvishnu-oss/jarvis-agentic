package com.jarvis.assistant.agentic.search

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Structured search result item.
 */
data class SearchResultItem(
    val title: String,
    val snippet: String,
    val url: String
)

/**
 * FreeLiveSearchClient: Zero-cost, zero-API-key live internet search engine
 * using DuckDuckGo (Instant Answer API, DDG Lite HTML, DDG Standard HTML) and
 * Wikipedia fallback for JARVIS agents.
 */
class FreeLiveSearchClient(
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()

    /**
     * Searches the live web for a query and returns structured results.
     */
    suspend fun search(query: String, maxResults: Int = 5): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return@withContext emptyList()
        }

        val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
        val results = mutableListOf<SearchResultItem>()

        // 1. Try DuckDuckGo Instant Answer API first
        val apiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; JARVIS Assistant)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = try { gson.fromJson(body, JsonObject::class.java) } catch (e: Exception) { null }

                    if (json != null) {
                        val abstractText = json.get("AbstractText")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                        val abstractUrl = json.get("AbstractURL")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                        val heading = json.get("Heading")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

                        if (abstractText.isNotBlank()) {
                            results.add(
                                SearchResultItem(
                                    title = if (heading.isNotBlank()) heading else trimmed,
                                    snippet = abstractText,
                                    url = abstractUrl
                                )
                            )
                        }

                        // Check RelatedTopics (handles both direct topics and nested topic groups)
                        val relatedTopics = json.getAsJsonArray("RelatedTopics")
                        relatedTopics?.forEach { elem ->
                            if (results.size >= maxResults) return@forEach
                            if (elem.isJsonObject) {
                                val obj = elem.asJsonObject
                                if (obj.has("Topics") && obj.get("Topics").isJsonArray) {
                                    obj.getAsJsonArray("Topics").forEach { subElem ->
                                        if (results.size >= maxResults) return@forEach
                                        if (subElem.isJsonObject) {
                                            extractTopicItem(subElem.asJsonObject, results)
                                        }
                                    }
                                } else {
                                    extractTopicItem(obj, results)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Fallback continues below
        }

        // 2. If Instant Answer returned insufficient results, try DuckDuckGo Lite HTML (POST)
        if (results.size < maxResults) {
            try {
                val postBody = FormBody.Builder()
                    .add("q", trimmed)
                    .build()

                val liteReq = Request.Builder()
                    .url("https://lite.duckduckgo.com/lite/")
                    .post(postBody)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                client.newCall(liteReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        parseDuckDuckGoLiteHtml(html, maxResults, results)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Fallback continues below
            }
        }

        // 3. If still empty, try DuckDuckGo Standard HTML
        if (results.isEmpty()) {
            try {
                val htmlUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
                val htmlReq = Request.Builder()
                    .url(htmlUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                client.newCall(htmlReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val html = response.body?.string().orEmpty()
                        parseDuckDuckGoStandardHtml(html, maxResults, results)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Fallback continues below
            }
        }

        // 4. Wikipedia OpenSearch fallback if search engines returned no results
        if (results.isEmpty()) {
            searchWikipediaFallback(encodedQuery, maxResults, results)
        }

        return@withContext results.take(maxResults)
    }

    /**
     * Reads full article/documentation content as clean Markdown in the background
     * using Jina Reader (`https://r.jina.ai/<url>`) or direct HTTP extraction,
     * with ZERO Chrome browser popup and ZERO user Google account cookies.
     */
    suspend fun readFullWebpageMarkdown(targetUrl: String, maxChars: Int = 4000): String = withContext(Dispatchers.IO) {
        val cleanUrl = if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
            targetUrl.trim()
        } else {
            "https://${targetUrl.trim()}"
        }
        try {
            val jinaReq = Request.Builder()
                .url("https://r.jina.ai/$cleanUrl")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; JARVIS Background Researcher)")
                .addHeader("X-Return-Format", "markdown")
                .build()
            client.newCall(jinaReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = resp.body?.string().orEmpty().trim()
                    if (text.length > 40) {
                        return@withContext text.take(maxChars)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }

        return@withContext try {
            val directReq = Request.Builder()
                .url(cleanUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; JARVIS Background Researcher)")
                .build()
            client.newCall(directReq).execute().use { resp ->
                val rawHtml = resp.body?.string().orEmpty()
                cleanHtml(rawHtml).take(maxChars).ifBlank { "Unable to extract text from $cleanUrl (HTTP ${resp.code})" }
            }
        } catch (e: Exception) {
            "Background webpage fetch error for $cleanUrl: ${e.message}"
        }
    }

    private fun extractTopicItem(obj: JsonObject, results: MutableList<SearchResultItem>) {
        val text = obj.get("Text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
        val firstUrl = obj.get("FirstURL")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
        if (text.isNotBlank()) {
            results.add(
                SearchResultItem(
                    title = text.take(80),
                    snippet = text,
                    url = firstUrl
                )
            )
        }
    }

    private fun parseDuckDuckGoLiteHtml(
        html: String,
        maxResults: Int,
        destination: MutableList<SearchResultItem>
    ) {
        val linkRegex = Regex("""<a[^>]*class=['"]result-link['"][^>]*href=['"]([^'"]*)['"][^>]*>(.*?)</a>|<a[^>]*href=['"]([^'"]*)['"][^>]*class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<td[^>]*class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)

        val linkMatches = linkRegex.findAll(html).map { m ->
            val url = m.groups[1]?.value ?: m.groups[3]?.value.orEmpty()
            val title = cleanHtml(m.groups[2]?.value ?: m.groups[4]?.value.orEmpty())
            Pair(title, url)
        }.toList()

        val snippetMatches = snippetRegex.findAll(html).map { m ->
            cleanHtml(m.groupValues[1])
        }.toList()

        val count = minOf(linkMatches.size, maxResults)
        for (i in 0 until count) {
            val (title, rawUrl) = linkMatches[i]
            val snippet = if (i < snippetMatches.size) snippetMatches[i] else ""
            val actualUrl = extractActualUrl(rawUrl)
            if (title.isNotBlank() && actualUrl.isNotBlank() && !destination.any { it.url == actualUrl }) {
                destination.add(SearchResultItem(title = title, snippet = snippet, url = actualUrl))
            }
        }
    }

    private fun parseDuckDuckGoStandardHtml(
        html: String,
        maxResults: Int,
        destination: MutableList<SearchResultItem>
    ) {
        val titleRegex = Regex("""<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]*)"[^>]*>(.*?)</a>|<a[^>]*href="([^"]*)"[^>]*class="[^"]*result__a[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val titleMatches = titleRegex.findAll(html).map { m ->
            val href = m.groups[1]?.value ?: m.groups[3]?.value.orEmpty()
            val title = cleanHtml(m.groups[2]?.value ?: m.groups[4]?.value.orEmpty())
            Pair(title, href)
        }.toList()

        val snippetMatches = snippetRegex.findAll(html).map { m ->
            cleanHtml(m.groupValues[1])
        }.toList()

        val count = minOf(titleMatches.size, maxResults)
        for (i in 0 until count) {
            val (title, rawUrl) = titleMatches[i]
            val snippet = if (i < snippetMatches.size) snippetMatches[i] else ""
            val actualUrl = extractActualUrl(rawUrl)
            if (title.isNotBlank() && actualUrl.isNotBlank() && !destination.any { it.url == actualUrl }) {
                destination.add(SearchResultItem(title = title, snippet = snippet, url = actualUrl))
            }
        }
    }

    private fun searchWikipediaFallback(
        encodedQuery: String,
        maxResults: Int,
        destination: MutableList<SearchResultItem>
    ) {
        try {
            val wikiUrl = "https://en.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=$maxResults&namespace=0&format=json"
            val request = Request.Builder()
                .url(wikiUrl)
                .addHeader("User-Agent", "JARVIS-Assistant/1.0 (Android Agent)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val array = try { gson.fromJson(body, JsonArray::class.java) } catch (e: Exception) { null }
                    if (array != null && array.size() >= 4) {
                        val titles = array.get(1).asJsonArray
                        val descriptions = array.get(2).asJsonArray
                        val urls = array.get(3).asJsonArray

                        val count = minOf(titles.size(), maxResults)
                        for (i in 0 until count) {
                            val title = titles.get(i)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                            val desc = descriptions.get(i)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                            val url = urls.get(i)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                            if (title.isNotBlank() && url.isNotBlank() && !destination.any { it.url == url }) {
                                destination.add(
                                    SearchResultItem(
                                        title = title,
                                        snippet = if (desc.isNotBlank()) desc else title,
                                        url = url
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Ignore Wikipedia fallback errors
        }
    }

    private fun extractActualUrl(href: String): String {
        return try {
            val uddgIndex = href.indexOf("uddg=")
            if (uddgIndex != -1) {
                val rest = href.substring(uddgIndex + 5)
                val ampIndex = rest.indexOf('&')
                val encoded = if (ampIndex != -1) rest.substring(0, ampIndex) else rest
                URLDecoder.decode(encoded, "UTF-8")
            } else if (href.startsWith("//")) {
                "https:$href"
            } else if (href.startsWith("/")) {
                "https://duckduckgo.com$href"
            } else {
                href
            }
        } catch (e: Exception) {
            href
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
