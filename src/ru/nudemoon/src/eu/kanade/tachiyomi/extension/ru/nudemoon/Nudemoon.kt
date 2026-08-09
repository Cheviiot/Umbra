package eu.kanade.tachiyomi.extension.ru.nudemoon

import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Nudemoon : HttpSource() {
    override val supportsLatest = true
    private val dateParseRu = SimpleDateFormat("d MMMM yyyy", Locale("ru"))
    private val domain get() = baseUrl.toHttpUrl().host
    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")

    override val client by lazy {
        network.client.newBuilder()
            .addNetworkInterceptor(CookieInterceptor(domain, listOf("NMfYa" to "1", "nm_mobile" to "1", "Domain" to domain)))
            .addInterceptor(CloudflareWebViewInterceptor(domain))
            .build()
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/all_manga?views&rowstart=${30 * (page - 1)}", headers)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/all_manga?date&rowstart=${30 * (page - 1)}", headers)

    override fun getFilterList(): FilterList = getFilters()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            "$baseUrl/search?stext=${URLEncoder.encode(query, "CP1251")}&rowstart=${30 * (page - 1)}"
        } else {
            val currentFilters = if (filters.isEmpty()) getFilterList() else filters
            val genreList = currentFilters.firstInstanceOrNull<GenreList>()
            val orderFilter = currentFilters.firstInstanceOrNull<OrderBy>()

            val genres = buildString {
                genreList?.state?.forEach { f ->
                    if (f.state) append(f.id).append('+')
                }
            }

            val orderIndex = orderFilter?.state?.index ?: 1
            val order = if (genres.isNotEmpty()) {
                arrayOf("&date", "&views", "&like")[orderIndex]
            } else {
                arrayOf("all_manga?date", "all_manga?views", "all_manga?like")[orderIndex]
            }

            val path = if (genres.isNotEmpty()) "tags/${genres.dropLast(1)}$order" else order
            "$baseUrl/$path&rowstart=${30 * (page - 1)}"
        }
        return GET(url, headers)
    }

    private val mangaSelector = "table.news_pic2"
    private val nextPageSelector = "a.small:contains(>)"

    // Each card's title sits in its own nested table.news_pic2, so a plain select()
    // also matches that inner table as a second, thumbnail-less "card" for the same entry.
    private fun Element.mangaCards(): List<Element> {
        val all = select(mangaSelector)
        return all.filterNot { el -> el.parents().any(all::contains) }
    }

    private fun parseMangaElement(element: Element): SManga? {
        val manga = SManga.create()
        manga.thumbnail_url = element.selectFirst("a img")?.attr("abs:src")
        element.selectFirst("a:has(h2)")?.let {
            manga.title = it.text().substringBefore(" / ").substringBefore(" №")
            manga.setUrlWithoutDomain(it.attr("href"))
            return manga
        }
        return null
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.mangaCards().mapNotNull(::parseMangaElement)
        val hasNextPage = document.selectFirst(nextPageSelector) != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()
        val infoElement = document.selectFirst(mangaSelector)
        manga.title = document.selectFirst("h1")?.text()?.substringBefore(" / ")?.substringBefore(" №") ?: ""
        manga.author = infoElement?.selectFirst("a[href*=mangaka]")?.text()
        manga.genre = infoElement?.select("div.tag-links a")?.joinToString { it.text() }
        manga.description = document.selectFirst(".description")?.text()
        manga.thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
        return manga
    }

    override fun chapterListParse(response: Response): List<SChapter> = mutableListOf<SChapter>().apply {
        val document = response.asJsoup()
        document.selectFirst("td.button a:contains(Все главы)")?.let { allPageElement ->
            var pageListLink = allPageElement.absUrl("href")
            var hasNextPage = true
            while (hasNextPage) {
                client.newCall(GET(pageListLink, headers)).execute().use { res ->
                    if (!res.isSuccessful) throw Exception("HTTP error ${res.code}")
                    val pageDoc = res.asJsoup()
                    val chapters = pageDoc.mangaCards().mapNotNull { element ->
                        SChapter.create().apply {
                            val nameAndUrl = element.selectFirst("tr[valign=top] a:has(h2)")
                            name = nameAndUrl?.selectFirst("h2")?.text() ?: return@mapNotNull null
                            setUrlWithoutDomain(nameAndUrl.attr("abs:href"))
                            val informBlock = element.selectFirst("tr[valign=top] td[align=left]")
                            scanlator = informBlock?.selectFirst("a[href*=perevod]")?.text()
                            date_upload = informBlock?.selectFirst("""span.small2:matches((0[1-9]|[12][0-9]|3[01])*(19|20)\d{2})""")?.text()?.let { text ->
                                dateParseRu.tryParse(text.replace("Май", "Мая"))
                            } ?: 0L
                            chapter_number = name.substringAfter("№").substringBefore(" ").toFloatOrNull() ?: -1f
                        }
                    }
                    if (chapters.isEmpty()) {
                        add(chapterFromSinglePage(document, response.request.url.toString()))
                        break
                    }
                    addAll(chapters)
                    val nextPageElement = pageDoc.selectFirst(nextPageSelector)
                    if (nextPageElement != null) {
                        pageListLink = nextPageElement.absUrl("href")
                    } else {
                        hasNextPage = false
                    }
                }
            }
        } ?: run {
            add(chapterFromSinglePage(document, response.request.url.toString()))
        }
    }

    private fun chapterFromSinglePage(document: Document, responseUrl: String): SChapter = SChapter.create().apply {
        val chapterName = document.selectFirst("table td.bg_style1 h1")?.text()
        name = "$chapterName Сингл"
        setUrlWithoutDomain(responseUrl)
        if (url.contains(baseUrl)) {
            url = url.replace(baseUrl, "")
        }
        scanlator = document.selectFirst("table.news_pic2 a[href*=perevod]")?.text()
        date_upload = document.selectFirst("""table.news_pic2 span.small2:matches((0[1-9]|[12][0-9]|3[01])*(19|20)\d{2})""")?.text()?.let { text ->
            dateParseRu.tryParse(text.replace("Май", "Мая"))
        } ?: 0L
        chapter_number = 0F
    }

    // The chapter's own url is the details page (no reader on it); the actual reader lives
    // at the same url with "-online" spliced in right after the numeric id, e.g.
    // "/8152--foo.html" -> "/8152-online--foo.html". It embeds every page's url as a
    // `images[N].src = '...'` assignment in one <script>, so a single request gets them all.
    override fun pageListRequest(chapter: SChapter): Request {
        val onlineUrl = chapter.url.replaceFirst(ONLINE_URL_REGEX, "$1-online--")
        return GET(baseUrl + onlineUrl, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val pages = IMAGE_SRC_REGEX.findAll(html).mapIndexed { index, match ->
            val src = match.groupValues[1]
            Page(index, imageUrl = if (src.startsWith("http")) src else baseUrl + src)
        }.toList()
        if (pages.isEmpty() && !cookieManager.getCookie(baseUrl).contains("fusion_user")) {
            throw Exception("Страницы не найдены. Возможно необходима авторизация в WebView")
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private val ONLINE_URL_REGEX = """^(/\d+)--""".toRegex()
        private val IMAGE_SRC_REGEX = """images\[\d+]\.src = '([^']+)'""".toRegex()
    }
}
