package eu.kanade.tachiyomi.animeextension.ar.movizland

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Movizland :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "موفيز لاند"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { "https://w2.movizlands.com" }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.select("a").attr("title").let { editTitle(it, details = true) }
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        GET("$baseUrl/?s=$query&type=all&page=$page", headers)
    } else {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.filterIsInstance<SectionFilter>().first()
        val genreFilter = filterList.filterIsInstance<GenreFilter>().first()
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (sectionFilter.state != 0) {
                addPathSegments("category")
                addPathSegments(sectionFilter.toUriPart())
                if (genreFilter.state != 0) {
                    addQueryParameter("genre", genreFilter.toUriPart())
                }
            } else if (genreFilter.state != 0) {
                addPathSegments("genre")
                addPathSegments(genreFilter.toUriPart())
            } else {
                throw Exception("من فضلك اختر قسم او تصنيف")
            }
            addQueryParameter("page", page.toString())
        }
        GET(url.toString(), headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        genre = document.select("ul.RightTaxContent li:contains(نوع) a")
            .mapNotNull { it.text().takeIf(String::isNotBlank)?.trim() }
            .joinToString()
        title = document.select("h1.PostTitle").text().let(::editTitle)
        author = document.select("ul.RightTaxContent li:contains(دولة) a").text()
        description = document.select("div.StoryArea").text().trim()
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

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "section.allepcont a"

    private fun seasonListSelector(): String = "section.otherser div.Block--Item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val seasonsDOM = document.select(seasonListSelector())
        val episodeDOM = document.select(episodeListSelector())
        return if (seasonsDOM.isEmpty() && episodeDOM.isEmpty()) {
            SEpisode.create().apply {
                setUrlWithoutDomain("$url/watch/")
                name = "مشاهدة"
            }.let(::listOf)
        } else if (seasonsDOM.isEmpty()) {
            document.select(episodeListSelector()).mapIndexed { index, episode ->
                val episodeNum = episode.select("div.epnum").text().filter { it.isDigit() }
                    .ifEmpty { (index + 1).toString() }
                SEpisode.create().apply {
                    setUrlWithoutDomain(episode.attr("abs:href") + "watch/")
                    name = "الحلقة : $episodeNum"
                    episode_number = ("1.$episodeNum").toFloat()
                }
            }
        } else {
            val selectedSeason = document.selectFirst("div#my-breadcrumbs a span:contains(الموسم)")?.text()?.trim().orEmpty()
            seasonsDOM.reversed().map { season ->
                val seasonText = season.select("h3").text().trim()
                val seasonUrl = season.selectFirst("a")?.attr("abs:href") ?: return@map emptyList<SEpisode>()
                val seasonDoc = if (selectedSeason == seasonText) {
                    document
                } else {
                    client.newCall(GET(seasonUrl)).execute().asJsoup()
                }
                val seasonNum = if (seasonsDOM.size == 1) "1" else seasonText.filter { it.isDigit() }.ifEmpty { "0" }
                seasonDoc.select(episodeListSelector()).mapIndexed { index, episode ->
                    val episodeNum = episode.select("div.epnum").text().filter { it.isDigit() }
                        .ifEmpty { (index + 1).toString() }
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episode.attr("abs:href") + "watch/")
                        name = "$seasonText : الحلقة $episodeNum"
                        episode_number = ("$seasonNum.$episodeNum").toFloat()
                    }
                }
            }.flatten()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "ul#watch li"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking { selector ->
            val url = selector.attr("data-watch").let {
                val new = it.substringAfter("php?to=")
                if(new.startsWith("http")) new else "https://$new"
            }
            when {
                "voe" in url -> {
                    voeExtractor.videosFromUrl(url)
                }
                mixDropExtractor.canHandleUrl(url) -> {
                    mixDropExtractor.videoFromUrl(url)
                }
                doodExtractor.canHandleUrl(url) -> {
                    doodExtractor.videosFromUrl(url)
                }
                streamWishExtractor.canHandleUrl(url) -> {
                    streamWishExtractor.videosFromUrl(url)
                }
                else -> emptyList()
            }
        }
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("لن تعمل الفلاتر أثناء البحث"),
        AnimeFilter.Separator(),
        SectionFilter(),
        GenreFilter(),
    )

    private class SectionFilter :
        SingleFilter(
            "اقسام الموقع",
            arrayOf(
                "اختر",
                "افلام اجنبي",
                "افلام اجنبى مترجمه 2026",
                "افلام اجنبية مدبلجة",
                "افلام هندية",
                "افلام اسيوي",
                "افلام اسلام الجيزاوي",
                "مسلسلات اجنبي",
                "مسلسلات اسيوية",
                "مسلسلات تركية",
                "مسلسلات هندية",
                "مسلسلات وثائقية",
                "افلام انمي",
                "مسلسلات انمي",
                "افلام كرتون",
            ),
        )

    private class GenreFilter :
        SingleFilter(
            "التصنيف",
            arrayOf(
                "اختر",
                "اكشن",
                "مغامرة",
                "كرتون",
                "فانتازيا",
                "خيال-علمي",
                "رومانسي",
                "كوميدي",
                "عائلي",
                "دراما",
                "اثارة",
                "غموض",
                "جريمة",
                "رعب",
                "تاريخي",
                "وثائقي",
            ),
        )

    open class SingleFilter(displayName: String, private val vals: Array<String>) : AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state].replace(" ", "-")
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
            default = "",
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
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val REGEX_MOVIE = Regex("""(?:فيلم|عرض)\s(.*\s\d+)\s(\S+)""")
        private val REGEX_SERIES = Regex("""(?:مسلسل|برنامج|انمي)\s(.+)\sالحلقة\s(\d+)""")
    }
}
