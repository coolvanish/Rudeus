package kz.rudy.ar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.emote.Emote
import kz.rudy.ar.core.meta.SheetParser

/** The languages the app speaks. Which one it opens in comes from the phone, see [preferred]. */
enum class Lang(val code: String) {
    KK("kk"), RU("ru"), EN("en");

    companion object {
        /**
         * Every language, best first: those in [systemLanguages] (ISO codes, the system's list from the top), then
         * English, then the rest. The first is what the app opens in, so a phone with none of ours gets English.
         */
        fun preferred(systemLanguages: List<String>): List<Lang> =
            (systemLanguages.mapNotNull { code -> entries.find { it.code == code } } + EN + entries).distinct()
    }
}

val LocalLang = staticCompositionLocalOf { Lang.KK }

/**
 * Every UI string. Constant names are the snake_case ids from DESIGN.md's copy table, so
 * `StringId.capture_title.name == "capture_title"` and the table can be diffed against this enum.
 */
@Suppress("EnumEntryName")
enum class StringId(val kk: String, val ru: String, val en: String) {
    app_name("Rudeus", "Rudeus", "Rudeus"),
    back("Артқа", "Назад", "Back"),
    retry("Тағы бір рет", "Ещё раз", "Retry"),
    cancel("Болдырмау", "Отмена", "Cancel"),
    next("Әрі қарай", "Дальше", "Next"),
    ok("Жарайды", "Ладно", "OK"),
    skip("Өткізу", "Пропустить", "Skip"),
    offline_badge("Офлайн", "Офлайн", "Offline"),
    lang_kk("ҚАЗ", "ҚАЗ", "KK"),
    lang_ru("РУС", "РУС", "RU"),
    lang_en("ENG", "ENG", "EN"),

    /** T27: the intro's captions (the fourth is play_tap_to_walk). */
    intro_draw("Кейіпкеріңді сал", "Нарисуй героя", "Draw a hero"),
    intro_alive("Ол жанданады", "Он оживёт", "It comes alive"),
    intro_table("Үстелің — деңгей", "Стол — твой уровень", "Your table is the level"),

    capture_title("Сурет немесе ойыншық", "Рисунок или игрушка", "Drawing or toy"),
    capture_hint("Суретті үстелге қой да, фотоға түсір", "Положи рисунок на стол и сфотографируй", "Put the drawing on the table and take a photo"),
    capture_take_photo("Фото", "Фото", "Photo"),
    capture_privacy_note("Фото Meta AI-ға жіберіледі. Адамдарды түсірме", "Фото уходит в Meta AI. Не снимай людей", "The photo goes to Meta AI. No people, please"),
    capture_pick_gallery("Галереядан", "Из галереи", "From gallery"),
    /** T38: up to 7 photos from the gallery become characters on the shelf, without playing them. */
    capture_add_toys("Ойыншық қосу", "Добавить игрушки", "Add toys"),
    capture_or_pick("Немесе дайынын таңда", "Или выбери готового", "Or pick a ready one"),
    capture_offline_note("Интернет жоқ — дайын кейіпкерлер", "Нет интернета — готовые герои", "No internet — ready characters"),
    capture_empty("Әзірге жоқ", "Пока пусто", "Nothing yet"),
    capture_empty_mine_hint("Суретті фотоға түсір — осында шығады", "Сфотографируй рисунок — он появится здесь", "Take a photo of a drawing and it shows up here"),
    capture_shelf_all("Бәрі", "Все", "All"),
    capture_shelf_mine("Менікі", "Мои", "Mine"),
    capture_shelf_ready("Дайын", "Готовые", "Ready"),
    metrics_button("Метрика", "Метрика", "Metrics"),

    processing_title("Кейіпкер жасалып жатыр", "Делаем героя", "Making your character"),
    stage_cutout("Қиып аламыз", "Вырезаем", "Cutting out"),
    stage_stylize("Бояймыз", "Раскрашиваем", "Painting"),
    stage_gate("Тексереміз", "Проверяем", "Checking"),
    stage_sheet("Атын ойлаймыз", "Придумываем имя", "Naming"),
    gate_score("Стиль ұпайы", "Оценка стиля", "Style score"),
    gate_passed("Өтті", "Прошёл", "Passed"),
    gate_failed("Өтпеді", "Не прошёл", "Not passed"),
    gate_attempt("Әрекет", "Попытка", "Attempt"),
    processing_failed_title("Болмады", "Не вышло", "Didn't work"),
    processing_failed_hint("Интернетті тексер де, қайталап көр", "Проверь интернет и попробуй ещё", "Check the internet and try again"),
    processing_pick_ready("Дайынын таңда", "Выбрать готового", "Pick a ready one"),
    review_title("Ұнай ма?", "Нравится?", "Like it?"),
    review_yes("Иә, ойнаймыз", "Да, играем", "Yes, let's play"),
    processing_done("Дайын!", "Готово!", "Done!"),

    // T38: the Import screen (several photos at once, nothing is played).
    import_title("Ойыншықтар қосылып жатыр", "Добавляем игрушки", "Adding toys"),
    import_hint("Бірден 7-ге дейін. Сәл күте тұр", "До 7 за раз. Подожди немного", "Up to 7 at once. Hang on"),
    import_queued("Кезекте", "В очереди", "Waiting"),
    /** Under the tile of a character below the gate: a tap keeps it on the shelf anyway. */
    import_keep("Қалдыру", "Оставить", "Keep"),
    import_kept("Қалады", "Останется", "Will stay"),
    /** "<n> added" at the end; the number goes in front. */
    import_added("қосылды", "добавлено", "added"),
    import_none_added("Ешқайсысы шықпады", "Ни один не вышел", "None came out"),
    import_finish("Болды", "Готово", "Done"),

    scan_title("Үстелді қара", "Посмотри на стол", "Look at the table"),
    scan_title_floor("Еденді қара", "Посмотри на пол", "Look at the floor"),
    scan_hint("Телефонды баяу қозғалт", "Медленно води телефоном", "Move the phone slowly"),
    scan_button("Скан", "Скан", "Scan"),
    scanning("Қарап жатырмын…", "Смотрю…", "Looking…"),
    scan_no_plane_title("Үстел табылмады", "Стол не найден", "No table found"),
    /** The scan failed while the host is offline: the network, not the table, is the problem. */
    scan_offline_title("Интернет жоқ", "Нет интернета", "No internet"),
    scan_no_plane_hint("Өрнекті мата жай, жарық бүйірден түссін", "Постели ткань с узором, свет сбоку", "Use a patterned cloth and light from the side"),
    scan_no_plane_title_floor("Еден табылмады", "Пол не найден", "No floor found"),
    scan_no_plane_hint_floor("Күндіз далада көр: шөп не тротуар", "Попробуй днём на улице: трава или тротуар", "Try outside in daylight: grass or pavement"),
    /** The camera looks almost straight down: the scan needs a side view of the objects. */
    scan_too_steep_title("Тым жоғарыдан", "Слишком сверху", "Too straight down"),
    scan_too_steep_hint("Телефонды тікірек ұстап, сәл артқа шегін", "Держи телефон прямее и отойди назад", "Hold the phone more upright and step back"),
    scan_empty_title("Үстел бос", "Стол пустой", "Table is empty"),
    scan_empty_hint("Кесе не кітап қой", "Поставь кружку или книгу", "Put a cup or a book on it"),
    scan_empty_title_floor("Еден бос", "Пол пустой", "Floor is empty"),
    scan_empty_hint_floor("Қорап не аяқ киім қой", "Поставь коробку или ботинок", "Put a box or a shoe on it"),
    scan_play_anyway("Бәрібір ойнау", "Играть так", "Play anyway"),
    scan_tap_to_place("Үстелді түрт — ол сонда тұрады", "Нажми на стол — он встанет там", "Tap the table — it stands there"),
    scan_tap_to_place_floor("Еденді түрт — ол сонда тұрады", "Нажми на пол — он встанет там", "Tap the floor — it stands there"),
    scan_done("Үстел дайын!", "Стол готов!", "Table ready!"),
    scan_done_floor("Еден дайын!", "Пол готов!", "Floor ready!"),
    surface_table("Үстел", "Стол", "Table"),
    surface_floor("Еден", "Пол", "Floor"),
    scan_objects_found("Заттар", "Предметы", "Objects"),

    play_tap_to_walk("Түрт — ол барады", "Нажми — он пойдёт", "Tap — it walks"),
    /** T20: the Play HUD's action button for JUMP and HIDE characters. */
    play_jump("Секір", "Прыгай", "Jump"),
    /** T20: the same button for FLY characters. */
    play_fly("Ұш", "Лети", "Fly"),
    /** T25: accessibility labels of the Play HUD's round size buttons (they show only a plus / minus glyph). */
    play_bigger("Үлкейт", "Увеличь", "Bigger"),
    play_smaller("Кішірейт", "Уменьши", "Smaller"),
    /** T20 debug (footprints on): replays the Day 5 scenario script. */
    play_scenario("Сценарий", "Сценарий", "Scenario"),
    /** T31: the cast's `+` chip (accessibility label; it shows only a plus glyph). */
    play_add("Қос", "Добавить", "Add"),
    /** T31: the Add sheet's title. */
    play_add_title("Кімді қосамыз?", "Кого добавить?", "Who joins?"),
    /** T31: the pill when `+` is tapped with `Cast.MAX` characters on the table. */
    play_table_full("Үстел толы", "Стол полон", "Table full"),
    /** T31: the emote row's labels, in `Emote` order. */
    emote_hello("Сәлем", "Привет", "Hi"),
    emote_bye("Сау бол", "Пока", "Bye"),
    emote_dance("Биле", "Танцуй", "Dance"),
    emote_spin("Айнал", "Крутись", "Spin"),
    emote_roar("Ақыр", "Рычи", "Roar"),
    /** T24: the pill beside the mic while it is held (recording). */
    voice_listening("Тыңдап тұрмын…", "Слушаю…", "Listening…"),
    /** T24: the pill after release, while the phrase is transcribed. */
    voice_thinking("Ойланып тұрмын…", "Думаю…", "Thinking…"),
    /** T24: the bubble when the phrase had no command word (or nothing was heard). */
    voice_unknown("Түсінбедім", "Не понял", "Didn't catch that"),
    /** T24: the mic button's accessibility label (the button itself shows only the mic glyph). */
    voice_hold("Ұстап тұрып айт", "Держи и говори", "Hold and talk"),
    /** AR host: a tap before the table plane has settled (T02 anchor gate) shows this for a moment. */
    ar_wait_table("Сәл күт — үстелді іздеп жатырмын", "Подожди — ищу стол", "Wait — finding the table"),
    /** AR host: the same, on the floor / outdoors (`Surface.FLOOR`). */
    ar_wait_floor("Сәл күт — еденді іздеп жатырмын", "Подожди — ищу пол", "Wait — finding the floor"),
    rescan("Қайта скан", "Скан снова", "Rescan"),
    ability_jump("Секіру", "Прыжок", "Jump"),
    ability_fly("Ұшу", "Полёт", "Fly"),
    ability_roll("Домалау", "Качение", "Roll"),
    ability_hide("Жасырыну", "Прятки", "Hide"),
    paused_title("Үзіліс", "Пауза", "Paused"),
    paused_hint("Телефонды үстелге бағытта", "Наведи телефон на стол", "Point the phone at the table"),
    paused_hint_floor("Телефонды еденге бағытта", "Наведи телефон на пол", "Point the phone at the floor"),
    footprints("Іздер", "Следы", "Footprints"),
    /** T36: the HUD switch for background scans (frames leave the phone only on Scan when off). */
    auto_scan("Өзі сканерлейді", "Авто-скан", "Auto scan"),
    fell("Ой! Құлап кетті", "Ой! Упал", "Oops! Fell"),
    respawn("Қайта тұрды", "Снова на месте", "Back up"),
    blocked("Кедергі!", "Преграда!", "Blocked!"),
    kind_obstacle("Кедергі", "Преграда", "Obstacle"),
    kind_platform("Алаң", "Площадка", "Platform"),
    kind_dynamic("Қол", "Рука", "Hand"),
    kind_cliff("Жар", "Обрыв", "Cliff"),

    metrics_title("Метрика", "Метрика", "Metrics"),
    metrics_empty("Әзірге жоқ", "Пока пусто", "Nothing yet"),
    metrics_empty_hint("Алдымен кейіпкер жаса", "Сначала сделай героя", "Make a character first"),
    metrics_export("JSON экспорт", "Экспорт JSON", "Export JSON"),
    metrics_exported("Сақталды", "Сохранено", "Saved"),
    metrics_export_failed("Сақталмады", "Не сохранилось", "Not saved"),
    /** Prefix for the exported file's location under the "Saved" toast ("Where: Download/Rudeus/<runId>.json"). */
    metrics_export_where("Қайда", "Где", "Where"),
    metrics_run("Іске қосу", "Запуск", "Run"),
    metrics_characters("Кейіпкерлер", "Герои", "Characters"),
    /** Count suffix in the Characters header for runs the child cancelled ("2 · 1 cancelled"). */
    metrics_cancelled("тоқтатылды", "отменено", "cancelled"),
    metrics_pass_rate("Бірден өтті", "Прошли сразу", "Passed first try"),
    metrics_p50("p50 уақыт", "p50 время", "p50 time"),
    metrics_p90("p90 уақыт", "p90 время", "p90 time"),
    metrics_cost("Бағасы", "Цена", "Cost each"),
    metrics_scans("Скандар", "Сканы", "Scans"),
    metrics_col_input("Сурет", "Рисунок", "Input"),
    metrics_col_score("Ұпай", "Оценка", "Score"),
    metrics_col_retries("Қайталау", "Повторы", "Retries"),
    metrics_col_time("Уақыт", "Время", "Time"),
    metrics_col_cost("Баға", "Цена", "Cost"),
    metrics_col_objects("Заттар", "Предметы", "Objects"),
    metrics_col_within_cm("Дәлдік, см", "Точность, см", "Accuracy, cm"),

    /** AR host: the camera permission screen shown instead of the AR view until the child allows the camera. */
    settings_title("Баптаулар", "Настройки", "Settings"),
    settings_language("Тіл", "Язык", "Language"),
    settings_sounds("Дыбыс", "Звук", "Sounds"),
    settings_reduce_motion("Қимылды азайту", "Меньше анимации", "Reduce motion"),
    settings_reduce_motion_hint("Секіру орнына баяу көріну", "Плавно вместо прыжков", "Fades instead of pops"),
    settings_metrics_hint("Іске қосулар, ұпай, экспорт", "Запуски, оценки, экспорт", "Runs, scores, export"),
    settings_about("Бағдарлама туралы", "О приложении", "About"),

    camera_permission_title("Камера керек", "Нужна камера", "Camera needed"),
    camera_permission_hint("Кейіпкерді үстелде көру үшін", "Чтобы увидеть героя на столе", "To see the character on the table"),
    camera_permission_button("Рұқсат беру", "Разрешить", "Allow");

    fun get(lang: Lang): String = when (lang) {
        Lang.KK -> kk
        Lang.RU -> ru
        Lang.EN -> en
    }
}

object Strings {
    private val byName: Map<String, StringId> = StringId.entries.associateBy { it.name }

    fun get(id: StringId, lang: Lang): String = id.get(lang)

    /** Lookup by snake_case id; returns the id itself when unknown so a missing string is visible, not silent. */
    fun get(id: String, lang: Lang): String = byName[id]?.get(lang) ?: id

    fun abilityName(ability: Ability): StringId = when (ability) {
        Ability.JUMP -> StringId.ability_jump
        Ability.FLY -> StringId.ability_fly
        Ability.ROLL -> StringId.ability_roll
        Ability.HIDE -> StringId.ability_hide
    }

    fun emoteName(emote: Emote): StringId = when (emote) {
        Emote.HELLO -> StringId.emote_hello
        Emote.BYE -> StringId.emote_bye
        Emote.DANCE -> StringId.emote_dance
        Emote.SPIN -> StringId.emote_spin
        Emote.ROAR -> StringId.emote_roar
    }

    fun kindName(kind: ObjectKind): StringId = when (kind) {
        ObjectKind.OBSTACLE -> StringId.kind_obstacle
        ObjectKind.PLATFORM -> StringId.kind_platform
        ObjectKind.DYNAMIC -> StringId.kind_dynamic
        ObjectKind.CLIFF -> StringId.kind_cliff
    }

    /** A sheet without this language (saved before it had one) says the generic lines, so the child can read them. */
    fun sheetLines(lines: Map<String, List<String>>, lang: Lang): List<String> =
        lines[lang.code] ?: SheetParser.FALLBACK_LINES[lang.code] ?: lines.values.firstOrNull() ?: emptyList()
}

/** Resolve a string for the current [LocalLang]. */
@Composable
fun t(id: StringId): String = id.get(LocalLang.current)
