---
name: "live-web-search"
description: "Zero-key 4-tier live web search (DuckDuckGo HTML, Instant Answer API, Wikipedia REST, Reddit JSON) for real-time news, facts, weather, and prices."
trigger: "When user asks about current events, news, weather, live scores, prices, or facts after knowledge cutoff"
---

# 🌐 Live Web Search Skill (Free 4-Tier Engine)

1. Always invoke `FreeLiveSearchClient.search(query)` first before consuming paid LLM tokens for factual queries.
2. Fallback chain:
   - Tier 1: DuckDuckGo Instant Answer JSON API
   - Tier 2: DuckDuckGo HTML Scraper
   - Tier 3: Wikipedia REST Summary API
   - Tier 4: Reddit JSON Search
3. Summarize top 3 snippets into a concise, voice-friendly response.
