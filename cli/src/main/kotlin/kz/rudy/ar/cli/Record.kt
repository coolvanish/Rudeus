package kz.rudy.ar.cli

import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.Fixture
import kz.rudy.ar.core.meta.FixtureApi
import kz.rudy.ar.core.meta.FixtureWriter
import kz.rudy.ar.core.meta.Jobs
import java.io.File

/**
 * File side of `character --live --record <dir>`: the [FixtureWriter] behind `RecordingModels`, the input-photo copy
 * (`sam-cutout/<ref>.input.<ext>`, so `character --fixtures <dir> --ref <ref>` replays it) and the overwrite check
 * that runs over every target before the first API call, so a refusal costs nothing.
 */
internal object Record {
    /** Stylize attempts a character can make (1 + the pipeline's 2 retries); one `<ref>.out-<n>.png` each. */
    const val MAX_ATTEMPTS = 3

    private val RECORDED_JOBS = listOf(Jobs.SAM_CUTOUT, Jobs.MUSE_STYLIZE, Jobs.SPARK_SCORE, Jobs.SPARK_SHEET)

    /**
     * Recorded Muse outputs are stored at most this many px on the long side. The originals are 1.4-1.8k px and
     * 2-3 MB each; replays, the matte and the gate's deterministic half work the same at 1024.
     */
    const val OUTPUT_MAX_SIDE = 1024

    fun writer(dir: File): FixtureWriter = FixtureWriter { path, bytes ->
        val f = File(dir, path)
        f.parentFile?.mkdirs()
        val isOutput = path.startsWith("${Jobs.MUSE_STYLIZE}/") && path.endsWith(".png")
        f.writeBytes(if (isOutput) JvmRaster.downscale(bytes, OUTPUT_MAX_SIDE) else bytes)
    }

    /** With `--force`: removes [ref]'s old stylize outputs, so a shorter re-recording leaves no orphan `out-<n>.png`. */
    fun clearOutputs(dir: File, ref: String) {
        for (n in 1..MAX_ATTEMPTS) File(dir, "${Jobs.MUSE_STYLIZE}/$ref.out-$n.png").takeIf { it.isFile }?.delete()
    }

    fun inputCopy(dir: File, ref: String, input: File): File =
        File(dir, "${Jobs.SAM_CUTOUT}/$ref.input.${input.extension.lowercase()}")

    /** Copies the photo next to the cutout fixture; a no-op when it already is that file. */
    fun copyInput(dir: File, ref: String, input: File) {
        val target = inputCopy(dir, ref, input)
        if (target.isFile && target.canonicalFile == input.canonicalFile) return
        target.parentFile?.mkdirs()
        input.copyTo(target, overwrite = true)
    }

    /**
     * Throws [CliError] listing every conflict: a synthetic fixture is never overwritten (tests depend on them, even
     * with `--force`); an existing real fixture, stylize output or input copy needs `--force`.
     */
    fun preflight(dir: File, inputs: List<Pair<String, File>>, force: Boolean) {
        val synthetic = ArrayList<String>()
        val existing = ArrayList<String>()
        for ((ref, input) in inputs) {
            for (job in RECORDED_JOBS) {
                val f = File(dir, "$job/$ref${FixtureApi.SUFFIX}")
                if (!f.isFile) continue
                val isSynthetic = runCatching { FixtureApi.json.decodeFromString(Fixture.serializer(), f.readText()).synthetic }.getOrDefault(false)
                if (isSynthetic) synthetic += f.path else existing += f.path
            }
            for (n in 1..MAX_ATTEMPTS) File(dir, "${Jobs.MUSE_STYLIZE}/$ref.out-$n.png").takeIf { it.isFile }?.let { existing += it.path }
            val copy = inputCopy(dir, ref, input)
            if (copy.isFile && copy.canonicalFile != input.canonicalFile) existing += copy.path
        }
        if (synthetic.isNotEmpty()) {
            throw CliError("--record would overwrite synthetic fixture(s), which tests rely on; rename the input(s):\n  " + synthetic.joinToString("\n  "))
        }
        if (existing.isNotEmpty() && !force) {
            throw CliError("--record would overwrite real recording(s); pass --force to replace them:\n  " + existing.joinToString("\n  "))
        }
    }
}
