package eu.kanade.tachiyomi.animeextension.ar.arabseed

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ArabSeed :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "عرب سيد"

    override val baseUrl
        get() = preferences.customDomain.ifBlank { "https://a.asd.ink" }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }
    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "ul.movie__blocks__ul a.movie__block"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trend2/")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.select("div.post__info > h3").text()
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
        setUrlWithoutDomain(element.attr("href"))
    }

    override fun popularAnimeNextPageSelector() = "ul.page-numbers li a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recently3/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/find/?word=$query&type=&page_numer=$page"
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
            val category = typeFilter.toUriPart()
            if (category.isEmpty()) throw Exception("اختر فلتر")

            "$baseUrl/category/$category"
        }
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Episode ===============================
    private fun seasonListSelector(): String = "div#seasons__list li"
    override fun episodeListSelector() = "ul.episodes__list a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val csrf = response.bodyString().substringAfter("'csrf__token': \"").substringBefore("\"")
        val document = response.useAsJsoup()
        val seasons = document.select(seasonListSelector())
        return when {
            seasons.isEmpty() -> {
                SEpisode.create().apply {
                    setUrlWithoutDomain("$url/watch/")
                    name = "مشاهدة"
                }.let(::listOf)
            }
            else -> {
                val newHeaders = headers.newBuilder().add("x-requested-with", "XMLHttpRequest").build()
                seasons.flatMap { season ->
                    if(season.hasClass("selected")) {
                        document.select(episodeListSelector()).map { episodeFromSeason(it, season.text()) }
                    } else {
                        val seasonData = FormBody.Builder().apply {
                            add("csrf_token", csrf)
                            add("season_id",season.attr("data-term"))
                        }.build()
                        val seasonJson = client.newCall(POST("$url/season__episodes/", newHeaders, seasonData)).execute().bodyString()
                        val seasonHtml = json.decodeFromString<SeasonDTO>(seasonJson).html
                        val seasonDoc = Jsoup.parseBodyFragment(seasonHtml, baseUrl)
                        seasonDoc.select("a").map { episodeFromSeason(it, season.text()) }
                    }
                }
            }
        }
    }

    private fun episodeFromSeason(element: Element, season: String) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href") + "watch/")
        name = "$season : ${element.text()}"
        episode_number = element.select(".epi__num b").text().toFloatOrNull() ?: 0F
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.useAsJsoup()
        val watchUrl = doc.selectFirst("a.watchBTn")!!.attr("href")
        val element = client.newCall(GET(watchUrl, headers)).execute().useAsJsoup()
        return videosFromElement(element)
    }

    override fun videoListSelector() = "div.containerServers ul li"

    private fun videosFromElement(document: Document): List<Video> = document.select(videoListSelector())
        .parallelCatchingFlatMapBlocking { element ->
            val quality = element.text()
            val embedUrl = element.attr("data-link")
            getVideosFromUrl(embedUrl, quality)
        }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    private suspend fun getVideosFromUrl(url: String, quality: String): List<Video> = when {
        "reviewtech" in url || "reviewrate" in url -> {
            val iframeResponse = client.newCall(GET(url)).awaitSuccess()
                .useAsJsoup()
            val videoUrl = iframeResponse.selectFirst("source")!!.attr("abs:src")
            listOf(Video(videoUrl, quality + "p", videoUrl))
        }

        "dood" in url -> doodExtractor.videosFromUrl(url)

        "fviplions" in url || "wish" in url -> streamwishExtractor.videosFromUrl(url)

        "voe.sx" in url -> voeExtractor.videosFromUrl(url)

        else -> null
    } ?: emptyList()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.url.contains("mp4") },

            )
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.Poster img")!!.let { img ->
            img.attr("abs:data-src")
                .ifEmpty { img.attr("abs:data-lazy-src") }
                .ifEmpty { img.attr("abs:src") }
        }
        title = document.selectFirst("div.BreadCrumbs ol li:last-child a span")!!
            .text()
            .replace(" مترجم", "").replace("فيلم ", "")
        genre = document.select("div.MetaTermsInfo  > li:contains(النوع) > a").eachText().joinToString()
        description = document.selectFirst("div.StoryLine p")!!.text()
        status = when {
            document.location().contains("/selary/") -> SAnime.UNKNOWN
            else -> SAnime.COMPLETED
        }
    }

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeFilter(),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class TypeFilter :
        UriPartFilter(
            "نوع الفلم",
            arrayOf(
                Pair("أختر", ""),
                Pair("افلام عربي", "arabic-movies-5/"),
                Pair("افلام اجنبى", "foreign-movies3/"),
                Pair("افلام اسيوية", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/"),
                Pair("افلام هندى", "indian-movies/"),
                Pair("افلام تركية", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/"),
                Pair("افلام انيميشن", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/"),
                Pair("افلام كلاسيكيه", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d9%84%d8%a7%d8%b3%d9%8a%d9%83%d9%8a%d9%87/"),
                Pair("افلام مدبلجة", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/"),
                Pair("افلام Netfilx", "netfilx/افلام-netfilx/"),
                Pair("مسلسلات عربي", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b9%d8%b1%d8%a8%d9%8a/"),
                Pair("مسلسلات اجنبي", "foreign-series/"),
                Pair("مسلسلات تركيه", "turkish-series-1/"),
                Pair("برامج تلفزيونية", "%d8%a8%d8%b1%d8%a7%d9%85%d8%ac-%d8%aa%d9%84%d9%81%d8%b2%d9%8a%d9%88%d9%86%d9%8a%d8%a9/"),
                Pair("مسلسلات كرتون", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d9%83%d8%b1%d8%aa%d9%88%d9%86/"),
                Pair("مسلسلات رمضان 2019", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2019/"),
                Pair("مسلسلات رمضان 2020", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2020-hd/"),
                Pair("مسلسلات رمضان 2021", "%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%b1%d9%85%d8%b6%d8%a7%d9%86-2021/"),
                Pair("مسلسلات Netfilx", "netfilx/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-netfilz/"),
            ),
        )


    // =============================== Utils ===============================
    private fun Element.getImageUrl(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }
        .substringBefore("?")
        .takeIf(String::isNotBlank)

    @Serializable
    class SeasonDTO(val html: String)
    // =============================== Preferences ===============================
    private var SharedPreferences.customDomain by preferences.delegate(PREF_DOMAIN_CUSTOM_KEY, "")
    private var SharedPreferences.quality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "الجودة المفضلة",
            entries = listOf("1080p", "720p", "480p", "360p", "240p"),
            entryValues = listOf("1080", "720", "480", "360", "240"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_DOMAIN_CUSTOM_KEY,
            default = "",
            title = "رابط الموقع",
            dialogMessage = "أدخل رابط الموقع (على سبيل المثال، https://example.com)",
            summary = preferences.customDomain,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "عنوان URL غير صالح أو مشوه أو ينتهي بشرطة مائلة" },
        )
    }

    // ============================= Utilities ==============================
    companion object {
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
