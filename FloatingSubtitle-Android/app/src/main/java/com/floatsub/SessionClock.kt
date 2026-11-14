package com.floatsub

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Cuma perlu ada dan diaktifkan di Pengaturan > Akses notifikasi. Android baru ngasih
 * aplikasi ini izin baca sesi media aplikasi lain (browser, YouTube, dll) kalau ini aktif.
 */
class MediaWatcher : NotificationListenerService()

/**
 * Baca posisi playback dari sesi media aplikasi lain lewat MediaSession (yang sama dipakai
 * kontrol media di notifikasi). Gak butuh script/ekstensi, tapi browsernya harus ngasih posisi.
 */
class SessionClock(private val ctx: Context) {

    private class Snap(
        val label: String, val playing: Boolean, val posMs: Long, val at: Long,
        val speed: Float, val durMs: Long, val title: String
    )

    companion object {
        fun hasAccess(c: Context): Boolean {
            val s = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
            return s != null && s.contains(c.packageName)
        }

        private fun mmss(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }

    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val comp = ComponentName(ctx, MediaWatcher::class.java)
    private val labels = HashMap<String, String>()

    @Volatile private var snap: Snap? = null

    /** Daftar sesi yang kebaca, buat ditampilin di layar utama (membantu diagnosa). */
    @Volatile var report: List<String> = emptyList()
        private set

    /** True kalau izin Akses notifikasi belum diaktifkan. */
    @Volatile var denied = false
        private set

    private fun labelOf(pkg: String): String {
        val old = labels[pkg]
        if (old != null) return old
        val l = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        labels[pkg] = l
        return l
    }

    /** Dipanggil berkala (tiap ~400 ms) dari service. */
    fun poll() {
        val list: List<MediaController> = try {
            msm.getActiveSessions(comp)
        } catch (e: SecurityException) {
            denied = true
            snap = null
            report = emptyList()
            return
        } catch (e: Exception) {
            snap = null
            report = listOf("⚠ " + e.javaClass.simpleName)
            return
        }
        denied = false
        val now = SystemClock.elapsedRealtime()
        var best: Snap? = null
        val lines = ArrayList<String>()
        for (c in list) {
            val label = labelOf(c.packageName)
            val st = c.playbackState
            if (st == null) {
                lines.add("• $label: belum ada status")
                continue
            }
            val playing = st.state == PlaybackState.STATE_PLAYING
            val md = c.metadata
            val dur = if (md != null) md.getLong(MediaMetadata.METADATA_KEY_DURATION) else 0L
            val title = (if (md != null) md.getString(MediaMetadata.METADATA_KEY_TITLE) else null) ?: ""
            if (st.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
                lines.add("• $label: " + (if (playing) "play" else "pause") + ", TAPI posisi gak dilaporin")
                continue
            }
            val at = if (st.lastPositionUpdateTime > 0L) st.lastPositionUpdateTime else now
            val s = Snap(label, playing, st.position, at, st.playbackSpeed, dur, title)
            lines.add("• $label: " + (if (playing) "play " else "pause ") + mmss(st.position) + (if (dur > 0L) " / " + mmss(dur) else ""))
            val b = best
            if (b == null || (s.playing && !b.playing)) best = s
        }
        snap = best
        report = lines
    }

    /** Posisi sekarang (diekstrapolasi dari status terakhir), atau null kalau gak ada sesi yang bisa dipakai. */
    fun get(): WebClock? {
        val s = snap ?: return null
        var pos = s.posMs
        if (s.playing) pos += ((SystemClock.elapsedRealtime() - s.at) * s.speed).toLong()
        pos = if (s.durMs > 0L) pos.coerceIn(0L, s.durMs) else maxOf(pos, 0L)
        return WebClock(pos, s.playing, false, s.durMs, s.title, "notifikasi media (" + s.label + ")")
    }
}
