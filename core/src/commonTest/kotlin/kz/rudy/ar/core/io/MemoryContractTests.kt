package kz.rudy.ar.core.io

/** The in-memory doubles keep the same contract as the real folders (the failure and on-disk cases need a disk). */
class MemoryCharacterFolderContractTest : CharacterFolderContract() {
    override fun newFolder(): CharacterFolder = MemoryCharacterFolder()
}

class MapFixtureSourceContractTest : FixtureSourceContract() {
    override fun sourceWith(files: Map<String, ByteArray>): FixtureSource = MapFixtureSource(files)
}

class MemoryMetricsSinkContractTest : MetricsSinkContract() {
    private var sink = MemoryMetricsSink()

    override fun newSink(): MetricsSink = MemoryMetricsSink().also { sink = it }

    override fun readBack(fileName: String): String? = sink.files[fileName]
}
