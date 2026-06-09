package com.example.youtubeprovider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Data models for the Invidious REST API (https://docs.invidious.io/api/).
 *
 * All classes carry @JsonIgnoreProperties(ignoreUnknown = true) so that future
 * Invidious fields or API version differences don't cause deserialization failures.
 *
 * Every field that the API may omit is typed as nullable (T?) with a default of null.
 * This allows partial responses (e.g. search results vs. full video detail) to be
 * deserialized into the same model without throwing MissingKotlinParameterExceptions.
 *
 * Jackson @JsonProperty annotations are explicit on every field so that Kotlin's
 * name-mangling (e.g. for isShort → is_short) is never a surprise.
 */

// ---------------------------------------------------------------------------
// Search & Listing Models
// ---------------------------------------------------------------------------

/**
 * Represents a single item returned by /api/v1/search and /api/v1/trending.
 *
 * The "type" field discriminates between "video", "playlist", and "channel" items.
 * Channel items are explicitly skipped in the provider because CloudStream has no
 * native channel browsing concept — only videos and playlists are surfaced.
 *
 * Note: [published] is a Unix timestamp in seconds, NOT milliseconds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousSearchItem(
    /** Discriminator: "video", "playlist", or "channel". */
    @JsonProperty("type") val type: String? = null,

    // ── Video-specific fields ────────────────────────────────────────────────
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("authorId") val authorId: String? = null,
    @JsonProperty("lengthSeconds") val lengthSeconds: Long? = null,
    /** Unix epoch seconds when the video was published. May be 0 for live streams. */
    @JsonProperty("published") val published: Long? = null,
    @JsonProperty("publishedText") val publishedText: String? = null,
    @JsonProperty("viewCount") val viewCount: Long? = null,
    @JsonProperty("videoThumbnails") val videoThumbnails: List<InvidiousThumbnail>? = null,
    /** True when the video was originally uploaded as a YouTube Short (< 60 s vertical). */
    @JsonProperty("isShort") val isShort: Boolean? = null,

    // ── Playlist-specific fields ─────────────────────────────────────────────
    @JsonProperty("playlistId") val playlistId: String? = null,
    @JsonProperty("playlistThumbnail") val playlistThumbnail: String? = null,
    @JsonProperty("videoCount") val videoCount: Int? = null,
)

/**
 * A thumbnail variant for a video or playlist.
 *
 * Invidious returns several variants per video ordered by quality.
 * We pick the best available thumbnail by iterating a priority list of [quality] values.
 *
 * The [url] may be a relative path (e.g. /vi/…/maxresdefault.jpg) on proxied instances,
 * so callers should prepend INVIDIOUS_BASE_URL when it does not start with "http".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousThumbnail(
    /** Quality label, e.g. "maxres", "sddefault", "high", "medium", "default". */
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("width") val width: Int? = null,
    @JsonProperty("height") val height: Int? = null,
)

// ---------------------------------------------------------------------------
// Full Video Detail Model
// ---------------------------------------------------------------------------

/**
 * Full video metadata returned by /api/v1/videos/{videoId}.
 *
 * This model is used in both [YouTubeProvider.loadVideo] (for metadata) and
 * [YouTubeProvider.loadLinks] (for stream URL extraction).  When PROXY_STREAMS
 * is true the request is made with ?local=true, which causes Invidious to proxy
 * all stream URLs through its own server rather than returning YouTube CDN URLs.
 *
 * IMPORTANT — Stream URL expiry:
 *   YouTube CDN URLs embedded in [formatStreams] and [adaptiveFormats] are
 *   time-limited (typically 6 hours after the /api/v1/videos request).  Saving
 *   these URLs for later playback without re-fetching will eventually result in
 *   403 Forbidden errors.  CloudStream calls loadLinks() just before playback,
 *   so in practice this is not an issue unless the user leaves a video paused
 *   for an extended period.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousVideo(
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("authorId") val authorId: String? = null,
    @JsonProperty("lengthSeconds") val lengthSeconds: Int? = null,
    /** Unix epoch seconds of publication. */
    @JsonProperty("published") val published: Long? = null,
    @JsonProperty("publishedText") val publishedText: String? = null,
    @JsonProperty("viewCount") val viewCount: Long? = null,
    @JsonProperty("likeCount") val likeCount: Long? = null,
    @JsonProperty("isShort") val isShort: Boolean? = null,
    @JsonProperty("videoThumbnails") val videoThumbnails: List<InvidiousThumbnail>? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("keywords") val keywords: List<String>? = null,

    /**
     * Progressive (muxed) streams — audio + video in a single container.
     * These are reliable but top out at 720 p.
     *
     * CAVEAT: Invidious sometimes returns formatStreams with no audio track on
     * certain videos due to YouTube's SABR (Server ABR) streaming changes.
     * Always prefer adaptiveFormats + DASH manifest when available.
     */
    @JsonProperty("formatStreams") val formatStreams: List<InvidiousFormatStream>? = null,

    /**
     * Separate audio-only and video-only streams — allows higher resolutions (up to 4K)
     * but requires a player capable of muxing them (e.g. ExoPlayer via DASH).
     */
    @JsonProperty("adaptiveFormats") val adaptiveFormats: List<InvidiousAdaptiveFormat>? = null,

    @JsonProperty("recommendedVideos") val recommendedVideos: List<InvidiousRecommendedVideo>? = null,
)

// ---------------------------------------------------------------------------
// Stream Format Models
// ---------------------------------------------------------------------------

/**
 * A progressive (muxed audio+video) stream from [InvidiousVideo.formatStreams].
 *
 * [url] is a direct CDN link subject to expiry (see [InvidiousVideo] for details).
 * [quality] uses legacy quality strings: "hd720", "medium", "small", "tiny".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousFormatStream(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    /** Legacy quality label: "hd720", "medium", "small", or "tiny". */
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("resolution") val resolution: String? = null,
    @JsonProperty("fps") val fps: Int? = null,
    @JsonProperty("container") val container: String? = null,
    @JsonProperty("encoding") val encoding: String? = null,
    @JsonProperty("itag") val itag: Int? = null,
)

/**
 * A separate audio-only or video-only adaptive stream from [InvidiousVideo.adaptiveFormats].
 *
 * Video streams have a non-null [qualityLabel] (e.g. "1080p", "720p60").
 * Audio streams have [qualityLabel] == null and can be identified by their [type]
 * containing "audio/".
 *
 * [bitrate] is in bits-per-second; for audio streams use the highest available value
 * for the best quality.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousAdaptiveFormat(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("type") val type: String? = null,
    /** Present on video streams, e.g. "1080p", "720p60", "4320p".  Null on audio streams. */
    @JsonProperty("qualityLabel") val qualityLabel: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("resolution") val resolution: String? = null,
    @JsonProperty("fps") val fps: Int? = null,
    /** Bits per second. Critical for selecting the best audio track. */
    @JsonProperty("bitrate") val bitrate: Long? = null,
    @JsonProperty("encoding") val encoding: String? = null,
    @JsonProperty("container") val container: String? = null,
    @JsonProperty("itag") val itag: Int? = null,
)

// ---------------------------------------------------------------------------
// Recommendation Model
// ---------------------------------------------------------------------------

/**
 * A video recommended at the end of another video, as returned by Invidious.
 * Only a subset of fields is available compared to [InvidiousVideo].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousRecommendedVideo(
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("lengthSeconds") val lengthSeconds: Int? = null,
    @JsonProperty("videoThumbnails") val videoThumbnails: List<InvidiousThumbnail>? = null,
)

// ---------------------------------------------------------------------------
// Playlist Models
// ---------------------------------------------------------------------------

/**
 * Full playlist metadata returned by /api/v1/playlists/{playlistId}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousPlaylist(
    @JsonProperty("playlistId") val playlistId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("videoCount") val videoCount: Int? = null,
    @JsonProperty("playlistThumbnail") val playlistThumbnail: String? = null,
    @JsonProperty("videos") val videos: List<InvidiousPlaylistVideo>? = null,
)

/**
 * A video entry within a playlist response.
 * [index] is 0-based in the Invidious response; the provider displays it as 1-based.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InvidiousPlaylistVideo(
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("author") val author: String? = null,
    @JsonProperty("lengthSeconds") val lengthSeconds: Int? = null,
    /** 0-based position within the playlist. */
    @JsonProperty("index") val index: Int? = null,
    @JsonProperty("videoThumbnails") val videoThumbnails: List<InvidiousThumbnail>? = null,
)
