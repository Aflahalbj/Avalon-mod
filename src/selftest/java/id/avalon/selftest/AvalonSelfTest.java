package id.avalon.selftest;

import id.avalon.AvalonMod;
import id.avalon.core.PlayerScale;
import id.avalon.gui.AvalonMenu;
import id.avalon.gui.TeamSelectionGUI;
import id.avalon.managers.GameManager;
import id.avalon.managers.VotingManager;
import id.avalon.models.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Uji end-to-end dev-only: memainkan satu game Avalon penuh dengan 5 fake player
 * (mod advfakeplayer), lalu mematikan server. Jalankan: ./gradlew runServer -Pselftest=true
 */
@Mod.EventBusSubscriber(modid = AvalonMod.MOD_ID)
public final class AvalonSelfTest {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String[] NAMES = {"a", "b", "c", "d", "e"};

    private static MinecraftServer server;
    private static final List<Step> steps = new ArrayList<>();
    private static int stepIndex = 0;
    private static int stepTicks = 0;
    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static boolean running = false;

    // State yang dibagi antar step
    private static String lastKing;
    private static int roundGoodOnly; // dipakai untuk memilih tim

    private record Step(String name, BooleanSupplier body, int timeout) {}

    private AvalonSelfTest() {}

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!"true".equals(System.getProperty("avalon.selftest"))) return;
        server = event.getServer();
        buildScript();
        running = true;
        LOG.info("[AvalonTest] Mulai self test, {} langkah", steps.size());
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (!running || event.phase != TickEvent.Phase.END) return;
        if (stepIndex >= steps.size()) {
            finish();
            return;
        }
        Step step = steps.get(stepIndex);
        boolean done;
        try {
            done = step.body.getAsBoolean();
        } catch (Throwable t) {
            LOG.error("[AvalonTest] EXCEPTION di langkah '" + step.name + "'", t);
            failures.add(step.name + ": exception " + t);
            done = true;
        }
        stepTicks++;
        if (done) {
            LOG.info("[AvalonTest] ✔ langkah selesai: {} ({} tick)", step.name, stepTicks);
            stepIndex++;
            stepTicks = 0;
        } else if (stepTicks > step.timeout) {
            failures.add("TIMEOUT: " + step.name);
            LOG.error("[AvalonTest] ✘ TIMEOUT: {}", step.name);
            dumpState();
            finish();
        }
    }

    private static void finish() {
        running = false;
        LOG.info("[AvalonTest] ==============================================");
        LOG.info("[AvalonTest] HASIL: {} assert lulus, {} gagal", passed, failures.size());
        for (String f : failures) LOG.info("[AvalonTest]   GAGAL: {}", f);
        LOG.info("[AvalonTest] {}", failures.isEmpty() ? "SEMUA LULUS" : "ADA YANG GAGAL");
        LOG.info("[AvalonTest] ==============================================");
        server.halt(false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static GameManager gm() {
        return AvalonMod.getInstance().getGameManager();
    }

    private static VotingManager vm() {
        return AvalonMod.getInstance().getVotingManager();
    }

    private static ServerPlayer p(String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    private static void cmd(String command) {
        LOG.info("[AvalonTest] /{}", command);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private static void check(boolean cond, String msg) {
        if (cond) {
            passed++;
            LOG.info("[AvalonTest]   ok: {}", msg);
        } else {
            failures.add(msg);
            LOG.error("[AvalonTest]   GAGAL: {}", msg);
        }
    }

    private static void run(String name, Runnable r) {
        steps.add(new Step(name, () -> { r.run(); return true; }, 1));
    }

    private static void waitFor(String name, BooleanSupplier cond, int timeout) {
        steps.add(new Step(name, cond, timeout));
    }

    private static void sleep(int ticks) {
        int[] counter = {0};
        steps.add(new Step("sleep " + ticks, () -> ++counter[0] >= ticks, ticks + 5));
    }

    /** Klik kanan item di slot hotbar tertentu (setara klik kanan di udara). */
    private static void useSlot(ServerPlayer player, int slot) {
        player.getInventory().selected = slot;
        ItemStack stack = player.getMainHandItem();
        player.gameMode.useItem(player, player.serverLevel(), stack, InteractionHand.MAIN_HAND);
    }

    /** Klik kiri slot di menu yang sedang terbuka. */
    private static void click(ServerPlayer player, int slot) {
        player.containerMenu.clicked(slot, 0, ClickType.PICKUP, player);
    }

    private static int findHotbarSlot(ServerPlayer player, java.util.function.Predicate<ItemStack> pred) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (pred.test(player.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private static boolean onSeat(ServerPlayer player) {
        Entity v = player.getVehicle();
        return v instanceof ArmorStand && v.getTags().contains("avalon_seat");
    }

    private static List<String> namesWith(java.util.function.Predicate<Role> pred) {
        List<String> list = new ArrayList<>();
        for (String n : NAMES) {
            ServerPlayer pl = p(n);
            if (pl != null && gm().getRole(pl) != null && pred.test(gm().getRole(pl))) list.add(n);
        }
        return list;
    }

    private static String nameWithRole(Role role) {
        List<String> l = namesWith(r -> r == role);
        return l.isEmpty() ? null : l.get(0);
    }

    private static void dumpState() {
        LOG.info("[AvalonTest] state: running={} king={} mission={} round={} voting={} missionActive={} discussion={} assassination={} bow={} revealPhase={}",
                gm().isGameRunning(), gm().getCurrentKingName(), gm().getCurrentMission(), gm().getCurrentRound(),
                vm().isVotingActive(), gm().isMissionActive(), gm().isDiscussionActive(),
                gm().isAssassinationActive(), gm().isAssassinBowActive(), gm().getCurrentRevealPhase());
        for (String n : NAMES) {
            ServerPlayer pl = p(n);
            if (pl == null) { LOG.info("[AvalonTest]   {} offline", n); continue; }
            LOG.info("[AvalonTest]   {} role={} mode={} seat={} pos={} slot0={}", n, gm().getRole(pl),
                    pl.gameMode.getGameModeForPlayer(), onSeat(pl), pl.position(), pl.getInventory().getItem(0));
        }
    }

    /** Raja membuka buku, memilih tim, lalu konfirmasi lewat GUI. */
    private static void kingPicksTeam(List<String> team) {
        ServerPlayer king = p(gm().getCurrentKingName());
        check(king != null, "raja online");
        int book = findHotbarSlot(king, GameManager::isTeamBook);
        check(book >= 0 && book < 9, "raja punya Buku Pemilihan Tim di hotbar (slot " + book + ")");
        useSlot(king, book);
        check(king.containerMenu instanceof AvalonMenu, "GUI pemilihan tim terbuka");
        AvalonMenu menu = (AvalonMenu) king.containerMenu;
        check(TeamSelectionGUI.isQuestionMark(menu.inv().getItem(0)), "slot target berisi question mark");

        for (String name : team) {
            int slot = -1;
            for (int s : TeamSelectionGUI.POOL_SLOTS) {
                if (name.equals(TeamSelectionGUI.getPlayerNameFromItem(menu.inv().getItem(s)))) slot = s;
            }
            check(slot >= 0, "kepala " + name + " ada di pool");
            click(king, slot);
        }

        // Uji kembalikan dari target ke pool lalu pilih lagi
        String first = team.get(0);
        click(king, TeamSelectionGUI.TARGET_SLOTS[0]);
        check(TeamSelectionGUI.isQuestionMark(menu.inv().getItem(0)), "klik target mengembalikan ke pool");
        int again = -1;
        for (int s : TeamSelectionGUI.POOL_SLOTS) {
            if (first.equals(TeamSelectionGUI.getPlayerNameFromItem(menu.inv().getItem(s)))) again = s;
        }
        click(king, again);

        check(TeamSelectionGUI.isConfirmButton(menu.inv().getItem(TeamSelectionGUI.CONFIRM_SLOT)), "tombol konfirmasi ada");
        click(king, TeamSelectionGUI.CONFIRM_SLOT);
        check(!(king.containerMenu instanceof AvalonMenu), "GUI tertutup setelah konfirmasi");
    }

    private static void everyoneVotes(String vote) {
        for (String n : NAMES) {
            ServerPlayer pl = p(n);
            if (pl == null) continue;
            int slot = VotingManager.VOTE_SETUJU.equals(vote) ? 1 : 0;
            if (!vm().isVotingActive()) return;
            useSlot(pl, slot);
        }
    }

    private static void everyoneSkips() {
        for (String n : NAMES) {
            ServerPlayer pl = p(n);
            if (pl == null) continue;
            if (gm().isDiscussionSkipItem(pl.getInventory().getItem(0))
                    || gm().isAssassinationSkipItem(pl.getInventory().getItem(0))) {
                useSlot(pl, 0);
            }
        }
    }

    private static int teamSize() {
        return TeamSelectionGUI.getTeamSize(5, gm().getCurrentMission());
    }

    private static List<String> goodTeam() {
        List<String> good = namesWith(Role::isGood);
        return new ArrayList<>(good.subList(0, Math.min(teamSize(), good.size())));
    }

    // ── Skrip uji ─────────────────────────────────────────────────────────────

    private static void buildScript() {
        sleep(40);
        run("spawn 5 fake player", () -> cmd("fakeplayer spawn 5"));
        waitFor("fake player online", () -> {
            for (String n : NAMES) if (p(n) == null) return false;
            return true;
        }, 400);

        // ── Command dasar ─────────────────────────────────────────────────────
        run("regis / unregis / listplayer / settimer / cutscene", () -> {
            for (String n : NAMES) cmd("avalon regis " + n);
            cmd("avalon regis tidakada");
            cmd("avalon regis a");
            check(gm().getRegisteredPlayers().size() == 5, "5 player terdaftar");
            cmd("avalon unregis e");
            check(gm().getRegisteredPlayers().size() == 4, "unregis mengurangi jadi 4");
            cmd("avalon regis e");
            cmd("avalon listplayer");
            cmd("avalon settimer reveal 1");
            cmd("avalon settimer voting 30");
            cmd("avalon settimer discuss 2");
            cmd("avalon settimer evildiscuss 3");
            cmd("avalon settimer foo 5");
            cmd("avalon settimer reveal abc");
            check(gm().getRevealSeconds() == 1 && gm().getVotingSeconds() == 30
                    && gm().getDiscussionSeconds() == 2 && gm().getEvilDiscussionSeconds() == 3, "settimer mengubah semua timer");
            cmd("avalon cutscene off");
            check(!gm().isCutsceneEnabled(), "cutscene off");
            cmd("avalon cutscene on");
            check(gm().isCutsceneEnabled(), "cutscene on");
            cmd("avalon roleinfo merlin");

            // Siapkan dunia uji seperti map asli: tanah di bawah tanaman, atap di atas spore blossom
            ServerLevel lvl = p("a").serverLevel();
            for (int i = 0; i < GameManager.PLANT_LOCATIONS.length; i++) {
                int[] l = GameManager.PLANT_LOCATIONS[i];
                BlockPos pos = new BlockPos(l[0], l[1], l[2]);
                if (GameManager.PLANT_MATERIALS[i] == Blocks.SPORE_BLOSSOM) {
                    lvl.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), 3);
                } else {
                    lvl.setBlock(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                }
            }
            // /queen dipakai admin di dekat arena (chunk queen harus ter-load)
            p("a").teleportTo(lvl, -19.5, 81, -420, 0, 0);
        });
        sleep(60);
        run("queen spawn / delete", () -> {
            cmd("avalon queen spawn");
            cmd("execute as a run avalon queen spawn");
            check(gm().hasQueen(p("a").serverLevel()), "queen ter-spawn");
            cmd("execute as a run avalon queen spawn");
            int queens = 0;
            for (Entity e : p("a").serverLevel().getAllEntities()) if (e.getTags().contains("avalon_queen")) queens++;
            check(queens == 1, "queen tidak dobel");
            cmd("execute as a run avalon queen delete");
            check(!gm().hasQueen(p("a").serverLevel()), "queen terhapus");
        });

        // ── Custom role GUI ───────────────────────────────────────────────────
        run("GUI custom role", () -> {
            cmd("execute as a run avalon customrole");
            ServerPlayer a = p("a");
            check(a.containerMenu instanceof AvalonMenu, "GUI custom role terbuka");
            AvalonMenu menu = (AvalonMenu) a.containerMenu;
            // Merlin tidak bisa dihapus
            click(a, 0);
            check(!menu.inv().getItem(0).getHoverName().getString().equals("Kosong"), "Merlin tidak bisa dihapus");
            // Hapus Percival (slot 1) lalu isi lagi dari pool
            click(a, 1);
            check(menu.inv().getItem(1).getHoverName().getString().equals("Kosong"), "Percival dihapus → Kosong");
            click(a, 53);
            check(a.containerMenu instanceof AvalonMenu, "save ditolak kalau masih kosong");
            click(a, 1);
            click(a, 27);
            check(menu.inv().getItem(1).getHoverName().getString().equals("Percival"), "Percival diisi dari pool");
            // Evil: hapus Assassin (slot 8) lalu isi lagi (pool rata kanan → slot 32)
            click(a, 8);
            click(a, 8);
            click(a, 32);
            check(menu.inv().getItem(8).getHoverName().getString().equals("Assassin"), "Assassin diisi dari evil pool");
            // Klik inventory player saat GUI terbuka tidak memindahkan apa pun
            click(a, 60);
            check(a.containerMenu.getCarried().isEmpty(), "klik di GUI tidak mengambil item");
            click(a, 53);
            check(!(a.containerMenu instanceof AvalonMenu), "GUI tertutup setelah simpan");
            check(gm().getCustomRoles(5).equals(List.of(Role.MERLIN, Role.PERCIVAL, Role.LOYAL_SERVANT, Role.ASSASSIN, Role.MORGANA)),
                    "custom role tersimpan");
        });

        // ── PvP & step height ─────────────────────────────────────────────────
        sleep(80); // tunggu spawn invulnerability (60 tick) habis
        run("PvP diblok & step height green wool", () -> {
            ServerPlayer a = p("a"), b = p("b");
            b.setGameMode(GameType.SURVIVAL);
            float before = b.getHealth();
            a.attack(b);
            check(b.getHealth() == before, "pukulan player ke player diblok");
            BlockPos under = b.blockPosition().above(2);
            b.serverLevel().setBlock(under, Blocks.GREEN_WOOL.defaultBlockState(), 3);
        });
        sleep(2);
        run("cek step height", () -> {
            ServerPlayer b = p("b");
            check(b.getAttribute(ForgeMod.STEP_HEIGHT_ADDITION.get()).getBaseValue() > 9.0, "step height naik dekat green wool");
            b.serverLevel().setBlock(b.blockPosition().above(2), Blocks.AIR.defaultBlockState(), 3);
        });

        // ── Start game ────────────────────────────────────────────────────────
        run("startgame", () -> {
            cmd("execute as a run avalon startgame");
            check(gm().isGameRunning(), "game berjalan");
            check(!server.isPvpAllowed(), "PvP dimatikan");
        });
        waitFor("cutscene berjalan", () -> gm().isCutsceneRunning(), 200);
        run("cek cutscene", () -> {
            ServerPlayer a = p("a");
            check(gm().isMovementLocked(a), "gerakan terkunci saat cutscene portal");
            check(gm().hasQueen(a.serverLevel()), "queen di-spawn saat startgame");
        });
        waitFor("fase perkenalan", () -> gm().getCurrentRevealPhase() == 1, 2400);
        sleep(3);
        run("cek fase perkenalan", () -> {
            check(nameWithRole(Role.MERLIN) != null, "ada Merlin");
            // Perkenalan sekali jalan: semua tetap duduk, kamera bebas
            for (String n : NAMES) {
                check(onSeat(p(n)), n + " tetap duduk saat perkenalan");
                check(!gm().isCameraLocked(p(n)), n + " kamera bebas saat perkenalan");
            }
        });

        waitFor("raja dipilih", () -> {
            String king = gm().getCurrentKingName();
            return king != null && p(king) != null && findHotbarSlot(p(king), GameManager::isTeamBook) >= 0;
        }, 1200);

        run("cek setelah perkenalan", () -> {
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                check(onSeat(pl), n + " duduk di kursi");
                check(!gm().isCameraLocked(pl) && PlayerScale.get(pl) == 1.0f, n + " kamera bebas & scale normal");
            }
            ServerPlayer king = p(gm().getCurrentKingName());
            king.stopRiding();
            check(onSeat(king), "player tidak bisa turun dari kursi");

            // Buku tidak bisa dipindah / dibuang
            int slot = findHotbarSlot(king, GameManager::isTeamBook);
            king.inventoryMenu.clicked(36 + slot, 0, ClickType.PICKUP, king);
            check(king.inventoryMenu.getCarried().isEmpty() && GameManager.isTeamBook(king.getInventory().getItem(slot)),
                    "buku tidak bisa diambil di inventory");
            king.getInventory().selected = slot;
            king.drop(false);
            check(GameManager.isTeamBook(king.getInventory().getItem(slot)), "buku tidak bisa di-drop");

            // Player lain tidak bisa pakai buku
            ServerPlayer other = null;
            for (String n : NAMES) if (!n.equals(gm().getCurrentKingName())) { other = p(n); break; }
            gm().giveTeamBook(other);
            int os = findHotbarSlot(other, GameManager::isTeamBook);
            useSlot(other, os);
            check(!(other.containerMenu instanceof AvalonMenu), "bukan raja tidak bisa buka GUI");
            gm().removeTeamBook(other);

            // Fall damage dibatalkan selama game
            ServerPlayer c = p("c");
            float before = c.getHealth();
            c.hurt(c.damageSources().fall(), 5f);
            check(c.getHealth() == before, "fall damage dibatalkan selama game");

            // /msg diblok
            cmd("execute as b run msg c halo");
            lastKing = gm().getCurrentKingName();
        });

        // ── Ronde: tim ditolak ────────────────────────────────────────────────
        run("raja memilih tim (akan ditolak)", () -> kingPicksTeam(List.of(NAMES[0], NAMES[1]).subList(0, teamSize())));
        waitFor("voting dimulai", () -> vm().isVotingActive(), 200);
        run("semua menolak", () -> {
            ServerPlayer a = p("a");
            check(vm().isVoteItem(a.getInventory().getItem(0)) && vm().isVoteItem(a.getInventory().getItem(1)), "item vote dibagikan");
            a.inventoryMenu.clicked(36, 0, ClickType.PICKUP, a);
            check(a.inventoryMenu.getCarried().isEmpty(), "item vote tidak bisa dipindah");
            a.inventoryMenu.clicked(36, 1, ClickType.SWAP, a);
            check(vm().isVoteItem(a.getInventory().getItem(0)), "number key swap diblok");
            useSlot(a, 1); // setuju dulu
            useSlot(a, 0); // lalu ganti ke tolak
            everyoneVotes(VotingManager.VOTE_TOLAK);
            check(!vm().isVotingActive(), "voting selesai saat semua sudah vote");
        });
        waitFor("raja berganti setelah ditolak", () -> gm().getCurrentKingName() != null
                && !gm().getCurrentKingName().equals(lastKing), 200);

        // ── Misi 1: sukses ────────────────────────────────────────────────────
        run("misi 1: raja memilih tim baik", () -> kingPicksTeam(goodTeam()));
        waitFor("voting misi 1", () -> vm().isVotingActive(), 200);
        run("semua setuju", () -> everyoneVotes(VotingManager.VOTE_SETUJU));
        waitFor("misi 1 aktif", () -> gm().isMissionActive(), 200);
        run("cek misi 1 & panen tanaman", () -> {
            List<String> team = gm().getCurrentMissionTeam();
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (team.contains(n)) {
                    check(pl.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, n + " (tim) survival");
                    check(gm().isMissionShears(pl.getInventory().getItem(0)), n + " pegang shears misi");
                    check(!onSeat(pl), n + " turun dari kursi");
                } else {
                    check(pl.gameMode.getGameModeForPlayer() == GameType.SPECTATOR, n + " (bukan tim) spectator");
                }
            }
            ServerPlayer member = p(team.get(0));
            // Shears tidak bisa di-drop
            member.getInventory().selected = 0;
            member.drop(false);
            check(gm().isMissionShears(member.getInventory().getItem(0)), "shears misi tidak bisa di-drop");
            // Blok lain tidak bisa dihancurkan
            BlockPos other = member.blockPosition().below();
            member.serverLevel().setBlock(other, Blocks.STONE.defaultBlockState(), 3);
            member.gameMode.destroyBlock(other);
            check(member.serverLevel().getBlockState(other).is(Blocks.STONE), "blok non-misi tidak bisa dihancurkan");
            int[] loc = GameManager.PLANT_LOCATIONS[0];
            BlockPos plant = new BlockPos(loc[0], loc[1], loc[2]);
            check(member.serverLevel().getBlockState(plant).is(Blocks.PITCHER_PLANT), "pitcher plant terpasang");
            member.gameMode.destroyBlock(plant);
            check(member.serverLevel().getBlockState(plant).isAir(), "pitcher plant dipanen");
            check(findHotbarSlot(member, s -> s.is(Items.PITCHER_PLANT)) >= 0, "item pitcher plant masuk inventory");
        });
        waitFor("tanaman 0 tercatat", () -> gm().getCompletedPlants().contains(0), 40);
        waitFor("diskusi setelah misi 1", () -> gm().isDiscussionActive(), 600);

        // ── Offline / online saat diskusi ─────────────────────────────────────
        run("player c disconnect", () -> {
            lastKing = gm().getCurrentKingName();
            cmd("fakeplayer remove c");
        });
        waitFor("mannequin c muncul", () -> gm().getOfflineMannequinCount() == 1, 100);
        run("player c reconnect", () -> cmd("fakeplayer spawn c"));
        waitFor("c kembali & mannequin hilang", () -> p("c") != null && gm().getOfflineMannequinCount() == 0, 200);
        run("cek restore c", () -> {
            ServerPlayer c = p("c");
            check(onSeat(c), "c didudukkan lagi");
            check(gm().isDiscussionSkipItem(c.getInventory().getItem(0)), "c dapat item skip lagi");
            everyoneSkips();
        });
        waitFor("raja berganti setelah misi 1", () -> !gm().isDiscussionActive() && gm().getCurrentKingName() != null
                && !gm().getCurrentKingName().equals(lastKing), 300);

        // ── Misi 2: disabotase ────────────────────────────────────────────────
        run("misi 2: tim berisi kubu jahat", () -> {
            lastKing = gm().getCurrentKingName();
            List<String> team = new ArrayList<>();
            team.add(namesWith(Role::isEvil).get(0));
            for (String g : namesWith(Role::isGood)) if (team.size() < teamSize()) team.add(g);
            kingPicksTeam(team);
        });
        waitFor("voting misi 2", () -> vm().isVotingActive(), 200);
        run("semua setuju (misi 2)", () -> everyoneVotes(VotingManager.VOTE_SETUJU));
        waitFor("misi 2 aktif", () -> gm().isMissionActive(), 200);
        run("sabotase", () -> {
            String evil = null;
            for (String n : gm().getCurrentMissionTeam()) if (gm().getRole(p(n)).isEvil()) evil = n;
            ServerPlayer saboteur = p(evil);
            check(gm().isSabotaseShears(saboteur.getInventory().getItem(0)), "kubu jahat pegang shears Sabotase");
            useSlot(saboteur, 0);
            int[] loc = GameManager.PLANT_LOCATIONS[1];
            BlockPos plant = new BlockPos(loc[0], loc[1], loc[2]);
            check(saboteur.serverLevel().getBlockState(plant).is(Blocks.DEAD_BUSH), "tanaman jadi dead bush");
            int[] loc2 = GameManager.PLANT_LOCATIONS[2];
            check(saboteur.serverLevel().getBlockState(new BlockPos(loc2[0], loc2[1], loc2[2])).is(Blocks.HANGING_ROOTS),
                    "spore blossom jadi hanging roots");
            useSlot(saboteur, 0);
            saboteur.teleportTo(saboteur.serverLevel(), loc[0] + 0.5, loc[1] + 1, loc[2] + 3.5, 0, 0);
        });
        waitFor("countdown sabotase", () -> !gm().isMissionActive(), 100);
        waitFor("diskusi setelah sabotase", () -> gm().isDiscussionActive(), 600);
        run("skip diskusi (gagal)", AvalonSelfTest::everyoneSkips);
        waitFor("raja berganti setelah misi 2", () -> !gm().isDiscussionActive() && gm().getEvilMissionFails() == 1
                && gm().getCurrentKingName() != null && !gm().getCurrentKingName().equals(lastKing), 300);

        // ── Misi 3 & 4: sukses ────────────────────────────────────────────────
        for (int m = 0; m < 2; m++) {
            final int plantIndex = m + 1;
            run("misi sukses (tanaman " + plantIndex + ")", () -> {
                lastKing = gm().getCurrentKingName();
                kingPicksTeam(goodTeam());
            });
            waitFor("voting (tanaman " + plantIndex + ")", () -> vm().isVotingActive(), 200);
            run("setuju (tanaman " + plantIndex + ")", () -> everyoneVotes(VotingManager.VOTE_SETUJU));
            waitFor("misi aktif (tanaman " + plantIndex + ")", () -> gm().isMissionActive(), 200);
            run("panen tanaman " + plantIndex, () -> {
                ServerPlayer member = p(gm().getCurrentMissionTeam().get(0));
                int[] loc = GameManager.PLANT_LOCATIONS[plantIndex];
                BlockPos plant = new BlockPos(loc[0], loc[1], loc[2]);
                check(member.serverLevel().getBlockState(plant).is(GameManager.PLANT_MATERIALS[plantIndex]),
                        "tanaman " + plantIndex + " dipasang ulang");
                member.gameMode.destroyBlock(plant);
            });
            waitFor("tanaman " + plantIndex + " tercatat", () -> gm().getCompletedPlants().contains(plantIndex), 40);
            if (m == 0) {
                waitFor("diskusi", () -> gm().isDiscussionActive(), 600);
                run("skip diskusi", AvalonSelfTest::everyoneSkips);
                waitFor("raja berganti", () -> !gm().isDiscussionActive() && gm().getCurrentKingName() != null
                        && !gm().getCurrentKingName().equals(lastKing), 300);
            }
        }

        // ── Assassination ─────────────────────────────────────────────────────
        waitFor("fase assassination", () -> gm().isAssassinationActive(), 600);
        run("cek fase assassination", () -> {
            check(server.isPvpAllowed(), "PvP dinyalakan");
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (gm().getRole(pl).isEvil()) {
                    check(!onSeat(pl) && !gm().isMovementLocked(pl), n + " (jahat) bebas berjalan");
                    check(gm().isAssassinationSkipItem(pl.getInventory().getItem(0)), n + " dapat item skip");
                } else {
                    check(onSeat(pl) && gm().isMovementLocked(pl), n + " (baik) duduk & terkunci");
                }
            }
            everyoneSkips();
        });
        waitFor("assassin dapat busur", () -> gm().isAssassinBowActive(), 100);
        run("assassin menembak Merlin", () -> {
            ServerPlayer assassin = p(nameWithRole(Role.ASSASSIN));
            ServerPlayer merlin = p(nameWithRole(Role.MERLIN));
            ItemStack bow = assassin.getInventory().getItem(0);
            check(gm().isAssassinBowItem(bow) && bow.getDamageValue() == 383, "busur assassin durability 1");
            check(assassin.getInventory().getItem(1).is(Items.ARROW), "assassin punya 1 panah");

            ServerLevel level = assassin.serverLevel();
            Arrow arrow = new Arrow(level, assassin);
            Vec3 target = merlin.position().add(0, 1.0, 0);
            Vec3 from = target.add(0, 0.2, 2.5);
            arrow.setPos(from.x, from.y, from.z);
            Vec3 dir = target.subtract(from);
            arrow.shoot(dir.x, dir.y, dir.z, 2.0f, 0f);
            level.addFreshEntity(arrow);
        });
        waitFor("game selesai (kubu jahat menang)", () -> !gm().isGameRunning(), 400);
        run("cek cleanup", () -> {
            ServerLevel level = p("a").serverLevel();
            check(level.getBlockState(new BlockPos(gm().BASE_X, gm().BASE_Y, gm().BASE_Z)).isAir(), "cauldron dihapus");
            check(level.getBlockState(new BlockPos(gm().BASE_X, gm().BASE_Y - 1, gm().BASE_Z)).is(Blocks.CHISELED_STONE_BRICKS),
                    "campfire diganti chiseled stone bricks");
            int seats = 0;
            for (Entity e : level.getAllEntities()) if (e.getTags().contains("avalon_seat")) seats++;
            check(seats == 0, "semua kursi dihapus");
            check(server.isPvpAllowed(), "PvP dinyalakan lagi");
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (pl == null) continue;
                check(!gm().isCameraLocked(pl) && !gm().isMovementLocked(pl), n + " tidak terkunci");
            }
        });

        // ── Game kedua: /stopgame ─────────────────────────────────────────────
        sleep(40);
        run("game kedua lalu stopgame", () -> {
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (pl != null && pl.isDeadOrDying()) server.getPlayerList().respawn(pl, false);
            }
            cmd("avalon cutscene off");
            cmd("execute as a run avalon startgame");
            check(gm().isGameRunning(), "game kedua berjalan");
        });
        sleep(200);
        run("stopgame", () -> {
            cmd("execute as a run avalon stopgame");
            check(!gm().isGameRunning(), "stopgame menghentikan game");
            check(gm().getRegisteredPlayers().size() == 5, "player tetap terdaftar setelah stopgame");
        });
        sleep(20);
    }
}
