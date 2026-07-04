package eu.kanade.tachiyomi.animeextension.ar.egydead

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamrubyextractor.StreamRubyExtractor
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
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class EgyDead :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Egy Dead"

    override val baseUrl
        get() = preferences.customDomain.ifBlank { BASE_URL }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", DEFAULT_UA)
    private val preferences by getPreferencesLazy()

    // ================================== popular ==================================

    override fun popularAnimeSelector(): String = "div.pin-posts-list li.movieItem"

    override fun popularAnimeNextPageSelector(): String = "div.whatever"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("h1.BottomTitle").text().let { editTitle(it, true) }
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
    }

    // ================================== latest ==================================

    override fun latestUpdatesSelector(): String = "section.main-section li.movieItem"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page/")

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // ================================== episodes ==================================
    override fun episodeListSelector() = "div.EpsList li a"
    private fun seasonListSelector() = "div.seasons-list li.movieItem a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val url = response.request.url.toString()

        return if (url.contains("/assembly/")) {
            document.select("div.salery-list li.movieItem a").map {
                SEpisode.create().apply {
                    setUrlWithoutDomain(it.attr("href"))
                    name = it.select(".BottomTitle").text().let(::editTitle)
                }
            }
        } else if (url.contains("/episode/")) {
            val seriesUrl = document.select("#breadcrumbs li a[itemprop=url]").attr("href")
            episodeListParse(client.newCall(GET(seriesUrl)).execute())
        } else if (url.contains("/serie/") || url.contains("/season/")) {
            val seasonsDOM = document.select(seasonListSelector())
            val episodeDom = document.select(episodeListSelector())
            if (seasonsDOM.isEmpty()) {
                episodeDom.map(::episodeFromElement)
            } else {
                document.select(seasonListSelector()).flatMap {
                    val season = it.select(".BottomTitle").text().substringAfter(" الموسم").substringAfter(" الجزء")
                    val seasonText = document.select(".div.singleTitle").text().trim()
                    val episodes = if (seasonText == it.text().trim()) {
                        episodeDom
                    } else {
                        client.newCall(GET(it.attr("abs:href"))).execute().useAsJsoup().select(episodeListSelector())
                    }
                    episodes.map { episode ->
                        SEpisode.create().apply {
                            setUrlWithoutDomain(episode.attr("abs:href"))
                            name = "الموسم $season : ${episode.select("a").text()}"
                        }
                    }
                }
            }
        } else {
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "مشاهدة"
            }.let(::listOf)
        }
    }
    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = element.text().filter { it.isDigit() }.toFloat()
    }

    // ================================== video urls ==================================
    private val webViewResolver by lazy { WebViewResolver(client, headers) }
    private val streamRubyExtractor by lazy { StreamRubyExtractor(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
//        val html = webViewResolver.resolve(baseUrl, episode.url)
        val formData = FormBody.Builder().add("View", "1").build()
        val request = POST(baseUrl + episode.url, headers, formData)
        val response = client.newCall(request).awaitSuccess().bodyString()
        return Video("https://", response, "https://").let(::listOf)
    }

    private suspend fun extractVideos(url: String): List<Video> = when {
        doodExtractor.canHandleUrl(url) -> {
            doodExtractor.videosFromUrl(url)
        }
        mixDropExtractor.canHandleUrl(url) -> {
            mixDropExtractor.videosFromUrl(url)
        }
        streamRubyExtractor.canHandleUrl(url) -> {
            streamRubyExtractor.videosFromUrl(url)
        }
        streamWishExtractor.canHandleUrl(url) -> {
            streamWishExtractor.videosFromUrl(url)
        }
        url.contains("voe") -> {
            voeExtractor.videosFromUrl(url)
        }

        else -> null
    } ?: emptyList()

    override fun videoListSelector() = "ul.serversList li"

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.url.contains("mp4") },

        )
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ================================== search ==================================

    override fun searchAnimeNextPageSelector(): String = "div.pagination-two a:contains(›), ul.page-numbers a.next"

    override fun searchAnimeSelector(): String = "div.catHolder li.movieItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        GET("$baseUrl/page/$page/?s=$query", headers)
    } else {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.firstInstance<SectionFilter>()
        val genreFilter = filterList.firstInstance<GenreFilter>()
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (sectionFilter.state != 0) {
                addPathSegments(sectionFilter.toUriPart())
            } else if (genreFilter.state != 0) {
                addPathSegment("type")
                addPathSegment(genreFilter.toUriPart())
            } else {
                throw Exception("من فضلك اختر قسم او تصنيف")
            }
            addPathSegments("page/$page")
        }.build()
        GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ================================== Filters ==================================
    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
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
                Pair("افلام اجنبي", "category/english-movies"),
                Pair("افلام كرتون", "category/افلام-كرتون"),
                Pair("افلام اسيوية", "category/افلام-اسيوية"),
                Pair("افلام هندية", "category/افلام-هندية"),
                Pair("افلام تركية", "category/افلام-تركية"),
                Pair("افلام عربي", "category/افلام-عربي"),
                Pair("افلام وثائقية", "category/افلام-وثائقية"),
                Pair("افلام اجنبية مدبلجة", "category/english-movies/افلام-اجنبية-مدبلجة"),
                Pair("سلاسل الافلام", "assembly"),
                Pair("افلام اسلام الجيزاوي", "category/ترجمات-اسلام-الجيزاوي"),
                Pair("افلام كرتون باللهجة المصرية", "category/افلام-كرتون/افلام-كرتون-ديزني-باللهجة-المصرية"),
                Pair("مسلسلات اجنبي", "series-category/english-series"),
                Pair("مسلسلات كرتون", "series-category/cartoon-series"),
                Pair("مسلسلات اسيوية", "series-category/asian-series"),
                Pair("مسلسلات تركية", "series-category/turkish-series"),
                Pair("مسلسلات لاتينية", "series-category/latino-series"),
                Pair("مسلسلات هندية", "series-category/indian-series"),
                Pair("مسلسلات وثائقية", "series-category/documentary-series"),
                Pair("مسلسلات عربي", "series-category/arabic-series"),
                Pair("مسلسلات افريقية", "series-category/african-series"),
                Pair("مسلسلات انمي", "series-category/anime-series"),
                Pair("افلام انمي", "category/افلام-انمي"),
                Pair("انميات ربيع 2026", "tag/انميات-ربيع-2026"),
                Pair("مسلسلات انمي مدبلجة", "series-category/anime-series-dubbed"),
                Pair("انميات شتاء 2026", "tag/انميات-شتاء-2026"),
                Pair("افلام انمي", "series-category/anime-movies"),
                Pair("انميات صينية", "series-category/chinese-anime"),
                Pair("انميات كورية", "series-category/korean-anime"),
                Pair("مسلسلات اجنبي مدبلجة", "series-category/english-series-dubbed"),
                Pair("مسلسلات تركية مدبلجة", "series-category/turkish-series-dubbed"),
                Pair("مسلسلات كرتون مدبلجة", "series-category/cartoon-series-dubbed"),
                Pair("مسلسلات لاتينية مدبلجة", "series-category/latino-series-dubbed"),
            ),
        )

    private class GenreFilter :
        PairFilter(
            "التصنيف",
            arrayOf(
                Pair("اختر", ""),
                Pair("اثارة", "thriller"),
                Pair("اكشن", "action"),
                Pair("انيميشن", "animation"),
                Pair("ايتشي", "ecchi"),
                Pair("ايدولز", "idols"),
                Pair("ايسيكاي", "isekai"),
                Pair("برامج حوارية", "talk-shows"),
                Pair("بوليسي", "detective"),
                Pair("تاريخي", "history"),
                Pair("تلفزيون الواقع", "reality-shows"),
                Pair("جريمة", "crime"),
                Pair("جوسي", "josei"),
                Pair("حربي", "war"),
                Pair("حريم", "harem"),
                Pair("خارق للطبيعة", "supernatural"),
                Pair("خيال علمي", "sci-fi"),
                Pair("دراما", "drama"),
                Pair("رعب", "horror"),
                Pair("رومانسي", "romance"),
                Pair("رياضي", "sports"),
                Pair("ساخر", "parody"),
                Pair("ساموراي", "samurai"),
                Pair("سنين", "seinen"),
                Pair("سيرة ذاتية", "biography"),
                Pair("شريحة من الحياة", "slice-of-life"),
                Pair("شوجو", "shoujo"),
                Pair("شونين", "shounen"),
                Pair("طبخ", "cooking"),
                Pair("طبي", "medical"),
                Pair("عائلي", "family"),
                Pair("عسكري", "military"),
                Pair("غموض", "mystery"),
                Pair("فانتازيا", "fantasy"),
                Pair("فضاء", "space"),
                Pair("فنون قتالية", "martial-arts"),
                Pair("فيلم نوار", "film-noir"),
                Pair("قصير", "short"),
                Pair("قوة خارقة", "super-power"),
                Pair("كلاسيك", "classic-movies"),
                Pair("كوميديا", "comedy"),
                Pair("لعبة", "game"),
                Pair("مدرسي", "school"),
                Pair("مسابقات", "game-show"),
                Pair("مصاصي دماء", "vampire"),
                Pair("مغامرة", "adventure"),
                Pair("موسيقي", "music"),
                Pair("ميكا", "mecha"),
                Pair("وثائقي", "documentary"),
                Pair("ويسترن", "western"),
            ),
        )

    // ================================== details ==================================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val fullTitle = document.select("div.infoBox div.singleTitle").text()
        thumbnail_url = document.select("div.single-thumbnail img").attr("src")
        title = fullTitle.let(::editTitle)
        author = document.select("div.LeftBox li:contains(البلد) a").text()
        artist = document.select("div.LeftBox li:contains(القسم) a").text()
        genre = document.select("div.LeftBox li:contains(النوع) a, div.LeftBox li:contains(اللغه) a, div.LeftBox li:contains(السنه) a").joinToString(", ") { it.text() }
        description = document.select("div.infoBox div.extra-content p").text()
        status = if (fullTitle.contains("كامل") || fullTitle.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
    }

    // =============================== Utils ===============================
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
    private fun Element.getImageUrl(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }
        .substringBefore("?")
        .takeIf(String::isNotBlank)

    // ================================== Preferences ==================================
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
            title = "رابط الموقع",
            dialogMessage = "أدخل رابط الموقع (على سبيل المثال، https://example.com)",
            summary = BASE_URL,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "عنوان URL غير صالح أو مشوه أو ينتهي بشرطة مائلة" },
        )
    }

    companion object {
        private const val BASE_URL = "https://tv9.egydead.live"
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val REGEX_MOVIE = Regex("""(?:فيلم|عرض)\s(.*\s\d+)\s(مترجم|مدبلج)""")
        private val REGEX_SERIES = Regex("""(?:مسلسل|برنامج|انمي)\s(.+)\sالحلقة\s(\d+)""")
        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
