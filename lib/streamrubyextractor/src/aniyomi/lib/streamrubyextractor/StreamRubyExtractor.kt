package aniyomi.lib.streamrubyextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamRubyExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun canHandleUrl(url: String): Boolean = URL_REGEX.containsMatchIn(url)

    suspend fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "StreamRuby: $quality" }): List<Video> {
        val script = fetchAndExtractScript(url) ?: return emptyList()
        val playlists = extractVideoUrl(script, url)

        return playlists.parallelCatchingFlatMap { videoUrl ->
            playlistUtils.extractFromHls(
                videoUrl,
                referer = url,
                videoNameGen = videoNameGen,
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

    companion object {
        private val URL_REGEX by lazy { Regex("""(?://|\.)((?:s?(?:tream|tm)?ruby(?:stream|stm|vid(?:hub)?)?|kinoger|tuktukcimamulti)\.(?:com|xyz|buzz|be))/(?:embed-|e/|d/)?(\w+)""") }
        private val sourceRegex = Regex(""""((?:https?:/)?/[^"]*m3u8[^"]*)"""")
    }
}
