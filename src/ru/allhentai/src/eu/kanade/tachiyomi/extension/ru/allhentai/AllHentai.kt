package eu.kanade.tachiyomi.extension.ru.allhentai

import android.content.SharedPreferences
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.grouple.GroupLe
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonElement

@Source
abstract class AllHentai : GroupLe() {

    override val isNeedAuth get() = baseUrl != "https://x.ahen.me"

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Falls back to these hardcoded lists (checked against the live site's whole genre
    // vocabulary at the time of writing) until fetchFilterData's background fetch lands, or
    // permanently for whichever group it fails to parse - see GroupLe.fetchFilterData/filterGroup.
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        OrderBy(),
        CategoryList(data.filterGroup("Категории") ?: getCategoryList()),
        GenreList(data.filterGroup("Жанры") ?: getGenreList()),
        AdditionalFilterList(data.filterGroup("Фильтры") ?: getAdditionalFilterList()),
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

    // The site has no "яой"/"гей" tag anywhere - checked its whole genre vocabulary (every
    // group on /search/advanced, ~50 tags) *and* its much bigger free-text tag search
    // (/search/elementsByType?type=40, searched "яой"/"гей"/"yaoi"/"BL": all empty). This is
    // the closest available: hide any of the real genres everywhere, not just Search - GroupLe's
    // genre exclusion in the filter UI only ever applied there, Popular/Latest ignored filters
    // entirely. GroupLe.parseMangasPage stashes each tile's genre badges on manga.genre, so
    // filtering here needs no extra request.
    override suspend fun getPopularManga(page: Int): MangasPage = dropHiddenGenres(super.getPopularManga(page))

    override suspend fun getLatestUpdates(page: Int): MangasPage = dropHiddenGenres(super.getLatestUpdates(page))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = dropHiddenGenres(super.getSearchMangaList(page, query, filters))

    private fun dropHiddenGenres(page: MangasPage): MangasPage {
        val hidden = hiddenGenres()
        if (hidden.isEmpty()) return page
        return MangasPage(page.mangas.filterNot { it.isHiddenByGenre(hidden) }, page.hasNextPage)
    }

    private fun SManga.isHiddenByGenre(hidden: Set<String>): Boolean = genre.orEmpty()
        .split(",")
        .map { it.trim().lowercase() }
        .any { it in hidden }

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
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)
    }

    companion object {
        private const val HIDDEN_GENRES_PREF = "hidden_genres"
    }
}
