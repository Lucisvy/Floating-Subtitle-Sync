package com.floatsub

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

// File ini SENGAJA gak pakai class Android sama sekali (cuma Java/Kotlin biasa),
// jadi bisa dites langsung di JVM. Isinya sama dengan "jembatan browser" di versi PC.

/** Port server lokal. Harus sama dengan port di userscript (Userscript.kt ikut otomatis). */
const val WEB_PORT = 41730

/** Posisi playback "sekarang" dari satu sumber (script browser atau sesi media Android). */
data class WebClock(
    val posMs: Long,
    val playing: Boolean,
    val lost: Boolean,
    val durMs: Long,
    val title: String,
    val via: String
)

/** Pilih sumber yang dipakai: yang lagi play menang; kalau seri, script browser (lebih akurat). */
fun chooseClock(web: WebClock?, ses: WebClock?): WebClock? {
    val w = if (web != null && !web.lost) web else null
    return when {
        w != null && w.playing -> w
        ses != null && ses.playing -> ses
        w != null -> w
        else -> ses
    }
}

// ====================================================================== JSON datar
private val BAD = Any()

private class JsonReader(val s: String) {
    var i = 0
    val n = s.length

    fun ws() {
        while (i < n && s[i].isWhitespace()) i++
    }

    fun str(): String? {
        if (i >= n || s[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < n) {
            val c = s[i++]
            if (c == '"') return sb.toString()
            if (c != '\\') {
                sb.append(c)
                continue
            }
            if (i >= n) return null
            when (s[i++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 > n) return null
                    val v = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                    sb.append(v.toChar())
                    i += 4
                }
                else -> return null
            }
        }
        return null
    }

    fun number(): Any? {
        val st = i
        if (s[i] == '-') i++
        while (i < n && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return s.substring(st, i).toDoubleOrNull() ?: BAD
    }

    fun value(): Any? {
        ws()
        if (i >= n) return BAD
        val c = s[i]
        return when {
            c == '"' -> str() ?: BAD
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            s.startsWith("null", i) -> { i += 4; null }
            s.startsWith("NaN", i) -> { i += 3; Double.NaN }
            s.startsWith("-Infinity", i) -> { i += 9; Double.NEGATIVE_INFINITY }
            s.startsWith("Infinity", i) -> { i += 8; Double.POSITIVE_INFINITY }
            c == '-' || c.isDigit() -> number()
            else -> BAD
        }
    }

    fun finish(m: Map<String, Any?>): Map<String, Any?>? {
        ws()
        return if (i == n) m else null
    }

    fun obj(): Map<String, Any?>? {
        ws()
        if (i >= n || s[i] != '{') return null
        i++
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (i < n && s[i] == '}') {
            i++
            return finish(m)
        }
        while (true) {
            ws()
            val k = str() ?: return null
            ws()
            if (i >= n || s[i] != ':') return null
            i++
            val v = value()
            if (v === BAD) return null
            m[k] = v
            ws()
            if (i >= n) return null
            if (s[i] == ',') {
                i++
                continue
            }
            if (s[i] == '}') {
                i++
                return finish(m)
            }
            return null
        }
    }
}

object MiniJson {
    /** Baca objek JSON datar jadi Map. Null kalau bentuknya gak valid atau ada objek/array di dalamnya. */
    fun parseFlat(s: String): Map<String, Any?>? = JsonReader(s).obj()
}

// ====================================================================== penampung laporan browser
class WebBridge(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private class Src(
        val t: Double, val rate: Double, val dur: Double,
        val paused: Boolean, val muted: Boolean, val title: String, val at: Long
    )

    companion object {
        const val STALE_PLAY = 6.0      // detik tanpa kabar (lagi play) sebelum dianggap putus
        const val STALE_PAUSE = 20.0    // idem, tapi lagi pause (script ngabarin tiap 3 detik)
    }

    private val lock = Any()
    private val src = HashMap<String, Src>()
    private var active: String? = null

    fun activeId(): String? = synchronized(lock) { active }

    fun clear() {
        synchronized(lock) {
            src.clear()
            active = null
        }
    }

    /** Terima satu laporan dari userscript. False kalau isinya gak valid. */
    fun update(m: Map<String, Any?>): Boolean {
        val sid = (m["id"] as? String)?.take(32) ?: return false
        val t = (m["t"] as? Double) ?: return false
        val rate = (m["rate"] as? Double)?.takeIf { it != 0.0 } ?: 1.0
        var dur = (m["dur"] as? Double) ?: 0.0
        if (!t.isFinite() || t < 0.0 || !rate.isFinite() || rate <= 0.0 || rate > 16.0) return false
        if (!dur.isFinite() || dur < 0.0) dur = 0.0
        val paused = m["paused"] == true
        val muted = m["muted"] == true
        val title = ((m["title"] as? String) ?: "").take(200)
        val ev = (m["ev"] as? String) ?: ""
        val now = nowMs()
        synchronized(lock) {
            src[sid] = Src(t, rate, dur, paused, muted, title, now)
            val iter = src.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (now - e.value.at > 300_000L && e.key != active) iter.remove()
            }
            if (active != sid) {
                val cur = active?.let { k -> src[k] }
                val limit = (if (cur != null && cur.paused) STALE_PAUSE else STALE_PLAY) * 1000.0
                val stale = cur == null || (now - cur.at) > limit
                // Ambil alih cuma kalau sumber lama udah putus, atau sumber ini BARU mulai diputar
                // (dan kedengeran). Jadi video iklan/background yang loop gak bisa nyuri sync.
                if (stale || (ev == "play" && !muted)) active = sid
            }
        }
        return true
    }

    /** Posisi sekarang (sudah diekstrapolasi), atau null kalau belum ada sumber. */
    fun get(): WebClock? {
        val now = nowMs()
        synchronized(lock) {
            val r = active?.let { k -> src[k] } ?: return null
            val age = (now - r.at) / 1000.0
            val playing = !r.paused
            val lost = age > (if (playing) STALE_PLAY else STALE_PAUSE)
            val t = r.t + (if (playing) minOf(age, STALE_PLAY) * r.rate else 0.0)
            return WebClock(
                posMs = (t * 1000.0).toLong(),
                playing = playing && !lost,
                lost = lost,
                durMs = (r.dur * 1000.0).toLong(),
                title = r.title,
                via = "script browser"
            )
        }
    }
}

// ====================================================================== server HTTP di 127.0.0.1
class WebServer(
    private val port: Int,
    private val bridge: WebBridge,
    private val script: () -> String
) {
    @Volatile private var ss: ServerSocket? = null

    @Volatile var error: String? = null
        private set

    fun start(): Boolean {
        stop()
        var s: ServerSocket? = null
        return try {
            val sock = ServerSocket()
            s = sock
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            ss = sock
            error = null
            thread(name = "fsp-web", isDaemon = true) { loop(sock) }
            true
        } catch (e: Exception) {
            try {
                s?.close()
            } catch (e2: IOException) {
            }
            error = e.message ?: e.javaClass.simpleName
            false
        }
    }

    fun stop() {
        val s = ss
        ss = null
        try {
            s?.close()
        } catch (e: IOException) {
        }
    }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            thread(name = "fsp-web-conn", isDaemon = true) { handle(c) }
        }
    }

    private fun handle(c: Socket) {
        try {
            c.use { sock ->
                sock.soTimeout = 3000
                val inp = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                val head = readHead(inp)
                if (head == null) {
                    reply(out, 400)
                    return
                }
                val lines = head.split("\r\n")
                val req = lines[0].split(" ")
                val method = req[0]
                val path = (req.getOrNull(1) ?: "").substringBefore('?')
                var len = 0
                for (k in 1 until lines.size) {
                    val ln = lines[k]
                    if (ln.substringBefore(':').trim().equals("content-length", ignoreCase = true)) {
                        len = ln.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                when (method) {
                    "OPTIONS" -> reply(out, 204)
                    "GET" -> when (path) {
                        "/ping" -> reply(out, 200, "floating-subtitle-player".toByteArray(Charsets.UTF_8))
                        "/fsp.user.js" -> reply(out, 200, script().toByteArray(Charsets.UTF_8), "text/javascript; charset=utf-8")
                        else -> reply(out, 404)
                    }
                    "POST" -> {
                        if (path != "/sync" || len < 1 || len > 4096) {
                            reply(out, 400)
                            return
                        }
                        val body = ByteArray(len)
                        readFully(inp, body)
                        val m = MiniJson.parseFlat(String(body, Charsets.UTF_8))
                        reply(out, if (m != null && bridge.update(m)) 204 else 400)
                    }
                    else -> reply(out, 405)
                }
            }
        } catch (e: Exception) {
            // koneksi putus / timeout: abaikan aja
        }
    }

    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var m = 0   // sudah cocok berapa karakter dari \r\n\r\n
        while (buf.size() < 8192) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            m = when {
                b == 13 && (m == 0 || m == 2) -> m + 1
                b == 10 && (m == 1 || m == 3) -> m + 1
                b == 13 -> 1
                else -> 0
            }
            if (m == 4) return String(buf.toByteArray(), Charsets.ISO_8859_1)
        }
        return null
    }

    private fun readFully(inp: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val r = inp.read(b, off, b.size - off)
            if (r < 0) throw IOException("body kepotong")
            off += r
        }
    }

    private fun reply(out: OutputStream, code: Int, body: ByteArray = ByteArray(0), ctype: String = "text/plain; charset=utf-8") {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Private-Network: true\r\n")
        if (code != 204) {
            sb.append("Content-Type: ").append(ctype).append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (code != 204 && body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
