package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.io.JvmRaster
import kz.rudy.ar.core.meta.MetaModels
import kz.rudy.ar.core.metrics.MetricsLog

/** [CharacterPipeline] with every JVM image function (decode, cutout, ...) wired in. The CLI builds its pipeline here. */
object JvmPipeline {
    fun create(models: MetaModels, metrics: MetricsLog, gate: StyleGate = StyleGate()): CharacterPipeline =
        CharacterPipeline(
            models = models,
            gate = gate,
            metrics = metrics,
            rasterDecoder = JvmRaster::decode,
            cutoutComposer = JvmRaster::cutout,
        )
}
