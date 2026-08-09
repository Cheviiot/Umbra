package eu.kanade.tachiyomi.extension.ru.allhentai

import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.grouple.GroupLe
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Response

@Source
abstract class AllHentai : GroupLe() {

    override val isNeedAuth get() = baseUrl != "https://x.ahen.me"

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun getFilterList() = FilterList(
        OrderBy(),
        CategoryList(getCategoryList()),
        GenreList(getGenreList()),
        AdditionalFilterList(getAdditionalFilterList()),
    )

    private fun getGenreList() = listOf(
        Genre("ahegao", "el_855"),
        Genre("анал", "el_828"),
        Genre("бдсм", "el_78"),
        Genre("без цензуры", "el_888"),
        Genre("большая грудь", "el_837"),
        Genre("большая попка", "el_3156"),
        Genre("большой член", "el_884"),
        Genre("бондаж", "el_5754"),
        Genre("в первый раз", "el_811"),
        Genre("в цвете", "el_290"),
        Genre("гарем", "el_87"),
        Genre("гендарная интрига", "el_89"),
        Genre("групповой секс", "el_88"),
        Genre("драма", "el_95"),
        Genre("зрелые женщины", "el_5679"),
        Genre("измена", "el_291"),
        Genre("изнасилование", "el_124"),
        Genre("инцест", "el_85"),
        Genre("исторический", "el_93"),
        Genre("комедия", "el_73"),
        Genre("маленькая грудь", "el_870"),
        Genre("научная фантастика", "el_76"),
        Genre("нетораре", "el_303"),
        Genre("оральный секс", "el_853"),
        Genre("романтика", "el_74"),
        Genre("тентакли", "el_69"),
        Genre("трагедия", "el_1321"),
        Genre("ужасы", "el_75"),
        Genre("футанари", "el_77"),
        Genre("фэнтези", "el_70"),
        Genre("чикан", "el_1059"),
        Genre("этти", "el_798"),
    )

    private fun getCategoryList() = listOf(
        Genre("3D", "el_626"),
        Genre("Анимация", "el_5777"),
        Genre("Без текста", "el_3157"),
        Genre("Манхва", "el_1104"),
        Genre("Маньхуа", "el_5902"),
        Genre("Порно комикс", "el_1003"),
        Genre("Руманга", "el_5896"),
    )

    private fun getAdditionalFilterList() = listOf(
        Genre("Высокий рейтинг", "s_high_rate"),
        Genre("Сингл", "s_single"),
        Genre("Для взрослых", "s_mature"),
        Genre("Завершенная", "s_completed"),
        Genre("Переведено", "s_translated"),
        Genre("Заброшен перевод", "s_abandoned_popular"),
        Genre("Длинная", "s_many_chapters"),
        Genre("Ожидает загрузки", "s_wait_upload"),
        Genre("Лицензия", "s_sale"),
        Genre("Белые жанры", "s_not_pessimized"),
        Genre("Онгоинг", "s_ongoing"),
    )

    // The site has no "яой"/"гей" genre tag at all - its whole genre vocabulary (checked
    // against every entry in the advanced search form) is the ~30 tags in getGenreList().
    // GenreList exclusion in the filter UI already lets a user drop a genre, but only for
    // Search - Popular/Latest ignore filters entirely in GroupLe, and re-implement their own
    // parsing rather than share it, so there's no single method to hook. Re-parse here instead,
    // dropping tiles whose badges (same markup on every listing template, so no extra request)
    // match a hidden genre. latestUpdatesParse/searchMangaParse both just call
    // popularMangaParse(response) in GroupLe, so overriding only this one covers all three.
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val hidden = hiddenGenres()

        val mangas = document.select("div.tile").mapNotNull { element ->
            if (hidden.isNotEmpty() && element.select(".elem_genre").any { it.text().trim().lowercase() in hidden }) {
                return@mapNotNull null
            }

            SManga.create().apply {
                thumbnail_url = element.selectFirst("img.lazy")?.let {
                    it.absUrl("data-original").ifEmpty { it.attr("data-original") }
                }?.replace("_p.", ".")
                element.selectFirst("h3 > a")?.let {
                    setUrlWithoutDomain(it.absUrl("href"))
                    title = it.attr("title")
                }
            }
        }
        val hasNextPage = document.selectFirst("a.nextLink") != null

        return MangasPage(mangas, hasNextPage)
    }

    private fun hiddenGenres(): Set<String> = preferences.getStringSet(HIDDEN_GENRES_PREF, emptySet())
        .orEmpty()
        .mapTo(mutableSetOf()) { it.lowercase() }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        MultiSelectListPreference(screen.context).apply {
            key = HIDDEN_GENRES_PREF
            title = "Скрывать жанры"
            summary = "Тайтлы с выбранными жанрами не будут показываться в популярном, новинках и поиске"
            val names = getGenreList().map { it.name }.toTypedArray()
            entries = names
            entryValues = names
        }.let(screen::addPreference)
    }

    companion object {
        private const val HIDDEN_GENRES_PREF = "hidden_genres"
    }
}
