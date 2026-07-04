package eu.kanade.tachiyomi.animeextension.ar.topcinema

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.luluextractor.LuluExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidtubeextractor.VidTubeExtractor
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
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TopCinema :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "توب سينما"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { BASE_URL }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.Block--Item, div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.select("a").attr("title").let { editTitle(it, details = true) }
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
    }

    override fun popularAnimeNextPageSelector(): String = "div.paginate ul.page-numbers li a:contains(»)"

    // ============================== Episodes ==============================
    private fun seasonListSelector(): String = "section.allseasonss div.Small--Box"
    override fun episodeListSelector(): String = "section.allepcont a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val url = response.request.url.toString()
        val seasonsDOM = document.select(seasonListSelector())
        return if (seasonsDOM.isEmpty()) {
            SEpisode.create().apply {
                setUrlWithoutDomain(url + "watch/")
                name = "مشاهدة"
            }.let(::listOf)
        } else {
            val selectedSeason = document.selectFirst("div#mpbreadcrumbs a span:contains(الموسم)")?.text().orEmpty()
            seasonsDOM.reversed().parallelCatchingFlatMapBlocking { season ->
                val seasonText = season.select("h3").text()
                val seasonUrl = season.selectFirst("a")?.attr("abs:href") ?: return@parallelCatchingFlatMapBlocking emptyList()
                val seasonDoc = if (selectedSeason == seasonText) {
                    document
                } else {
                    client.newCall(GET(seasonUrl)).awaitSuccess().useAsJsoup()
                }
                val seasonNum = if (seasonsDOM.size == 1) "1" else seasonText.filter { it.isDigit() }.ifEmpty { "0" }
                val seasonTitle = seasonText.let {
                    "الموسم " + it.substringAfter("الموسم ").substringBefore(" ")
                }
                seasonDoc.select(episodeListSelector()).mapIndexed { index, episode ->
                    val episodeNum = episode.select("div.epnum").text().filter { it.isDigit() }
                        .ifEmpty { (index + 1).toString() }
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episode.attr("abs:href") + "watch/")
                        name = "$seasonTitle : الحلقة $episodeNum"
                        episode_number = ("$seasonNum.$episodeNum").toFloat()
                    }
                }
            }
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "ul li.server--item"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        val reqUrl = "https://${response.request.url.host}"
        val getUrl = "$reqUrl/wp-content/themes/movies2023/Ajaxat/Single/Server.php"
        val iHeaders = headers.newBuilder().apply {
            add("X-Requested-With", "XMLHttpRequest")
            set("Referer", reqUrl)
        }.build()
        return document.select(videoListSelector()).map {
            val formBody = FormBody.Builder()
                .add("id", it.attr("data-id"))
                .add("i", it.attr("data-server"))
                .build()
            val iframe = client.newCall(POST(getUrl, iHeaders, formBody)).execute().useAsJsoup()
            iframe.selectFirst("iframe")?.attr("src")?.substringAfter("php?to=")
        }.parallelCatchingFlatMapBlocking {
            if (it == null) return@parallelCatchingFlatMapBlocking emptyList()
            val url = if (it.startsWith("http")) it else "https://$it"
            extractVideos(url)
        }
    }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidTubeExtractor by lazy { VidTubeExtractor(client, headers) }

    private suspend fun extractVideos(url: String): List<Video> = when {
        doodExtractor.canHandleUrl(url) -> {
            doodExtractor.videosFromUrl(url)
        }
        luluExtractor.canHandleUrl(url) -> {
            luluExtractor.videosFromUrl(url)
        }
        mixDropExtractor.canHandleUrl(url) -> {
            mixDropExtractor.videosFromUrl(url)
        }
        streamTapeExtractor.canHandleUrl(url) -> {
            streamTapeExtractor.videosFromUrl(url)
        }
        streamWishExtractor.canHandleUrl(url) -> {
            streamWishExtractor.videosFromUrl(url)
        }
        uqloadExtractor.canHandleUrl(url) -> {
            uqloadExtractor.videosFromUrl(url)
        }
        vidHideExtractor.canHandleUrl(url) -> {
            vidHideExtractor.videosFromUrl(url)
        }
        vidTubeExtractor.canHandleUrl(url) -> {
            vidTubeExtractor.videosFromUrl(url)
        }
        else -> emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.url.contains("mp4") },

        )
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        GET("$baseUrl/search/?query=$query&type=all&offset=$page/", headers)
    } else {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.firstInstance<SectionFilter>()
        val genreFilter = filterList.firstInstance<GenreFilter>()
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (sectionFilter.state != 0) {
                addPathSegments(sectionFilter.toUriPart())
            } else {
                if (sectionFilter.state != 0) {
                    addPathSegments(sectionFilter.toUriPart())
                } else if (genreFilter.state != 0) {
                    addPathSegment("genre")
                    addPathSegment(genreFilter.toUriPart())
                } else {
                    throw Exception("من فضلك اختر قسم او تصنيف")
                }
                addPathSegments("page/$page")
            }
        }.build()
        GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        genre = document.select("ul.RightTaxContent li:contains(نوع) a")
            .mapNotNull { it.text().takeIf(String::isNotBlank)?.trim() }
            .joinToString()
        title = document.select("h1.post-title").text().let(::editTitle)
        author = document.select("ul.RightTaxContent li:contains(قسم) a").text()
        description = document.select("div.story").text().trim()
        status = SAnime.COMPLETED
        thumbnail_url = document.selectFirst("div.left div.image img")?.getImageUrl()
    }

    private fun editTitle(title: String, details: Boolean = false): String {
        REGEX_MOVIE.find(title)?.let { match ->
            val (movieName, type) = match.destructured
            return if (details) "$movieName ($type)".trim() else movieName.trim()
        }

        REGEX_SERIES.find(title)?.let { match ->
            val (seriesName, epNum) = match.destructured
            return when {
                details -> "$seriesName (ep:$epNum)".trim()
                seriesName.contains("الموسم") -> seriesName.substringBefore("الموسم").trim()
                else -> seriesName.trim()
            }
        }

        return title.trim()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("لن تعمل الفلاتر أثناء البحث"),
        SectionFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
    )

    private class SectionFilter :
        PairFilter(
            "اقسام الموقع",
            arrayOf(
                Pair("اختر", ""),
                Pair("افلام اجنبى", "category/افلام-اجنبي-8/"),
                Pair("افلام نتفليكس", "netflix-movies/"),
                Pair("سلاسل افلام", "movies-collections/"),
                Pair("افلام اسيوي", "category/افلام-اسيوي/"),
                Pair("افلام انمي", "category/افلام-انمي-2/"),
                Pair("الافلام الاعلي تقييما IMDB", "top-rating-imdb/"),

                Pair("أحدث الحلقات الأجنبي", "category/مسلسلات-اجنبي/?key=episodes"),
                Pair("قائمة المسلسلات الأجنبي", "category/مسلسلات-اجنبي/"),
                Pair("مسلسلات نتفليكس", "netflix-series/?cat=7"),
                Pair("مسلسلات كاملة", "full-packs/?cat=7"),
                Pair("المسلسلات الاعلي تقييما IMDB", "top-rating-imdb-series/"),

                Pair("أحدث الحلقات الأسيوى", "category/مسلسلات-اسيوية/?key=episodes"),
                Pair("قائمة المسلسلات الأسيوى", "category/مسلسلات-اسيوية/"),
                Pair("مسلسلات نتفليكس أسيوى", "netflix-series/?cat=9"),
                Pair("مسلسلات أسيوى كاملة", "full-packs/?cat=9"),

                Pair("أحدث حلقات الأنمى", "category/مسلسلات-انمي/?key=episodes"),
                Pair("قائمة مسلسلات الأنمى", "category/مسلسلات-انمي/"),
                Pair("مسلسلات نتفليكس أنمى", "netflix-series/?cat=8"),
                Pair("مسلسلات أنمى كاملة", "category/مسلسلات-انمي/?key=fullPack"),
            ),
        )

    private class GenreFilter :
        PairFilter(
            "التصنيف",
            arrayOf(
                Pair("اختر", ""),
                Pair("اكشن", "اكشن"),
                Pair("مغامرة", "مغامرة"),
                Pair("كرتون", "كرتون"),
                Pair("فانتازيا", "فانتازيا"),
                Pair("خيال-علمي", "خيال-علمي"),
                Pair("رومانسي", "رومانسي"),
                Pair("كوميدي", "كوميدي"),
                Pair("عائلي", "عائلي"),
                Pair("دراما", "دراما"),
                Pair("اثارة", "اثارة"),
                Pair("غموض", "غموض"),
                Pair("جريمة", "جريمة"),
                Pair("رعب", "رعب"),
                Pair("تاريخي", "تاريخي"),
                Pair("وثائقي", "وثائقي"),
            ),
        )

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun Element.getImageUrl(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }
        .substringBefore("?")
        .takeIf(String::isNotBlank)

    // =============================== Settings ===============================
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
            default = BASE_URL,
            title = "عنوان الموقع",
            dialogMessage = "أدخل عنوان الموقع (على سبيل المثال، https://example.com)",
            summary = preferences.customDomain,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "عنوان URL غير صالح أو مشوه أو ينتهي بشرطة مائلة" },
        )
    }
    companion object {
        private const val BASE_URL = "https://topcinemaa.com"
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val REGEX_MOVIE = Regex("""(?:فيلم|عرض)\s(.*\s\d+)\s(\S+)""")
        private val REGEX_SERIES = Regex("""(?:مسلسل|برنامج|انمي)\s(.+)\sالحلقة\s(\d+)""")
    }
}
