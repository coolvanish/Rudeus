package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import java.io.File
import java.io.IOException

/**
 * `java.io.File.renameTo` (rename(2)), not kotlinx-io's `atomicMove`: that one needs `java.nio.file.Files`, which
 * Android has only from API 26, and throws `UnsupportedOperationException` below it (minSdk is 24). java.io only:
 * no `android.*` in `:core`.
 */
internal actual fun renameAtomically(from: Path, to: Path) {
    if (!File(from.toString()).renameTo(File(to.toString()))) throw IOException("cannot rename $from to $to")
}
