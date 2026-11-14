FLOATING SUBTITLE (Android) v2 - cara bikin APK tanpa Android Studio
====================================================================
BARU di v2: MODE BROWSER (bagian E). Subtitle ngikut audio yang diputar di browser, gak perlu download audionya.
File yang berubah dari v1: app/src/main/AndroidManifest.xml, PlayerService.kt, MainActivity.kt
File baru: WebSync.kt, Userscript.kt, SessionClock.kt (semuanya di app/src/main/java/com/floatsub/)
Update dari v1: upload ulang semua isi folder ini ke repo-mu (file yang namanya sama otomatis ketimpa), lalu tunggu Actions build.


A. BUILD APK DI CLOUD (GitHub, gratis)
1. Extract file ZIP ini.
2. Buka github.com, daftar / login, lalu klik "New repository". Nama bebas (mis. floatsub), pilih Private, klik "Create repository".
3. Di halaman repo, klik link "uploading an existing file". Drag SEMUA isi folder hasil extract ke halaman itu
   (file settings.gradle.kts, build.gradle.kts, gradle.properties, folder app, dan folder .github). Klik "Commit changes".
   - Kalau folder .github tidak ikut ke-upload (karena namanya diawali titik):
     klik "Add file" > "Create new file", ketik nama  .github/workflows/build.yml
     (ketik garis miring supaya otomatis jadi folder), lalu tempel isi file build.yml yang ada di ZIP ini. Klik "Commit changes".
4. Buka tab "Actions". Tunggu workflow "Build APK" sampai ada centang hijau (sekitar 2-5 menit).
   Kalau jadi merah (X): klik workflow-nya, buka langkah "Build", copy pesan error-nya dan kirim ke Claude.
5. Klik workflow yang sukses, scroll ke bagian "Artifacts", download "FloatingSubtitle-APK". Extract, isinya app-debug.apk.

B. PASANG DI TABLET
1. Pindahin app-debug.apk ke tablet (USB, Google Drive, WhatsApp ke diri sendiri, dll).
2. Buka file-nya. Izinkan "pasang dari sumber ini" kalau diminta. Kalau ada pemindaian keamanan Huawei, pilih lanjutkan.

C. PENGATURAN PERTAMA KALI
1. Buka aplikasi, izinkan notifikasi.
2. Tekan "Izinkan tampil di atas aplikasi lain" lalu aktifkan untuk Floating Subtitle.
3. Biar nggak dimatikan sistem Huawei: Pengaturan > Baterai > Peluncuran aplikasi > Floating Subtitle > matikan "Kelola otomatis",
   lalu aktifkan semua: Peluncuran otomatis, Peluncuran sekunder, Berjalan di latar belakang.

D. PAKAI
1. "Pilih audio + subtitle": pilih keduanya sekaligus (tahan lama satu file, tap yang lain). Nama sama = otomatis berpasangan.
2. Tekan Putar, lalu pindah ke aplikasi lain. Subtitle melayang di atas semuanya; geser teksnya buat memindahkan.
3. Ukuran teks dan kepekatan latar bisa diatur. "Kunci overlay" bikin sentuhan tembus ke aplikasi di belakangnya
   (matiin dulu kalau mau menggeser).

E. MODE BROWSER (audio diputar di browser, subtitle ngikut otomatis)
1. Buka aplikasi, centang "Sinkron ke browser", lalu tekan "Ganti subtitle" dan pilih file .srt/.lrc/.vtt-nya.
   (Subtitle bisa dipilih sebelum atau sesudah centang; urutannya bebas.)
2. Ada DUA cara buat ngasih tahu aplikasi posisi audionya. Aplikasi otomatis pakai yang lagi play,
   jadi boleh disiapin dua-duanya.

   CARA 1 - Lewat notifikasi media (gak perlu script, gak perlu ekstensi)
   a. Tekan "Izinkan akses notifikasi", lalu aktifkan Floating Subtitle di daftar yang kebuka.
   b. Putar audio di browser. Balik ke aplikasi ini dan lihat "Sesi media yang kebaca".
      - Kalau browser-mu muncul lengkap dengan waktu (contoh "Huawei Browser: play 3:12 / 60:00"), cara ini jalan.
      - Kalau muncul "TAPI posisi gak dilaporin", atau browser-mu gak muncul sama sekali: pakai cara 2.
   Catatan: tergantung browser dan situsnya, belum tentu semua ngasih posisi.

   CARA 2 - Lewat script di browser (paling akurat, butuh browser yang bisa userscript)
   a. Pakai Firefox untuk Android (APK resmi dari mozilla.org atau F-Droid; atau AppGallery kalau ada).
      Browser bawaan Huawei gak bisa pasang userscript.
   b. Di Firefox: menu > Add-ons (Pengaya) > cari "Violentmonkey" (atau Tampermonkey) > Tambahkan.
   c. Di aplikasi ini (dengan "Sinkron ke browser" tercentang): tekan "Pasang script ke browser".
      Firefox kebuka ke halaman install Violentmonkey, tekan Install/Confirm.
      Kalau gak muncul halaman install: tekan "Salin script", lalu di Violentmonkey buat script baru (tombol +),
      hapus isinya, tempel, simpan.
   d. Buka situs audionya di Firefox yang sama dan putar. Status di layar utama jadi "Nyambung via script browser".
   Script cuma ngirim posisi audio dan judul halaman ke aplikasi ini lewat 127.0.0.1 (di tablet-mu sendiri).
   Script yang sama dipakai di versi PC (file floating_subtitle_sync.user.js).
3. Subtitle muncul di atas browser. Kalau selisih sama audio, geser pakai tombol Delay di layar utama,
   atau tombol -0.5s / +0.5s di notifikasi (gak perlu keluar dari browser).
   Tanda + artinya subtitle muncul lebih lambat, tanda - lebih cepat.
4. Mau balik muter file lokal: tekan "Pilih audio + subtitle" (mode browser otomatis mati).
5. Sama seperti C.3: matikan "Kelola otomatis" dan izinkan semua untuk aplikasi ini di pengaturan baterai Huawei,
   kalau gak, sistem bisa mematikan aplikasi pas layar mati atau browser lagi dibuka lama.
