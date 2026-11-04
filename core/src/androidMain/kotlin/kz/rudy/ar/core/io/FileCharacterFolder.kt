package kz.rudy.ar.core.io

import java.io.File

/**
 * `<dir>/<id>/sprite.png` + `sheet.json` (+ `choreo.json`) (e.g. `context.filesDir/characters`), the layout of a bundle entry. A write
 * goes through one fixed scratch folder and a rename, so a process killed halfway leaves no half character, and the
 * next write clears what it left.
 */
class FileCharacterFolder(private val dir: File) : CharacterFolder {
    override fun ids(): List<String> = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    override fun write(id: String, sprite: ByteArray, sheet: String, choreo: String?) {
        require(!id.contains('/') && !id.contains('\\')) { "id must be bare: $id" }
        val scratch = File(dir, SCRATCH)
        scratch.deleteRecursively()
        check(scratch.mkdirs()) { "cannot create $scratch" }
        File(scratch, SPRITE).writeBytes(sprite)
        File(scratch, SHEET).writeText(sheet)
        if (choreo != null) File(scratch, CHOREO).writeText(choreo)
        check(scratch.renameTo(File(dir, id))) { "cannot publish $id" }
    }

    override fun readSprite(id: String): ByteArray? = File(File(dir, id), SPRITE).takeIf { it.isFile }?.readBytes()

    override fun readSheet(id: String): String? = File(File(dir, id), SHEET).takeIf { it.isFile }?.readText()

    override fun readChoreo(id: String): String? = File(File(dir, id), CHOREO).takeIf { it.isFile }?.readText()

    override fun delete(id: String) {
        File(dir, id).deleteRecursively()
    }

    private companion object {
        const val SCRATCH = ".writing"
        const val SPRITE = "sprite.png"
        const val SHEET = "sheet.json"
        const val CHOREO = "choreo.json"
    }
}
