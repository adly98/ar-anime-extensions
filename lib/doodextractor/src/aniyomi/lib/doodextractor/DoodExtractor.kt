package aniyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URI

class DoodExtractor(private val client: OkHttpClient) {

    fun canHandleUrl(url: String) = DOOD_REGEX.containsMatchIn(url)

    fun videoFromUrl(
        url: String,
        prefix: String? = null,
        redirect: Boolean = true,
        externalSubs: List<Track> = emptyList(),
    ): Video? {
        return runCatching {
            val response = client.newCall(GET(url)).execute()
            val newUrl = if (redirect) response.request.url.toString() else url

            val doodHost = getBaseUrl(newUrl)
            val content = response.body.string()
            if (!content.contains("'/pass_md5/")) return null

            val extractedQuality = Regex("\\d{3,4}p")
                .find(content.substringAfter("<title>").substringBefore("</title>"))
                ?.groupValues
                ?.getOrNull(0)

            val newQuality = listOfNotNull(
                prefix,
                "Doodstream " + (extractedQuality ?: (if (redirect) "mirror" else "")),
            ).joinToString(" - ")

            val md5 = doodHost + (Regex("/pass_md5/[^']*").find(content)?.value ?: return null)
            val token = md5.substringAfterLast("/")
            val randomString = createHashTable()
            val expiry = System.currentTimeMillis()

            val videoUrlStart = client.newCall(
                GET(
                    md5,
                    Headers.headersOf("referer", newUrl),
                ),
            ).execute().body.string()
            val videoUrl = "$videoUrlStart$randomString?token=$token&expiry=$expiry"
            Video(videoUrl, newQuality, videoUrl, headers = doodHeaders(doodHost), subtitleTracks = externalSubs)
        }.getOrNull()
    }

    fun videosFromUrl(
        url: String,
        quality: String? = null,
        redirect: Boolean = true,
    ): List<Video> {
        val video = videoFromUrl(url, quality, redirect)
        return video?.let(::listOf) ?: emptyList()
    }

    private fun createHashTable(length: Int = 10): String {
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return buildString {
            repeat(length) {
                append(alphabet.random())
            }
        }
    }

    private fun getBaseUrl(url: String): String = URI(url).let {
        "${it.scheme}://${it.host}"
    }

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://$host/")
    }.build()

    companion object {
        private val DOOD_REGEX by lazy { Regex("""(?://|\.)((?:do*0*o*0*ds?(?:tream|ter|cdn)?|ds[2v](?:play|video)|(?:my)?v*id(?:pla?y|e0)|all3do|d-s|do(?:7go|ply)|playmogo)\.(?:[cit]om?|watch|s[ho]|cx|l[ai]|w[sf]|pm|re|yt|stream|pro|work|net))/[de]/([0-9a-zA-Z]+)""") }
    }
}
