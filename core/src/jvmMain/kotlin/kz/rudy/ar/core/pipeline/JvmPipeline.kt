package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.metrics.MetricsLog

/**
 * [CharacterPipeline] with every JVM image function (decode, cutout, matte, ...) wired in. The CLI builds its pipeline
 * here; the flags turn single pieces off (e.g. `matte = false` ships Muse's opaque output as is).
 */
object JvmPipeline {
    fun create(
        models: MetaModels,
        metrics: MetricsLog,
        gate: StyleGate = StyleGate(),
        matte: Boolean = true,
    ): CharacterPipeline =
        CharacterPipeline(
            models = models,
            gate = gate,
            metrics = metrics,
            rasterDecoder = JvmRaster::decode,
            cutoutComposer = JvmRaster::cutout,
            spriteMatter = if (matte) JvmRaster::matte else null,
        )
}
