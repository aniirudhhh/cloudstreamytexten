package com.example.youtubeprovider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

/**
 * CloudStream3 plugin entry point for the YouTubeProvider extension.
 *
 * CloudStream discovers this class via the [CloudstreamPlugin] annotation and the
 * `pluginClassName` value set in the sub-project's build.gradle.kts.  The two must
 * match exactly (including package name resolution) or the extension will silently
 * fail to load at runtime.
 *
 * The [load] function is called once when CloudStream initialises the extension.
 * It registers [YouTubeProvider] with the CloudStream content resolver so that
 * home page rows, search, and video loading are routed through it.
 */
@CloudstreamPlugin
class YouTubeProviderPlugin : Plugin() {

    /**
     * Called by CloudStream when the plugin is loaded.
     *
     * We register a single [YouTubeProvider] instance here.  If you later add
     * more providers (e.g. a separate InvidiousMusicProvider for audio-focused
     * browsing), call [registerMainAPI] for each one.
     *
     * @param context  The Android application context provided by CloudStream.
     *                 Not currently used by YouTubeProvider, but kept as a parameter
     *                 for future use (e.g. reading SharedPreferences for user settings).
     */
    override fun load(context: Context) {
        // Instantiate and register the main provider.
        // registerMainAPI() adds it to CloudStream's internal provider registry so
        // that search(), getMainPage(), load(), and loadLinks() are called at the
        // appropriate times.
        registerMainAPI(YouTubeProvider())
    }
}
