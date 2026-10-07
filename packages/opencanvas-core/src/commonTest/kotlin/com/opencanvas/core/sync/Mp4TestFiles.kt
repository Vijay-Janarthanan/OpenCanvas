package com.opencanvas.core.sync

/** Builds small MP4 files with a chosen sequence of audio frame sizes, for the parser tests. */
internal object Mp4TestFiles {

    fun u32(value: Long): ByteArray = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    fun u32(value: Int): ByteArray = u32(value.toLong())

    fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        return u32(8 + payload.size) + type.encodeToByteArray() + payload
    }

    private fun zeros(count: Int) = ByteArray(count)

    private fun trak(id: Int, handler: String, timescale: Int, sizes: IntArray?, delta: Int): ByteArray {
        val tkhd = box("tkhd", u32(0), u32(0), u32(0), u32(id), zeros(68))
        val mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(timescale), u32(0), zeros(4))
        val hdlr = box("hdlr", u32(0), u32(0), handler.encodeToByteArray(), zeros(13))
        val count = sizes?.size ?: 0
        val stts = box("stts", u32(0), u32(if (count > 0) 1 else 0), if (count > 0) u32(count) + u32(delta) else ByteArray(0))
        val stsz = box(
            "stsz", u32(0), u32(0), u32(count),
            sizes?.fold(ByteArray(0)) { acc, size -> acc + u32(size) } ?: ByteArray(0),
        )
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", box("stbl", stts, stsz))))
    }

    /** A progressive file: a video track, then the audio track with [sizes]; moov first unless [moovLast]. */
    fun progressive(sizes: IntArray, timescale: Int = 44_100, delta: Int = 1024, moovLast: Boolean = false, mediaBytes: Int = 4096): ByteArray {
        val ftyp = box("ftyp", "isom".encodeToByteArray(), u32(0), "isomiso2".encodeToByteArray())
        val video = trak(1, "vide", 90_000, IntArray(sizes.size * 2) { 7000 + it }, 3000)
        val moov = box("moov", video, trak(2, "soun", timescale, sizes, delta))
        val mdat = box("mdat", zeros(mediaBytes))
        return if (moovLast) ftyp + mdat + moov else ftyp + moov + mdat
    }

    /** A fragmented (DASH-style) audio-only file: the frame sizes live in moof boxes. */
    fun fragmented(sizes: IntArray, timescale: Int = 44_100, delta: Int = 1024, perFragment: Int = 40): ByteArray {
        val ftyp = box("ftyp", "iso6".encodeToByteArray(), u32(0), "iso6".encodeToByteArray())
        val trex = box("trex", u32(0), u32(1), u32(1), u32(delta), u32(0), u32(0))
        val moov = box("moov", trak(1, "soun", timescale, null, delta), box("mvex", trex))
        val fragments = sizes.toList().chunked(perFragment).mapIndexed { index, chunk ->
            val mfhd = box("mfhd", u32(0), u32(index + 1))
            val tfhd = box("tfhd", u32(0x020000), u32(1))
            val trun = box("trun", u32(0x201), u32(chunk.size), u32(0), chunk.fold(ByteArray(0)) { acc, size -> acc + u32(size) })
            box("moof", mfhd, box("traf", tfhd, trun)) + box("mdat", zeros(chunk.sum().coerceAtMost(2048)))
        }
        return fragments.fold(ftyp + moov) { acc, fragment -> acc + fragment }
    }
}

/** A [RangeReader] over bytes already in memory; counts how many bytes were asked for. */
internal class MemoryReader(private val data: ByteArray) : RangeReader {
    var requested = 0L
        private set

    override val length: Long get() = data.size.toLong()

    override suspend fun read(from: Long, count: Int): ByteArray {
        if (from >= data.size) return ByteArray(0)
        val end = minOf(data.size.toLong(), from + count).toInt()
        requested += end - from
        return data.copyOfRange(from.toInt(), end)
    }
}
