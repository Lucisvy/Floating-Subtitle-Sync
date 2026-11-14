package com.floatsub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Status yang dibaca layar utama (satu proses, jadi cukup objek biasa). */
object State {
    @Volatile var title = ""
    @Volatile var sub = ""
    @Volatile var error = ""
    @Volatile var playing = false
    @Volatile var pos = 0
    @Volatile var dur = 0
    @Volatile var index = 0
    @Volatile var names: List<String> = emptyList()
    @Volatile var running = false

    // ---- mode browser
    @Volatile var web = false                          // true = waktu subtitle ngikut audio di browser
    @Volatile var webInfo = ""                         // status sinkron, buat layar utama
    @Volatile var delayMs = 0                          // + = subtitle muncul lebih lambat
    @Volatile var serverError = ""                     // kalau port server lokal gak bisa dibuka
    @Volatile var sessionReport: List<String> = emptyList()
    @Volatile var sessionDenied = false                // izin Akses notifikasi belum aktif
}

fun fmtDelay(ms: Int): String = String.format(Locale.US, "%+.1fs", ms / 1000.0)

class PlayerService : Service() {

    companion object {
        const val LOAD = "com.floatsub.LOAD"
        const val TOGGLE = "com.floatsub.TOGGLE"
        const val SEEK_BY = "com.floatsub.SEEK_BY"
        const val SEEK_TO = "com.floatsub.SEEK_TO"
        const val SELECT = "com.floatsub.SELECT"
        const val STYLE = "com.floatsub.STYLE"
        const val STOP = "com.floatsub.STOP"
        const val WEB_ON = "com.floatsub.WEB_ON"
        const val WEB_OFF = "com.floatsub.WEB_OFF"
        const val DELAY = "com.floatsub.DELAY"          // extra "ms": geser delay sekian ms (0 = reset)
        private const val CH = "floatsub"
        private const val FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }

    private class Item(val audio: Uri, val name: String, val sub: Uri?, val subName: String, val auto: Boolean)

    private val h = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("float", MODE_PRIVATE) }
    private var mp: MediaPlayer? = null
    private var items: List<Item> = emptyList()
    private var idx = 0
    private var cues: List<Cue> = emptyList()
    private var wm: WindowManager? = null
    private var tv: TextView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var lastText = "\u0000"
    private var hintText = ""
    private var hintUntil = 0L
    private var lastKey = ""
    private var warned = false
    private val bridge = WebBridge()
    private var server: WebServer? = null
    private val sessions by lazy { SessionClock(this) }
    private var lastPoll = 0L
    private var dragX = 0f
    private var dragY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Pemutar", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notif())
        }
        State.running = true
        hintText = "Overlay siap. Geser teks ini buat pindahin."
        hintUntil = System.currentTimeMillis() + 6000
        h.post(tick)
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) return START_NOT_STICKY
        when (i.action) {
            LOAD -> load(i)
            TOGGLE -> if (!State.web || mp != null) {   // tekan Putar pas mode browser = pindah ke mode file
                setWeb(false)
                toggle()
            }
            SEEK_BY -> if (!State.web) mp?.let { p -> p.seekTo((p.currentPosition + i.getIntExtra("ms", 0)).coerceIn(0, p.duration)) }
            SEEK_TO -> if (!State.web) mp?.seekTo(i.getIntExtra("ms", 0))
            SELECT -> {
                val n = i.getIntExtra("i", 0)
                if (n in items.indices) {
                    setWeb(false)
                    loadItem(n)
                }
            }
            STYLE -> applyStyle()
            WEB_ON -> setWeb(true)
            WEB_OFF -> setWeb(false)
            DELAY -> {
                val by = i.getIntExtra("ms", 0)
                State.delayMs = if (by == 0) 0 else (State.delayMs + by).coerceIn(-600000, 600000)
                lastText = "\u0000"
                hintText = "Delay subtitle " + fmtDelay(State.delayMs)
                hintUntil = System.currentTimeMillis() + 2000
            }
            STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        mp?.release()
        mp = null
        stopServer()
        removeOverlay()
        State.running = false
        State.web = false
        super.onDestroy()
    }

    // ---------- mode browser
    private fun setWeb(on: Boolean) {
        if (State.web == on) return
        State.web = on
        if (on) {
            mp?.pause()
            val s = WebServer(WEB_PORT, bridge) { Userscript.text(WEB_PORT) }
            s.start()
            server = s
            State.serverError = s.error ?: ""
            hintText = "Mode browser aktif. Putar audio di browser, subtitle ngikut."
            hintUntil = System.currentTimeMillis() + 5000
        } else {
            stopServer()
            State.title = items.getOrNull(idx)?.name?.substringBeforeLast('.') ?: ""
        }
        lastText = "\u0000"
        refreshNotif()
    }

    private fun stopServer() {
        server?.stop()
        server = null
        State.serverError = ""
        bridge.clear()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()                      // aplikasi dibuang dari daftar aplikasi terbuka -> semuanya berhenti
        super.onTaskRemoved(rootIntent)
    }

    // ---------- muat file
    private fun load(i: Intent) {
        val uris = i.getStringArrayListExtra("uris") ?: return
        val names = i.getStringArrayListExtra("names") ?: return
        State.error = ""
        val isSub = Regex("""\.(srt|lrc|vtt|txt)$""", RegexOption.IGNORE_CASE)
        fun base(n: String) = n.substringBeforeLast('.').lowercase()
        val subs = names.indices.filter { k -> isSub.containsMatchIn(names[k]) }
        val auds = names.indices.filter { k -> !isSub.containsMatchIn(names[k]) }
        if (auds.isEmpty()) {                       // yang dipilih cuma subtitle: pasang ke lagu yang lagi aktif
            if (subs.isNotEmpty()) applySub(Uri.parse(uris[subs[0]]), names[subs[0]], false)
            return
        }
        setWeb(false)                               // pilih file audio = balik ke mode file
        items = auds.map { a ->
            val same = subs.firstOrNull { s -> base(names[s]) == base(names[a]) }
            val s = same ?: (if (auds.size == 1 && subs.size == 1) subs[0] else null)
            Item(Uri.parse(uris[a]), names[a], s?.let { x -> Uri.parse(uris[x]) }, s?.let { x -> names[x] } ?: "", same != null)
        }
        State.names = items.map { x -> (if (x.sub != null) "✓ " else "• ") + x.name }
        loadItem(0)
    }

    private fun applySub(u: Uri, name: String, auto: Boolean) {
        cues = try {
            val bytes = contentResolver.openInputStream(u)!!.use { s -> s.readBytes() }
            Sub.parse(Sub.decode(bytes), name)
        } catch (e: Throwable) {
            fail("Gagal baca subtitle: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        State.sub = if (cues.isEmpty()) "gak ada baris subtitle yang terbaca" else
            "$name (${cues.size} baris)" + (if (auto) ", otomatis karena nama sama" else "")
        lastText = "\u0000"
    }

    private fun loadItem(n: Int) {
        idx = n
        val cur = items[n]
        mp?.release()
        mp = null
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            p.setDataSource(applicationContext, cur.audio)
            p.setOnCompletionListener {
                if (idx + 1 < items.size) {
                    loadItem(idx + 1)
                    mp?.start()
                }
            }
            p.prepare()
            mp = p
        } catch (e: Exception) {
            fail("Gagal memuat audio: ${e.message}")
            return
        }
        State.title = cur.name.substringBeforeLast('.')
        State.index = n
        State.dur = mp?.duration ?: 0
        if (cur.sub != null) {
            applySub(cur.sub, cur.subName, cur.auto)
        } else {
            cues = emptyList()
            State.sub = "gak ada subtitle bernama sama: pilih manual lewat tombol Ganti subtitle"
            lastText = "\u0000"
        }
        refreshNotif()
    }

    private fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) p.pause() else p.start()
        State.playing = p.isPlaying
        refreshNotif()
    }

    private fun fail(msg: String) {
        State.error = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- overlay
    private val drag = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = e.rawX
                dragY = e.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x += (e.rawX - dragX).toInt()
                lp.y -= (e.rawY - dragY).toInt()
                dragX = e.rawX
                dragY = e.rawY
                tv?.let { v -> wm?.updateViewLayout(v, lp) }
                true
            }
            MotionEvent.ACTION_UP -> {
                prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                true
            }
            else -> false
        }
    }

    private fun ensureOverlay(): Boolean {
        if (tv != null) return true
        if (!Settings.canDrawOverlays(this)) {
            if (!warned) {
                warned = true
                fail("Izin tampil di atas aplikasi lain belum aktif. Buka layar utama lalu tekan tombol izin.")
            }
            return false
        }
        val d = resources.displayMetrics.density
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = TextView(this)
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        v.setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
        v.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        v.maxWidth = (resources.displayMetrics.widthPixels * 0.92).toInt()
        v.visibility = View.GONE
        v.setOnTouchListener(drag)
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAGS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = prefs.getInt("x", 0)
        lp.y = prefs.getInt("y", (96 * d).toInt())
        w.addView(v, lp)
        wm = w
        tv = v
        applyStyle()
        return true
    }

    private fun applyStyle() {
        val v = tv ?: return
        val d = resources.displayMetrics.density
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getInt("size", 22).toFloat())
        v.typeface = Typeface.DEFAULT_BOLD
        val bg = GradientDrawable()
        bg.cornerRadius = 14 * d
        bg.setColor(Color.argb(prefs.getInt("alpha", 55) * 255 / 100, 0, 0, 0))
        v.background = bg
        val locked = prefs.getBoolean("lock", false)
        lp.flags = FLAGS or (if (locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        // Android 12+: sentuhan baru tembus ke aplikasi lain kalo alpha jendela overlay <= 0.8
        lp.alpha = if (locked) 0.79f else 1f
        wm?.updateViewLayout(v, lp)
    }

    private fun removeOverlay() {
        val v = tv
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (e: Exception) {
            }
        }
        tv = null
    }

    private fun showText(t: String) {
        if (t.isNotEmpty() && !ensureOverlay()) return
        val v = tv ?: return
        v.text = t
        v.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------- notifikasi
    private fun notif(): Notification {
        fun pi(a: String, code: Int, ms: Int? = null): PendingIntent {
            val ix = Intent(this, PlayerService::class.java).setAction(a)
            if (ms != null) ix.putExtra("ms", ms)
            return PendingIntent.getService(this, code, ix, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val open = PendingIntent.getActivity(this, 9, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
        if (State.web) {
            // mode browser: tombol delay langsung di notifikasi, jadi gak perlu keluar dari browser
            b.setContentTitle(if (State.title.isEmpty()) "Mode browser" else State.title)
                .setContentText("Delay " + fmtDelay(State.delayMs) + (if (State.playing) " • lagi play" else " • pause"))
                .addAction(android.R.drawable.ic_media_rew, "-0.5s", pi(DELAY, 3, -500))
                .addAction(android.R.drawable.ic_media_ff, "+0.5s", pi(DELAY, 4, 500))
        } else {
            b.setContentTitle(if (State.title.isEmpty()) "Floating Subtitle" else State.title)
                .setContentText(if (State.playing) "Memutar" else "Dijeda")
                .addAction(
                    if (State.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (State.playing) "Jeda" else "Putar", pi(TOGGLE, 1)
                )
        }
        return b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup", pi(STOP, 2)).build()
    }

    private fun refreshNotif() {
        getSystemService(NotificationManager::class.java).notify(1, notif())
    }

    private fun shutdown() {
        h.removeCallbacks(tick)
        mp?.release()
        mp = null
        stopServer()
        removeOverlay()
        State.running = false
        State.web = false
        State.playing = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Baris subtitle yang aktif di posisi [pos] (ms); kosong kalau lagi di celah antar baris. */
    private fun cueAt(pos: Long): String {
        var lo = 0
        var hi = cues.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].s <= pos) {
                i = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var j = i
        while (j >= 0 && j >= i - 3) {
            if (pos < cues[j].e) return cues[j].t
            j--
        }
        return ""
    }

    /** Mode browser: ambil posisi dari script browser dan/atau sesi media Android. Return -1 kalau belum ada. */
    private fun webPos(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoll >= 400) {
            lastPoll = now
            sessions.poll()
            State.sessionReport = sessions.report
            State.sessionDenied = sessions.denied
        }
        val c = chooseClock(bridge.get(), sessions.get())
        if (c == null) {
            State.playing = false
            State.webInfo = "○ Nunggu audio dari browser…"
            return -1L
        }
        State.pos = c.posMs.toInt()
        State.dur = c.durMs.toInt()
        State.playing = c.playing
        if (c.title.isNotEmpty()) State.title = c.title
        State.webInfo = if (c.lost) "⚠ Koneksi ke browser putus (tab-nya ketutup?)"
        else "● Nyambung via " + c.via + " • " + (if (c.playing) "lagi play" else "pause")
        return c.posMs
    }

    // ---------- loop: cocokin posisi audio dengan baris subtitle
    private val tick = object : Runnable {
        override fun run() {
            var pos = -1L
            if (State.web) {
                pos = webPos()
            } else {
                val p = mp
                if (p != null) {
                    pos = p.currentPosition.toLong()
                    State.pos = p.currentPosition
                    State.dur = p.duration
                    State.playing = p.isPlaying
                }
            }
            var text = if (pos >= 0L) cueAt(pos - State.delayMs) else ""
            if (text.isEmpty() && System.currentTimeMillis() < hintUntil) text = hintText
            if (text != lastText) {
                lastText = text
                showText(text)
            }
            val key = "" + State.web + State.playing + State.delayMs + State.title
            if (key != lastKey) {
                lastKey = key
                refreshNotif()
            }
            h.postDelayed(this, 200)
        }
    }
}
