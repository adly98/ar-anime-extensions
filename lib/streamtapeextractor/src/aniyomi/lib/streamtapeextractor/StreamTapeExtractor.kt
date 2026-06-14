package aniyomi.lib.streamtapeextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.useAsJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamTapeExtractor(private val client: OkHttpClient) {

    fun canHandleUrl(url: String): Boolean = STREAM_TAPE_REGEX.containsMatchIn(url)

    suspend fun videosFromUrl(url: String, subtitleList: List<Track> = emptyList()): List<Video> {
        return runCatching {
            val document = client.newCall(GET(url)).awaitSuccess().useAsJsoup()
            val targetLine = "document.getElementById('robotlink')"
            val script = document.selectFirst("script:containsData($targetLine)")
                ?.data()
                ?: return emptyList()

            val part1 = script.substringAfter("innerHTML = '").substringBefore("'")
            val part2 = script.substringAfter("+ ('xcd").substringBefore("'")

            val videoUrl = "https:$part1$part2"

            listOf(
                Video(
                    url = videoUrl,
                    quality = "StreamTape: Mirror",
                    videoUrl = videoUrl,
                    headers = Headers.headersOf("Referer", url),
                    subtitleTracks = subtitleList,
                ),
            )
        }.getOrDefault(emptyList())
    }

    companion object {
        private val STREAM_TAPE_REGEX by lazy {
            Regex("""(?://|\.)((?:s(?:tr)?(?:eam|have)?|tapewith|watchadson)?(?:adblock(?:er|plus)?|antiad|noads)?(?:ta?p?e?|cloud)?(?:blocker|advertisement|adsenjoyer)?\.(?:com|cloud|net|pe|site|link|cc|online|fun|cash|to|xyz|org|wiki|club))/[ev]/([0-9a-zA-Z]+)""")
        }
    }
}
