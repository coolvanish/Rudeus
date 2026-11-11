package kz.rudy.ar.core.io

import kotlinx.io.files.Path
import java.io.File

/** The existing jvm `File*` copies (the ones Android and the CLI still use) against the same contract as `Io*`. */
class FileCharacterFolderContractTest : OnDiskCharacterFolderContract() {
    override fun folderAt(root: Path): CharacterFolder = FileCharacterFolder(File(root.toString()))

    /**
     * FINDING (T39): `FileCharacterFolder.write` publishes with `File.renameTo`, which fails on an existing non-empty
     * entry, so an id cannot be rewritten (SavedCharacters never does: its ids strictly rise). `IoCharacterFolder`
     * replaces. The contract asserts the File behaviour exactly while this is false.
     */
    override val replacesAnExistingId: Boolean get() = false

    /**
     * `FileCharacterFolder` takes no file system to break, so its temp step fails for real: a frame whose name is too
     * long for the file system (ENAMETOOLONG) is written after the sprite and the sheet, before the rename.
     */
    override fun failingFolderAt(root: Path): CharacterFolder {
        val real = FileCharacterFolder(File(root.toString()))
        return object : CharacterFolder by real {
            override fun write(id: String, sprite: ByteArray, sheet: String, choreo: String?, frames: Map<String, ByteArray>) =
                real.write(id, sprite, sheet, choreo, frames + ("x".repeat(300) + ".png" to byteArrayOf(1)))
        }
    }
}

class FileFixtureSourceContractTest : OnDiskFixtureSourceContract() {
    override fun sourceAt(root: Path): FixtureSource = FileFixtureSource(File(root.toString()))
}

class FileMetricsSinkContractTest : OnDiskMetricsSinkContract() {
    override fun sinkAt(dir: Path): MetricsSink = FileMetricsSink(File(dir.toString()))
}
