# Avalon — Forge 1.20.1

Port dari plugin Paper **Avalon** (`MINECRAFT/avalon-plugin`) ke mod Minecraft Forge 1.20.1
(Forge 47.x, Java 17, official mappings, Mixin).

**Wajib dipasang di server dan di semua client.** Client butuh mod ini untuk render Mannequin
("Bot" player offline), kunci kamera/gerakan, dan skala player 1.5×.

## Build

```
./gradlew build
```

Hasil: `build/libs/avalon-1.1.0.jar` → taruh di folder `mods/` server dan client.

## Command

Sama persis dengan plugin. Semua butuh OP (permission level 2), kecuali `/avalon roleinfo`.

| Command | Fungsi |
| --- | --- |
| `/avalon regis <player>` | daftarkan player (harus online, maks 10); boleh selector (`@a`, ...). Tiap player terdaftar dapat satu kursi crimson slab di dimensi Avalon |
| `/avalon unregis <player>` | hapus dari daftar; boleh selector, `@a` = semua yang terdaftar termasuk yang offline |
| `/avalon listplayer` | daftar player terdaftar |
| `/avalon customrole` | GUI atur komposisi role (5-10 player terdaftar) |
| `/avalon cutscene <on\|off>` | cutscene pembuka: `on` = player tersedot portal lalu masuk dimensi Avalon, `off` = langsung dipindahkan |
| `/avalon cutscene portal play [animasi]` | tes cutscene portal: portal terbuka di arah pandangmu, semua player dalam 32 blok tersedot (tanpa pindah dimensi). `animasi` = satu gaya untuk semua player: `baling`, `keseret`, `salto`, `superman`, `ngelawan`, `spiral`, `ragdoll`, `kejerat`; tanpa argumen gayanya acak |
| `/avalon cutscene portal stop` | batalkan cutscene portal |
| `/avalon cutscene ending <menang\|kalah\|merlin>` | tes cutscene akhir game tanpa bermain (harus di dimensi Avalon, di luar game). Semua player di dimensi itu ikut tampil; kubunya dibagi bergantian, pengirim = kubu baik / Merlin |
| `/avalon cutscene ending kalah <0-3>` | sama, tapi nyalakan dulu sejumlah pilar itu: di ending kalah hanya bola pilar yang menyala yang meledak (0 = langsung ke sorotan kubu jahat) |
| `/avalon cutscene ending stop` | batalkan cutscene akhir |
| `/avalon roleinfo [role]` | info role sendiri / role tertentu |
| `/avalon gotoavalon [player]` | pindah ke titik datang dimensi Avalon (`-422 191 -496`, di depan portal); `player` boleh nama atau selector (`@a`, `@s`, ...), kosong = diri sendiri |
| `/avalon gotoworld [player]` | pindah ke overworld (X/Z dipertahankan); argumen sama seperti di atas |
| `/avalon startgame` | mulai game (5-10 player online): semua player dibawa ke kursinya di dimensi Avalon (`-422 192 -510`, menghadap tengah), animasi buka mata, lalu game berjalan |
| `/avalon stopgame` | hentikan game dan pulangkan semua player ke tempat asalnya; player tetap terdaftar. Boleh dari console |
| `/avalon debugroles` | lihat role semua player |
| `/avalon setrole <player> <role\|acak>` | pastikan player terdaftar mendapat role itu di game berikutnya. Hanya role yang aktif untuk jumlah player terdaftar (default / hasil `customrole`), dan tidak bisa dobel kecuali role yang memang ada beberapa (Loyal Servant, Minion). Tanpa argumen: daftar yang sudah diatur |
| `/avalon debug alwaysking <player\|off>` | tes: player itu selalu jadi raja (raja tidak bergilir) |
| `/avalon settimer <reveal\|voting\|discuss\|evildiscuss> <detik>` | ubah timer |

## Padanan fitur Paper → Forge

| Plugin (Paper 1.21) | Mod (Forge 1.20.1) |
| --- | --- |
| `org.bukkit.entity.Mannequin` | entity `avalon:mannequin` (model + skin player) |
| `Attribute.SCALE` | skala sendiri: hitbox & tinggi mata lewat `EntityEvent.Size`, render di client |
| `Attribute.STEP_HEIGHT` (green wool) | `ForgeMod.STEP_HEIGHT_ADDITION` |
| `Player#setRotation` / lock kamera / cancel `PlayerMoveEvent` | paket jaringan ke client + fallback teleport di server |
| `InventoryClickEvent` / `InventoryDragEvent` | Mixin `AbstractContainerMenu#clicked` |
| `PersistentDataContainer` | NBT tag `Avalon` di item |
| Chest GUI (`Bukkit.createInventory`) | `ChestMenu` 9×6 yang semua kliknya dibatalkan |
| `BukkitRunnable` | `id.avalon.core.Scheduler` / `Task` (per tick server) |
| `World#setPVP` | `MinecraftServer#setPvpAllowed` |

Perbaikan kecil dibanding plugin (perilaku game tetap sama):

- Player yang dibuang dari kursi saat game **sudah selesai** boleh turun (di plugin bisa nyangkut).
- Notifikasi "X sedang offline" di fase perkenalan benar-benar muncul (di plugin kodenya tidak pernah jalan).
- Pengumuman role di akhir game juga menyebut player yang sedang offline.
- Cek "semua anggota tim misi offline" dijalankan setelah player benar-benar keluar, dan juga saat misi dimulai.
- Ukuran tim mengikuti nomor ronde (1-5), bukan jumlah misi yang sudah sukses.
- Player yang sedang offline tetap tampil di GUI pemilihan tim (bertanda OFFLINE), tapi hanya bisa dipilih
  sebanyak kekurangannya kalau player yang online tidak cukup untuk mengisi tim.
- Selama tidak ada satu pun pemain game yang online, semua timer game dibekukan dan lanjut saat ada yang masuk lagi.
- Panah assassin yang tidak pernah mendarat (jatuh ke void) dihitung meleset.
- Blokir PvP, fall damage, `/msg`, dan step height green wool hanya berlaku untuk pemain game selama game
  berjalan; di luar itu server tidak disentuh. Setelan PvP server dikembalikan seperti sebelum game.

## Dimensi Avalon (`avalon:avalon`)

Dimensi tambahan berlangit ungu: pulau melayang, pohon mati, kabut tebal, waktu terkunci di senja.
Semuanya diatur lewat JSON di `src/main/resources/data/avalon/`:

| File | Isi |
| --- | --- |
| `dimension_type/avalon.json` | aturan dunia; `fixed_time` = jam yang dikunci (makin malam, langit makin gelap) |
| `dimension/avalon.json` | generator terrain (`settings`: `minecraft:floating_islands`) |
| `worldgen/biome/avalon.json` | warna langit, kabut, air, rumput, partikel abu, suara latar |
| `worldgen/*_feature/dead_tree.json` | pohon mati (batang dark oak tanpa daun) |

Kabut & langit di sisi client ada di `id.avalon.client.AvalonSkyEffects`.

Seed dimensi ini tetap (`AvalonDimensions.AVALON_SEED`, dipasang lewat `ServerLevelMixin`), jadi
terrain-nya sama persis di semua world, apa pun seed world-nya. Mengganti angka itu hanya berpengaruh
ke chunk yang belum pernah di-generate.

Portal (`data/avalon/schematics/portal.schem`) ditempel otomatis sekali per world saat server start,
seperti `//paste` WorldEdit dari posisi `-423 189 -539` (`id.avalon.world.AvalonPortal`). Blok 1.21 yang
tidak ada di 1.20.1 diganti padanan terdekat (lihat `SpongeSchematic.REPLACEMENTS`).

Saat client masuk ke dimensi ini, layar "Loading terrain" diganti `id.avalon.client.AvalonLoadingScreen`.
Sumber & lisensi asetnya ada di `assets/avalon/CREDITS.txt`.

## Fake player (dev)

Kalau `libs/advfakeplayer-1.0.0.jar` ada, mod itu otomatis ikut dimuat di `runClient` / `runServer`
(`/fakeplayer spawn 5`, dst). Hanya `runtimeOnly`: `./gradlew build` tidak membutuhkannya dan jar
Avalon tidak bergantung padanya.

## Uji end-to-end (dev)

```
./gradlew runServer -Pselftest=true
```

Memainkan satu game penuh dengan 5 fake player (butuh `libs/advfakeplayer-1.0.0.jar`), lalu
mematikan server dan mencetak `[AvalonTest] HASIL: ...` di log. Kode uji ada di `src/selftest`
dan tidak ikut ke jar mod.
