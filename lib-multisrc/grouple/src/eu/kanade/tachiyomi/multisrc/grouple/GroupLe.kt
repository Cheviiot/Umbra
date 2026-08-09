package eu.kanade.tachiyomi.multisrc.grouple

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.get
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.string
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

abstract class GroupLe :
    KeiSource(),
    ConfigurableSource {

    private val dateFormat = SimpleDateFormat("dd.MM.yy", Locale.ROOT)

    private val preferences: SharedPreferences by getPreferencesLazy()

    protected open val isNeedAuth = false

    override val supportsLatest = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
        .addNetworkInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)
            if (originalRequest.url.toString().contains(baseUrl) && (
                    originalRequest.url.toString().contains("internal/redirect") || response.code == 301
                    )
            ) {
                if (originalRequest.url.toString().contains("/list?")) {
                    throw IOException("Смените домен: Поисковик > Расширения > $name > ⚙️")
                }
                throw IOException(
                    "URL серии изменился. Перенесите/мигрируйте с $name на $name (или смежный с GroupLe), чтобы список глав обновился",
                )
            }
            response
        }
        .rateLimit(2)

    private val uagent get() = preferences.getString(UAGENT_TITLE, UAGENT_DEFAULT)!!

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", uagent)

    // ============================== Popular ==============================
    override suspend fun getPopularManga(page: Int): MangasPage = parseMangasPage(client.get("$baseUrl/list?sortType=rate&offset=${50 * (page - 1)}").asJsoup())

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select("div.tile").map { element ->
            SManga.create().apply {
                thumbnail_url = element.selectFirst("img.lazy")?.let {
                    it.absUrl("data-original").ifEmpty { it.attr("data-original") }
                }?.replace("_p.", ".")
                element.selectFirst("h3 > a")?.let {
                    setUrlWithoutDomain(it.absUrl("href"))
                    title = it.attr("title")
                }
                // Every listing template renders each tile's genres as .elem_genre badges;
                // stash them here so a subclass can filter listings by genre without an extra
                // per-manga fetch (mangaDetailsParse below re-derives a fuller genre list from
                // the details page anyway, so this is only ever a short-lived placeholder).
                genre = element.select(".elem_genre").joinToString { it.text().trim() }
            }
        }
        val hasNextPage = document.selectFirst("a.nextLink") != null

        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangasPage(client.get("$baseUrl/list?sortType=updated&offset=${50 * (page - 1)}").asJsoup())

    // ============================== Search ===============================
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/advancedResults?offset=${50 * (page - 1)}".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }

        (filters.ifEmpty { getFilterList() }).forEach { filter ->
            when (filter) {
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(genre.id, arrayOf("=", "=in", "=ex")[genre.state])
                    }
                }

                is CategoryList -> filter.state.forEach { category ->
                    if (category.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(category.id, arrayOf("=", "=in", "=ex")[category.state])
                    }
                }

                is AgeList -> filter.state.forEach { age ->
                    if (age.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(age.id, arrayOf("=", "=in", "=ex")[age.state])
                    }
                }

                is MoreList -> filter.state.forEach { more ->
                    if (more.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(more.id, arrayOf("=", "=in", "=ex")[more.state])
                    }
                }

                is AdditionalFilterList -> filter.state.forEach { fils ->
                    if (fils.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(fils.id, arrayOf("=", "=in", "=ex")[fils.state])
                    }
                }

                is OrderBy -> {
                    url.addQueryParameter(
                        "sortType",
                        arrayOf("RATING", "POPULARITY", "YEAR", "NAME", "DATE_CREATE", "DATE_UPDATE", "USER_RATING")[filter.state],
                    )
                }

                else -> {}
            }
        }

        val document = client.get(url.toString().replace("=%3D", "=")).asJsoup()
        return parseMangasPage(document)
    }

    // A pasted https:// series url gets routed here automatically by KeiSource instead of
    // going through getSearchMangaList.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = mangaDetailsParse(client.get(url).asJsoup())
        .apply { setUrlWithoutDomain(url.toString()) }

    // ============================== Details ==============================
    open fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title =
            document.selectFirst(".cr-hero-names__main")?.text() ?: document.selectFirst("meta[itemprop=name]")?.attr("content").orEmpty()

        val details = mutableMapOf<String, String>()
        document.selectFirst(".cr-hero .cr-info-details")?.children()?.forEach { element ->
            val title = element.selectFirst(".cr-info-details-item__title")?.text()?.lowercase(Locale.ROOT).orEmpty()
            val value = element.selectFirst(".cr-info-details-item__status")?.text()?.lowercase(Locale.ROOT).orEmpty()

            if (title.isNotEmpty() && value.isNotEmpty() && !details.containsKey(title)) {
                details[title] = value
            }
        }

        val releaseStatus = details["выпуск"] ?: ""
        val translationStatus = details["перевод"] ?: ""

        manga.status = when {
            releaseStatus.contains("продолж") || releaseStatus.contains("начат") -> SManga.ONGOING

            releaseStatus.contains("заверш") -> if (translationStatus.contains("заверш")) {
                SManga.COMPLETED
            } else {
                SManga.PUBLISHING_FINISHED
            }

            releaseStatus.contains("приост") || releaseStatus.contains("заморож") -> SManga.ON_HIATUS

            else -> SManga.UNKNOWN
        }

        val authorNames = mutableListOf<String>()
        val artistNames = mutableListOf<String>()
        document.select(".cr-main-person-item").forEach { person ->
            val role = person.selectFirst(".cr-main-person-item__role")?.text()?.lowercase(Locale.ROOT).orEmpty()
            val name = person.selectFirst(".cr-main-person-item__name a, .cr-main-person-item__name")?.text()

            if (name.isNullOrBlank()) return@forEach
            when {
                role.contains("автор") || role.contains("сценар") -> authorNames += name
                role.contains("худож") || role.contains("иллюст") -> artistNames += name
            }
        }
        manga.author = authorNames.distinct().joinToString().takeIf { it.isNotBlank() }
        manga.artist = artistNames.distinct().joinToString().takeIf { it.isNotBlank() }

        val category = document.selectFirst(".cr-hero-short-details a[href*=\"/list/category/\"]")?.text().orEmpty()
        val age = normalizeAgeRating(
            document.selectFirst(".cr-hero-short-details a[href*=\"/list/limitation/\"]")?.text().orEmpty(),
        )
        val tags = document.select(".cr-tags .cr-tags__item").mapNotNull { tag ->
            tag.select("span").last()?.text()?.takeIf { it.isNotEmpty() }
        }

        manga.genre = listOf(category, age).asSequence()
            .plus(tags)
            .map { it.lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()

        val altNames = document.select("#alt-names-dialog .modal-body .py-1")
            .mapNotNull { it.text().takeIf(String::isNotEmpty) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { "Альтернативные названия:\n${it.joinToString(" / ")}\n\n" } ?: ""

        val ratingValue = document.selectFirst(".cr-hero-rating .cr-hero-rating__value")?.text()?.toFloatOrNull()

        val ratingSummary = ratingValue?.let { rating ->
            val ratingVotes = document.selectFirst(".cr-hero-rating__text")?.text()?.filter { it.isDigit() } ?: "0"

            "${ratingToStars(rating)} $rating (голосов: $ratingVotes)\n"
        } ?: ""

        val descriptionText = document.selectFirst(".cr-description__content")?.text().orEmpty()

        manga.description = ratingSummary + altNames + descriptionText

        val thumbElement = document.selectFirst(".cr-hero-poster__img") ?: document.selectFirst(".cr-hero-overlay__bg")
        manga.thumbnail_url = thumbElement?.let { element ->
            element.absUrl("src").ifEmpty { element.absUrl("data-src") }
                .ifEmpty { element.absUrl("data-original") }
                .ifEmpty { element.absUrl("data-bg") }
        }.orEmpty()

        return manga
    }

    protected fun normalizeAgeRating(rawAgeValue: String): String = when (rawAgeValue) {
        "NC-17", "R18+" -> "18+"
        "R", "G", "PG" -> "16+"
        "PG-13" -> "12+"
        else -> rawAgeValue
    }

    protected fun ratingToStars(ratingValue: Float): String = when {
        ratingValue > 9.5f -> "★★★★★"
        ratingValue > 8.5f -> "★★★★✬"
        ratingValue > 7.5f -> "★★★★☆"
        ratingValue > 6.5f -> "★★★✬☆"
        ratingValue > 5.5f -> "★★★☆☆"
        ratingValue > 4.5f -> "★★✬☆☆"
        ratingValue > 3.5f -> "★★☆☆☆"
        ratingValue > 2.5f -> "★✬☆☆☆"
        ratingValue > 1.5f -> "★☆☆☆☆"
        ratingValue > 0.5f -> "✬☆☆☆☆"
        else -> "☆☆☆☆☆"
    }

    open class OrderBy :
        Filter.Select<String>(
            "Сортировка",
            arrayOf("По популярности", "Популярно сейчас", "По году", "По алфавиту", "Новинки", "По дате обновления", "По рейтингу"),
        )

    open class Genre(name: String, val id: String) : Filter.TriState(name)

    open class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Жанры", genres)
    open class CategoryList(categories: List<Genre>) : Filter.Group<Genre>("Категории", categories)
    open class AgeList(ages: List<Genre>) : Filter.Group<Genre>("Возрастная рекомендация", ages)
    open class MoreList(more: List<Genre>) : Filter.Group<Genre>("Прочее", more)
    open class AdditionalFilterList(fils: List<Genre>) : Filter.Group<Genre>("Фильтры", fils)

    // ============================ Filter data =============================
    // The genre/category/etc checkboxes on /search/advanced are grouped under a label
    // ("Жанры", "Категории", "Фильтры", ...) with each option as an <li class="property">
    // holding a hidden `el_*`/`s_*`-named input and a labelled span. Scrape it generically
    // here; subclasses turn named groups back into their own filter lists via [filterGroup],
    // falling back to their own hardcoded list when a group wasn't fetched (yet, or ever -
    // some sites don't have every group this scrapes for).
    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/search/advanced").asJsoup()
        return buildJsonArray {
            document.select("div.form-group.row").forEach { row ->
                val groupName = row.selectFirst(".col-form-label a")?.text()?.trim()
                    ?: row.selectFirst(".col-form-label")?.ownText()?.trim()
                if (groupName.isNullOrBlank()) return@forEach

                val options = row.select("li.property").mapNotNull { li ->
                    val id = li.selectFirst("input[name]")?.attr("name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val optionName = li.selectFirst("span[title]")?.attr("title")?.trim()?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    id to optionName
                }
                if (options.isEmpty()) return@forEach

                addJsonObject {
                    put("group", groupName)
                    putJsonArray("options") {
                        options.forEach { (id, optionName) ->
                            addJsonObject {
                                put("id", id)
                                put("name", optionName)
                            }
                        }
                    }
                }
            }
        }
    }

    protected fun JsonElement?.filterGroup(groupName: String): List<Genre>? = this?.jsonArray
        ?.firstOrNull { it["group"]?.stringOrNull == groupName }
        ?.get("options")?.jsonArray
        ?.mapNotNull { option ->
            val id = option["id"]?.stringOrNull ?: return@mapNotNull null
            Genre(option["name"]!!.string, id)
        }
        ?.takeIf { it.isNotEmpty() }

    // ============================= Chapters ==============================
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        val updatedManga = if (fetchDetails) mangaDetailsParse(document) else manga

        val updatedChapters = if (!fetchChapters) {
            chapters
        } else if (updatedManga.status == SManga.LICENSED) {
            throw Exception("Лицензировано - Нет глав")
        } else {
            authGuard(document)
            val chapterSearchParams = getChapterSearchParams(document)
            document.select("tr.item-row:has(td > a):has(td.date:not(.text-info))")
                .map { chapterFromElement(it, updatedManga, chapterSearchParams) }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    protected open fun getChapterSearchParams(document: Document): String {
        val scriptContent = document.selectFirst("script:containsData(user_hash)")?.data()
        val userHash = scriptContent?.let { USER_HASH_REGEX.find(it)?.groupValues?.get(1) }
        return userHash?.let { "?d=$it&mtr=true" } ?: "?mtr=true"
    }

    private fun chapterFromElement(element: Element, manga: SManga, chapterSearchParams: String): SChapter {
        val urlElement = element.selectFirst("a.chapter-link")!!
        val chapterInf = element.selectFirst("td.item-title")!!
        val urlText = urlElement.text()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.absUrl("href") + chapterSearchParams)

        chapter.scanlator = chapterScanlatorFromElement(urlElement, element)

        chapter.name = urlText.removeSuffix(" новое").trim()
        if (manga.title.length > 25) {
            for (word in manga.title.split(' ')) {
                chapter.name = chapter.name.removePrefix(word).trim()
            }
        }
        val dots = chapter.name.indexOf("…")
        val numbers = chapter.name.findAnyOf(IntRange(0, 9).map { it.toString() })?.first ?: 0

        if (dots in 0 until numbers) {
            chapter.name = chapter.name.substringAfter("…").trim()
        }

        chapter.chapter_number = chapterInf.attr("data-num").toFloat() / 10

        chapter.date_upload = dateFormat.tryParse(element.select("td.d-none").last()?.text())

        // KeiSource has no prepareNewChapter hook (final no-op in the base class), so this
        // runs inline instead of as a separate post-processing pass over the built chapter.
        when {
            EXTRA_REGEX.containsMatchIn(chapter.name) -> {
                if (chapter.name.substringAfter("Экстра").isBlank()) {
                    chapter.name = chapter.name.replaceFirst(
                        " ",
                        " - " + DecimalFormat("#,###.##").format(chapter.chapter_number).replace(",", ".") + " ",
                    )
                }
            }

            SINGLE_REGEX.containsMatchIn(chapter.name) -> {
                if (chapter.name.substringAfter("Сингл").isBlank()) {
                    chapter.name = DecimalFormat("#,###.##").format(chapter.chapter_number).replace(",", ".") + " " + chapter.name
                }
            }
        }

        return chapter
    }

    protected open fun chapterScanlatorFromElement(chapterLinkElement: Element, chapterRowElement: Element): String {
        val translatorElement = chapterLinkElement.attr("title")
        return translatorElement.takeIf { it.isNotBlank() }?.replace("(Переводчик),", "&")?.removeSuffix(" (Переводчик)") ?: ""
    }

    // =============================== Pages ===============================
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url)
        val document = response.asJsoup()

        authGuard(document)

        val html = document.html()

        val readerMark = when {
            html.contains("rm_h.readerInit(") -> "rm_h.readerInit("

            html.contains("rm_h.readerDoInit(") -> "rm_h.readerDoInit("

            !response.request.url.toString().contains(baseUrl) -> {
                throw Exception("Не удалось загрузить главу. Url: ${response.request.url}")
            }

            else -> {
                if (document.selectFirst("div.alert") != null || document.selectFirst("form.purchase-form") != null) {
                    throw Exception("Эта глава платная. Используйте сайт, чтобы купить и прочитать ее.")
                }
                throw Exception("Дизайн сайта обновлен, для дальнейшей работы необходимо обновление дополнения")
            }
        }

        val beginIndex = html.indexOf(readerMark)
        val endIndex = html.indexOf(");", beginIndex)
        val trimmedHtml = html.substring(beginIndex, endIndex)

        val p = Pattern.compile("""\[['"](.*?)['"],['"](.*?)['"],['"](.*?)['"].*?]""")
        val m = p.matcher(trimmedHtml)

        val pages = mutableListOf<Page>()

        var i = 0
        while (m.find()) {
            val host = m.group(1) ?: ""
            val middle = m.group(2) ?: ""
            val end = m.group(3) ?: ""
            var imageUrl = if (middle.isBlank() && end.startsWith("/static/")) {
                baseUrl + end
            } else {
                if (middle.endsWith("/manga/")) {
                    host + end
                } else {
                    middle + host + end
                }
            }
            if (!imageUrl.contains("://")) {
                imageUrl = "https:$imageUrl"
            }
            if (imageUrl.contains("one-way.work")) {
                // domain that does not need a token
                imageUrl = imageUrl.substringBefore("?")
            }
            pages.add(Page(i++, imageUrl = imageUrl.replace("//resh", "//h")))
        }
        return pages
    }

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
            add("Referer", "$baseUrl/")
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }

    // ============================= Utilities =============================
    private fun authGuard(document: Document) {
        if (document.select(".user-avatar").isEmpty() && isNeedAuth) {
            throw Exception("Для просмотра контента необходима авторизация через WebView🌎")
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = UAGENT_TITLE
            title = UAGENT_TITLE
            summary = uagent
            setDefaultValue(UAGENT_DEFAULT)
            dialogTitle = UAGENT_TITLE
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(
                    screen.context,
                    "Для смены User-Agent необходимо перезапустить приложение с полной остановкой.",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.let(screen::addPreference)
    }

    companion object {
        private const val UAGENT_TITLE = "User-Agent(для некоторых стран)"
        private const val UAGENT_DEFAULT = "arora"
        private val USER_HASH_REGEX = "user_hash.+'(.+)'".toRegex()
        private val EXTRA_REGEX = Regex("""\s*([0-9]+\sЭкстра)\s*""")
        private val SINGLE_REGEX = Regex("""\s*Сингл\s*""")
    }
}
