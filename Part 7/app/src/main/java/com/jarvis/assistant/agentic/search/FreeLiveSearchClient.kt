package com.jarvis.assistant.agentic.search

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * using DuckDuckGo HTML / JSON endpoint with clean parsing for JARVIS agents.
 */
class FreeLiveSearchClient(
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()

    /**
     * Searches the live web for a query and returns structured results.
     */
    suspend fun search(query: String, maxResults: Int = 5): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResultItem>()

        // Try DuckDuckGo Instant Answer API first
        val apiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; JARVIS Assistant)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = gson.fromJson(body, JsonObject::class.java)

                    val abstractText = json.get("AbstractText")?.asString.orEmpty()
                    val abstractUrl = json.get("AbstractURL")?.asString.orEmpty()
                    val heading = json.get("Heading")?.asString.orEmpty()

                    if (abstractText.isNotBlank()) {
                        results.add(
                            SearchResultItem(
                                title = if (heading.isNotBlank()) heading else query,
                                snippet = abstractText,
                                url = abstractUrl
                            )
                        )
                    }

                    // Check RelatedTopics
                    val relatedTopics = json.getAsJsonArray("RelatedTopics")
                    relatedTopics?.forEach { elem ->
                        if (results.size >= maxResults) return@forEach
                        if (elem.isJsonObject) {
                            val obj = elem.asJsonObject
                            val text = obj.get("Text")?.asString.orEmpty()
                            val firstUrl = obj.get("FirstURL")?.asString.orEmpty()
                            if (text.isNotBlank()) {
                                results.add(
                                    SearchResultItem(
                                        title = text.take(60),
                                        snippet = text,
                                        url = firstUrl
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback continues below
        }

        // If Instant Answer returned few results, fallback to DuckDuckGo HTML Lite search
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
                        parseDuckDuckGoHtml(html, maxResults, results)
                    }
                }
            } catch (e: Exception) {
                // Return whatever was collected
            }
        }

        return@withContext results
    }

    private fun parseDuckDuckGoHtml(
        html: String,
        maxResults: Int,
        destination: MutableList<SearchResultItem>
    ) {
        // Lightweight regex parser to extract results without heavy external HTML libs
        val snippetRegex = Regex("""<a class="result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("""<a class="result__url[^>]*href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

        val snippets = snippetRegex.findAll(html).map { cleanHtml(it.groupValues[1]) }.toList()
        val titles = titleRegex.findAll(html).map {
            Pair(cleanHtml(it.groupValues[2]), it.groupValues[1])
        }.toList()

        val count = minOf(snippets.size, titles.size, maxResults)
        for (i in 0 until count) {
            val (title, url) = titles[i]
            val snippet = snippets[i]
            destination.add(SearchResultItem(title = title, snippet = snippet, url = url))
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
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
