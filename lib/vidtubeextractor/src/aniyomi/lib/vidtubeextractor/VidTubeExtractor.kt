package aniyomi.lib.vidtubeextractor

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class VidTubeExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun canHandleUrl(url: String): Boolean = VID_TUBE_REGEX.containsMatchIn(url)

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
        val scriptBody = document.selectFirst("script:containsData(m3u8)")?.data()
            ?.let { script ->
                if (script.contains("eval(function(p,a,c")) {
                    JsUnpacker.unpackAndCombine(script)
                } else {
                    script
                }
            } ?: return emptyList()

        val masterUrl = M3U8_REGEX.find(scriptBody)?.value ?: return emptyList()
        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = masterUrl.toHttpUrlOrNull()
                ?.let { "${it.scheme}://${it.host}/" }
                ?: url.toHttpUrl().let { "${it.scheme}://${it.host}/" },
            videoNameGen = { "${prefix.ifBlank { "VidTube" }}: $it" },
        )
    }

    companion object {
        private val VID_TUBE_REGEX by lazy { Regex("""(?://|\.)(.*?vidtube\.(?:pro|cam|one))/(?:embed-|d/)?([0-9a-zA-Z$:/.]+)""") }
        private val M3U8_REGEX by lazy { Regex("""https[^"]*m3u8[^"]*""") }
    }
}
