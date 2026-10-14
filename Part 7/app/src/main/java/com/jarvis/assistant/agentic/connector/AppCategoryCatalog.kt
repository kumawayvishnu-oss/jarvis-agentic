package com.jarvis.assistant.agentic.connector

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standardized app categories for classification and intent routing.
 */
enum class AppCategory {
    SOCIAL,
    COMMUNICATION,
    UTILITY,
    PRODUCTIVITY,
    MEDIA,
    NAVIGATION,
    FINANCE,
    SHOPPING,
    ENTERTAINMENT,
    SYSTEM_SETTINGS,
    TOOLS,
    OTHER
}

/**
 * Structured schema defining a specific action that an application can perform.
 */
data class AppActionSchema(
    val actionName: String,
    val description: String,
    val uriTemplate: String? = null,
    val intentAction: String = Intent.ACTION_VIEW,
    val intentType: String? = null,
    val requiredParams: List<String> = emptyList(),
    val optionalParams: List<String> = emptyList()
) {
    /**
     * Builds an Android Intent using the parameter map according to this schema.
     */
    fun buildIntent(params: Map<String, String> = emptyMap()): Intent? {
        val intent = Intent(intentAction)
        if (intentType != null) {
            intent.type = intentType
        }

        if (uriTemplate != null) {
            var uriString = uriTemplate
            for ((key, value) in params) {
                val encoded = try {
                    URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
                } catch (_: Exception) {
                    value
                }
                uriString = uriString.replace("{$key}", encoded)
            }
            // Check if any required placeholders remain unreplaced
            if (requiredParams.any { uriString.contains("{$it}") }) {
                return null
            }
            intent.data = Uri.parse(uriString)
        }

        for ((key, value) in params) {
            intent.putExtra(key, value)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}

/**
 * Represents an application entry with package identifier, category, aliases, and action schemas.
 */
data class AppEntry(
    val packageName: String,
    val appName: String,
    val category: AppCategory,
    val aliases: List<String> = emptyList(),
    val uriSchemes: List<String> = emptyList(),
    val actions: List<AppActionSchema> = emptyList(),
    val isSystemApp: Boolean = false,
    val isInstalled: Boolean = false
) {
    fun findAction(actionName: String): AppActionSchema? {
        return actions.firstOrNull { it.actionName.equals(actionName, ignoreCase = true) }
    }
}

/**
 * AppCategoryCatalog: Comprehensive registry of deep intents, URI schemes,
 * package names, and action schemas for top apps across Android categories.
 * Also houses the DynamicPackageDetector for 1500+ installed apps.
 */
object AppCategoryCatalog {
    private const val TAG = "AppCategoryCatalog"

    // -------------------------------------------------------------
    // Curated Action Schemas for Popular Apps
    // -------------------------------------------------------------

    private val YOUTUBE_ACTIONS = listOf(
        AppActionSchema(
            actionName = "watch",
            description = "Watch a YouTube video by ID",
            uriTemplate = "vnd.youtube:{videoId}",
            requiredParams = listOf("videoId")
        ),
        AppActionSchema(
            actionName = "search",
            description = "Search YouTube for videos",
            uriTemplate = "https://www.youtube.com/results?search_query={query}",
            requiredParams = listOf("query")
        ),
        AppActionSchema(
            actionName = "channel",
            description = "Open YouTube channel",
            uriTemplate = "https://www.youtube.com/@{channel}",
            requiredParams = listOf("channel")
        ),
        AppActionSchema(
            actionName = "shorts",
            description = "Open YouTube shorts",
            uriTemplate = "https://www.youtube.com/shorts/{videoId}",
            requiredParams = listOf("videoId")
        )
    )

    private val INSTAGRAM_ACTIONS = listOf(
        AppActionSchema(
            actionName = "user_profile",
            description = "Open an Instagram user profile",
            uriTemplate = "instagram://user?username={username}",
            requiredParams = listOf("username")
        ),
        AppActionSchema(
            actionName = "direct_inbox",
            description = "Open Instagram Direct Messages inbox",
            uriTemplate = "instagram://direct-inbox"
        ),
        AppActionSchema(
            actionName = "camera",
            description = "Open Instagram Story camera",
            uriTemplate = "instagram://camera"
        ),
        AppActionSchema(
            actionName = "explore",
            description = "Open Instagram Explore tab",
            uriTemplate = "instagram://explore"
        ),
        AppActionSchema(
            actionName = "view_post",
            description = "View specific Instagram post",
            uriTemplate = "instagram://media?id={mediaId}",
            requiredParams = listOf("mediaId")
        )
    )

    private val WHATSAPP_ACTIONS = listOf(
        AppActionSchema(
            actionName = "send_message",
            description = "Send a WhatsApp text message to a phone number",
            uriTemplate = "https://api.whatsapp.com/send?phone={phone}&text={text}",
            requiredParams = listOf("phone", "text")
        ),
        AppActionSchema(
            actionName = "open_chat",
            description = "Open WhatsApp chat with a contact",
            uriTemplate = "https://wa.me/{phone}",
            requiredParams = listOf("phone")
        ),
        AppActionSchema(
            actionName = "view_status",
            description = "Open WhatsApp status updates tab",
            uriTemplate = "whatsapp://status"
        )
    )

    private val TWITTER_ACTIONS = listOf(
        AppActionSchema(
            actionName = "user_profile",
            description = "Open Twitter / X user profile",
            uriTemplate = "twitter://user?screen_name={username}",
            requiredParams = listOf("username")
        ),
        AppActionSchema(
            actionName = "post_tweet",
            description = "Compose a new tweet / post",
            uriTemplate = "twitter://post?message={text}",
            requiredParams = listOf("text")
        ),
        AppActionSchema(
            actionName = "search",
            description = "Search Twitter / X for query",
            uriTemplate = "twitter://search?query={query}",
            requiredParams = listOf("query")
        ),
        AppActionSchema(
            actionName = "timeline",
            description = "Open Twitter / X home timeline",
            uriTemplate = "twitter://timeline"
        )
    )

    private val TELEGRAM_ACTIONS = listOf(
        AppActionSchema(
            actionName = "open_chat",
            description = "Open a Telegram chat or channel by username",
            uriTemplate = "tg://resolve?domain={username}",
            requiredParams = listOf("username")
        ),
        AppActionSchema(
            actionName = "share_message",
            description = "Share text or URL into Telegram",
            uriTemplate = "tg://msg_url?url={url}&text={text}",
            optionalParams = listOf("url", "text")
        )
    )

    private val CHROME_ACTIONS = listOf(
        AppActionSchema(
            actionName = "open_url",
            description = "Open a web URL in Google Chrome",
            uriTemplate = "https://{url}",
            requiredParams = listOf("url")
        ),
        AppActionSchema(
            actionName = "search",
            description = "Search Google on Chrome",
            uriTemplate = "https://www.google.com/search?q={query}",
            requiredParams = listOf("query")
        )
    )

    private val GMAIL_ACTIONS = listOf(
        AppActionSchema(
            actionName = "compose",
            description = "Compose a new email in Gmail",
            uriTemplate = "mailto:{to}?subject={subject}&body={body}",
            intentAction = Intent.ACTION_SENDTO,
            optionalParams = listOf("to", "subject", "body")
        )
    )

    private val MAPS_ACTIONS = listOf(
        AppActionSchema(
            actionName = "navigate",
            description = "Start Google Maps turn-by-turn navigation",
            uriTemplate = "google.navigation:q={query}&mode={mode}",
            requiredParams = listOf("query"),
            optionalParams = listOf("mode") // d (drive), w (walk), b (bike)
        ),
        AppActionSchema(
            actionName = "search_place",
            description = "Search for a place or address on Google Maps",
            uriTemplate = "geo:0,0?q={query}",
            requiredParams = listOf("query")
        ),
        AppActionSchema(
            actionName = "street_view",
            description = "Open Google Street View at latitude/longitude",
            uriTemplate = "google.streetview:cbll={lat},{lng}",
            requiredParams = listOf("lat", "lng")
        )
    )

    private val CAMERA_ACTIONS = listOf(
        AppActionSchema(
            actionName = "take_photo",
            description = "Open Camera to take a photo",
            intentAction = MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        ),
        AppActionSchema(
            actionName = "record_video",
            description = "Open Camera to record a video",
            intentAction = MediaStore.INTENT_ACTION_VIDEO_CAMERA
        )
    )

    private val SPOTIFY_ACTIONS = listOf(
        AppActionSchema(
            actionName = "search",
            description = "Search music on Spotify",
            uriTemplate = "spotify:search:{query}",
            requiredParams = listOf("query")
        ),
        AppActionSchema(
            actionName = "play_track",
            description = "Play track on Spotify",
            uriTemplate = "spotify:track:{trackId}",
            requiredParams = listOf("trackId")
        ),
        AppActionSchema(
            actionName = "open_playlist",
            description = "Open Spotify playlist",
            uriTemplate = "spotify:playlist:{playlistId}",
            requiredParams = listOf("playlistId")
        )
    )

    private val CLOCK_ACTIONS = listOf(
        AppActionSchema(
            actionName = "set_alarm",
            description = "Set an alarm clock",
            intentAction = AlarmClock.ACTION_SET_ALARM,
            optionalParams = listOf(AlarmClock.EXTRA_HOUR, AlarmClock.EXTRA_MINUTES, AlarmClock.EXTRA_MESSAGE)
        ),
        AppActionSchema(
            actionName = "set_timer",
            description = "Set a countdown timer",
            intentAction = AlarmClock.ACTION_SET_TIMER,
            optionalParams = listOf(AlarmClock.EXTRA_LENGTH, AlarmClock.EXTRA_MESSAGE)
        ),
        AppActionSchema(
            actionName = "show_alarms",
            description = "Show all alarms",
            intentAction = AlarmClock.ACTION_SHOW_ALARMS
        )
    )

    private val PHONE_ACTIONS = listOf(
        AppActionSchema(
            actionName = "dial",
            description = "Open dialer with phone number",
            uriTemplate = "tel:{phone}",
            intentAction = Intent.ACTION_DIAL,
            requiredParams = listOf("phone")
        ),
        AppActionSchema(
            actionName = "call",
            description = "Directly place a phone call",
            uriTemplate = "tel:{phone}",
            intentAction = Intent.ACTION_CALL,
            requiredParams = listOf("phone")
        )
    )

    private val SMS_ACTIONS = listOf(
        AppActionSchema(
            actionName = "send_sms",
            description = "Compose SMS to phone number",
            uriTemplate = "smsto:{phone}",
            intentAction = Intent.ACTION_SENDTO,
            requiredParams = listOf("phone"),
            optionalParams = listOf("sms_body")
        )
    )

    // -------------------------------------------------------------
    // Catalog of Curated Top Applications
    // -------------------------------------------------------------

    private val CURATED_CATALOG = listOf(
        // Social
        AppEntry(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            category = AppCategory.SOCIAL,
            aliases = listOf("yt", "youtube", "video", "videos"),
            uriSchemes = listOf("vnd.youtube", "https://youtube.com", "https://youtu.be"),
            actions = YOUTUBE_ACTIONS
        ),
        AppEntry(
            packageName = "com.instagram.android",
            appName = "Instagram",
            category = AppCategory.SOCIAL,
            aliases = listOf("insta", "instagram", "ig", "reels"),
            uriSchemes = listOf("instagram"),
            actions = INSTAGRAM_ACTIONS
        ),
        AppEntry(
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("wa", "whatsapp", "whats app"),
            uriSchemes = listOf("whatsapp", "https://wa.me", "https://api.whatsapp.com"),
            actions = WHATSAPP_ACTIONS
        ),
        AppEntry(
            packageName = "com.whatsapp.w4b",
            appName = "WhatsApp Business",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("wa business", "whatsapp business", "w4b"),
            uriSchemes = listOf("whatsapp", "https://wa.me"),
            actions = WHATSAPP_ACTIONS
        ),
        AppEntry(
            packageName = "com.twitter.android",
            appName = "Twitter / X",
            category = AppCategory.SOCIAL,
            aliases = listOf("twitter", "x", "tweet"),
            uriSchemes = listOf("twitter", "x"),
            actions = TWITTER_ACTIONS
        ),
        AppEntry(
            packageName = "org.telegram.messenger",
            appName = "Telegram",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("tg", "telegram"),
            uriSchemes = listOf("tg", "https://t.me"),
            actions = TELEGRAM_ACTIONS
        ),
        AppEntry(
            packageName = "com.facebook.katana",
            appName = "Facebook",
            category = AppCategory.SOCIAL,
            aliases = listOf("fb", "facebook"),
            uriSchemes = listOf("fb")
        ),
        AppEntry(
            packageName = "com.facebook.orca",
            appName = "Messenger",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("messenger", "fb messenger"),
            uriSchemes = listOf("fb-messenger")
        ),
        AppEntry(
            packageName = "com.snapchat.android",
            appName = "Snapchat",
            category = AppCategory.SOCIAL,
            aliases = listOf("snap", "snapchat"),
            uriSchemes = listOf("snapchat")
        ),
        AppEntry(
            packageName = "com.reddit.frontpage",
            appName = "Reddit",
            category = AppCategory.SOCIAL,
            aliases = listOf("reddit", "subreddit"),
            uriSchemes = listOf("reddit")
        ),
        AppEntry(
            packageName = "com.linkedin.android",
            appName = "LinkedIn",
            category = AppCategory.SOCIAL,
            aliases = listOf("linkedin", "in"),
            uriSchemes = listOf("linkedin")
        ),
        AppEntry(
            packageName = "com.discord",
            appName = "Discord",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("discord", "dc"),
            uriSchemes = listOf("discord")
        ),

        // Utility
        AppEntry(
            packageName = "com.android.chrome",
            appName = "Google Chrome",
            category = AppCategory.UTILITY,
            aliases = listOf("chrome", "browser", "google chrome", "web"),
            uriSchemes = listOf("http", "https", "googlechrome"),
            actions = CHROME_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.gm",
            appName = "Gmail",
            category = AppCategory.UTILITY,
            aliases = listOf("gmail", "email", "mail", "google mail"),
            uriSchemes = listOf("mailto"),
            actions = GMAIL_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.apps.maps",
            appName = "Google Maps",
            category = AppCategory.NAVIGATION,
            aliases = listOf("maps", "map", "navigation", "nav", "gps", "google maps"),
            uriSchemes = listOf("geo", "google.navigation", "google.streetview"),
            actions = MAPS_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.GoogleCamera",
            appName = "Camera",
            category = AppCategory.UTILITY,
            aliases = listOf("camera", "cam", "photo", "video camera"),
            actions = CAMERA_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.apps.photos",
            appName = "Google Photos",
            category = AppCategory.MEDIA,
            aliases = listOf("photos", "gallery", "google photos", "pics", "images")
        ),
        AppEntry(
            packageName = "com.google.android.dialer",
            appName = "Phone",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("phone", "dialer", "call", "telephone"),
            uriSchemes = listOf("tel"),
            actions = PHONE_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.apps.messaging",
            appName = "Messages",
            category = AppCategory.COMMUNICATION,
            aliases = listOf("messages", "sms", "text messages", "messaging"),
            uriSchemes = listOf("smsto", "sms"),
            actions = SMS_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.deskclock",
            appName = "Clock",
            category = AppCategory.UTILITY,
            aliases = listOf("clock", "alarm", "timer", "stopwatch"),
            actions = CLOCK_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.calculator",
            appName = "Calculator",
            category = AppCategory.UTILITY,
            aliases = listOf("calculator", "calc")
        ),

        // Productivity
        AppEntry(
            packageName = "com.google.android.keep",
            appName = "Google Keep",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("keep", "notes", "google keep", "memo")
        ),
        AppEntry(
            packageName = "com.google.android.calendar",
            appName = "Google Calendar",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("calendar", "schedule", "events", "google calendar")
        ),
        AppEntry(
            packageName = "com.google.android.apps.docs",
            appName = "Google Drive",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("drive", "google drive", "cloud")
        ),
        AppEntry(
            packageName = "com.google.android.apps.docs.editors.docs",
            appName = "Google Docs",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("docs", "google docs", "word")
        ),
        AppEntry(
            packageName = "com.google.android.apps.docs.editors.sheets",
            appName = "Google Sheets",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("sheets", "google sheets", "excel", "spreadsheet")
        ),
        AppEntry(
            packageName = "so.notion.client",
            appName = "Notion",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("notion", "workspace")
        ),
        AppEntry(
            packageName = "com.Slack",
            appName = "Slack",
            category = AppCategory.PRODUCTIVITY,
            aliases = listOf("slack")
        ),

        // Media
        AppEntry(
            packageName = "com.spotify.music",
            appName = "Spotify",
            category = AppCategory.MEDIA,
            aliases = listOf("spotify", "music", "songs"),
            uriSchemes = listOf("spotify"),
            actions = SPOTIFY_ACTIONS
        ),
        AppEntry(
            packageName = "com.google.android.apps.youtube.music",
            appName = "YouTube Music",
            category = AppCategory.MEDIA,
            aliases = listOf("yt music", "youtube music")
        ),
        AppEntry(
            packageName = "com.netflix.mediaclient",
            appName = "Netflix",
            category = AppCategory.ENTERTAINMENT,
            aliases = listOf("netflix", "movies", "shows")
        ),
        AppEntry(
            packageName = "org.videolan.vlc",
            appName = "VLC",
            category = AppCategory.MEDIA,
            aliases = listOf("vlc", "vlc player", "media player")
        ),

        // System Settings
        AppEntry(
            packageName = "com.android.settings",
            appName = "Settings",
            category = AppCategory.SYSTEM_SETTINGS,
            aliases = listOf("settings", "system settings", "phone settings", "preferences"),
            actions = listOf(
                AppActionSchema("wifi", "Open Wi-Fi Settings", intentAction = Settings.ACTION_WIFI_SETTINGS),
                AppActionSchema("bluetooth", "Open Bluetooth Settings", intentAction = Settings.ACTION_BLUETOOTH_SETTINGS),
                AppActionSchema("display", "Open Display Settings", intentAction = Settings.ACTION_DISPLAY_SETTINGS),
                AppActionSchema("sound", "Open Sound & Vibration Settings", intentAction = Settings.ACTION_SOUND_SETTINGS),
                AppActionSchema("battery", "Open Battery Settings", intentAction = Settings.ACTION_BATTERY_SAVER_SETTINGS),
                AppActionSchema("apps", "Open Installed Applications Settings", intentAction = Settings.ACTION_APPLICATION_SETTINGS),
                AppActionSchema("accessibility", "Open Accessibility Settings", intentAction = Settings.ACTION_ACCESSIBILITY_SETTINGS),
                AppActionSchema("date_time", "Open Date and Time Settings", intentAction = Settings.ACTION_DATE_SETTINGS),
                AppActionSchema("location", "Open Location Settings", intentAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                AppActionSchema("storage", "Open Internal Storage Settings", intentAction = Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
                AppActionSchema("security", "Open Security & Privacy Settings", intentAction = Settings.ACTION_SECURITY_SETTINGS),
                AppActionSchema("airplane_mode", "Open Network & Airplane Mode Settings", intentAction = Settings.ACTION_WIRELESS_SETTINGS)
            ),
            isSystemApp = true
        )
    )

    /**
     * Map of curated apps indexed by package name.
     */
    val curatedCatalogMap: Map<String, AppEntry> = CURATED_CATALOG.associateBy { it.packageName }

    /**
     * Map of curated aliases pointing to package names.
     */
    val aliasMap: Map<String, String> = buildMap {
        for (entry in CURATED_CATALOG) {
            put(entry.appName.lowercase(Locale.ROOT), entry.packageName)
            for (alias in entry.aliases) {
                put(alias.lowercase(Locale.ROOT), entry.packageName)
            }
        }
    }

    // -------------------------------------------------------------
    // Dynamic Package Detector (1500+ Installed Apps)
    // -------------------------------------------------------------

    /**
     * Dynamic package detector that queries the Android PackageManager
     * to index, categorize, and detect all 1500+ installed applications on the device.
     */
    class DynamicPackageDetector(private val context: Context) {
        private val installedAppsCache = ConcurrentHashMap<String, AppEntry>()
        private val aliasIndex = ConcurrentHashMap<String, String>()
        private val isInitialized = AtomicBoolean(false)

        /**
         * Scans and indexes all installed apps on the device.
         * Runs efficiently and safely caches metadata.
         */
        @Synchronized
        fun refreshInstalledApps(): Int {
            try {
                val pm = context.packageManager
                val flags = PackageManager.GET_META_DATA
                val installedPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(flags)
                }

                installedAppsCache.clear()
                aliasIndex.clear()

                for (appInfo in installedPackages) {
                    val pkgName = appInfo.packageName
                    val appLabel = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        pkgName.substringAfterLast('.')
                    }

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val category = inferCategory(appInfo)

                    // Merge with curated actions and schemes if available
                    val curated = curatedCatalogMap[pkgName]
                    val actions = curated?.actions ?: emptyList()
                    val uriSchemes = curated?.uriSchemes ?: emptyList()
                    val aliases = (curated?.aliases ?: emptyList()).toMutableList().apply {
                        add(appLabel.lowercase(Locale.ROOT))
                        add(pkgName.lowercase(Locale.ROOT))
                        val simpleName = appLabel.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                        if (simpleName.isNotEmpty()) add(simpleName)
                    }.distinct()

                    val entry = AppEntry(
                        packageName = pkgName,
                        appName = appLabel,
                        category = category,
                        aliases = aliases,
                        uriSchemes = uriSchemes,
                        actions = actions,
                        isSystemApp = isSystem,
                        isInstalled = true
                    )

                    installedAppsCache[pkgName] = entry

                    // Index aliases for instant sub-millisecond retrieval
                    for (alias in aliases) {
                        aliasIndex[alias] = pkgName
                    }
                }

                isInitialized.set(true)
                Log.i(TAG, "DynamicPackageDetector indexed ${installedAppsCache.size} installed applications.")
                return installedAppsCache.size
            } catch (e: Exception) {
                Log.e(TAG, "Error indexing installed apps: ${e.message}", e)
                return 0
            }
        }

        /**
         * Ensures that the detector has scanned installed packages at least once.
         */
        fun ensureInitialized() {
            if (!isInitialized.get() || installedAppsCache.isEmpty()) {
                refreshInstalledApps()
            }
        }

        /**
         * Resolves an application entry by query (package name, app name, or alias).
         */
        fun findApp(query: String): AppEntry? {
            ensureInitialized()
            val cleanQuery = query.trim().lowercase(Locale.ROOT)
            if (cleanQuery.isEmpty()) return null

            // 1. Direct package name match
            installedAppsCache[cleanQuery]?.let { return it }

            // 2. Exact alias match
            aliasIndex[cleanQuery]?.let { pkg ->
                installedAppsCache[pkg]?.let { return it }
            }

            // 3. Fallback to curated static alias map
            aliasMap[cleanQuery]?.let { pkg ->
                installedAppsCache[pkg]?.let { return it }
            }

            // 4. Normalized alphanumeric match
            val stripped = cleanQuery.replace(Regex("[^a-z0-9]"), "")
            aliasIndex[stripped]?.let { pkg ->
                installedAppsCache[pkg]?.let { return it }
            }

            // 5. Prefix or contains matching across installed apps
            val prefixMatch = installedAppsCache.values.firstOrNull { entry ->
                entry.appName.lowercase(Locale.ROOT).startsWith(cleanQuery) ||
                entry.aliases.any { it.startsWith(cleanQuery) }
            }
            if (prefixMatch != null) return prefixMatch

            val containsMatch = installedAppsCache.values.firstOrNull { entry ->
                entry.appName.lowercase(Locale.ROOT).contains(cleanQuery) ||
                entry.aliases.any { it.contains(cleanQuery) }
            }
            return containsMatch
        }

        /**
         * Searches apps by text query and returns ranked matching results.
         */
        fun searchApps(query: String, limit: Int = 10): List<AppEntry> {
            ensureInitialized()
            val clean = query.trim().lowercase(Locale.ROOT)
            if (clean.isEmpty()) return emptyList()

            return installedAppsCache.values
                .mapNotNull { entry ->
                    val score = computeMatchScore(entry, clean)
                    if (score > 0) Pair(entry, score) else null
                }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        }

        /**
         * Returns all indexed installed apps.
         */
        fun getAllInstalledApps(): List<AppEntry> {
            ensureInitialized()
            return installedAppsCache.values.toList()
        }

        /**
         * Returns apps filtered by category.
         */
        fun getAppsByCategory(category: AppCategory): List<AppEntry> {
            ensureInitialized()
            return installedAppsCache.values.filter { it.category == category }
        }

        /**
         * Computes a relevance match score for search ranking.
         */
        private fun computeMatchScore(entry: AppEntry, query: String): Int {
            val nameLower = entry.appName.lowercase(Locale.ROOT)
            val pkgLower = entry.packageName.lowercase(Locale.ROOT)

            if (nameLower == query) return 100
            if (pkgLower == query) return 95
            if (entry.aliases.any { it == query }) return 90
            if (nameLower.startsWith(query)) return 80
            if (nameLower.contains(query)) return 60
            if (entry.aliases.any { it.contains(query) }) return 50
            if (pkgLower.contains(query)) return 40
            return 0
        }

        /**
         * Infers the category of an application based on Android category flags and heuristics.
         */
        private fun inferCategory(appInfo: ApplicationInfo): AppCategory {
            val pkg = appInfo.packageName.lowercase(Locale.ROOT)

            // Check curated catalog first
            curatedCatalogMap[appInfo.packageName]?.let { return it.category }

            // Check native category (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (appInfo.category) {
                    ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MEDIA
                    ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.MEDIA
                    ApplicationInfo.CATEGORY_IMAGE -> return AppCategory.MEDIA
                    ApplicationInfo.CATEGORY_SOCIAL -> return AppCategory.SOCIAL
                    ApplicationInfo.CATEGORY_NEWS -> return AppCategory.MEDIA
                    ApplicationInfo.CATEGORY_MAPS -> return AppCategory.NAVIGATION
                    ApplicationInfo.CATEGORY_PRODUCTIVITY -> return AppCategory.PRODUCTIVITY
                    ApplicationInfo.CATEGORY_GAME -> return AppCategory.ENTERTAINMENT
                }
            }

            // Heuristic categorization based on package name keywords
            return when {
                pkg.contains("chat") || pkg.contains("message") || pkg.contains("talk") || pkg.contains("sms") ->
                    AppCategory.COMMUNICATION
                pkg.contains("social") || pkg.contains("insta") || pkg.contains("tweet") || pkg.contains("face") || pkg.contains("share") ->
                    AppCategory.SOCIAL
                pkg.contains("music") || pkg.contains("audio") || pkg.contains("player") || pkg.contains("video") || pkg.contains("tv") ->
                    AppCategory.MEDIA
                pkg.contains("map") || pkg.contains("nav") || pkg.contains("gps") || pkg.contains("locat") ->
                    AppCategory.NAVIGATION
                pkg.contains("bank") || pkg.contains("pay") || pkg.contains("wallet") || pkg.contains("upi") || pkg.contains("money") ->
                    AppCategory.FINANCE
                pkg.contains("shop") || pkg.contains("store") || pkg.contains("cart") || pkg.contains("buy") || pkg.contains("mart") ->
                    AppCategory.SHOPPING
                pkg.contains("doc") || pkg.contains("sheet") || pkg.contains("note") || pkg.contains("task") || pkg.contains("office") ->
                    AppCategory.PRODUCTIVITY
                pkg.contains("setting") || pkg.contains("system") ->
                    AppCategory.SYSTEM_SETTINGS
                pkg.contains("tool") || pkg.contains("util") || pkg.contains("cleaner") || pkg.contains("calc") ->
                    AppCategory.UTILITY
                pkg.contains("game") || pkg.contains("play") ->
                    AppCategory.ENTERTAINMENT
                else -> AppCategory.OTHER
            }
        }
    }

    /**
     * Singleton instance holder for the detector.
     */
    @Volatile
    private var detectorInstance: DynamicPackageDetector? = null

    fun getDetector(context: Context): DynamicPackageDetector {
        return detectorInstance ?: synchronized(this) {
            detectorInstance ?: DynamicPackageDetector(context.applicationContext).also {
                detectorInstance = it
            }
        }
    }
}
