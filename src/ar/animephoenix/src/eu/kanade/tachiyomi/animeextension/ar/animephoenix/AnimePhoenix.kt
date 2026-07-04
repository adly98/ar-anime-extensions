package eu.kanade.tachiyomi.animeextension.ar.animephoenix

import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.useAsJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import kotlin.toString

class AnimePhoenix :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime Phoenix"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { BASE_URL }

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.FJ-episode-wrap"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/completed/page/$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.select(".FJ-Phoenix-Anastasia-EpCard-Name").text()
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
    }

    override fun popularAnimeNextPageSelector(): String = "div.FJ-Phoenix-Anastasia-Pagination a.next"

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.FJ-EpsGrid a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.useAsJsoup()
        val url = response.request.url.toString()
        val episodes = mutableListOf<SEpisode>()
        if (url.contains("movies")) {
            val episode = SEpisode.create().apply {
                setUrlWithoutDomain("$url/watch")
                name = "مشاهدة"
            }
            episodes.add(episode)
        } else {
            val lastEpisode = document.select("div.FJ-EpsGrid a").attr("abs:href")
            val lastEpNum = lastEpisode.substringAfterLast("-").toInt()
            for (i in 1..lastEpNum) {
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(lastEpisode.substringBeforeLast("-") + "-$i")
                    name = "الحلقة $i"
                    episode_number = i.toFloat()
                }
                episodes.add(episode)
            }
        }
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "a.FJ-Server-Link[data-server]"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.useAsJsoup()
        return document.select(videoListSelector()).map(::videoFromElement)
    }

    override fun videoFromElement(element: Element): Video {
        val data = String(Base64.decode(element.attr("data-server"), Base64.DEFAULT), Charsets.UTF_8)
        val jData = URLDecoder.decode(data, "UTF-8").toString().trim()
        val server = json.decodeFromString<ServerDTO>(jData)
        return Video(server.link, server.name, server.link)
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/?query=$query&type=all&offset=$page/", headers)
        } else {
            val sectionFilter = filterList.firstInstance<SectionFilter>()
            val genreFilter = filterList.firstInstance<GenreFilter>()
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (sectionFilter.state != 0) {
                    addPathSegments(sectionFilter.toUriPart())
                } else if (genreFilter.state != 0) {
                    addPathSegments("search")
                    addPathSegment(genreFilter.toUriPart())
                } else {
                    throw Exception("من فضلك اختر قسم او تصنيف")
                }
                addQueryParameter("paged", page.toString())
            }.build()
            throw Exception(url.toString())
            // return GET(url, headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        genre = document.select("div.FJ-Modern-Tags:contains(الأقسام) a")
            .mapNotNull { it.text().takeIf(String::isNotBlank)?.trim() }
            .joinToString()
        title = document.select("h1.FJ-Phoenix-Hero-Title").text()
        author = document.select("div.FJ-Modern-Info-Item:contains(استوديو) span.FJ-Modern-Val").text()
        description = document.select("div.FJ-Phoenix-Desc-Full").text().trim()
        status = when (document.select("div.FJ-Modern-Info-Item:contains(الحالة) span.FJ-Modern-Val").text()) {
            "مُكتمل" -> SAnime.COMPLETED
            "مستمر" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        thumbnail_url = document.selectFirst("div.FJ-Phoenix-Hero-Poster img")?.getImageUrl()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesSelector(): String = "a.FJ-Phoenix-Anastasia-EpCard"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodes/page/$page", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        val name = element.select(".FJ-Phoenix-Anastasia-EpCard-Name").text()
        val details = element.select(".FJ-Phoenix-Anastasia-EpCard-MetaModern")
        val season = details.select("span.season").text().trim()
        val episode = details.select("span.episode").text().filter { it.isDigit() }
        title = "$name ($season) [$episode]"
        thumbnail_url = element.selectFirst("img")?.getImageUrl()
        val url = element.attr("abs:href").replace("episodes", "animes").let {
            val anime = it.substringAfterLast("/").split("-")
            val epNum = anime.last()
            val animeUrl = anime.dropLast(2).joinToString("-")
            it.replaceAfterLast("/", "$animeUrl#ep-$epNum")
        }
        setUrlWithoutDomain(url)
    }

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
                Pair("أنيميات جارية", "search/releasing"),
                Pair("أفلام", "movies"),
            ),
        )

    private class GenreFilter :
        UniFilter(
            "التصنيف",
            arrayOf("اختر") +
                arrayOf(
                    "Action",
                    "Adult Cast",
                    "Adventure",
                    "Animation",
                    "Anthropomorphic",
                    "Award Winning",
                    "Bluray",
                    "Cars",
                    "Childcare",
                    "Combat Sports",
                    "Comedy",
                    "Crime",
                    "Delinquents",
                    "Dementia",
                    "Demons",
                    "Detective",
                    "Drama",
                    "Family",
                    "Fantasy",
                    "Gag Humor",
                    "Game",
                    "Gore",
                    "Gourmet",
                    "High Stakes Game",
                    "Historical",
                    "Horror",
                    "Isekai",
                    "Iyashikei",
                    "Josei",
                    "Kids",
                    "Love Polygon",
                    "Magic",
                    "Mahou Shoujo",
                    "Martial Arts",
                    "Mecha",
                    "Medical",
                    "Military",
                    "Music",
                    "Musical",
                    "Mystery",
                    "Mythology",
                    "Organized Crime",
                    "Otaku Culture",
                    "Parody",
                    "Police",
                    "Psychological",
                    "Racing",
                    "Reincarnation",
                    "Romance",
                    "Samurai",
                    "School",
                    "Sci-Fi",
                    "Seinen",
                    "Shoujo",
                    "Shounen",
                    "Showbiz",
                    "Slice of Life",
                    "Space",
                    "Sports",
                    "Strategy Game",
                    "Super Power",
                    "Supernatural",
                    "Suspense",
                    "Team Sports",
                    "Thriller",
                    "Time Travel",
                    "Vampire",
                    "Video Game",
                    "Visual Arts",
                    "Workplace",
                ),
        )

    open class UniFilter(displayName: String, private val vals: Array<String>) : AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state].lowercase().replace(" ", "-")
    }
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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

    @Serializable
    class ServerDTO(val name: String, val link: String)
    companion object {
        private const val BASE_URL = "https://anime-phoenix.com"
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
    }
}
