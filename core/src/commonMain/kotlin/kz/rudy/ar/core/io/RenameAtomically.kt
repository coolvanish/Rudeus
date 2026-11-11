package kz.rudy.ar.core.io

import kotlinx.io.files.Path

/**
 * Renames [from] to [to] in one step (rename(2)); throws an `IOException` when it cannot. [to] must not exist.
 *
 * A leaf `expect` because kotlinx-io's `SystemFileSystem.atomicMove` is not usable everywhere: on the JVM it needs
 * `java.nio.file.Files` (looked up with `Class.forName`) and otherwise throws `UnsupportedOperationException("Atomic
 * move not supported")`, with no `File.renameTo` fallback. Android has `java.nio.file` only from API 26 and `:core`
 * runs from minSdk 24 without library desugaring, so the Android actual uses `java.io.File.renameTo` (the call
 * `FileCharacterFolder` makes today); jvm and ios use `atomicMove`.
 */
internal expect fun renameAtomically(from: Path, to: Path)
