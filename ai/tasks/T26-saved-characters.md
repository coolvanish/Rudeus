# T26 — saved-characters

status: review
created: 2026-11-03
day: Day 8 (user request after the Pixel runs: a new photo every time is slow and inconvenient)
gate: Day 6 gate must stay reachable; the bundled row and the offline path are unchanged
cut-if-slips: revert the brief; nothing else depends on it

## Goal
A character made from the child's photo joins the "pick a ready one" row on Capture and stays there after a restart,
so it can be played again with no new photo, no waiting and no model calls, offline too.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/bundle/SavedCharacters.kt, core/io/CharacterFolder.kt
- core/src/jvmMain and androidMain: kz/rudy/ar/core/io/FileCharacterFolder.kt
- core/src/commonTest/kotlin/kz/rudy/ar/core/bundle/SavedCharactersTest.kt, core/src/jvmTest/.../io/FileCharacterFolderTest.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/App.kt, CaptureHost.kt, ui/screens/CaptureScreen.kt
- androidApp/src/main/kotlin/kz/rudy/ar/di/AndroidCaptureHost.kt (hand-review)
- DESIGN.md (Capture)

## Contract
- When a result joins: on `Finished` with `gatePassed`, or when the child taps "Play anyway" below the gate. A result
  below the gate followed by Retry or Back is not kept. The same result is kept once.
- `SavedCharacters.save(sprite, sheet)`, `load()`: ids `saved-<epoch ms>`, strictly rising; `load` is newest first and
  skips what it cannot read; past 30 the oldest is deleted. The bundled characters are never touched.
- `FileCharacterFolder`: `files/characters/<id>/sprite.png` + `sheet.json`, written through a scratch folder and a rename.
- The sprite is stored at <= 768 px on the long side, as the bundled ones; Play shows the same size from then on.
- Row order on Capture: saved, then bundled, and a card added in front is scrolled into view. No new strings, no new
  dependency.
- A failed save loses only the card, with a logcat line; "Next" tries once more.

## Tests required
- `savedCharacterComesBackWhenTheAppStartsAgain`, `newestComesFirstWhenTheClockStandsStillOrGoesBack`
- `oldestGoesPastTheCapAndItsFilesWithIt`, `unreadableEntriesAreSkippedAndTheRestStillLoad`, `foldersThatAreNotCharactersAreLeftAlone`
- `characterSurvivesInRealFilesInTheBundleLayout`, `aWriteKilledHalfwayLeavesNoCharacterAndTheNextWriteClearsIt`
- `evictionRemovesTheWholeFolder`, `aMissingFolderHasNoCharacters`
Tests not required: Compose screens.

## Done when
- `scripts/verify.sh` exit 0
- Pixel, build with the key: a photo that passes the check logs `saved_character=` and shows first in the row; after
  force-stop and relaunch it is still there (`saved_characters=`); picking it reaches Scanning with no model calls.

## Result (filled on acceptance)
- measured: JVM green. Emulator (Pixel_7_Pro, no key), characters seeded through run-as: the good one shows first in the
  row, the one with a broken sprite is skipped (`saved_characters=1`), no crash. This found the off-screen first card, now
  fixed. A real save and playing a saved card are still pending on the Pixel with the key.
- review: rudeus-reviewer found no blockers or majors. Fixed: retry of a failed save, the in-memory row cap, a wrong
  uninstall note. Accepted: a save that races Cancel by a frame keeps a valid character; "never throws" covers `Exception`,
  not an out-of-memory error; no fsync, so a power cut right after a save can leave a truncated sprite.
- open for the Pixel: a sprite with a long side >= 1536 px is sampled down in one step and may have rougher edges.
- open for the user: `allowBackup="true"` puts files/characters in Auto Backup, up to about 25 MB (its app quota).
- cut/deferred: deleting a saved character from the row.
