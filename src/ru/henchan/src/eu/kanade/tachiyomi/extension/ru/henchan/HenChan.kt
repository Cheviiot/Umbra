package eu.kanade.tachiyomi.extension.ru.henchan

import android.content.SharedPreferences
import eu.kanade.tachiyomi.multisrc.multichan.MultiChan
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class HenChan : MultiChan() {

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Site retired /mostfavorites (returns an empty maintenance page); closest equivalent
    // still alive is sorting the "newest" listing by favorites, descending.
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga/newest&n=favdesc?offset=${20 * (page - 1)}", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga/newest?offset=${20 * (page - 1)}", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("do", "search")
                .addQueryParameter("subaction", "search")
                .addQueryParameter("story", query)
                .addQueryParameter("search_start", page.toString())
                .build()
                .toString()
            return GET(url, headers)
        }

        var genres = ""
        val filterList = filters.ifEmpty { getFilterList() }

        filterList.forEach { filter ->
            if (filter is GenreList) {
                filter.state
                    .filter { !it.isIgnored() }
                    .forEach { f ->
                        genres += (if (f.isExcluded()) "-" else "") + f.id + '+'
                    }
            }
        }

        val orderBy = filterList.firstInstanceOrNull<OrderBy>()
        val url = if (genres.isNotEmpty()) {
            val order = orderBy?.toUriPartWithGenres() ?: ""
            "$baseUrl/tags/${genres.dropLast(1)}&sort=manga$order?offset=${20 * (page - 1)}"
        } else {
            val order = orderBy?.toUriPartWithoutGenres() ?: ""
            "$baseUrl/$order?offset=${20 * (page - 1)}"
        }

        return GET(url, headers)
    }

    override fun searchMangaSelector() = ".content_row:not(:has(div.item:containsOwn(Тип)))"

    private fun String.getHQThumbnail(): String {
        val isExHenManga = this.contains("/manganew_thumbs_blur/")
        val regex = manganewThumbsRegex
        return this.replace(regex, "showfull_retina/manga")
            .replace(
                "_".plus(URL(baseUrl).host),
                "_hentaichan.ru",
            ) // domain-related replacing for very old mangas
            .plus(
                if (isExHenManga) {
                    "#"
                } else {
                    ""
                },
            ) // # for later so we know what type manga is it
    }

    // Ongoing works get published one part at a time, each part its own separate manga entry
    // (e.g. "Друзья - часть 50"), so browsing/search listings show the same series many times
    // over. Clean the display title so duplicates collapse to the same string, then drop
    // repeats within the page. The url itself gets swapped for a cached canonical one when we
    // already know which group this part belongs to (see chapterListParse) - Komikku fixes a
    // manga's identity to whatever url it saw *here*, at listing time, and never updates it
    // again from mangaDetailsParse/getMangaUpdate results, so this is the only place a redirect
    // can actually take effect.
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = super.popularMangaFromElement(element)
        manga.thumbnail_url = element.selectFirst("img")?.attr("abs:src")?.getHQThumbnail()
        manga.title = manga.title.stripChapterSuffix()
        cachedCanonicalUrl(manga.url)?.let { manga.setUrlWithoutDomain(it) }
        return manga
    }

    private fun mangaIdFromUrl(url: String): String? = MANGA_ID_REGEX.find(url)?.groupValues?.get(1)

    private fun cachedCanonicalUrl(url: String): String? {
        val id = mangaIdFromUrl(url) ?: return null
        return preferences.getString(GROUP_PREF_PREFIX + id, null)
    }

    // Remembers which numbered parts belong together, keyed by each part's own id, so that the
    // *next* time any of them turns up in a listing we can point it at the same canonical url
    // right away - see the comment on popularMangaFromElement for why this has to happen there.
    private fun cacheChapterGroup(siblingUrlsOldestFirst: List<String>) {
        if (siblingUrlsOldestFirst.size < 2) return
        val canonicalUrl = siblingUrlsOldestFirst.first()
        val editor = preferences.edit()

        // A heavy reader browsing years of ongoing series would otherwise grow this file
        // forever (one key per part ever seen). Reset rather than let it creep past a size
        // where loading it starts costing noticeable startup time - losing the cache just
        // means the next few series encountered take the "first time" path again.
        val groupKeyCount = preferences.all.keys.count { it.startsWith(GROUP_PREF_PREFIX) }
        if (groupKeyCount > MAX_CACHED_GROUP_ENTRIES) {
            preferences.all.keys.filter { it.startsWith(GROUP_PREF_PREFIX) }.forEach { editor.remove(it) }
        }

        siblingUrlsOldestFirst.forEach { url ->
            mangaIdFromUrl(url)?.let { id -> editor.putString(GROUP_PREF_PREFIX + id, canonicalUrl) }
        }
        editor.apply()
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }.distinctByTitle()
        val hasNextPage = document.select(popularMangaNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector()).map { latestUpdatesFromElement(it) }.distinctByTitle()
        val hasNextPage = document.select(latestUpdatesNextPageSelector()).isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        var hasNextPage = false

        val mangas = document.select(searchMangaSelector()).map { searchMangaFromElement(it) }.distinctByTitle()

        val nextSearchPage = document.select(searchMangaNextPageSelector())
        if (nextSearchPage.isNotEmpty()) {
            val query = document.selectFirst("input#searchinput")?.attr("value") ?: ""
            val pageNum = nextSearchPage.let { selector ->
                val onClick = selector.attr("onclick")
                onClick.split("""\\d+""")
            }
            nextSearchPage.attr("href", "$baseUrl/?do=search&subaction=search&story=$query&search_start=$pageNum")
            hasNextPage = true
        }

        if (document.select(popularMangaNextPageSelector()).isNotEmpty()) {
            hasNextPage = true
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun List<SManga>.distinctByTitle(): List<SManga> {
        val seen = HashSet<String>()
        return filter { seen.add(it.title) }
    }

    // The marker isn't always at the end - e.g. "Gunjo Gunzo - часть 2. Я даже не знаю её
    // имени 2" has a per-part subtitle trailing after it. Cut at the first occurrence and
    // keep only what's before it, rather than only stripping a trailing match.
    private fun String.stripChapterSuffix(): String {
        val match = CHAPTER_MARKER_REGEX.find(this) ?: return this
        val truncated = this.substring(0, match.range.first).trim()
        return truncated.ifEmpty { this }
    }

    // Not delegating to super: the site renamed the "Тип" label to "Аниме/манга" and now
    // leaves a blank text node before the description's leading <br>, breaking both fields
    // in MultiChan's version of this parser.
    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("#info_wrap tr,#info_wrap > div")
        val rawCategory = infoElement.select(":contains(Аниме/манга) a").text().lowercase()

        val manga = SManga.create()
        manga.title = document.select("title").text().substringBefore(" »")
        manga.author = infoElement.select(":contains(Автор) .item2").text()
        manga.genre = rawCategory + ", " + document.select(".sidetags ul a:last-child").joinToString { it.text() }
        manga.status = when {
            infoElement.text().contains("перевод завершен") -> SManga.COMPLETED
            infoElement.text().contains("перевод продолжается") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        manga.description = document.selectFirst("div#description")
            ?.textNodes()
            ?.firstOrNull { it.text().isNotBlank() }
            ?.text()
        manga.thumbnail_url = document.selectFirst("img#cover")?.attr("abs:src")?.getHQThumbnail()

        return manga
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = client.newCall(chapterListRequest(manga))
        .asObservable().doOnNext { response ->
            if (!response.isSuccessful) {
                response.close()
                // Error message for exceeding last page
                if (response.code == 404) {
                    Observable.just(
                        listOf(
                            SChapter.create().apply {
                                url = manga.url
                                name = "Chapter"
                                chapter_number = 1f
                            },
                        ),
                    )
                } else {
                    throw Exception("HTTP error ${response.code}")
                }
            }
        }
        .map { response ->
            chapterListParse(response)
        }

    override fun chapterListRequest(manga: SManga): Request {
        val url = baseUrl + if (manga.thumbnail_url?.endsWith("#") == true) {
            manga.url
        } else {
            manga.url.replace("/manga/", "/related/")
        }
        return GET(url, headers)
    }

    override fun chapterListSelector() = ".related"

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseUrl = response.request.url.toString()
        val document = response.asJsoup()

        // exhentai chapter
        if (responseUrl.contains("/manga/")) {
            val chap = SChapter.create()
            chap.setUrlWithoutDomain(responseUrl)
            chap.name = document.select("a.title_top_a").text()
            chap.chapter_number = 1F

            val dateText = document.select("div.row4_right b").text()
            chap.date_upload = exhentaiDateFormat.tryParse(dateText)
            return listOf(chap)
        }

        // one chapter, nothing related
        val relatedText = document.select("#right > div:nth-child(4)").text()
        if (relatedText.contains(" похожий на ")) {
            val chap = SChapter.create()
            chap.setUrlWithoutDomain(document.selectFirst("#left > div > a")?.attr("abs:href") ?: "")
            chap.name = relatedText
                .split(" похожий на ")[1]
                .replace("\\\"", "\"")
                .replace("\\'", "'")
            chap.chapter_number = 1F
            return listOf(chap)
        }

        // has related chapters
        val result = mutableListOf<SChapter>()
        result.addAll(
            document.select(chapterListSelector()).map {
                chapterFromElement(it)
            },
        )

        var nextElement = document.selectFirst("div#pagination_related a:contains(Вперед)")
        while (nextElement != null) {
            val url = nextElement.attr("abs:href")
            if (url.isEmpty()) break

            val get = GET(url, headers = headers)
            val nextPage = client.newCall(get).execute().asJsoup()
            result.addAll(
                nextPage.select(chapterListSelector()).map {
                    chapterFromElement(it)
                },
            )

            nextElement = nextPage.selectFirst("div#pagination_related a:contains(Вперед)")
        }

        // Learn the whole group now so future listing scrapes (see popularMangaFromElement)
        // can point any of these parts at the same canonical (oldest/first) url right away,
        // instead of only ever fixing this manga.url - which Komikku won't let us touch again.
        cacheChapterGroup(result.map { it.url })

        return result.reversed()
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val aElement = element.selectFirst("h2 a")
        chapter.setUrlWithoutDomain(aElement?.attr("abs:href") ?: "")
        val chapterName = aElement?.attr("title") ?: ""
        chapter.name = chapterName
        chapter.chapter_number = chapterNumberRegex.find(chapterName)?.groupValues?.get(2)?.toFloat() ?: -1F
        return chapter
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.contains("/manga/")) {
            baseUrl + chapter.url.replace("/manga/", "/online/")
        } else {
            baseUrl + chapter.url
        }
        return GET(url, Headers.Builder().add("Accept", "image/webp,image/apng").build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val prefix = "fullimg\": ["
        val beginIndex = html.indexOf(prefix) + prefix.length
        val endIndex = html.indexOf("]", beginIndex)
        val trimmedHtml = html.substring(beginIndex, endIndex)
            .replace("\"", "")
            .replace("\'", "")

        val pageUrls = trimmedHtml.split(", ")
        return pageUrls.mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    override fun getFilterList() = FilterList(
        OrderBy(),
        GenreList(getGenreList()),
    )

    companion object {
        private val manganewThumbsRegex = "(?<=/)manganew_thumbs\\w*?(?=/)".toRegex(RegexOption.IGNORE_CASE)
        private val chapterNumberRegex = "(глава\\s|часть\\s)([0-9]+\\.?[0-9]*)".toRegex(RegexOption.IGNORE_CASE)

        // Matches "- часть N", "- Глава N", ", Глава N" etc. anywhere in a title, not just at
        // the end - e.g. "Gunjo Gunzo - часть 2. Я даже не знаю её имени 2" has it mid-string.
        private val CHAPTER_MARKER_REGEX = "[-—,]\\s*(?:часть|глава)\\.?\\s*\\d"
            .toRegex(RegexOption.IGNORE_CASE)
        private val MANGA_ID_REGEX = "/manga/(\\d+)-".toRegex()
        private const val GROUP_PREF_PREFIX = "chapter_group_"
        private const val MAX_CACHED_GROUP_ENTRIES = 5000
        private val exhentaiDateFormat by lazy {
            SimpleDateFormat("dd MMMM yyyy", Locale("ru"))
        }
    }
}
