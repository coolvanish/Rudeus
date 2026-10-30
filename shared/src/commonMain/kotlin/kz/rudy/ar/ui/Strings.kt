package kz.rudy.ar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.ObjectKind

/** Kazakh first, then Russian; English only for the demo video. */
enum class Lang(val code: String) { KK("kk"), RU("ru"), EN("en") }

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

    capture_title("Сурет немесе ойыншық", "Рисунок или игрушка", "Drawing or toy"),
    capture_hint("Суретті үстелге қой да, фотоға түсір", "Положи рисунок на стол и сфотографируй", "Put the drawing on the table and take a photo"),
    capture_take_photo("Фото", "Фото", "Photo"),
    capture_pick_gallery("Галереядан", "Из галереи", "From gallery"),
    capture_or_pick("Немесе дайынын таңда", "Или выбери готового", "Or pick a ready one"),
    capture_offline_note("Интернет жоқ — дайын кейіпкерлер", "Нет интернета — готовые герои", "No internet — ready characters"),
    capture_empty("Әзірге жоқ", "Пока пусто", "Nothing yet"),
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
    ship_anyway("Бәрібір ойнаймыз", "Всё равно играем", "Play anyway"),
    ship_anyway_note("Стиль сәл басқаша болуы мүмкін", "Стиль может чуть отличаться", "Style may look a bit different"),
    processing_done("Дайын!", "Готово!", "Done!"),

    scan_title("Үстелді қара", "Посмотри на стол", "Look at the table"),
    scan_title_floor("Еденді қара", "Посмотри на пол", "Look at the floor"),
    scan_hint("Телефонды баяу қозғалт", "Медленно води телефоном", "Move the phone slowly"),
    scan_button("Скан", "Скан", "Scan"),
    scanning("Қарап жатырмын…", "Смотрю…", "Looking…"),
    scan_no_plane_title("Үстел табылмады", "Стол не найден", "No table found"),
    scan_no_plane_hint("Өрнекті мата жай, жарық бүйірден түссін", "Постели ткань с узором, свет сбоку", "Use a patterned cloth and light from the side"),
    scan_no_plane_title_floor("Еден табылмады", "Пол не найден", "No floor found"),
    scan_no_plane_hint_floor("Күндіз далада көр: шөп не тротуар", "Попробуй днём на улице: трава или тротуар", "Try outside in daylight: grass or pavement"),
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
    /** T20 debug (footprints on): replays the Day 5 scenario script. */
    play_scenario("Сценарий", "Сценарий", "Scenario"),
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

    fun kindName(kind: ObjectKind): StringId = when (kind) {
        ObjectKind.OBSTACLE -> StringId.kind_obstacle
        ObjectKind.PLATFORM -> StringId.kind_platform
        ObjectKind.DYNAMIC -> StringId.kind_dynamic
        ObjectKind.CLIFF -> StringId.kind_cliff
    }

    /** Character sheet lines fall back kk -> ru -> en -> first available. */
    fun sheetLines(lines: Map<String, List<String>>, lang: Lang): List<String> =
        lines[lang.code] ?: lines["kk"] ?: lines["ru"] ?: lines["en"] ?: lines.values.firstOrNull() ?: emptyList()
}

/** Resolve a string for the current [LocalLang]. */
@Composable
fun t(id: StringId): String = id.get(LocalLang.current)
