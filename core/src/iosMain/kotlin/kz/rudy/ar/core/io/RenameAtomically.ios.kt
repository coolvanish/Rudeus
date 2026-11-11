package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** kotlinx-io's native atomic move is rename(2). */
internal actual fun renameAtomically(from: Path, to: Path) = SystemFileSystem.atomicMove(from, to)
