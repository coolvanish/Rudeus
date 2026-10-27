package kz.rudy.ar.core.meta

/**
 * Reads SAM 3.1 special-token output (the Responses `output_text`) into boxes and masks, and turns the
 * detections of ONE concept prompt into ONE [MaskResult] in our format.
 *
 * Grammar (https://dev.meta.ai/docs/sam/reading-segmentation and meta-models/meta-sam `protocol/sam3.md`):
 * ```
 * line   ::= "<" N "f" [";"fields] ">" ( [","] record )*        one line per frame; an image is frame <0f>
 * record ::= OBJECT_ID token+                                   exactly one box and one mask, any order
 * box    ::= "<|box;x1=..;y1=..;x2=..;y2=..;w=W;h=H[;c=..]|>"   x2/y2 INCLUSIVE source pixels, W×H = frame size
 * mask   ::= "<|mask;x=0;y=0[;c=..];data=MH,MW,<enc>payload|>" MH×MW raster (rows, cols), enc ~ lossless | ! one_bit
 * ```
 * Fields are read by key (order free, whitespace trimmed, unknown keys/tokens ignored). Lines that do not start
 * with `<` are prose and skipped. Zero matches = empty text = no detections (not an error).
 *
 * Our [MaskResult] is a full-frame mask: `width = W`, `height = H`, row-major uncompressed RLE starting with OFF
 * and summing to `W * H`. The MH×MW raster is placed at the box's top-left corner; the box is the raster's bounds
 * (`@meta-sam/parser` README: "attaches the box as bounds, so a renderer knows where the H × W raster sits inside
 * the w × h frame").
 *
 * **Merge rule** (several matches for one prompt, e.g. two cups): the masks are OR-ed into one frame-sized mask,
 * `bbox` is the union of the boxes and `score` the highest confidence. The pipeline then takes the largest
 * connected component where it needs a single object (cutout), so nothing is lost by merging.
 */
object SamTokenParser {

    /** Half-open box in source pixels (`right = x2 + 1`, `bottom = y2 + 1`) plus the frame size. */
    data class Box(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val frameWidth: Int, val frameHeight: Int,
        val confidence: Float? = null,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** `data=height,width,payload`; [payload] keeps its marker (`~` / `!`). */
    data class Mask(val height: Int, val width: Int, val payload: String, val confidence: Float? = null) {
        val encoding: SamMaskCodec.Encoding get() = SamMaskCodec.encodingOf(payload)
    }

    data class Detection(val frame: Int, val objectId: String, val box: Box, val mask: Mask)

    private val frameHeader = Regex("""^<(\d+)f(;[^>]*)?>""")
    private val signedInt = Regex("""-?\d+""")
    private val unsignedInt = Regex("""\d+""")
    private val decimal = Regex("""-?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?""")

    /**
     * Parses every record of every line. Only frame 0 is legal for an image; another frame index is rejected.
     *
     * [strict] (default): a malformed API line throws [MetaError.Parse] naming the line and the problem.
     * Lenient: a malformed line yields no records and is skipped, earlier and later lines are kept (the reference
     * parser's `malformed_record` behaviour, meta-sam protocol/sam3.md and `@meta-sam/parser` README).
     */
    fun parse(text: String, strict: Boolean = true): List<Detection> {
        val out = ArrayList<Detection>()
        for ((lineNo, raw) in text.split('\n').withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || !line.startsWith("<")) continue
            val records = ArrayList<Detection>()
            try {
                parseLine(line, records)
            } catch (e: SamMaskCodec.InvalidMask) {
                if (strict) throw MetaError.Parse("SAM line ${lineNo + 1}: ${e.message}", e)
                continue
            } catch (e: MalformedLine) {
                if (strict) throw MetaError.Parse("SAM line ${lineNo + 1}: ${e.message}: ${line.take(120)}", e)
                continue
            }
            out += records
        }
        return out
    }

    private class MalformedLine(message: String) : Exception(message)

    private fun parseLine(line: String, out: MutableList<Detection>) {
        val header = frameHeader.find(line) ?: throw MalformedLine("no <Nf> frame marker")
        val frame = header.groupValues[1].toIntOrNull() ?: throw MalformedLine("bad frame index")
        if (frame != 0) throw MalformedLine("image output must be frame <0f>, got <${frame}f>")
        var i = header.range.last + 1
        var records = 0
        while (i < line.length) {
            if (line[i] == ',') i++
            val idStart = i
            while (i < line.length && line[i].isDigit()) i++
            if (i == idStart) throw MalformedLine("expected an object id at column $i")
            val objectId = line.substring(idStart, i)
            var box: Box? = null
            var mask: Mask? = null
            while (line.startsWith("<|", i)) {
                val end = line.indexOf("|>", i + 2)
                if (end < 0) throw MalformedLine("unterminated token")
                val body = line.substring(i + 2, end)
                val name = body.substringBefore(';').trim()
                val fields = fields(body)
                when (name) {
                    "box" -> { if (box != null) throw MalformedLine("two boxes in one record"); box = box(fields) }
                    "mask" -> { if (mask != null) throw MalformedLine("two masks in one record"); mask = mask(fields) }
                    else -> Unit // unknown token: ignored, like the reference parser
                }
                i = end + 2
            }
            if (box == null || mask == null) throw MalformedLine("record $objectId needs exactly one box and one mask")
            out += Detection(frame, objectId, box, mask)
            records++
        }
        if (records == 0) throw MalformedLine("frame marker without records")
    }

    private fun fields(body: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var confidences = 0
        for (part in body.split(';').drop(1)) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val key = p.substringBefore('=').trim()
            val value = if ('=' in p) p.substringAfter('=').trim() else ""
            if (key == "c") { // a repeated c is ignored (record kept without confidence), per protocol/sam3.md
                confidences++
                if (confidences == 1) map[key] = value else map.remove(key)
                continue
            }
            if (key in map) throw MalformedLine("repeated field '$key'")
            map[key] = value
        }
        return map
    }

    private fun Map<String, String>.int(key: String, pattern: Regex): Int {
        val v = this[key] ?: throw MalformedLine("missing field '$key'")
        if (!pattern.matches(v)) throw MalformedLine("field '$key' is not an integer: '$v'")
        return v.toIntOrNull() ?: throw MalformedLine("field '$key' is out of range: '$v'")
    }

    /** `c` in 0..1, or null when absent or invalid (invalid confidence is ignored, not fatal). */
    private fun Map<String, String>.confidence(): Float? {
        val v = this["c"] ?: return null
        if (!decimal.matches(v)) return null
        val f = v.toFloatOrNull() ?: return null
        return if (f.isFinite() && f in 0f..1f) (if (f == 0f) 0f else f) else null
    }

    private fun box(f: Map<String, String>): Box {
        val x1 = f.int("x1", signedInt); val y1 = f.int("y1", signedInt)
        val x2 = f.int("x2", signedInt); val y2 = f.int("y2", signedInt)
        val w = f.int("w", unsignedInt); val h = f.int("h", unsignedInt)
        if (w <= 0 || h <= 0) throw MalformedLine("frame size must be positive (w=$w, h=$h)")
        if (x1 < 0 || y1 < 0 || x2 < x1 || y2 < y1 || x2 >= w || y2 >= h) {
            throw MalformedLine("box x1=$x1 y1=$y1 x2=$x2 y2=$y2 does not lie inside ${w}x$h")
        }
        return Box(x1, y1, x2 + 1, y2 + 1, w, h, f.confidence())
    }

    private fun mask(f: Map<String, String>): Mask {
        if (f["x"] != "0" || f["y"] != "0") throw MalformedLine("mask needs x=0;y=0")
        val data = f["data"] ?: throw MalformedLine("mask has no data field")
        val first = data.indexOf(',')
        val second = if (first < 0) -1 else data.indexOf(',', first + 1)
        if (second < 0) throw MalformedLine("mask data must be H,W,payload")
        val mh = data.substring(0, first).trim().toIntOrNull() ?: throw MalformedLine("mask height is not an integer")
        val mw = data.substring(first + 1, second).trim().toIntOrNull() ?: throw MalformedLine("mask width is not an integer")
        val payload = data.substring(second + 1)
        if (mh <= 0 || mw <= 0) throw MalformedLine("mask raster must be positive (${mh}x$mw)")
        SamMaskCodec.encodingOf(payload) // validates the marker
        return Mask(mh, mw, payload, f.confidence())
    }

    // ------------------------------------------------------------ to our MaskResult

    /**
     * One [MaskResult] for [label] from the token [text] of one SAM call.
     *
     * Empty text (no match) -> an empty mask (no rle, no bbox) of [emptyWidth] x [emptyHeight], i.e. "absent".
     * With [strict] a malformed line or a payload that does not decode throws [MetaError.Parse] (the CLI fixture
     * converter); without it malformed lines are skipped and an undecodable detection falls back to its filled box,
     * so a protocol or codec problem degrades to fewer/boxier masks instead of losing the prompt on the phone.
     */
    fun maskResult(label: String, text: String, strict: Boolean = true, emptyWidth: Int = 0, emptyHeight: Int = 0): MaskResult =
        merge(label, parse(text, strict), strict, emptyWidth, emptyHeight)

    fun merge(label: String, detections: List<Detection>, strict: Boolean = true, emptyWidth: Int = 0, emptyHeight: Int = 0): MaskResult {
        if (detections.isEmpty()) return MaskResult(label = label, width = emptyWidth, height = emptyHeight)
        val fw = detections[0].box.frameWidth
        val fh = detections[0].box.frameHeight
        if (detections.any { it.box.frameWidth != fw || it.box.frameHeight != fh }) {
            throw MetaError.Parse("SAM records disagree on the frame size for '$label'")
        }
        if (fw.toLong() * fh > SamMaskCodec.MAX_AREA) throw MetaError.Parse("SAM frame ${fw}x$fh is too large")
        val bits = BooleanArray(fw * fh)
        var left = fw; var top = fh; var right = 0; var bottom = 0
        var score: Float? = null
        for (d in detections) {
            val raster = try {
                SamMaskCodec.decode(d.mask.payload, d.mask.width, d.mask.height)
            } catch (e: SamMaskCodec.InvalidMask) {
                if (strict) throw MetaError.Parse("SAM mask of object ${d.objectId} for '$label': ${e.message}", e)
                null
            }
            if (raster == null) fillBox(bits, fw, d.box) else place(bits, fw, fh, d.box, raster, d.mask.width, d.mask.height)
            left = minOf(left, d.box.left); top = minOf(top, d.box.top)
            right = maxOf(right, d.box.right); bottom = maxOf(bottom, d.box.bottom)
            val c = d.box.confidence ?: d.mask.confidence
            if (c != null && (score == null || c > score)) score = c
        }
        return MaskResult(
            label = label, width = fw, height = fh,
            rle = MaskResult.encodeRle(bits),
            bbox = BBox(left, top, right - left, bottom - top),
            score = score,
        )
    }

    private fun fillBox(bits: BooleanArray, fw: Int, box: Box) {
        for (y in box.top until box.bottom) for (x in box.left until box.right) bits[y * fw + x] = true
    }

    /**
     * ORs the [mw] x [mh] raster into the frame with its origin at the box's top-left corner. Every documented
     * example has a raster exactly the size of its inclusive box (`mh = y2 - y1 + 1`, `mw = x2 - x1 + 1`).
     */
    private fun place(bits: BooleanArray, fw: Int, fh: Int, box: Box, raster: BooleanArray, mw: Int, mh: Int) {
        if (mw == box.width && mh == box.height) {
            for (y in 0 until mh) {
                val fy = box.top + y
                if (fy >= fh) break
                val src = y * mw
                val dst = fy * fw + box.left
                for (x in 0 until mw) if (raster[src + x] && box.left + x < fw) bits[dst + x] = true
            }
            return
        }
        // TODO(probe): no doc or conformance vector shows a raster whose size differs from its box; if the live API
        //  ever sends one, we assume it covers the box and resample nearest-neighbour. Check on the Day 1 probe.
        for (y in 0 until box.height) {
            val sy = (y.toLong() * mh / box.height).toInt()
            for (x in 0 until box.width) {
                val sx = (x.toLong() * mw / box.width).toInt()
                if (raster[sy * mw + sx]) bits[(box.top + y) * fw + box.left + x] = true
            }
        }
    }
}
