package com.floatsub

data class Cue(val s: Long, val e: Long, val t: String)

object Sub {
    private val CUE = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val LRC = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")
    private val TAGS = Regex("""<[^>]+>|\{[^}]*\}""")

    private fun clean(s: String) = TAGS.replace(s, "").trim()

    private fun ms(h: String, m: String, s: String, f: String): Long =
        ((if (h.isEmpty()) 0L else h.toLong()) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').toLong()

    fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, Charsets.UTF_16LE)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, Charsets.UTF_16BE)
        else -> String(b, Charsets.UTF_8)
    }

    /** Baca SRT / VTT / LRC jadi daftar baris subtitle (waktu dalam milidetik). */
    fun parse(raw: String, name: String): List<Cue> {
        val lines = raw.removePrefix("\uFEFF").lines()
        val out = ArrayList<Cue>()

        if (name.endsWith(".lrc", ignoreCase = true)) {
            val items = ArrayList<Pair<Long, String>>()
            for (ln in lines) {
                val body = clean(LRC.replace(ln, ""))
                for (m in LRC.findAll(ln)) {
                    val t = m.groupValues[1].toLong() * 60000 + (m.groupValues[2].toDouble() * 1000).toLong()
                    items.add(Pair(t, body))
                }
            }
            items.sortBy { p -> p.first }
            for (i in items.indices) {
                val t = items[i].first
                val body = items[i].second
                val end = if (i + 1 < items.size) items[i + 1].first else t + 5000
                if (body.isNotEmpty()) out.add(Cue(t, minOf(end, t + 15000), body))
            }
            return out
        }

        var start = 0L
        var end = 0L
        var buf: ArrayList<String>? = null

        fun flush() {
            val b = buf
            if (b != null) {
                val t = b.filter { x -> x.isNotEmpty() }.joinToString("\n")
                if (t.isNotEmpty()) out.add(Cue(start, end, t))
            }
            buf = null
        }

        for (i in lines.indices) {
            val ln = lines[i]
            val m = CUE.find(ln)
            if (m != null) {
                flush()
                val g = m.groupValues
                start = ms(g[1], g[2], g[3], g[4])
                end = ms(g[5], g[6], g[7], g[8])
                buf = ArrayList()
                continue
            }
            val b = buf ?: continue
            val s = ln.trim()
            if (s.isEmpty()) flush()
            else if (s.all { c -> c.isDigit() } && i + 1 < lines.size && CUE.containsMatchIn(lines[i + 1])) flush()
            else b.add(clean(s))
        }
        flush()
        return out.sortedBy { c -> c.s }
    }
}
