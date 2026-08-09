package eu.kanade.tachiyomi.extension.ru.henchan

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
    // repeats within the page.
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = super.popularMangaFromElement(element)
        manga.thumbnail_url = element.selectFirst("img")?.attr("abs:src")?.getHQThumbnail()
        manga.title = manga.title.stripChapterSuffix()
        return manga
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

    private fun String.stripChapterSuffix(): String {
        val stripped = CHAPTER_SUFFIX_REGEX.replace(this, "").trim()
        return stripped.ifEmpty { this }
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

        // Same fragmentation as above, but the consequence here is worse: each part is a
        // distinct manga.url, so Komikku treats every part as an unrelated title and reading
        // history never carries over between them. Redirect once to whichever entry the
        // site's own "related" listing puts first (oldest/root) so every part of a series
        // converges on the same library entry, no matter which one was opened.
        if (CHAPTER_SUFFIX_REGEX.containsMatchIn(manga.title)) {
            val relatedUrl = document.baseUri().replace("/manga/", "/related/")
            val rootLink = runCatching {
                client.newCall(GET(relatedUrl, headers)).execute().use { it.asJsoup() }
            }.getOrNull()?.selectFirst("${chapterListSelector()} h2 a")

            if (rootLink != null && rootLink.attr("abs:href") != document.baseUri()) {
                manga.title = rootLink.attr("title")
                manga.setUrlWithoutDomain(rootLink.attr("abs:href"))
            }
        }

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

        // Matches a trailing "- часть N", "- часть N (стр. X-Y)", "- Глава N", ", Глава N" etc.
        private val CHAPTER_SUFFIX_REGEX = "\\s*[-—,]\\s*(?:часть|глава)\\.?\\s*\\d+(?:\\.\\d+)?(?:\\s*\\([^)]*\\))?\\s*$"
            .toRegex(RegexOption.IGNORE_CASE)
        private val exhentaiDateFormat by lazy {
            SimpleDateFormat("dd MMMM yyyy", Locale("ru"))
        }
    }
}
