# Recon Map — Olauncher (kompetitor)

Target: **Olauncher — Minimal AF Launcher** (`app.olauncher`) oleh tanujnotes
Dibuat: clean-room dari sumber publik (README, FAQ Substack, Play Store listing + review asli, HN/Reddit). Tidak baca source, tidak scrape, tidak decompile.

Fakta repo: github.com/tanujnotes/Olauncher · GPLv3 · 3,843 stars · 513 forks · 1,017 commit · sejak 2020-07-10

---

## Inti produk (core loop)

Home screen **text-only, tanpa icon**. User pin 1–8 app favorit (teks doang), sisanya di app drawer search teks. Auto-launch: saat query search hanya cocok 1 app, langsung dibuka (muscle memory). Wallpaper baru tiap hari. Tujuan eksplisitnya: **kurangi screen time**.

> "Minimalist homescreen: no icons, ads or any distraction."
> "By default, a few niche features are available but hidden." (README)

---

## Screen inventory

| ID | Screen | Cara akses | Komponen utama |
|----|--------|-----------|----------------|
| S01 | Home | default | jam/tanggal, 1–8 app teks, alignment L/C/R, wallpaper |
| S02 | App Drawer | swipe (atau tap) | list teks + search bar, auto-launch, !bang DDG |
| S03 | Settings | long-press home | list toggle; beberapa item punya "long-press" tersembunyi |
| S04 | Hidden apps | tap teks "Olauncher" di settings | list app tersembunyi |
| S05 | About / FAQ | (i) icon di settings | daftar fitur + FAQ |
| S06 | App rename sheet | long-press app (drawer/home) | input nama baru |
| S07 | Set default launcher | settings | chooser sistem |
| S08 | Daily wallpaper preview | settings | toggle + galeri |

**Catatan:** Olauncher sengaja datang dengan layar minim — "a few niche features are available but hidden" adalah filosofi desainnya.

---

## User flows

```
F01 Buka app favorit
    S01 home -> tap nama app -> launch
    happy path: 1 klik. Ini angka yang dikalahkan.

F02 Buka app non-favorit
    S01 home -> swipe buka drawer -> ketik query -> auto-launch saat unik
    happy path: ~3 aksi (swipe, ketik 2-3 huruf, otomatis terbuka)
    edge: query ambigu (beberapa app cocok) -> pilih manual; nama dobel (duplikat) -> ambigu

F03 Kunci layar
    S01 home -> double-tap -> device admin lock
    edge: permission admin belum diberikan; diblokir optimisasi baterai

F04 Ganti wallpaper harian
    S03 settings -> toggle Daily wallpaper -> S08
    edge: offline -> wallpaper lama dipertahankan; long-press toggle -> wallpaper hitam

F05 Cari app yang tidak terlihat
    S01 -> long-press -> S03 settings -> tap "Olauncher" -> S04
```

---

## Komponen (berulang)

- **TextAppRow** — baris teks tunggal; state: normal, hidden, renamed, duplicate-name
- **SearchInput** — filter drawer; state: empty, typing, unique-match (auto-launch), no-result (tawarkan web search)
- **ToggleRow** — settings; beberapa punya long-press untuk aksi tersembunyi (align all apps, disable swipe, wallpaper hitam)
- **WallpaperLayer** — compositing sistem saat default launcher
- **ClockWidget** — bawaan; tap buka app pilihan (long-press set app)

---

## Data model (inferensi)

```
AppModel      label, packageName, activityClassName, userHandle
              evidence: Rename + duplicate-name complaint   confidence: high
Pref slot     1..8: appName/appPackage/appActivity/appUser (+ alignment)
              evidence: "up to eight apps"                   confidence: high
HiddenApps    string-set package names
              evidence: FAQ "tap 'Olauncher' to see hidden"  confidence: high
Theme         light|dark, text color, font size, bold toggle
DailyWallpaper enabled, last fetch date (needs internet)
DeviceAdmin   enabled (double-tap lock)
```

---

## Apa yang TIDAK bisa/mau diklon (skip + alasan)

| Skip | Alasan |
|------|--------|
| Katalog wallpaper harian | Olauncher punya feed infra sendiri; Sakinah punya mekanismenya sendiri |
| Base pengguna & rating 71.8K review | network effect, bukan fitur |
| Pro Launcher (weather/widget/folder) | produk berbayar terpisah — lihat sebagai daftar gap, bukan disalin mentah |

---

## Keluhan user Olauncher (HANYA quote asli + link)

Inilah celah diferensiasi. Semua verbatim dari review publik.

### 1. Tidak ada widget — keluhan #1 paling sering
- "On the downside: I miss having some widgets!" — Seb Guadarrama, Play Store, 7 Jan 2025 (108 orang terbantu)
- "It's great, definitely minimal. Not being able to use widgets kinda blows." — AcriminousJ, Play Store, 19 Apr 2026
- "I do miss Google's weather and calendar widget." — Matthew Eskuchen, Play Store, 27 Apr 2025
- Artikel android.sc: "Both [Olauncher & Before Launcher] are completely useless the moment you want to see Tuesday's 9am meeting... a home screen that hides your schedule isn't minimalist. It's just broken." Play listing Olauncher secara eksplisit "no widgets by design."

### 2. Nama app dobel di drawer tanpa pembeda
- "I don't like when apps have duplicate names and I can't tell what they are without icons or publisher info. That part of the app drawer needs work." — Matthew Eskuchen, Play Store, 27 Apr 2025 (96 terbantu)

### 3. Pesan developer yang tidak bisa dimatikan + butuh internet
- "My only complaint... the developer sometimes sends messages (e.g., happy new year) and there doesn't seem to be any way to disable it. I don't see why a minimal launcher should have internet access at all." — HN thread uLauncher (membahas basis serupa)

### 4. Pro versi dianggap mahal untuk fitur dasar
- "I see there's a shockingly expensive pro version, but it's obscene to have to pay that much to have my data collected and sold to third-parties while the free version doesn't." — AcriminousJ, Play Store, 19 Apr 2026

### 5. Glitch/lag di OEM tertentu + pain baterai
- "I am experiencing glitches/lags sometimes on my s24, but a quick restart solve the problem." — Reddit r/digitalminimalism
- FAQ resmi harus mengajarkan user set "Allow background usage = Unrestricted" — tanda pain point berulang.

---

## Changelog Olauncher (apa yang SUDAH mereka perbaiki — jangan di-"fix" lagi)

- Private space support
- Press Home gesture buat recent apps
- Bold font option; e-ink: disable animations
- Improved PWA shortcut handling
- Fix theme change on adaptive refresh rate
- Fix apps not opening setelah ganti icon (Duolingo case)
- Fix wrong screen time; app drawer top margin small screens

---

## Feature matrix — vs Sakinah (lihat juga features.csv)

| Fitur | Olauncher | Sakinah (as-is) | Celah |
|-------|-----------|-----------------|-------|
| Home text-only, 1–8 favorit | ya | ya | — (paritas) |
| App drawer search + auto-launch | ya | ya | — (paritas) |
| Widget di home | **tidak (by design)** | **ada (AppWidget host tab di Productive)** | ✅ **Sakinah unggul** |
| Notes/Todo built-in | tidak (app lain) | ya (Productive) | ✅ unggul |
| Pomodoro/focus timer | tidak | ya | ✅ unggul |
| Waktu sholat (offline 1 thn) | tidak | ya | ✅ unggul |
| Dzikir + counter | tidak | ya | ✅ unggul |
| Folders | hanya Pro | tidak | ⚠️ gap |
| Weather widget | hanya Pro | tidak | ⚠️ gap |
| Daily wallpaper | ya | ya | paritas |
| Double-tap lock | ya | ya | paritas |
| Screen time | ya | ya | paritas |
| Private space (API 35+) | ya | ya | paritas |
| Rename app | ya | ? | cek |
| Landscape di phone | tidak (tablet only) | ? | ⚠️ cek |
| App blocker / mindful launch | tidak (hanya di "minimalist phone" $29/yr) | tidak | ⚠️ gap besar di pasar |

---

## Ukuran & kesimpulan

- **Paritas inti launcher: sudah tercapai.** Sakinah punya semua mekanik inti Olauncher.
- **Diferensiasi nyata: fitur Muslim + Productive (widget/notes/timer).** Olauncher "no widgets by design" — ini keunggulan paling defensible Sakinah dan langsung menjawab keluhan #1.
- **Gap Olauncher yang belum Sakinah tutup**: folders, weather widget, penanganan nama dobel, landscape phone.
- **Gap besar di pasar**: app blocker / mindful launch (saat ini hanya di "minimalist phone" berbayar $29/yr — user komplain mahal).

**Rekomendasi prioritas (evidence-based):**
1. **Pastikan widget host benar-benar terlihat/jangkau** — ini senjata utama, langsung jawab keluhan #1 Olauncher (3 review, 200+ terbantu).
2. **Selesaikan nama dobel di drawer** (tampilkan package/publisher) — 1 review 96 terbantu, murah.
3. **Pertimbangkan folders** (fitur yang user Pro bayar mahal) + **app blocker ringan** (gap pasar).
