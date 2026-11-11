package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Desktop JVM always has `java.nio.file`, so kotlinx-io's atomic move works here. */
internal actual fun renameAtomically(from: Path, to: Path) = SystemFileSystem.atomicMove(from, to)
