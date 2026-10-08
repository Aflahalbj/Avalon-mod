package id.avalon.cutscene;

import id.avalon.block.PillarBlock;
import id.avalon.core.Scheduler;
import id.avalon.core.Task;
import id.avalon.managers.GameManager;
import id.avalon.network.AvalonNetwork;
import id.avalon.world.AvalonGate;
import id.avalon.world.AvalonPillars;
import id.avalon.world.AvalonPortal;
import id.avalon.world.AvalonSeats;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cutscene akhir game (sisi server), lihat {@link EndingTimeline}.
 *
 * Server menaruh para player di tempatnya, mengunci gerakannya, mengubah pilar & cincin portal tepat
 * waktu, dan membagikan naskah. Ending menang: semua duduk di kursinya dulu, baru saat perpisahan
 * kubu baik dipindahkan ke tangga depan portal dan kubu jahat ke antara kursi dan portal. Ending
 * kalah: kubu baik duduk di kursinya, kubu jahat berdiri di antara kursi dan portal menghadap mereka.
 * Ending Merlin: kubu baik duduk, kubu jahat tetap di posisi terakhirnya. Kamera dan seluruh animasinya dikerjakan client (EndingClient).
 */
public final class EndingCutscene {

    private EndingCutscene() {}

    /** Player sejauh ini dari lingkaran kursi ikut menonton cutscene. */
    private static final double VIEW_RANGE = 160.0;

    // Tangga di depan cincin portal: lima kolom, beberapa baris ke belakang
    private static final int[] STAIR_COLUMNS = {-422, -423, -421, -424, -420};
    private static final int[] STAIR_ROWS = {-481, -483, -485};
    private static final int STAIR_SCAN_Y = 203;

    /** Garis di antara lingkaran kursi dan portal, tempat kubu jahat berdiri. */
    private static final double MID_Z = -493.5;
    private static final double SPACING = 1.6;
    private static final int MID_SCAN_Y = 199;

    private static final List<Task> tasks = new ArrayList<>();
    private static final List<ServerPlayer> locked = new ArrayList<>();
    /** Player yang didudukkan untuk ending menang. */
    private static final List<ServerPlayer> seated = new ArrayList<>();
    /** Slab kursi dipasang melebihi jumlah player terdaftar (tes di luar game). */
    private static boolean seatsBorrowed = false;
    /** Assassin yang diberi busur untuk reka ulang tembakan. */
    private static ServerPlayer bowHolder;
    private static ServerLevel level;
    private static GameManager game;
    private static boolean running = false;
    /** Pilar dikembalikan ke keadaan mati setelah cutscene (tes di luar game). */
    private static boolean resetPillars = false;

    public static boolean isRunning() {
        return running;
    }

    /**
     * Mainkan cutscene akhir.
     *
     * @param type        {@link EndingTimeline#WIN}, {@link EndingTimeline#EVIL} atau {@link EndingTimeline#MERLIN}
     * @param merlin      player Merlin (untuk ending Merlin terbunuh), boleh null
     * @param assassin    player assassin yang menembaknya (untuk ending Merlin terbunuh), boleh null
     * @param pillarsOff  true = matikan semua pilar setelah selesai (tes di luar game)
     * @param onDone      dipanggil setelah cutscene selesai dan para player dibebaskan
     */
    public static void play(GameManager gm, ServerLevel avalon, int type, List<ServerPlayer> good,
                            List<ServerPlayer> evil, ServerPlayer merlin, ServerPlayer assassin,
                            boolean pillarsOff, Runnable onDone) {
        if (running) return;
        running = true;
        level = avalon;
        game = gm;
        resetPillars = pillarsOff;

        List<AvalonNetwork.EndingActor> actors = new ArrayList<>();

        // Kubu baik: di tangga depan portal (menang), atau di tengah lingkaran kursi (kalah)
        List<Integer> styles = new ArrayList<>();
        for (int i = 0; i < EndingTimeline.FAREWELL_STYLES; i++) styles.add(i);
        Collections.shuffle(styles);
        for (int i = 0; i < good.size(); i++) {
            ServerPlayer p = good.get(i);
            actors.add(new AvalonNetwork.EndingActor(p.getId(), true, styles.get(i % styles.size()), i));
        }

        for (int i = 0; i < evil.size(); i++) {
            ServerPlayer p = evil.get(i);
            actors.add(new AvalonNetwork.EndingActor(p.getId(), false, 0, i));
        }

        if (type != EndingTimeline.WIN) {
            // Kalah: kubu baik duduk di kursinya; kubu jahat berdiri di antara kursi dan portal (tempat
            // yang sama dengan ending menang), tapi membelakangi portal: menghadap kubu baik
            seatAll(good, List.of());
            for (int i = 0; i < evil.size(); i++) {
                if (type == EndingTimeline.MERLIN) {
                    // Merlin tertembak: kubu jahat tetap di posisi terakhirnya (assassin menembak dari
                    // tempatnya berdiri), hanya gerakannya yang dikunci
                    stay(evil.get(i));
                } else {
                    place(evil.get(i), midSpot(i, evil.size()), false);
                }
            }
        }

        if (type == EndingTimeline.WIN) {
            // Menang: semua duduk di kursinya menonton; baru saat perpisahan mereka dipindahkan ke depan
            // portal. Kubu baik berdiri di tangga membelakangi portal (menghadap kubu jahat), kubu jahat
            // di antara kursi dan portal.
            seatAll(good, evil);
            tasks.add(Scheduler.later(EndingTimeline.FAREWELL_MOVE_AT, () -> {
                for (int i = 0; i < good.size(); i++) {
                    ServerPlayer p = live(good.get(i));
                    if (p != null) place(p, stairSpot(i), false);
                }
                for (int i = 0; i < evil.size(); i++) {
                    ServerPlayer p = live(evil.get(i));
                    if (p != null) place(p, midSpot(i, evil.size()), true);
                }
            }));
        }

        // Pilar yang sedang menyala (misi sukses): hanya itu yang bolanya meledak di ending kalah
        List<Integer> lit = new ArrayList<>();
        for (int i = 0; i < AvalonPillars.SITES.size(); i++) {
            if (PillarBlock.isLit(level, AvalonPillars.SITES.get(i).pillarPos())) lit.add(i);
        }

        int total = EndingTimeline.total(type, good.size(), lit.size());
        AvalonNetwork.EndingStart start = new AvalonNetwork.EndingStart(
                type, total, good.size(), merlin == null ? -1 : merlin.getId(),
                assassin == null ? -1 : assassin.getId(), lit, actors);

        // Reka ulang tembakan: assassin memegang busur
        if (type == EndingTimeline.MERLIN && assassin != null) {
            assassin.getInventory().setItem(assassin.getInventory().selected, new ItemStack(Items.BOW));
            bowHolder = assassin;
        }
        Vec3 center = Vec3.atCenterOf(AvalonSeats.CENTER);
        for (ServerPlayer viewer : level.players()) {
            if (viewer.distanceToSqr(center) <= VIEW_RANGE * VIEW_RANGE) AvalonNetwork.sendTo(viewer, start);
        }

        // Cincin portal: bola kacanya pecah jadi portal (menang) atau meledak (kalah)
        AvalonGate.prepare(level);
        if (type == EndingTimeline.WIN) {
            int closeStart = EndingTimeline.winCloseStart(good.size());
            tasks.add(new Task() {
                int tick = 0;

                @Override
                public void run() {
                    tick++;
                    if (tick >= EndingTimeline.WIN_SHATTER_START
                            && tick <= EndingTimeline.WIN_SHATTER_START + EndingTimeline.WIN_SHATTER_TICKS) {
                        AvalonGate.shatter(level, (tick - EndingTimeline.WIN_SHATTER_START) / (float) EndingTimeline.WIN_SHATTER_TICKS);
                    }
                    if (tick >= EndingTimeline.WIN_GATE_OPEN_START && tick <= EndingTimeline.WIN_GATE_OPEN_END) {
                        AvalonGate.fill(level, (tick - EndingTimeline.WIN_GATE_OPEN_START)
                                / (float) (EndingTimeline.WIN_GATE_OPEN_END - EndingTimeline.WIN_GATE_OPEN_START));
                    }
                    if (tick >= closeStart) {
                        AvalonGate.fill(level, 1f - (tick - closeStart) / (float) EndingTimeline.WIN_CLOSE_TICKS);
                    }
                }
            }.runTimer(1L, 1L));
        }

        // Pilar
        if (type != EndingTimeline.WIN) {
            // Bola pilar yang menyala meluap lalu meledak, satu per satu
            for (int order = 0; order < lit.size(); order++) {
                BlockPos pillar = AvalonPillars.SITES.get(lit.get(order)).pillarPos();
                double x = pillar.getX() + 0.5, y = pillar.getY() + PillarBlock.ORB_HEIGHT + 0.5, z = pillar.getZ() + 0.5;
                tasks.add(Scheduler.later(EndingTimeline.evilOverload(type, order), () -> PillarBlock.overload(level, pillar)));
                tasks.add(Scheduler.later(EndingTimeline.evilBurst(type, order), () -> {
                    level.sendParticles(ParticleTypes.FLASH, x, y, z, 1, 0, 0, 0, 0);
                    level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
                    level.sendParticles(ParticleTypes.END_ROD, x, y, z, 120, 0.2, 0.2, 0.2, 0.45);
                    level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 40, 0.6, 0.6, 0.6, 0.08);
                }));
            }
        }

        tasks.add(Scheduler.later(total, () -> {
            finish();
            if (onDone != null) onDone.run();
        }));
    }

    /**
     * Dudukkan semua player di kursinya: kursi sesuai urutan daftar, yang tidak terdaftar (tes di luar
     * game) mengisi kursi yang masih kosong. Slab kursinya dipasang kalau belum ada.
     */
    private static void seatAll(List<ServerPlayer> good, List<ServerPlayer> evil) {
        List<ServerPlayer> all = new ArrayList<>(good);
        all.addAll(evil);
        List<String> registered = game.getRegisteredPlayers();
        int seats = AvalonSeats.OFFSETS.length;

        boolean[] taken = new boolean[seats];
        int[] seatOf = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            int index = registered.indexOf(all.get(i).getGameProfile().getName());
            seatOf[i] = index >= 0 && index < seats && !taken[index] ? index : -1;
            if (seatOf[i] >= 0) taken[seatOf[i]] = true;
        }
        int highest = registered.size();
        for (int i = 0; i < all.size(); i++) {
            for (int free = 0; seatOf[i] < 0 && free < seats; free++) {
                if (!taken[free]) {
                    seatOf[i] = free;
                    taken[free] = true;
                }
            }
            highest = Math.max(highest, seatOf[i] + 1);
        }

        // Slab untuk kursi yang dipakai tapi belum ada (dikembalikan lagi setelah cutscene)
        if (highest > registered.size()) {
            AvalonSeats.sync(level.getServer(), highest);
            seatsBorrowed = true;
        }
        for (int i = 0; i < all.size(); i++) {
            ServerPlayer p = all.get(i);
            p.setGameMode(GameType.ADVENTURE);
            p.removeEffect(MobEffects.INVISIBILITY);
            p.removeEffect(MobEffects.BLINDNESS);
            game.seatPlayerAt(p, seatOf[i]);
            seated.add(p);
        }
    }

    /** Objek player yang berlaku sekarang (ia bisa keluar-masuk sejak cutscene dimulai); null kalau offline. */
    private static ServerPlayer live(ServerPlayer p) {
        return game == null ? null : game.getPlayer(p.getUUID());
    }

    /** Taruh player berdiri di {@code at}, menghadap (atau membelakangi) portal, tidak bisa bergerak. */
    private static void place(ServerPlayer p, Vec3 at, boolean faceGate) {
        Vec3 gate = AvalonPortal.GATE_CENTER;
        float yaw = (float) (Mth.atan2(-(gate.x - at.x), gate.z - at.z) * Mth.RAD_TO_DEG);
        place(p, at, faceGate ? yaw : Mth.wrapDegrees(yaw + 180f));
    }

    /** Biarkan player di tempatnya berdiri sekarang; hanya gerakannya yang dikunci. */
    private static void stay(ServerPlayer p) {
        game.releaseFromSeat(p);
        p.setGameMode(GameType.ADVENTURE);
        p.removeEffect(MobEffects.INVISIBILITY);
        p.removeEffect(MobEffects.BLINDNESS);
        game.lockMovement(p);
        locked.add(p);
    }

    /** Taruh player berdiri di {@code at} menghadap {@code yaw}, tidak bisa bergerak. */
    private static void place(ServerPlayer p, Vec3 at, float yaw) {
        game.releaseFromSeat(p);
        p.setGameMode(GameType.ADVENTURE);
        p.removeEffect(MobEffects.INVISIBILITY);
        p.removeEffect(MobEffects.BLINDNESS);
        p.getAbilities().flying = false;
        p.onUpdateAbilities();

        p.teleportTo(level, at.x, at.y, at.z, yaw, 0f);
        p.fallDistance = 0;
        game.lockMovement(p);
        locked.add(p);
    }

    private static Vec3 stairSpot(int index) {
        int x = STAIR_COLUMNS[index % STAIR_COLUMNS.length];
        int z = STAIR_ROWS[(index / STAIR_COLUMNS.length) % STAIR_ROWS.length];
        return new Vec3(x + 0.5, standY(x, z, STAIR_SCAN_Y), z + 0.5);
    }

    private static Vec3 midSpot(int index, int count) {
        double x = AvalonPortal.GATE_CENTER.x + (index - (count - 1) / 2.0) * SPACING;
        return new Vec3(x, standY(Mth.floor(x), Mth.floor(MID_Z), MID_SCAN_Y), MID_Z);
    }

    /** Ketinggian berdiri di kolom (x, z): di atas blok padat pertama dari {@code fromY} ke bawah. */
    private static double standY(int x, int z, int fromY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, fromY, z);
        for (int i = 0; i < 24; i++) {
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return pos.getY() + level.getBlockState(pos).getCollisionShape(level, pos).max(net.minecraft.core.Direction.Axis.Y);
            }
            pos.move(0, -1, 0);
        }
        return fromY;
    }

    private static void pillarsOff() {
        if (level == null) return;
        for (AvalonPillars.Site site : AvalonPillars.SITES) PillarBlock.setLit(level, site.pillarPos(), false);
    }

    /** Bebaskan para player dan akhiri cutscene di client. */
    private static void finish() {
        if (!running) return;
        for (ServerPlayer p : locked) {
            ServerPlayer now = live(p);
            game.unlockMovement(now != null ? now : p);
        }
        // Dihentikan sebelum perpisahan: yang masih duduk diturunkan dari kursi cutscene-nya
        for (ServerPlayer p : seated) {
            ServerPlayer now = live(p);
            if (now != null && !locked.contains(now)) game.releaseFromSeat(now);
        }
        // Busur reka ulang (tes di luar game; di dalam game inventory dibereskan cleanup)
        ServerPlayer bow = bowHolder == null ? null : live(bowHolder);
        if (bow != null) {
            bow.getInventory().clearOrCountMatchingItems(stack -> stack.is(Items.BOW), -1,
                    bow.inventoryMenu.getCraftSlots());
        }
        if (seatsBorrowed && level != null) {
            AvalonSeats.sync(level.getServer(), game.getRegisteredPlayers().size());
        }
        if (level != null) {
            for (ServerPlayer viewer : level.players()) AvalonNetwork.sendTo(viewer, AvalonNetwork.EndingStop.INSTANCE);
        }
        if (resetPillars) pillarsOff();
        reset();
    }

    /** Hentikan cutscene di tengah jalan. */
    public static boolean stop() {
        if (!running) return false;
        finish();
        return true;
    }

    /** Lupakan semua state (cutscene selesai / server berhenti). */
    public static void reset() {
        for (Task task : tasks) task.cancel();
        tasks.clear();
        locked.clear();
        seated.clear();
        seatsBorrowed = false;
        bowHolder = null;
        // Cincin portal kembali seperti semula: blok portal dicabut, bola kaca dipasang lagi
        if (level != null) AvalonGate.restore(level);
        level = null;
        game = null;
        running = false;
        resetPillars = false;
    }
}
