package kz.rudy.ar.cli

import kz.rudy.ar.core.io.JvmRaster
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * `--drawings` inputs ImageIO cannot decode (webp, HEIC, …), converted before the run so photos go in as they are.
 *
 * Converted with macOS `sips`; webp falls back to `dwebp` (libwebp, `brew install webp`). No new dependency: the JVM
 * has no webp/HEIC reader and the CLI only runs on a dev machine. webp becomes PNG (keeps alpha); HEIC and the rest
 * become JPEG, where `sips` keeps the EXIF orientation that [JvmRaster.upright] applies (iPhone photos rely on it).
 * The file keeps its name, so the ref, `sam-cutout/<ref>.input.<png|jpg>` and the bundle match a png/jpg input.
 */
internal object ImageConvert {
    val EXTENSIONS = setOf("webp", "heic", "heif", "avif", "gif", "bmp", "tif", "tiff")

    private const val TIMEOUT_S = 60L
    private val tmpDir: File by lazy {
        Files.createTempDirectory("rudeus-inputs").toFile().apply { deleteOnExit() }
    }

    fun needed(file: File): Boolean = file.extension.lowercase() in EXTENSIONS

    /** A decodable copy of [file] in a temp dir (deleted on exit); [CliError] naming the tools tried otherwise. */
    fun convert(file: File): File {
        val webp = file.extension.lowercase() == "webp"
        val out = File(tmpDir, "${file.nameWithoutExtension}.${if (webp) "png" else "jpg"}").apply { deleteOnExit() }
        val commands = buildList {
            add(listOf("sips", "-s", "format", if (webp) "png" else "jpeg", "-s", "formatOptions", "best", file.path, "--out", out.path))
            if (webp) add(listOf("dwebp", file.path, "-o", out.path))
        }
        val failed = mutableListOf<String>()
        for (cmd in commands) {
            out.delete()
            val ok = run(cmd) && out.isFile && JvmRaster.decode(out.readBytes()) != null
            if (ok) return out
            failed += cmd.first()
        }
        throw CliError("${file.name}: could not convert it (${failed.joinToString()} failed or missing); save it as png or jpg")
    }

    private fun run(cmd: List<String>): Boolean = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) p.exitValue() == 0 else { p.destroyForcibly(); false }
    } catch (e: IOException) {
        false // tool not installed
    }
}
