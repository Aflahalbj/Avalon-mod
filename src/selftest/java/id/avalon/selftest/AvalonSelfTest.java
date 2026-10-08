package id.avalon.selftest;

import id.avalon.AvalonMod;
import id.avalon.block.BatteryRackBlock;
import id.avalon.block.ModBlocks;
import id.avalon.block.PillarBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.PlayerScale;
import id.avalon.gui.AvalonMenu;
import id.avalon.gui.TeamSelectionGUI;
import id.avalon.managers.GameManager;
import id.avalon.managers.VotingManager;
import id.avalon.models.Role;
import id.avalon.world.AvalonPillars;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
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
        // Ukuran tim mengikuti nomor ronde, bukan jumlah misi sukses
        int targets = 0;
        for (int s : TeamSelectionGUI.TARGET_SLOTS) if (!menu.inv().getItem(s).isEmpty()) targets++;
        check(targets == ROUND_TEAM_SIZE[gm().getCurrentRound() - 1],
                "ronde " + gm().getCurrentRound() + ": GUI meminta " + ROUND_TEAM_SIZE[gm().getCurrentRound() - 1] + " anggota tim");

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

    /** Klik kanan item vote = Setuju; klik kiri (datang lewat paket dari client) = Tolak. */
    private static void vote(ServerPlayer player, String vote) {
        if (VotingManager.VOTE_SETUJU.equals(vote)) {
            useSlot(player, 0);
        } else {
            gm().handleLeftClick(player);
        }
    }

    private static void everyoneVotes(String vote) {
        for (String n : NAMES) {
            ServerPlayer pl = p(n);
            if (pl == null) continue;
            if (!vm().isVotingActive()) return;
            vote(pl, vote);
        }
    }

    // ── Misi baterai ──────────────────────────────────────────────────────────

    private static ServerLevel avalon() {
        return server.getLevel(AvalonDimensions.AVALON);
    }

    /** Rak gudang ke-{@code i} (0..6). */
    private static BlockPos sourceRack(int i) {
        return new BlockPos(-419 - i, 199, -476);
    }

    private static BlockPos pillarRack(int site) {
        return AvalonPillars.SITES.get(site).rackPos();
    }

    private static BlockPos pillar(int site) {
        return AvalonPillars.SITES.get(site).pillarPos();
    }

    /** Titik tengah lubang rak di sisi depannya. */
    private static BlockHitResult rackHit(BlockPos pos, int slot) {
        Direction facing = avalon().getBlockState(pos).getValue(BatteryRackBlock.FACING);
        Direction left = facing.getClockWise();
        double side = (1 - slot % 3) * 5.0 / 16.0;
        Vec3 at = Vec3.atCenterOf(pos).add(
                facing.getStepX() * 0.5 + left.getStepX() * side,
                slot < 3 ? 0.25 : -0.25,
                facing.getStepZ() * 0.5 + left.getStepZ() * side);
        return new BlockHitResult(at, facing, pos, false);
    }

    /** Fake player yang baru reconnect bisa tercatat di dimensi lain: pastikan dia di Avalon dulu. */
    private static void bringToAvalon(ServerPlayer player, BlockPos near) {
        if (player.serverLevel() != avalon()) {
            player.teleportTo(avalon(), near.getX() + 0.5, near.getY() + 1.0, near.getZ() + 0.5, 0f, 0f);
        }
    }

    /** Klik kiri lubang rak baterai (ambil / pasang), seperti paket RackClick dari client. */
    private static void clickRack(ServerPlayer player, BlockPos pos, int slot) {
        bringToAvalon(player, pos);
        BlockHitResult hit = rackHit(pos, slot);
        player.getInventory().selected = 0;
        BatteryRackBlock.leftClick(player, pos, hit.getDirection(), hit.getLocation());
    }

    /** Klik kanan lubang rak baterai: tidak memasang / mengambil apa pun. */
    private static void rightClickRack(ServerPlayer player, BlockPos pos, int slot) {
        bringToAvalon(player, pos);
        player.getInventory().selected = 0;
        player.gameMode.useItemOn(player, avalon(), player.getMainHandItem(), InteractionHand.MAIN_HAND, rackHit(pos, slot));
    }

    private static boolean holdsBattery(ServerPlayer player) {
        return player.getInventory().getItem(0).is(ModBlocks.BATTERY.get());
    }

    private static BatteryRackBlock.Slot slotState(BlockPos rack, int slot) {
        return avalon().getBlockState(rack).getValue(BatteryRackBlock.SLOTS.get(slot));
    }

    private static int firstSlot(BlockPos rack, BatteryRackBlock.Slot wanted) {
        for (int i = 0; i < BatteryRackBlock.SLOT_COUNT; i++) {
            if (slotState(rack, i) == wanted) return i;
        }
        return -1;
    }

    /** Pilar pertama yang raknya masih terbuka (belum menyala). */
    private static int openSite() {
        for (int i = 0; i < AvalonPillars.SITES.size(); i++) {
            if (BatteryRackBlock.openSlots(avalon().getBlockState(pillarRack(i))) > 0) return i;
        }
        return -1;
    }

    /** Semua anggota tim mengambil baterai dari gudang lalu memasangnya di rak pilar {@code site}. */
    private static void teamFillsPillar(int site) {
        List<String> team = gm().getCurrentMissionTeam();
        for (int i = 0; i < team.size(); i++) {
            ServerPlayer member = p(team.get(i));
            if (member == null) continue;
            if (!holdsBattery(member)) clickRack(member, sourceRack(i), 3);
            check(holdsBattery(member), team.get(i) + " mengambil baterai dari gudang");
            clickRack(member, pillarRack(site), firstSlot(pillarRack(site), BatteryRackBlock.Slot.EMPTY));
            check(!holdsBattery(member), team.get(i) + " memasang baterai di pilar " + site);
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

    /** Ukuran tim tiap ronde untuk 5 player (aturan Avalon), ditulis ulang di sini supaya tabelnya ikut teruji. */
    private static final int[] ROUND_TEAM_SIZE = {2, 3, 2, 3, 3};

    /**
     * Anggota tim yang akan keluar di tengah misi. Sebisa mungkin bukan fake player yang sudah pernah
     * keluar-masuk ("c" dan {@code alsoAvoid}): mereka bisa tercatat di dimensi lain.
     */
    private static String pickLeaver(String alsoAvoid) {
        String fallback = null, best = null;
        for (String n : gm().getCurrentMissionTeam()) {
            if (n.equals("c")) continue;
            fallback = n;
            if (!n.equals(alsoAvoid)) best = n;
        }
        return best != null ? best : fallback;
    }

    private static int teamSize() {
        return ROUND_TEAM_SIZE[gm().getCurrentRound() - 1];
    }

    /** Raja sudah diumumkan, online, dan memegang Buku Pemilihan Tim. */
    private static boolean kingHoldsBook() {
        String king = gm().getCurrentKingName();
        return gm().isTeamSelectionActive() && king != null && p(king) != null
                && findHotbarSlot(p(king), GameManager::isTeamBook) >= 0;
    }

    private static boolean everyoneInOverworld() {
        for (String n : NAMES) {
            if (p(n) == null || p(n).serverLevel() != server.overworld()) return false;
        }
        return true;
    }

    /** Satu misi sukses dari awal sampai pilarnya menyala: tim kubu baik, semua setuju, semua memasang. */
    private static void quickSuccessMission(String label) {
        waitFor(label + ": raja memegang buku", AvalonSelfTest::kingHoldsBook, 400);
        run(label + ": tim baik", () -> kingPicksTeam(goodTeam()));
        waitFor(label + ": voting", () -> vm().isVotingActive(), 200);
        run(label + ": semua setuju", () -> everyoneVotes(VotingManager.VOTE_SETUJU));
        waitFor(label + ": misi aktif", () -> gm().isMissionActive(), 200);
        run(label + ": pasang baterai", () -> teamFillsPillar(openSite()));
        waitFor(label + ": sukses", () -> !gm().isMissionActive(), 400);
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

        // ── Di luar game mod tidak ikut campur: step height green wool hanya untuk pemain game ──
        sleep(80); // tunggu spawn invulnerability (60 tick) habis
        run("green wool di luar game", () -> {
            ServerPlayer b = p("b");
            b.serverLevel().setBlock(b.blockPosition().above(2), Blocks.GREEN_WOOL.defaultBlockState(), 3);
        });
        sleep(2);
        run("cek step height di luar game", () -> {
            ServerPlayer b = p("b");
            check(b.getAttribute(ForgeMod.STEP_HEIGHT_ADDITION.get()).getBaseValue() == 0.0, "step height tidak diubah di luar game");
            b.serverLevel().setBlock(b.blockPosition().above(2), Blocks.AIR.defaultBlockState(), 3);
        });

        // ── Start hanya kalau semua yang terdaftar online ─────────────────────
        run("e offline sebelum start", () -> cmd("fakeplayer remove e"));
        waitFor("e offline", () -> p("e") == null, 100);
        run("startgame ditolak", () -> {
            cmd("execute as a run avalon startgame");
            check(!gm().isGameRunning(), "startgame ditolak selagi ada yang offline");
            cmd("fakeplayer spawn e");
        });
        waitFor("e online lagi", () -> p("e") != null, 100);

        // ── Keluar saat hitung mundur: game batal ─────────────────────────────
        run("e keluar saat hitung mundur", () -> {
            cmd("execute as a run avalon startgame");
            check(gm().isGameRunning(), "hitung mundur berjalan");
            cmd("fakeplayer remove e");
        });
        waitFor("game dibatalkan", () -> !gm().isGameRunning(), 100);
        run("e masuk lagi", () -> {
            check(gm().getRegisteredPlayers().size() == 5, "semua tetap terdaftar setelah batal");
            cmd("fakeplayer spawn e");
        });
        waitFor("e online lagi", () -> p("e") != null, 100);
        sleep(40);

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

        final BlockPos[] woolAt = new BlockPos[1];
        final BlockState[] woolBefore = new BlockState[1];
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

            // Inventory 1 slot: barang di slot lain & slot terpilih selain slot pertama tidak bertahan
            check(gm().isOneSlot(other), "inventory 1 slot aktif selama game");
            other.getInventory().setItem(4, new ItemStack(Items.STICK));
            other.getInventory().selected = 4;

            // Step height green wool (hanya pemain game selama game)
            woolAt[0] = c.blockPosition().above(2);
            woolBefore[0] = c.serverLevel().getBlockState(woolAt[0]);
            c.serverLevel().setBlock(woolAt[0], Blocks.GREEN_WOOL.defaultBlockState(), 3);
        });
        sleep(2);
        run("cek inventory 1 slot", () -> {
            ServerPlayer c = p("c");
            check(c.getAttribute(ForgeMod.STEP_HEIGHT_ADDITION.get()).getBaseValue() > 9.0, "step height naik dekat green wool");
            c.serverLevel().setBlock(woolAt[0], woolBefore[0], 3);

            ServerPlayer other = null;
            for (String n : NAMES) if (!n.equals(gm().getCurrentKingName())) { other = p(n); break; }
            check(other.getInventory().selected == 0, "slot terpilih dipaksa ke slot pertama");
            check(other.getInventory().getItem(4).isEmpty() && other.getInventory().getItem(0).is(Items.STICK),
                    "barang di slot lain dipindah ke slot pertama");
            other.drop(false);
            check(other.getInventory().getItem(0).is(Items.STICK), "barang tidak bisa di-drop selama game");
            other.getInventory().clearContent();
        });

        // ── Ronde: tim ditolak ────────────────────────────────────────────────
        run("raja memilih tim (akan ditolak)", () -> kingPicksTeam(List.of(NAMES[0], NAMES[1]).subList(0, teamSize())));
        waitFor("voting dimulai", () -> vm().isVotingActive(), 200);
        run("semua menolak", () -> {
            ServerPlayer a = p("a");
            check(vm().isVoteItem(a.getInventory().getItem(0)) && a.getInventory().getItem(1).isEmpty(), "satu item vote dibagikan");
            a.inventoryMenu.clicked(36, 0, ClickType.PICKUP, a);
            check(a.inventoryMenu.getCarried().isEmpty(), "item vote tidak bisa dipindah");
            a.inventoryMenu.clicked(36, 1, ClickType.SWAP, a);
            check(vm().isVoteItem(a.getInventory().getItem(0)), "number key swap diblok");
            vote(a, VotingManager.VOTE_SETUJU); // klik kanan: setuju dulu
            check(VotingManager.VOTE_SETUJU.equals(vm().getVote(a)), "klik kanan = setuju");
            vote(a, VotingManager.VOTE_TOLAK); // klik kiri: ganti ke tolak
            check(VotingManager.VOTE_TOLAK.equals(vm().getVote(a)), "klik kiri = tolak");
            check(vm().isVoteItem(a.getInventory().getItem(0)), "item vote tetap ada setelah memilih");
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
        run("cek misi 1 & pasang baterai", () -> {
            List<String> team = gm().getCurrentMissionTeam();
            ServerPlayer outsider = null;
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (team.contains(n)) {
                    check(pl.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, n + " (tim) survival");
                    check(pl.getInventory().isEmpty(), n + " mulai dengan tangan kosong");
                    check(!onSeat(pl), n + " turun dari kursi");
                } else {
                    check(pl.gameMode.getGameModeForPlayer() == GameType.SPECTATOR, n + " (bukan tim) spectator");
                    outsider = pl;
                }
            }
            for (int i = 0; i < AvalonPillars.SITES.size(); i++) {
                check(BatteryRackBlock.openSlots(avalon().getBlockState(pillarRack(i))) == team.size(),
                        "rak pilar " + i + " terbuka sebanyak anggota tim");
                check(!PillarBlock.isLit(avalon(), pillar(i)), "pilar " + i + " masih mati");
            }
            check(BatteryRackBlock.batteries(avalon().getBlockState(sourceRack(0))) == 6, "rak gudang penuh");

            ServerPlayer member = p(team.get(0));
            ServerPlayer mate = p(team.get(1));

            // Bukan anggota tim tidak bisa mengambil baterai
            outsider.setGameMode(GameType.SURVIVAL);
            clickRack(outsider, sourceRack(6), 0);
            check(!holdsBattery(outsider), "bukan anggota tim tidak bisa mengambil baterai");
            outsider.setGameMode(GameType.SPECTATOR);

            // Satu baterai per orang, tidak bisa di-drop, tidak bisa dikembalikan ke gudang
            clickRack(member, sourceRack(0), 0);
            check(holdsBattery(member), "anggota tim mengambil baterai");
            check(slotState(sourceRack(0), 0) == BatteryRackBlock.Slot.EMPTY, "lubang gudang jadi kosong");
            member.drop(false);
            check(holdsBattery(member), "baterai tidak bisa di-drop");
            clickRack(member, sourceRack(0), 0);
            check(holdsBattery(member), "baterai tidak bisa dikembalikan ke gudang");

            // Kubu baik tidak bisa masuk mode sabotase
            useSlot(member, 0);
            check(!gm().getBatteryMission().isSabotaging(member), "kubu baik tidak punya mode sabotase");

            // Salah pilar: pasang di pilar 0, ambil lagi, pindah ke pilar 1
            int slot = firstSlot(pillarRack(0), BatteryRackBlock.Slot.EMPTY);
            rightClickRack(member, pillarRack(0), slot);
            check(holdsBattery(member) && slotState(pillarRack(0), slot) == BatteryRackBlock.Slot.EMPTY,
                    "klik kanan tidak memasang baterai");
            clickRack(member, pillarRack(0), slot);
            check(!holdsBattery(member) && slotState(pillarRack(0), slot) == BatteryRackBlock.Slot.BATTERY,
                    "baterai terpasang di rak pilar 0");
            check(BatteryRackBlock.openSlots(avalon().getBlockState(pillarRack(1))) == 0
                    && BatteryRackBlock.openSlots(avalon().getBlockState(pillarRack(2))) == 0,
                    "pilar lain ditutup setelah baterai pertama dipasang");
            clickRack(member, sourceRack(1), 0);
            check(!holdsBattery(member), "tidak bisa mengambil baterai kedua dari gudang");
            clickRack(mate, pillarRack(0), slot);
            check(!holdsBattery(mate), "baterai orang lain tidak bisa diambil");
            rightClickRack(member, pillarRack(0), slot);
            check(!holdsBattery(member), "klik kanan tidak mengambil baterai");
            clickRack(member, pillarRack(0), slot);
            check(holdsBattery(member), "baterai sendiri bisa diambil lagi dari rak pilar");
            check(gm().isMissionActive(), "misi belum selesai");
        });
        sleep(2);
        run("pilar lain dibuka lagi & misi 1 diselesaikan di pilar 1", () -> {
            List<String> team = gm().getCurrentMissionTeam();
            ServerPlayer member = p(team.get(0));
            check(BatteryRackBlock.openSlots(avalon().getBlockState(pillarRack(1))) == team.size(),
                    "pilar lain dibuka lagi setelah rak pilar 0 kosong");

            // Blok tidak bisa dihancurkan
            BlockPos other = member.blockPosition().below();
            BlockState before = member.serverLevel().getBlockState(other);
            member.serverLevel().setBlock(other, Blocks.STONE.defaultBlockState(), 3);
            member.gameMode.destroyBlock(other);
            check(member.serverLevel().getBlockState(other).is(Blocks.STONE), "blok tidak bisa dihancurkan");
            member.serverLevel().setBlock(other, before, 3);

            teamFillsPillar(1);
            check(!gm().getBatteryMission().isActive(), "rak pilar 1 penuh: misi menunggu hasil");
            clickRack(member, pillarRack(1), firstSlot(pillarRack(1), BatteryRackBlock.Slot.BATTERY));
            check(!holdsBattery(member), "baterai tidak bisa diambil setelah rak penuh");

            // Cutscene pilar: semua player diparkir di atas pilar, tak terlihat & terkunci
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                check(pl.hasEffect(MobEffects.INVISIBILITY) && gm().isMovementLocked(pl) && pl.serverLevel() == avalon()
                        && pl.getY() > pillar(1).getY() + PillarBlock.ORB_HEIGHT, n + " diparkir untuk cutscene pilar");
            }
            check(!PillarBlock.isLit(avalon(), pillar(1)), "pilar belum menyala saat cutscene baru mulai");
        });
        waitFor("pilar 1 mulai menyala", () -> PillarBlock.isLit(avalon(), pillar(1)), 80);
        run("cek player tetap tak terlihat selama cutscene", () -> {
            for (String n : NAMES) check(p(n).isInvisible(), n + " tak terlihat selama cutscene pilar");
        });
        waitFor("misi 1 sukses", () -> !gm().isMissionActive(), 320);
        run("cek pilar setelah misi 1", () -> {
            check(PillarBlock.isLit(avalon(), pillar(1)), "pilar 1 tetap menyala");
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                check(!pl.hasEffect(MobEffects.INVISIBILITY) && !pl.getAbilities().flying, n + " kembali normal setelah cutscene");
            }
            for (int i = 0; i < AvalonPillars.SITES.size(); i++) {
                BlockState rack = avalon().getBlockState(pillarRack(i));
                check(BatteryRackBlock.openSlots(rack) == 0 && BatteryRackBlock.batteries(rack) == 0,
                        "rak pilar " + i + " kosong & tertutup setelah misi");
            }
        });
        waitFor("diskusi setelah misi 1", () -> gm().isDiscussionActive(), 600);

        // ── Offline / online saat diskusi ─────────────────────────────────────
        run("player c disconnect", () -> {
            lastKing = gm().getCurrentKingName();
            cmd("fakeplayer remove c");
        });
        waitFor("mannequin c muncul", () -> gm().getOfflineMannequinCount() == 1, 100);
        run("player c reconnect", () -> cmd("fakeplayer spawn c"));
        waitFor("c kembali & mannequin hilang", () -> p("c") != null && gm().getOfflineMannequinCount() == 0, 200);
        // Fake player masuk lagi di overworld: dibawa ke Avalon dulu, kursinya menyusul beberapa tick
        waitFor("c didudukkan lagi", () -> onSeat(p("c")), 100);
        run("cek restore c", () -> {
            ServerPlayer c = p("c");
            check(c.level().dimension() == id.avalon.core.AvalonDimensions.AVALON, "c kembali ke dimensi Avalon");
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

        // Satu baterai per orang, juga kalau bot-nya yang memasang: pemilik bot yang kembali di tengah
        // misi tidak boleh memasang baterai kedua (dulu bisa, dan dihitung dua sabotase)
        final String[] away = new String[1];
        run("misi 2: anggota tim baik keluar sebelum mengambil baterai", () -> {
            check(gm().getCurrentMissionTeam().size() == 3, "tim ronde 2 berisi 3 orang");
            check(!PillarBlock.isLit(avalon(), pillar(0)) && PillarBlock.isLit(avalon(), pillar(1)),
                    "hanya pilar 1 yang menyala");
            check(BatteryRackBlock.openSlots(avalon().getBlockState(pillarRack(1))) == 0, "rak pilar yang sudah menyala tetap tertutup");
            check(BatteryRackBlock.batteries(avalon().getBlockState(sourceRack(0))) == 6, "gudang diisi penuh lagi");
            for (String n : gm().getCurrentMissionTeam()) if (gm().getRole(p(n)).isGood() && !n.equals("c")) away[0] = n;
            cmd("fakeplayer remove " + away[0]);
        });
        waitFor("bot-nya ikut misi 2", () -> p(away[0]) == null && gm().getOfflineMannequinCount() == 1, 100);
        run("anggota baik lain memilih pilar 0", () -> {
            List<String> team = gm().getCurrentMissionTeam();
            for (int i = 0; i < team.size(); i++) {
                ServerPlayer member = p(team.get(i));
                if (member == null || gm().getRole(member).isEvil()) continue;
                clickRack(member, sourceRack(i), 3);
                clickRack(member, pillarRack(0), firstSlot(pillarRack(0), BatteryRackBlock.Slot.EMPTY));
                check(!holdsBattery(member), team.get(i) + " memasang baterai pertama di pilar 0");
            }
        });
        waitFor("bot memasang baterainya di pilar 0",
                () -> BatteryRackBlock.batteries(avalon().getBlockState(pillarRack(0))) == 2, 6000);
        run("pemilik bot masuk lagi", () -> cmd("fakeplayer spawn " + away[0]));
        waitFor("pemilik bot kembali ke misi", () -> p(away[0]) != null && gm().getOfflineMannequinCount() == 0, 200);
        sleep(5);
        run("pemilik bot tidak bisa memasang baterai kedua", () -> {
            ServerPlayer back = p(away[0]);
            check(gm().getBatteryMission().hasPlaced(back), "baterai yang dipasang bot tercatat milik pemiliknya");
            clickRack(back, sourceRack(5), 0);
            check(!holdsBattery(back), "pemilik bot tidak bisa mengambil baterai kedua dari gudang");
            check(BatteryRackBlock.batteries(avalon().getBlockState(pillarRack(0))) == 2 && gm().isMissionActive(),
                    "rak pilar 0 tetap berisi 2 baterai");
        });

        run("sabotase", () -> {
            String evil = null;
            for (String n : gm().getCurrentMissionTeam()) if (gm().getRole(p(n)).isEvil()) evil = n;
            ServerPlayer saboteur = p(evil);

            int index = gm().getCurrentMissionTeam().indexOf(evil);
            clickRack(saboteur, sourceRack(index), 3);
            check(holdsBattery(saboteur), "kubu jahat mengambil baterai");
            useSlot(saboteur, 0);
            check(gm().getBatteryMission().isSabotaging(saboteur), "klik kanan sambil pegang baterai = mode sabotase");
            useSlot(saboteur, 0);
            check(gm().getBatteryMission().isSabotaging(saboteur), "klik beruntun tidak membolak-balik mode");

            clickRack(saboteur, pillarRack(0), firstSlot(pillarRack(0), BatteryRackBlock.Slot.EMPTY));
            check(!holdsBattery(saboteur), "kubu jahat memasang baterai sabotase");
            check(!gm().getBatteryMission().isActive() && gm().isMissionActive(), "rak pilar 0 penuh: cutscene berjalan");
        });
        waitFor("pilar 0 meluap", () -> avalon().getBlockState(pillar(0)).getValue(PillarBlock.OVERLOAD), 80);
        waitFor("pilar pecah & misi gagal", () -> !gm().isMissionActive(), 320);
        waitFor("pilar 0 mati lagi", () -> !avalon().getBlockState(pillar(0)).getValue(PillarBlock.OVERLOAD), 100);
        run("cek pilar setelah sabotase", () ->
                check(!PillarBlock.isLit(avalon(), pillar(0)), "pilar yang disabotase tidak menyala"));
        waitFor("diskusi setelah sabotase", () -> gm().isDiscussionActive(), 600);
        run("skip diskusi (gagal)", AvalonSelfTest::everyoneSkips);
        waitFor("raja berganti setelah misi 2", () -> !gm().isDiscussionActive() && gm().getEvilMissionFails() == 1
                && gm().getCurrentKingName() != null && !gm().getCurrentKingName().equals(lastKing), 300);

        // ── Misi 3 & 4: sukses ────────────────────────────────────────────────
        for (int m = 0; m < 2; m++) {
            final int round = m + 3;
            final int[] site = new int[1];
            run("misi " + round + ": tim baik", () -> {
                lastKing = gm().getCurrentKingName();
                // Satu misi sudah gagal: nomor ronde dan jumlah misi sukses tidak lagi sama
                check(gm().getCurrentRound() == round && gm().getCurrentMission() == round - 1,
                        "ronde " + round + " dengan " + (round - 2) + " misi sukses");
                kingPicksTeam(goodTeam());
            });
            waitFor("voting misi " + round, () -> vm().isVotingActive(), 200);
            run("setuju misi " + round, () -> everyoneVotes(VotingManager.VOTE_SETUJU));
            waitFor("misi " + round + " aktif", () -> gm().isMissionActive(), 200);
            final String[] offline = new String[1];
            if (m == 0) {
                run("anggota tim disconnect di tengah misi", () -> {
                    offline[0] = pickLeaver(away[0]);
                    cmd("fakeplayer remove " + offline[0]);
                });
                waitFor("bot menggantikan", () -> p(offline[0]) == null && gm().getOfflineMannequinCount() == 1, 100);
            }
            if (m == 1) {
                final Vec3[] botAt = new Vec3[1];
                run("anggota tim disconnect (akan kembali di tengah misi)", () -> {
                    offline[0] = pickLeaver(away[0]);
                    cmd("fakeplayer remove " + offline[0]);
                });
                waitFor("bot mengambil baterai", () -> gm().getBatteryMission().botCarriesBattery(offline[0]), 3000);
                run("anggota tim reconnect di tengah misi", () -> {
                    botAt[0] = gm().getBatteryMission().botPosition(offline[0]);
                    cmd("fakeplayer spawn " + offline[0]);
                });
                waitFor("kembali ke misi", () -> p(offline[0]) != null && gm().getOfflineMannequinCount() == 0, 200);
                sleep(5);
                run("cek player menggantikan bot-nya", () -> {
                    ServerPlayer back = p(offline[0]);
                    check(holdsBattery(back), "baterai yang diambil bot pindah ke tangan player");
                    check(!onSeat(back) && back.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "player berdiri, bukan duduk");
                    check(back.serverLevel() == avalon() && back.position().distanceTo(botAt[0]) < 3.0,
                            "player muncul di posisi terakhir bot");
                    offline[0] = null;
                });
            }
            run("pasang baterai misi " + round, () -> {
                site[0] = openSite();
                check(site[0] >= 0 && site[0] != 1, "pilar yang belum menyala dibuka lagi (pilar " + site[0] + ")");
                teamFillsPillar(site[0]);
                if (offline[0] != null) check(gm().getBatteryMission().isActive(), "misi menunggu baterai bot");
            });
            if (m == 0) {
                waitFor("bot mengambil & memasang baterainya", () -> !gm().getBatteryMission().isActive(), 6000);
                run("cek rak setelah bot memasang", () ->
                        check(BatteryRackBlock.isFull(avalon().getBlockState(pillarRack(site[0]))), "rak pilar penuh berkat bot"));
            }
            waitFor("misi " + round + " sukses", () -> !gm().isMissionActive(), 320);
            run("cek pilar misi " + round, () -> check(PillarBlock.isLit(avalon(), pillar(site[0])), "pilar " + site[0] + " menyala"));
            if (m == 0) {
                run("anggota tim reconnect", () -> cmd("fakeplayer spawn " + offline[0]));
                waitFor("kembali & mannequin hilang", () -> p(offline[0]) != null && gm().getOfflineMannequinCount() == 0, 200);
            }
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
            // PvP server menyala di fase ini; pemain game tetap tidak bisa saling melukai
            ServerPlayer attacker = p(namesWith(Role::isEvil).get(0));
            ServerPlayer victim = p(namesWith(Role::isGood).get(0));
            float before = victim.getHealth();
            attacker.attack(victim);
            check(victim.getHealth() == before, "pukulan antar pemain game diblok");
            everyoneSkips();
        });
        waitFor("assassin dapat busur", () -> gm().isAssassinBowActive(), 100);
        run("assassin menembak Merlin", () -> {
            ServerPlayer assassin = p(nameWithRole(Role.ASSASSIN));
            ServerPlayer merlin = p(nameWithRole(Role.MERLIN));
            ItemStack bow = assassin.getInventory().getItem(0);
            check(gm().isAssassinBowItem(bow) && bow.getDamageValue() == 383, "busur assassin durability 1");
            check(assassin.getInventory().getItem(1).isEmpty()
                    && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.INFINITY_ARROWS, bow) > 0,
                    "busur Infinity tanpa item panah");
            useSlot(assassin, 0);
            check(assassin.isUsingItem(), "busur bisa ditarik tanpa panah");
            assassin.stopUsingItem();

            ServerLevel level = assassin.serverLevel();
            Arrow arrow = new Arrow(level, assassin);
            Vec3 target = merlin.position().add(0, 1.0, 0);
            Vec3 from = target.add(0, 0.2, 2.5);
            arrow.setPos(from.x, from.y, from.z);
            Vec3 dir = target.subtract(from);
            arrow.shoot(dir.x, dir.y, dir.z, 2.0f, 0f);
            level.addFreshEntity(arrow);
        });
        waitFor("game selesai (kubu jahat menang)", () -> !gm().isGameRunning(), 1200);
        run("cek cleanup", () -> {
            // Game berlangsung di dimensi Avalon; setelah selesai semua player dipulangkan ke overworld
            ServerLevel level = server.getLevel(AvalonDimensions.AVALON);
            for (String n : NAMES) {
                check(p(n).serverLevel() == server.overworld(), n + " dipulangkan ke overworld");
            }
            int seats = 0;
            for (Entity e : level.getAllEntities()) if (e.getTags().contains("avalon_seat")) seats++;
            check(seats == 0, "semua kursi dihapus");
            for (int i = 0; i < AvalonPillars.SITES.size(); i++) {
                check(!PillarBlock.isLit(avalon(), pillar(i)), "pilar " + i + " dimatikan setelah game");
            }
            check(!gm().isOneSlot(p("a")), "inventory 1 slot berakhir bersama game");
            check(server.isPvpAllowed(), "PvP dinyalakan lagi");
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (pl == null) continue;
                check(!gm().isCameraLocked(pl) && !gm().isMovementLocked(pl), n + " tidak terkunci");
            }
        });

        // ── Game kedua: raja offline, tim offline, panah assassin ke void ─────
        sleep(40);
        run("game kedua", () -> {
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (pl != null && pl.isDeadOrDying()) server.getPlayerList().respawn(pl, false);
            }
            check(gm().getRole(p("a")) == null, "role game sebelumnya dibersihkan");
            cmd("avalon cutscene off");
            cmd("execute as a run avalon startgame");
            check(gm().isGameRunning(), "game kedua berjalan");
        });
        waitFor("game 2: raja pertama memegang buku", AvalonSelfTest::kingHoldsBook, 2400);

        // Raja keluar saat gilirannya memilih tim: setelah grace 90 detik raja berikutnya yang memilih
        run("game 2: raja keluar saat memilih tim", () -> {
            lastKing = gm().getCurrentKingName();
            cmd("fakeplayer remove " + lastKing);
        });
        waitFor("game 2: raja diganti setelah grace", () -> kingHoldsBook()
                && !gm().getCurrentKingName().equals(lastKing), 90 * 20 + 400);
        run("game 2: raja lama masuk lagi", () -> cmd("fakeplayer spawn " + lastKing));
        waitFor("game 2: raja lama duduk lagi", () -> p(lastKing) != null && gm().getOfflineMannequinCount() == 0
                && onSeat(p(lastKing)), 300);
        run("game 2: cek raja lama", () -> {
            check(findHotbarSlot(p(lastKing), GameManager::isTeamBook) < 0, "raja lama tidak lagi memegang buku");
            check(kingHoldsBook(), "raja baru tetap memegang buku");
        });

        // Seluruh tim sudah offline saat misi dimulai: misinya langsung dibatalkan, bukan macet
        final List<String> gone = new ArrayList<>();
        run("game 2: raja memilih dua player lain", () -> {
            lastKing = gm().getCurrentKingName();
            for (String n : NAMES) if (!n.equals(lastKing) && gone.size() < teamSize()) gone.add(n);
            kingPicksTeam(gone);
        });
        waitFor("game 2: voting", () -> vm().isVotingActive(), 200);
        run("game 2: tim setuju lalu keluar", () -> {
            for (String n : gone) {
                vote(p(n), VotingManager.VOTE_SETUJU);
                cmd("fakeplayer remove " + n);
            }
        });
        waitFor("game 2: tim offline", () -> p(gone.get(0)) == null && p(gone.get(1)) == null, 100);
        run("game 2: sisanya setuju", () -> {
            check(vm().isVotingActive(), "voting menunggu player yang masih online");
            everyoneVotes(VotingManager.VOTE_SETUJU);
            check(!vm().isVotingActive(), "voting selesai");
        });
        waitFor("game 2: misi dibatalkan & raja berganti", () -> !gm().isMissionActive() && kingHoldsBook()
                && !gm().getCurrentKingName().equals(lastKing), 600);
        run("game 2: cek misi batal & player offline tidak bisa dipilih", () -> {
            check(gm().getCurrentRound() == 1 && gm().getEvilMissionFails() == 0, "misi batal tidak dihitung gagal");

            // Tiga player online cukup untuk tim berisi dua: yang offline tidak bisa dipilih
            ServerPlayer king = p(gm().getCurrentKingName());
            useSlot(king, findHotbarSlot(king, GameManager::isTeamBook));
            AvalonMenu menu = (AvalonMenu) king.containerMenu;
            int slot = -1;
            for (int s : TeamSelectionGUI.POOL_SLOTS) {
                if (gone.get(0).equals(TeamSelectionGUI.getPlayerNameFromItem(menu.inv().getItem(s)))) slot = s;
            }
            check(slot >= 0, "player offline tetap tampil di GUI");
            check(menu.inv().getItem(slot).getHoverName().getString().contains("OFFLINE"), "player offline diberi tanda");
            click(king, slot);
            check(TeamSelectionGUI.isQuestionMark(menu.inv().getItem(TeamSelectionGUI.TARGET_SLOTS[0])),
                    "player offline tidak bisa dipilih selagi yang online cukup");
            king.closeContainer();

            for (String n : gone) cmd("fakeplayer spawn " + n);
        });
        waitFor("game 2: tim masuk lagi", () -> p(gone.get(0)) != null && p(gone.get(1)) != null
                && gm().getOfflineMannequinCount() == 0 && onSeat(p(gone.get(0))) && onSeat(p(gone.get(1))), 300);

        // Tiga misi sukses beruntun, lalu assassin menembak ke void
        for (int m = 1; m <= 3; m++) {
            quickSuccessMission("game 2 misi " + m);
            if (m < 3) {
                waitFor("game 2: diskusi " + m, () -> gm().isDiscussionActive(), 600);
                run("game 2: skip diskusi " + m, AvalonSelfTest::everyoneSkips);
            }
        }
        waitFor("game 2: fase assassination", () -> gm().isAssassinationActive(), 600);
        run("game 2: kubu jahat skip", AvalonSelfTest::everyoneSkips);
        waitFor("game 2: assassin dapat busur", () -> gm().isAssassinBowActive(), 100);
        run("game 2: panah assassin jatuh ke void", () -> {
            ServerPlayer assassin = p(nameWithRole(Role.ASSASSIN));
            ServerLevel level = assassin.serverLevel();
            // Di bawah dunia, tidak ada block maupun entity yang bisa dikenai: tidak pernah ada event tumbukan
            Arrow arrow = new Arrow(level, assassin);
            arrow.setPos(assassin.getX(), level.getMinBuildHeight() - 50, assassin.getZ());
            level.addFreshEntity(arrow);
        });
        waitFor("game 2 selesai (panah meleset, kubu baik menang)", () -> !gm().isGameRunning(), 4000);
        run("game 2: cek pulang", () -> check(everyoneInOverworld(), "semua dipulangkan ke overworld"));

        // ── Game ketiga: /stopgame dari console ───────────────────────────────
        sleep(40);
        run("game ketiga lalu stopgame", () -> {
            for (String n : NAMES) {
                ServerPlayer pl = p(n);
                if (pl != null && pl.isDeadOrDying()) server.getPlayerList().respawn(pl, false);
            }
            cmd("execute as a run avalon startgame");
            check(gm().isGameRunning(), "game ketiga berjalan");
        });
        sleep(200);
        run("stopgame", () -> {
            check(p("a").serverLevel() == avalon(), "player sudah dibawa ke dimensi Avalon");
            cmd("avalon stopgame");
            check(!gm().isGameRunning(), "stopgame dari console menghentikan game");
            check(gm().getRegisteredPlayers().size() == 5, "player tetap terdaftar setelah stopgame");
            check(everyoneInOverworld(), "stopgame memulangkan semua player");
            check(server.isPvpAllowed(), "PvP dikembalikan seperti sebelum game");
        });
        sleep(20);
    }
}
