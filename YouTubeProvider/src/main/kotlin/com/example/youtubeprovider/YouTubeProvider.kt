package com.example.youtubeprovider

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import java.util.Calendar

// ============================================================================
// Top-Level Configuration Constants
// ============================================================================

/**
 * Base URL of your self-hosted Invidious instance.
 *
 * ⚠️  CHANGE THIS before building.  Do NOT include a trailing slash.
 *
 * Example: const val INVIDIOUS_BASE_URL = "https://invidious.yourdomain.com"
 *
 * You can find public instances at https://api.invidious.io/ but for production
 * use you should host your own to avoid rate-limiting and downtime.
 */
const val INVIDIOUS_BASE_URL = "http://192.168.1.6:3000"

/**
 * When true, all API requests that return stream URLs will include ?local=true.
 *
 * This instructs Invidious to proxy the YouTube CDN media through itself, which:
 *   • Hides the viewer's IP address from Google/YouTube.
 *   • Bypasses certain geographic restrictions.
 *   • Increases server load on your Invidious instance.
 *
 * Set to false if your instance's bandwidth is limited or if you prefer direct
 * YouTube CDN delivery (faster but less private).
 *
 * NOTE: DASH manifest URLs are ALWAYS fetched with ?local=true (see loadLinks)
 * because an Invidious-hosted DASH manifest that references remote CDN segments
 * is not useful — the segments must also be proxied for the manifest to work.
 */
const val PROXY_STREAMS = true

// ============================================================================
// URL Encoding Prefixes
// ============================================================================

/**
 * Internal prefix prepended to a video ID when it is stored in a SearchResponse.url
 * or LoadResponse.dataUrl field.  This allows [loadLinks] and [load] to distinguish
 * a video lookup from a playlist lookup without a separate type field.
 *
 * Example encoded URL: "yt_video:dQw4w9WgXcQ"
 */
private const val VIDEO_PREFIX = "yt_video:"

/**
 * Internal prefix for playlist IDs, analogous to [VIDEO_PREFIX].
 *
 * Example encoded URL: "yt_playlist:PLbpi6ZahtOH6Ar_3GPy3workqMWJ7GVYD"
 */
private const val PLAYLIST_PREFIX = "yt_playlist:"

// ============================================================================
// YouTubeProvider
// ============================================================================

/**
 * CloudStream3 extension that sources YouTube content from a self-hosted
 * Invidious instance (https://github.com/iv-org/invidious).
 *
 * The extension supports:
 *   • Home page rows: Trending, Popular, category-specific trending, Technology search.
 *   • Full-text search returning both videos and playlists.
 *   • Video detail pages with stream extraction (DASH, adaptive, progressive).
 *   • Playlist pages mapped to TvSeries episodes.
 *   • Video recommendations shown as related content.
 *
 * All network calls are suspend functions backed by CloudStream's built-in
 * [app] HTTP client.  No blocking I/O is performed on the calling thread.
 */
class YouTubeProvider : MainAPI() {

    /** Human-readable name shown in the CloudStream extensions list. */
    override var name = "YouTube (Invidious)"

    /**
     * The base URL is set to the Invidious instance so CloudStream can display it
     * in the extension details and use it for cookie/header scoping.
     */
    override var mainUrl = INVIDIOUS_BASE_URL

    /**
     * We use Others + Movie + TvSeries to allow videos (Movie), playlists (TvSeries),
     * and anything else (Others) to appear in CloudStream's type filters.
     */
    override val supportedTypes = setOf(TvType.Others, TvType.Movie, TvType.TvSeries)

    /** Default language; YouTube is global but content returned by Invidious defaults to English. */
    override var lang = "en"

    /** Enables the home-page rows defined in [mainPage]. */
    override val hasMainPage = true

    // ──────────────────────────────────────────────────────────────────────────
    // Jackson JSON mapper
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Shared Jackson mapper configured for Kotlin data classes.
     *
     * We disable FAIL_ON_UNKNOWN_PROPERTIES at the mapper level as a belt-and-
     * suspenders measure alongside @JsonIgnoreProperties on each model class.
     * This ensures that even models without the annotation survive future
     * Invidious API additions gracefully.
     *
     * KotlinModule handles:
     *   • Kotlin data class constructor inference (no default constructor needed).
     *   • Nullable field mapping (null JSON → null Kotlin, missing field → null Kotlin).
     *   • Default parameter values in constructors.
     */
    private val mapper: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    // ──────────────────────────────────────────────────────────────────────────
    // Home Page Definition
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Defines the horizontal rows shown on the CloudStream home screen.
     *
     * The string key is passed as [HomePageRequest.data] to [getMainPage] so it
     * can be used to decide which Invidious endpoint to call.
     *
     * "popular" uses the same /api/v1/trending endpoint as "trending" but without
     * a type filter — Invidious returns a slightly different content mix depending
     * on the instance's configuration.
     */
    override val mainPage = mainPageOf(
        "trending"   to "Trending",
        "popular"    to "Popular",
        "music"      to "Music",
        "gaming"     to "Gaming",
        "news"       to "News",
        "technology" to "Technology",
    )

    // ──────────────────────────────────────────────────────────────────────────
    // getMainPage
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetches content for a home-page row identified by [request.data].
     *
     * Invidious's /api/v1/trending endpoint does not support pagination — it always
     * returns the same list regardless of the page parameter.  To prevent CloudStream
     * from endlessly requesting more pages (which would just re-show the same content),
     * we return an empty list for any page > 1 on non-paginated endpoints.
     *
     * The "technology" category is not a trending type on YouTube, so we proxy it
     * through the search endpoint instead.
     *
     * @param request  Contains [MainPageRequest.data] (the key from [mainPage]) and
     *                 [MainPageRequest.page] (1-based page number).
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val data = request.data

            // Trending and popular don't paginate — bail early on subsequent pages
            // to avoid showing duplicates and confusing the user.
            if ((data == "trending" || data == "popular") && page > 1) {
                return newHomePageResponse(
                    request,
                    list = emptyList(),
                    hasNext = false,
                )
            }

            // Build the endpoint URL based on the row type.
            val url = when (data) {
                "trending" -> "$mainUrl/api/v1/trending"
                "popular"  -> "$mainUrl/api/v1/trending"
                // Invidious supports type=Music, Gaming, News for its trending endpoint.
                "music"    -> "$mainUrl/api/v1/trending?type=Music"
                "gaming"   -> "$mainUrl/api/v1/trending?type=Gaming"
                "news"     -> "$mainUrl/api/v1/trending?type=News"
                // "technology" is not a trending category; search for it instead.
                "technology" -> "$mainUrl/api/v1/search?q=technology&type=video&sort_by=relevance&page=$page"
                else       -> return null
            }

            val responseBody = safeGet(url) ?: throw Exception("Network request failed or returned non-200 for URL: $url")
            val items: List<InvidiousSearchItem> = mapper.readValue(responseBody)

            // Map each Invidious item to a CloudStream SearchResponse.
            // Items with missing videoId or title are skipped via mapNotNull.
            val searchResponses = items.mapNotNull { item ->
                val inferredType = item.type ?: if (item.videoId != null) "video" else if (item.playlistId != null) "playlist" else "unknown"
                when (inferredType) {
                    "video" -> item.toVideoSearchResponse()
                    "playlist" -> item.toPlaylistSearchResponse()
                    // Skip channel items — CloudStream has no channel browsing UI.
                    else -> null
                }
            }

            // hasNext = false for trending rows (no pagination); true for search-backed rows.
            val hasNext = data == "technology" && searchResponses.isNotEmpty()

            newHomePageResponse(
                request,
                list = searchResponses,
                hasNext = hasNext,
            )
        } catch (e: Exception) {
            val errorMsg = e.stackTraceToString().take(500)
            HomePageResponse(
                items = listOf(HomePageList(
                    name = "Error: $errorMsg",
                    list = emptyList()
                ))
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // search
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Searches the Invidious instance for the given [query].
     *
     * We request both "video" and "playlist" results in a single call using
     * type=all.  The [fields] param limits the response to only the fields we
     * actually use, reducing payload size.
     *
     * Channel results are silently discarded because CloudStream cannot represent
     * a channel as a navigable content item.
     *
     * Shorts are marked with " [Short]" appended to their title so users can
     * distinguish them in the search results list.
     *
     * @param query  The raw search string entered by the user.
     * @return       A list of [SearchResponse]s or null on failure.
     */
    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            // Restrict the response to fields we need; reduces bandwidth, especially on
            // instances with slow connections to YouTube.
            val fields = listOf(
                "type", "videoId", "playlistId", "title", "author",
                "lengthSeconds", "videoThumbnails", "publishedText",
                "videoCount", "playlistThumbnail", "isShort",
            ).joinToString(",")

            val url = buildString {
                append("$mainUrl/api/v1/search")
                append("?q=${query.encodeUrl()}")
                append("&page=1")
                append("&type=all")
                append("&fields=$fields")
            }

            val responseBody = safeGet(url) ?: return null
            val items: List<InvidiousSearchItem> = mapper.readValue(responseBody)

            items.mapNotNull { item ->
                val inferredType = item.type ?: if (item.videoId != null) "video" else if (item.playlistId != null) "playlist" else "unknown"
                when (inferredType) {
                    "video"    -> item.toVideoSearchResponse()
                    "playlist" -> item.toPlaylistSearchResponse()
                    else       -> null // skip channels
                }
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // load
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Dispatches load requests to the appropriate handler based on the URL prefix
     * embedded by [search] and [getMainPage].
     *
     * If the URL has no recognised prefix we assume it is a bare video ID (e.g.
     * from a deep link or a manually constructed URL) and fall through to [loadVideo].
     *
     * @param url  Encoded content URL in one of the following forms:
     *               "yt_video:{videoId}"
     *               "yt_playlist:{playlistId}"
     *               "{videoId}"  (bare, treated as video)
     */
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val videoId = Regex("""(?:v=|/embed/|youtu\.be/)([^&?]+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""yt_video:([^&?]+)""").find(url)?.groupValues?.get(1)
            val playlistId = Regex("""(?:list=)([^&?]+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""yt_playlist:([^&?]+)""").find(url)?.groupValues?.get(1)

            when {
                playlistId != null -> loadPlaylist(playlistId)
                videoId != null -> loadVideo(videoId)
                url.startsWith(VIDEO_PREFIX)    -> loadVideo(url.removePrefix(VIDEO_PREFIX))
                url.startsWith(PLAYLIST_PREFIX) -> loadPlaylist(url.removePrefix(PLAYLIST_PREFIX))
                // Bare ID fallback: assume video (e.g. from external deep link).
                else                             -> loadVideo(url)
            }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // loadVideo
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetches full metadata for a single video and builds a [MovieLoadResponse].
     *
     * ?local=true is appended when [PROXY_STREAMS] is true so that any embedded
     * stream URLs in the response are proxied through Invidious.  This is important
     * even here (not just in loadLinks) because the DASH manifest URL returned in
     * the response must also be a proxied URL when proxying is enabled.
     *
     * Description is truncated to 2000 characters to avoid overflowing CloudStream's
     * synopsis UI which has a fixed maximum height on some skins.
     *
     * @param videoId  The raw YouTube video ID (11 characters), e.g. "dQw4w9WgXcQ".
     */
    private suspend fun loadVideo(videoId: String): MovieLoadResponse? {
        val localParam = if (PROXY_STREAMS) "?local=true" else ""
        val url = "$mainUrl/api/v1/videos/$videoId$localParam"

        val responseBody = safeGet(url) ?: return null
        val video: InvidiousVideo = mapper.readValue(responseBody)

        val title = video.title ?: return null // A video with no title is unusable.

        // Build the plot/description string that appears in CloudStream's detail screen.
        // Prepend view count, age, and channel name so they're visible at a glance.
        val plot = buildString {
            video.viewCount?.let { append("👁 ${formatViewCount(it)} views") }
            video.publishedText?.let { if (isNotEmpty()) append("  •  "); append(it) }
            video.author?.let { if (isNotEmpty()) append("  •  "); append(it) }
            if (isNotEmpty()) append("\n\n")
            // Truncate description to avoid layout overflow in CloudStream skins.
            video.description?.take(2000)?.let { append(it) }
        }.ifBlank { null }

        // Genre, channel, and "Shorts" label as CloudStream tags (shown as chips in some skins).
        val tags = buildList {
            video.genre?.let { add(it) }
            video.author?.let { add(it) }
            if (video.isShort == true) add("Shorts")
        }

        // Build the set of recommendations using only fields available on the stub model.
        val recommendations = video.recommendedVideos.orEmpty().mapNotNull { rec ->
            val recId = rec.videoId ?: return@mapNotNull null
            val recTitle = rec.title ?: return@mapNotNull null
            newMovieSearchResponse(
                name = recTitle,
                url = "$mainUrl/watch?v=$recId",
                type = TvType.Movie,
            ) {
                posterUrl = rec.videoThumbnails.orEmpty().bestThumbnailUrl()
                    ?.resolveUrl()
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = "$mainUrl/watch?v=$videoId",
            type = TvType.Movie,
            // dataUrl is the value passed verbatim to loadLinks(); we embed the video ID.
            dataUrl = "$mainUrl/watch?v=$videoId",
        ) {
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations

            // Pick the best available thumbnail by quality priority.
            posterUrl = video.videoThumbnails.orEmpty().bestThumbnailUrl()?.resolveUrl()

            // Convert Unix timestamp to calendar year for the year badge.
            year = video.published?.let { timestampToYear(it) }

            // Duration shown as "H:MM:SS" or "M:SS" in the CloudStream detail card.
            this.duration = video.lengthSeconds?.let { it / 60 }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // loadPlaylist
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetches playlist metadata and maps each video to a [TvSeriesEpisode].
     *
     * Playlists are modelled as a TvSeries with a single season (season = 1) and
     * one episode per playlist video.  The episode number is [index] + 1 because
     * Invidious returns 0-based indices but CloudStream expects 1-based episode numbers.
     *
     * Note: Invidious caps playlist responses at 100 videos.  For playlists larger
     * than 100 items the remaining videos are not shown.  Pagination of the
     * /api/v1/playlists/{id}?page=N endpoint is not implemented here but could be
     * added as a future enhancement by calling loadEpisodes() instead.
     *
     * @param playlistId  The YouTube playlist ID (starts with "PL", "LL", "FL", etc.).
     */
    private suspend fun loadPlaylist(playlistId: String): TvSeriesLoadResponse? {
        val url = "$mainUrl/api/v1/playlists/$playlistId"

        val responseBody = safeGet(url) ?: return null
        val playlist: InvidiousPlaylist = mapper.readValue(responseBody)

        val title = playlist.title ?: return null

        // Map each playlist entry to a CloudStream episode.
        val episodes = playlist.videos.orEmpty().mapIndexed { idx, video ->
            val vid = video.videoId ?: return@mapIndexed null
            newEpisode(data = "$mainUrl/watch?v=$vid") {
                this.name = video.title
                // Use index from API if present (handles non-sequential playlists);
                // fall back to the list iteration index.
                this.season = 1
                this.episode = (video.index ?: idx) + 1
                this.posterUrl = video.videoThumbnails.orEmpty().bestThumbnailUrl()
                    ?.resolveUrl()
                this.runTime = video.lengthSeconds?.let { it / 60 } // minutes
            }
        }.filterNotNull()

        return newTvSeriesLoadResponse(
            name = "$title [Playlist]",
            url = "$mainUrl/playlist?list=$playlistId",
            type = TvType.TvSeries,
            episodes = episodes,
        ) {
            // Use playlist thumbnail if present; otherwise fall back to first video's thumbnail.
            posterUrl = playlist.playlistThumbnail?.resolveUrl()
                ?: playlist.videos.orEmpty().firstOrNull()
                    ?.videoThumbnails.orEmpty().bestThumbnailUrl()?.resolveUrl()

            plot = playlist.description?.take(2000)?.ifBlank { null }
            tags = listOfNotNull(playlist.author, "Playlist")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // loadLinks
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Extracts playback stream URLs for a given video and emits them via [callback].
     *
     * Stream extraction is attempted in three priority tiers:
     *
     *   Priority 1 — DASH manifest (best for quality/compatibility):
     *     A single URL that describes all adaptive streams in an XML document.
     *     ExoPlayer (used by CloudStream) handles DASH natively and can switch
     *     quality tiers automatically.  We do a HEAD request first to confirm the
     *     manifest is reachable before emitting it.
     *
     *   Priority 2 — Individual adaptive streams:
     *     Separate video-only and audio-only URLs.  We pick the best audio stream
     *     by bitrate and emit all MP4 video streams.  This is the fallback when
     *     DASH is unavailable or the HEAD check fails.
     *
     *   Priority 3 — Progressive (muxed) streams:
     *     Single URLs containing both audio and video, capped at 720 p.
     *     CAVEAT: These may be unavailable on some videos due to YouTube's SABR
     *     (Server-side Adaptive Bitrate) changes that Invidious cannot always
     *     work around.  See https://github.com/iv-org/invidious/issues/3500.
     *     We only fall back to these if neither DASH nor adaptive worked.
     *
     * All exceptions are caught so CloudStream receives a clean false return
     * rather than a crash.
     *
     * @param data          The encoded URL string ("yt_video:{videoId}").
     * @param subtitleCallback  Unused; YouTube subtitles are not supported here.
     * @param callback      Called once per discovered stream link.
     * @return              true if at least one link was emitted, false otherwise.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            // Strip the prefix to get the bare video ID.
            val videoId = Regex("""(?:v=|/embed/|youtu\.be/)([^&?]+)""").find(data)?.groupValues?.get(1)
                ?: Regex("""yt_video:([^&?]+)""").find(data)?.groupValues?.get(1)
                ?: data.removePrefix(VIDEO_PREFIX)

            var emittedCount = 0

            // ── Priority 1: DASH manifest ──────────────────────────────────────
            //
            // The DASH manifest is ALWAYS fetched with ?local=true, regardless of
            // PROXY_STREAMS, because a manifest that references remote CDN segments
            // is useless — all segments must come from the same origin as the manifest
            // for ExoPlayer's DASH source to work correctly through Invidious.
            val dashManifestUrl = "$mainUrl/api/v1/manifest/dash/id/$videoId?local=true"

            if (safeHead(dashManifestUrl)) {
                // HEAD check passed — the manifest exists and is reachable.
                callback(
                        newExtractorLink(
                            source = name,
                            name = "$name DASH",
                            url = dashManifestUrl,
                            type = ExtractorLinkType.DASH,
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }              )
                emittedCount++
            }

            // ── Priority 2: Adaptive streams (video-only + audio-only) ──────────
            //
            // Re-fetch the video detail to get fresh stream URLs.
            // Stream URL expiry: YouTube CDN URLs are valid for approximately 6 hours
            // after the API call.  Since loadLinks() is called immediately before
            // playback begins, expiry is not an issue in normal usage.
            val localParam = if (PROXY_STREAMS) "?local=true" else ""
            val videoDetailUrl = "$mainUrl/api/v1/videos/$videoId$localParam"
            val videoBody = safeGet(videoDetailUrl)

            if (videoBody != null) {
                val video: InvidiousVideo = mapper.readValue(videoBody)

                // Find the best audio stream: no qualityLabel, audio MIME type, highest bitrate.
                val bestAudio = video.adaptiveFormats.orEmpty()
                    .filter { fmt ->
                        fmt.url != null
                            && fmt.qualityLabel == null
                            && fmt.type?.contains("audio/mp4") == true
                    }
                    .maxByOrNull { it.bitrate ?: 0L }

                // Emit all MP4 video-only adaptive streams.
                val videoStreams = video.adaptiveFormats.orEmpty().filter { fmt ->
                    fmt.url != null
                        && fmt.qualityLabel != null
                        && fmt.type?.contains("video/mp4") == true
                }

                for (vStream in videoStreams) {
                    val streamUrl = vStream.url ?: continue
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name ${vStream.qualityLabel ?: "Unknown"}",
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = mainUrl
                            this.quality = qualityLabelToInt(vStream.qualityLabel)
                        }
                    )
                    emittedCount++
                }

                // Emit the best audio stream so that players can mux video + audio.
                bestAudio?.url?.let { audioUrl ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name Audio Only",
                            url = audioUrl,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    emittedCount++
                }

                // ── Priority 3: Progressive (muxed) streams ──────────────────────
                //
                // CAVEAT: formatStreams rely on Invidious being able to extract the
                // legacy progressive stream URLs from YouTube.  Since YouTube's SABR
                // rollout (2023–2024), these URLs are increasingly unavailable or
                // return 403 errors.  Only fall back to them if no adaptive streams
                // were found.
                //
                // See: https://github.com/iv-org/invidious/issues/3500
                if (emittedCount == 0 || videoStreams.isEmpty()) {
                    for (fStream in video.formatStreams.orEmpty()) {
                        val streamUrl = fStream.url ?: continue
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name ${fStream.quality ?: "Unknown"}",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.referer = mainUrl
                                this.quality = qualityStringToInt(fStream.quality)
                            }
                        )
                        emittedCount++
                    }
                }
            }

            emittedCount > 0
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HTTP Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Performs a GET request and returns the response body as a string.
     *
     * Returns null on any error (network failure, non-2xx status, timeout, etc.)
     * rather than throwing, so callers don't need individual try/catch blocks.
     *
     * @param url     The full URL to GET.
     * @param params  Optional query parameters to append (not URL-encoded here).
     */
    private suspend fun safeGet(url: String, params: Map<String, String> = emptyMap()): String? {
        return try {
            val response = if (params.isEmpty()) {
                app.get(url)
            } else {
                app.get(url, params = params)
            }
            if (response.isSuccessful) response.text else null
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * Performs a HEAD request to check whether a URL is reachable without
     * downloading the body.  Used to validate the DASH manifest URL before emitting it.
     *
     * Returns false on any error or non-2xx response.
     *
     * @param url  The URL to check.
     */
    private suspend fun safeHead(url: String): Boolean {
        return try {
            app.head(url).isSuccessful
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // InvidiousSearchItem → SearchResponse helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Converts a video-type [InvidiousSearchItem] to a [MovieSearchResponse].
     *
     * Returns null if the item lacks the mandatory [videoId] or [title] fields.
     * Appends " [Short]" to the title when the video is a YouTube Short so that
     * users can identify them in search results before opening the detail screen.
     */
    private fun InvidiousSearchItem.toVideoSearchResponse(): MovieSearchResponse? {
        val id = videoId ?: return null
        val displayTitle = buildString {
            append(title ?: return null)
            if (isShort == true) append(" [Short]")
        }
        return newMovieSearchResponse(
            name = displayTitle,
            url = "$mainUrl/watch?v=$id",
            type = TvType.Movie,
        ) {
            posterUrl = videoThumbnails.orEmpty().bestThumbnailUrl()?.resolveUrl()
        }
    }

    /**
     * Converts a playlist-type [InvidiousSearchItem] to a [TvSeriesSearchResponse].
     *
     * Returns null if the item lacks [playlistId] or [title].
     * Appends " [Playlist]" to the title so users can distinguish playlists from
     * individual videos in mixed search results.
     */
    private fun InvidiousSearchItem.toPlaylistSearchResponse(): TvSeriesSearchResponse? {
        val id = playlistId ?: return null
        val displayTitle = buildString {
            append(title ?: return null)
            append(" [Playlist]")
        }
        return newTvSeriesSearchResponse(
            name = displayTitle,
            url = "$mainUrl/playlist?list=$id",
            type = TvType.TvSeries,
        ) {
            // Use the playlist thumbnail URL directly — it's a full URL from Invidious.
            posterUrl = playlistThumbnail?.resolveUrl()
                ?: videoThumbnails.orEmpty().bestThumbnailUrl()?.resolveUrl()
        }
    }
}

// ============================================================================
// Extension Functions & Utility Helpers
// ============================================================================

/**
 * Returns the URL of the best available thumbnail from a list of [InvidiousThumbnail]s.
 *
 * Priority order (highest to lowest resolution):
 *   maxres → sddefault → high → medium → default
 *
 * If none of the priority qualities are present, falls back to the first thumbnail
 * in the list (whatever Invidious returned first).
 *
 * Returns null if the list is empty or no thumbnail has a non-null URL.
 */
fun List<InvidiousThumbnail>.bestThumbnailUrl(): String? {
    val priorityOrder = listOf("maxres", "sddefault", "high", "medium", "default")
    val byQuality = associateBy { it.quality }

    for (quality in priorityOrder) {
        byQuality[quality]?.url?.let { return it }
    }

    // Fallback: return the first thumbnail with any URL.
    return firstOrNull { it.url != null }?.url
}

/**
 * Resolves a thumbnail URL that may be relative (e.g. "/vi/…/maxresdefault.jpg")
 * to an absolute URL by prepending [INVIDIOUS_BASE_URL].
 *
 * Invidious instances that proxy thumbnails through themselves return relative paths.
 * Instances configured to return direct YouTube thumbnail URLs return absolute paths.
 * This helper handles both cases transparently.
 */
fun String.resolveUrl(): String {
    return if (startsWith("http://") || startsWith("https://")) {
        this
    } else {
        "$INVIDIOUS_BASE_URL$this"
    }
}

/**
 * Percent-encodes a string for use in a URL query parameter.
 *
 * We use Java's standard URLEncoder with UTF-8.  The space character is encoded
 * as "%20" (not "+") because Invidious's search endpoint expects RFC-3986 encoding.
 */
fun String.encodeUrl(): String {
    return java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

/**
 * Formats an integer number of seconds into a human-readable duration string.
 *
 * Returns "H:MM:SS" for videos ≥ 1 hour, "M:SS" for shorter videos.
 * Returns null if [seconds] is null, zero, or negative (e.g. live streams).
 *
 * Examples:
 *   3661 → "1:01:01"
 *   125  → "2:05"
 *   0    → null
 */
fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

/**
 * Formats a view count as a locale-independent comma-separated number.
 *
 * Example: 1234567 → "1,234,567"
 */
fun formatViewCount(count: Long): String {
    return String.format("%,d", count)
}

/**
 * Converts a Unix timestamp in seconds to a calendar year integer.
 *
 * Returns null if the timestamp is 0 or negative (which Invidious uses for
 * live streams and unlisted videos whose upload date is unknown).
 *
 * @param unixSeconds  Seconds since 1970-01-01T00:00:00Z.
 */
fun timestampToYear(unixSeconds: Long): Int? {
    if (unixSeconds <= 0) return null
    val cal = Calendar.getInstance()
    cal.timeInMillis = unixSeconds * 1000L
    return cal.get(Calendar.YEAR)
}

/**
 * Maps an Invidious adaptive-format [qualityLabel] (e.g. "1080p", "4K") to a
 * CloudStream [Qualities] integer value.
 *
 * The label may include a frame-rate suffix (e.g. "1080p60") which is stripped
 * before matching.
 *
 * Falls back to [Qualities.Unknown.value] for unrecognised labels.
 */
fun qualityLabelToInt(label: String?): Int {
    if (label == null) return Qualities.Unknown.value
    // Strip frame-rate suffix so "1080p60" matches the same as "1080p".
    val normalised = label.lowercase().replace(Regex("[^0-9a-z]"), "")
    return when {
        normalised.contains("2160") || normalised.contains("4k") -> Qualities.P2160.value
        normalised.contains("1440")                              -> Qualities.P1440.value
        normalised.contains("1080")                              -> Qualities.P1080.value
        normalised.contains("720")                               -> Qualities.P720.value
        normalised.contains("480")                               -> Qualities.P480.value
        normalised.contains("360")                               -> Qualities.P360.value
        normalised.contains("240")                               -> Qualities.P240.value
        normalised.contains("144")                               -> Qualities.P144.value
        else                                                     -> Qualities.Unknown.value
    }
}

/**
 * Maps a legacy Invidious [formatStreams] quality string to a CloudStream
 * [Qualities] integer value.
 *
 * The legacy strings are fixed YouTube identifiers that have not changed since the
 * pre-adaptive era.  They do not include a frame-rate suffix.
 *
 * See: https://docs.invidious.io/api/#get-apiv1videosid — "quality" field.
 */
fun qualityStringToInt(quality: String?): Int {
    return when (quality?.lowercase()) {
        "hd720"  -> Qualities.P720.value
        "medium" -> Qualities.P480.value
        "small"  -> Qualities.P360.value
        "tiny"   -> Qualities.P240.value
        else     -> Qualities.Unknown.value
    }
}
