package aniyomi.lib.vidhideextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidHideExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    fun canHandleUrl(url: String): Boolean = VIDE_HIDE_REGEX.containsMatchIn(url)

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidHide - $quality" }): List<Video> {
        val script = fetchAndExtractScript(url) ?: return emptyList()
        val playlists = extractVideoUrl(script, url)
        val subtitleList = extractSubtitles(script, url)

        return playlists.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                referer = url,
                videoNameGen = videoNameGen,
                subtitleList = subtitleList,
            )
        }
    }

    private suspend fun fetchAndExtractScript(url: String): String? = client.newCall(GET(url, headers)).awaitSuccess()
        .useAsJsoup()
        .select("script")
        .find { it.html().contains("eval(function(p,a,c,k,e,d)") }
        ?.html()
        ?.let(::autoUnpacker)

    private fun extractVideoUrl(script: String, baseUrl: String): List<String> = sourceRegex
        .findAll(script).mapNotNull {
            UrlUtils.fixUrl(it.groupValues[1], baseUrl)
        }.toList()

    private fun extractSubtitles(script: String, baseUrl: String): List<Track> = try {
        val subtitleStr = script
            .substringAfter("tracks")
            .substringAfter("[")
            .substringBefore("]")
        json.decodeFromString<List<TrackDto>>("[$subtitleStr]")
            .filter { it.kind.equals("captions", true) }
            .mapNotNull {
                UrlUtils.fixUrl(it.file, baseUrl)?.let { url ->
                    Track(url, it.label ?: "")
                }
            }
    } catch (_: SerializationException) {
        emptyList()
    }

    @Serializable
    private data class TrackDto(
        val file: String,
        val kind: String,
        val label: String? = null,
    )

    companion object {
        private val VIDE_HIDE_REGEX by lazy { Regex("""(?://|\.)((?:filelions|ajmidyadfihayh|alhayabambi|techradar|moflix-stream|azipcdn|[mad]lions|lumiawatch|javplaya|javlion|fviplions|egsyxutd|fdewsdc|vidhide|peytone|anime7u|coolciima|gsfomqu|katomen|dht|6sfkrspw4u|ryderjet|e4xb5c2xnz|smooth|streamvid|movearnpre|bingezove|dingtezuni|dinisglows|motvy55|videoland|mivalyo|lookmovie2|taylorplayer|dintezuvio|callistanise|minochinos|earnvids)(?:pro|vip|pre|plus|hub|fast)?\.(?:su|com?|to|sbs|ink|click|pro|live|store|xyz|top|online|site|fun|skin))/((?:s|v|f|d|e|embed|file|download)/[0-9a-zA-Z$:/.]+)""") }
        private val sourceRegex = Regex(""""((?:https?:/)?/[^"]*m3u8[^"]*)"""")
    }
}
