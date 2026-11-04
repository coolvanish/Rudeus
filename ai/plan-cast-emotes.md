# План: несколько персонажей на столе + действия (эмоции)

> Naming (Nov 5): no 1.0 was ever published. The single-character build of Day 7 is called "Day 7" in this file, and what was planned as v1.1 (cast and emotes) shipped as v1.0, tag `v1.0` on the final commit.

Составлен 4 ноя 2026, 16:20 (+05). Дедлайн хакатона: 9 PM ET = 07:00 5 ноя по Алматы, то есть ~14,5 ч.
`release/rudeus-debug.apk` собран 3 ноя 01:01; после него в main только полировка памяти и зависимостей.

## Что есть сейчас

- Один персонаж: `GameLoop` держит одно `PhysicsBody`, `RudeusArView` один `PlaneNode`, `ArSlot` один `spritePng`.
- Движение: ходьба по тапу, прыжок / полёт / качение (`Physics`), squash-stretch на трансформе квада (`CharacterPoser`).
  Спрайт-листов нет, персонаж = одна текстура.
- События (`CharacterEvent`) → реплики из sheet (5 строк на язык) + 5 звуков (place / jump / land / fall / blocked).
- Голос: ключевые слова "jump" / "go" (kk/ru/en) → `jumpRequest` / `walkForwardRequest`.
- Модели: SAM 3.1, Muse Image edit (промпт + рефы, 12–19 с, $0.01 за картинку), Spark 1.3 (JSON / vision),
  Voice ASR. Job `sam-rig` (части тела) ни разу не пробовали, meta.json нет.
- Жесты: `ARSceneView` отдаёт сырой `onTouchEvent(MotionEvent, HitResult?)` → мультитач доступен без новых зависимостей.
- Claude-токен есть (уточнить: ключ API для приложения или бюджет агентов).

## A. Действия («эмоции»): три слоя, каждый следующий опционален

### A1. Процедурные эмоции в ядре (офлайн, детерминировано). База, режется последней

- `core/emote/`: `Pose(yawDeg, rollDeg, pitchDeg, liftM, scaleX, scaleY)` — презентация поверх физики, тело остаётся IDLE.
- Примитивы: `spin` (оборот вокруг Y), `wiggle` (крен ±15°), `bounce` (подскок 0–2 см со squash), `nod`, `grow`
  (1 → 1.3 → 1), `shake` (дрожь ±5 мм), `bow` (наклон к камере).
- `EmoteClip` = сериализуемый таймлайн примитивов + реплика + звук. Встроенные: HI, BYE, DANCE, ROAR, SPIN, HIDE
  (у ability HIDE наконец появится смысл: сжаться и спрятаться).
- `EmotePlayer.poseAt(clip, tMs)` чистая функция → тесты в `core/src/commonTest`.
- Играет только пока персонаж IDLE; тап по столу прерывает; PAUSED замораживает.
- Хост: `CharacterPoser` складывает позу с текущим squash / flip / feet-bias. Небольшой диф, hand-review.
- HUD: ряд из трёх чипов под чипом способности (Привет / Танец / Рык) + голосовые слова в kk/ru/en
  (сәлем / привет / hi, би / танцуй / dance, ақыр / рычи / roar, айнал / крутись / spin, сау бол / пока / bye).
- Звуки: +roar, +tada (CC0, в `docs/third-party.md`).

### A2. Хореограф: модель превращает фразу в EmoteClip

- `MetaModels.choreograph(ref, phrase, sheet): EmoteClip` — JSON строго по словарю примитивов, с реплика + звук.
- Провайдер: Spark contributor основной (считается в рубрику Tooling), Claude второй через тот же интерфейс.
  Ключ Claude по тем же правилам, что META_API_KEY: `local.properties` → `BuildConfig`, никогда в git и в логах.
- Порядок: сначала ключевые слова (мгновенно, офлайн), модель только для свободной фразы (1–3 с, пилюля «Thinking…»).
- Бонус без лишнего вызова: тот же sheet-вызов Spark добавляет три «фирменных движения» по характеру персонажа
  (например, «ревёт как лев», «топает») → чипы этого персонажа, работают офлайн после создания.
- Фикстуры `docs/api-samples/spark-choreo/` для тестов и офлайна; CLI `choreo --phrase "..."` для проб.

### A3. Позы от Muse (стретч; сначала проба за $0.05). Режется первым

- Один вызов Muse Image edit: «тот же персонаж, четыре позы листом 2×2: машет, танцует, рычит, кланяется» →
  режем на 4 кадра → эмоция подменяет текстуру на ключевой кадр, процедурное движение остаётся сверху.
- Проба в CLI на 2 bundled-персонажах: узнаваем ли персонаж, чистый ли белый фон, выдержан ли стиль (гейт есть).
- Если да: CLI генерирует `poses.png` для bundled (офлайн в APK); в приложении фоновая стадия после входа в Play
  (+12–19 с, +$0.01; персонаж играбелен сразу, кадры подъезжают потом).
- Хост: одна дополнительная текстура на персонажа (384 px, ~0,8 МБ), подмена текстуры в MaterialInstance. Hand-review.
- SAM-риг (отдельные квады для рук/головы) отложен: дырки в теле после вырезания части, без инпейнтинга не решается.

## B. Несколько персонажей («труппа», до 4)

### B1. Ядро: `core/cast/`

- `Actor(id, body: PhysicsBody, ability, colour)`, `Cast.step(level, dtMs)`: `Physics.step` каждому, затем
  расталкивание кругов актёр–актёр (симметричное, никого не выталкивает за край).
- События адресные: `(actorId, CharacterEvent)`. Спавн кольцом вокруг `level.spawn` (радиус от числа актёров).
- `pause` / `resume` всем; `walkTo(id)`, `jump(id)`, `emote(id, clip)`.
- Тесты: двое не перекрываются; события приходят своему актёру; спавн без наложений; пауза замораживает всех.

### B2. Управление (рекомендация: «палец держит персонажа»)

- У ног каждого цветное кольцо (Sun / Sky / Rose / Leaf): ребёнок видит «мой».
- Палец вниз рядом с персонажем (≤ 48 dp на экране) — палец «держит» его; ведёт пальцем — `walkTo` 10 Гц;
  отпустил — идёт до последней точки. Каждый pointer id независим: два пальца с двух сторон планшета = два персонажа.
- Палец не рядом ни с кем — ведёт выбранного (последнего тронутого); у выбранного кольцо толще.
- Jump / Fly, чипы эмоций и голос действуют на выбранного.
- Хост проецирует мир → экран для попадания по персонажу (view / projection из `frame.camera`).
- Правила тапа сохраняются: допуск по высоте, граница стола, проверка провала по глубине на первой точке.

### B3. Добавление и HUD

- Capture: на карточках «+» (до 4 выбранных), Play стартует с труппой; результат Processing добавляется к труппе.
- Play: круглая «+» слева (рядом с Bigger / Smaller) → лист готовых персонажей: добавить / убрать.
- `ArSlot`: `cast: List<CastMember(id, spritePng, ability, colour)>`, `selectedId`, `emoteRequest`,
  `jumpRequest` → выбранному, `onCharacterEvent(actorId, event)`. Один якорь на сессию не меняется.
- Пузырь: «Имя: реплика» внизу как сейчас; пузырь над каждым персонажем — стретч (нужны экранные позиции).
- Память: спрайт 768 px ≈ 3 МБ с мипами, 4 персонажа ≈ 12 МБ. Нормально.
- Bigger / Smaller пока общий для всех.

## C. Оркестрация

Контракты (`ArSlot`, `EmoteClip`, `Cast` API) фиксируются в брифах до старта волны 1, чтобы агенты не разъехались.

| Волна | Бриф | Агент | Владеет |
|---|---|---|---|
| 1 | T28 core-emotes | rudeus-opus-high | `core/emote/**` + тесты |
| 1 | T29 core-cast | rudeus-opus-extra | `core/cast/**` + тесты |
| 1 | T30 shared-actions-cast-hud | rudeus-sonnet-high | `ArSlot`, `PlayScreen`, `CaptureScreen`, `Strings`, `App` |
| 1 | T31 choreographer | rudeus-opus-high | `core/meta/` (новый job), фикстуры, CLI `choreo` |
| 2 | T32 ar-host-cast-emotes | rudeus-fable-high → человек | `androidApp/ar/**` (hand-review) |
| 2 | T33 muse-poses-probe | CLI, rudeus-opus-high | `docs/api-samples/muse-poses/`, go/no-go |
| 2 | T34 poses-stage (если T33 go) | rudeus-opus-high | pipeline + bundle |
| 3 | review | rudeus-reviewer | на каждый бриф, диф без истории |
| 3 | QA | rudeus-device-qa + человек | эмулятор для экранов, Pixel для AR |

## Варианты по срокам

1. Всё после дедлайна: ноль риска для сабмита, полный план без спешки.
2. Сегодня только A1 одному персонажу (ядро + чипы + маленький диф в `CharacterPoser`), проверка на Pixel к вечеру;
   B и A2 / A3 после дедлайна. Низкий риск, что-то новое для видео.
3. Сегодня и труппа, и эмоции: большой диф в AR-хосте ночью перед дедлайном. Высокий риск сломать демо.

## Что режем, если что-то плывёт (по порядку)

1. A3 Muse-позы → 2. пузыри над головой → 3. «+» на Play (остаётся выбор на Capture) → 4. A2 модель-хореограф
(остаются ключевые слова) → 5. мультитач (остаётся выбор тапом) → 6. труппа целиком (эмоции одному).
## Decisions (user, 2026-11-04 16:30 Almaty)
1. **Timing: today, minimal cut.** Deadline 9 PM ET = 07:00 Nov 5 Almaty. Integration branch `day9/cast-emotes` from
   `main`; nothing lands on `main` until the Pixel run. Waves: T28 + T29 (core) → T30 (cli, live) + T31 (shared) →
   T32 (AR host draft, hand-review) → reviewer → Pixel → release/ rebuilt from the main checkout with the key.
2. **Control: a finger leads its own character** (multi-touch grab); a tap on the table walks the active one.
3. **Animation: Spark choreographs from the move vocabulary now; Muse pose frames as a CLI spike** (T30, go/no-go by
   the user on `docs/api-samples/muse-pose/contact.png`; wiring frames into the app is post-deadline work).
4. **Triggers: the HUD emote row only.** Voice emote words and the long-press wheel are cut from today.
Status line: `status: in progress` — briefs T28–T32 in `ai/tasks/`.
5. **Pose frames wired (T33, 2026-11-04, after the spike's GO):** `EmoteKey.frame` points at a model-drawn frame
   (`PoseFrames`, two HELLO + two ROAR per character), the pipeline draws them as stage `frames` after `choreo`
   (`poseFrames = true` on the phone, `cli character --frames`), `cli choreo --frames[-only]` draws them for bundled
   characters, and the host swaps the quad's material on the keyed frame (single quad; the T32 cast is still todo).
   A character without frames plays the v1.0 choreographies unchanged.
