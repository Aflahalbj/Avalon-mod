package id.avalon.managers;

import id.avalon.block.BatteryRackBlock;
import id.avalon.block.BatteryRackBlockEntity;
import id.avalon.block.ModBlocks;
import id.avalon.block.PillarBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.AvalonItems;
import id.avalon.core.AvalonLog;
import id.avalon.core.Fx;
import id.avalon.core.Scheduler;
import id.avalon.core.Txt;
import id.avalon.entity.MannequinEntity;
import id.avalon.models.Role;
import id.avalon.world.AvalonPillars;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Misi baterai: tiap anggota tim mengambil satu baterai dari rak gudang lalu memasangnya di rak
 * salah satu pilar. Misi selesai begitu satu rak pilar penuh; kubu jahat bisa memasang baterainya
 * dalam mode sabotase. Kelas ini juga jadi aturan akses semua rak baterai selama game.
 */
public class BatteryMission implements BatteryRackBlock.Access {

    /** Rak gudang: berjajar dari -425 sampai -419 di y 199, z -476, menghadap utara, selalu penuh. */
    private static final int SOURCE_MIN_X = -425, SOURCE_MAX_X = -419, SOURCE_Y = 199, SOURCE_Z = -476;
    private static final Direction SOURCE_FACING = Direction.NORTH;

    /** Tag di item baterai: UUID player yang mengambilnya dari gudang. */
    private static final String KEY_OWNER = "battery_owner";

    private final GameManager game;

    private boolean active;
    /** Player yang sudah mengambil baterai dari gudang di misi ini. */
    private final Set<UUID> taken = new HashSet<>();
    /** Kubu jahat yang sedang dalam mode sabotase. */
    private final Set<UUID> sabotaging = new HashSet<>();
    private final Map<UUID, Long> lastToggle = new HashMap<>();
    /** Player yang baterainya sedang terpasang di rak pilar → apakah dipasang dalam mode sabotase. */
    private final Map<UUID, Boolean> placed = new HashMap<>();
    private static final long TOGGLE_COOLDOWN_TICKS = 10L;
    /** Indeks pilar (di {@link AvalonPillars#SITES}) yang sudah menyala karena misi sukses. */
    private final Set<Integer> completed = new HashSet<>();
    private int teamSize;
    /**
     * Pilar yang dipilih tim di misi ini: begitu ada baterai terpasang di satu rak pilar, rak pilar
     * lain ditutup. -1 = belum ada (semua rak pilar yang belum menyala masih terbuka).
     */
    private int chosenSite = -1;
    /** Bot player offline yang ikut misi (nama player → bot). */
    private final Map<String, Bot> bots = new LinkedHashMap<>();

    public BatteryMission(GameManager game) {
        this.game = game;
    }

    // ── Dunia ─────────────────────────────────────────────────────────────────

    private static ServerLevel level(MinecraftServer server) {
        return server == null ? null : server.getLevel(AvalonDimensions.AVALON);
    }

    private static boolean isSource(BlockPos pos) {
        return pos.getY() == SOURCE_Y && pos.getZ() == SOURCE_Z
                && pos.getX() >= SOURCE_MIN_X && pos.getX() <= SOURCE_MAX_X;
    }

    /** Indeks pilar yang raknya di {@code pos}, atau -1. */
    private static int siteOfRack(BlockPos pos) {
        List<AvalonPillars.Site> sites = AvalonPillars.SITES;
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i).rackPos().equals(pos)) return i;
        }
        return -1;
    }

    /** Pasang (ulang) rak gudang dalam keadaan penuh. */
    public static void placeSourceRacks(MinecraftServer server) {
        ServerLevel level = level(server);
        if (level == null) return;
        BlockState full = ModBlocks.BATTERY_RACK.get().fullState(SOURCE_FACING);
        for (int x = SOURCE_MIN_X; x <= SOURCE_MAX_X; x++) {
            BlockPos pos = new BlockPos(x, SOURCE_Y, SOURCE_Z);
            if (level.getBlockState(pos) != full) level.setBlock(pos, full, Block.UPDATE_ALL);
        }
    }

    // ── Jalannya misi ─────────────────────────────────────────────────────────

    /** Awal game / game dihentikan: semua pilar mati, semua rak pilar kosong & tertutup. */
    public void reset(MinecraftServer server) {
        active = false;
        taken.clear();
        sabotaging.clear();
        placed.clear();
        completed.clear();
        chosenSite = -1;
        releaseBots();

        ServerLevel level = level(server);
        if (level == null) return;
        placeSourceRacks(server);
        for (AvalonPillars.Site site : AvalonPillars.SITES) {
            PillarBlock.setLit(level, site.pillarPos(), false);
            closeRack(level, site);
        }
    }

    /** Mulai misi: gudang diisi penuh, rak tiap pilar yang belum menyala dibuka sebanyak anggota tim. */
    public void start(MinecraftServer server, int teamSize) {
        active = true;
        this.teamSize = teamSize;
        chosenSite = -1;
        taken.clear();
        sabotaging.clear();
        placed.clear();
        releaseBots();

        ServerLevel level = level(server);
        if (level == null) return;
        placeSourceRacks(server);
        List<AvalonPillars.Site> sites = AvalonPillars.SITES;
        for (int i = 0; i < sites.size(); i++) {
            if (completed.contains(i)) {
                closeRack(level, sites.get(i));
            } else {
                BlockPos rack = sites.get(i).rackPos();
                BatteryRackBlock.clearBatteries(level, rack);
                BatteryRackBlock.setOpenSlots(level, rack, teamSize);
            }
        }
    }

    /** Misi berakhir (selesai atau dibatalkan): semua rak pilar dikosongkan & ditutup. */
    public void stop(MinecraftServer server) {
        active = false;
        sabotaging.clear();
        placed.clear();
        chosenSite = -1;
        releaseBots();
        ServerLevel level = level(server);
        if (level == null) return;
        for (AvalonPillars.Site site : AvalonPillars.SITES) {
            closeRack(level, site);
        }
    }

    private static void closeRack(ServerLevel level, AvalonPillars.Site site) {
        BatteryRackBlock.clearBatteries(level, site.rackPos());
        BatteryRackBlock.setOpenSlots(level, site.rackPos(), 0);
    }

    public boolean isActive() {
        return active;
    }

    public boolean hasTaken(ServerPlayer player) {
        return taken.contains(player.getUUID());
    }

    public boolean isSabotaging(ServerPlayer player) {
        return sabotaging.contains(player.getUUID());
    }

    /** Baterai player ini sedang terpasang di rak pilar (dipasang sendiri atau oleh bot-nya). */
    public boolean hasPlaced(ServerPlayer player) {
        return placed.containsKey(player.getUUID());
    }

    /** Tandai pilar ini sudah menyala: raknya tidak dibuka lagi di misi berikutnya. */
    public void markCompleted(int site) {
        completed.add(site);
    }

    public int completedCount() {
        return completed.size();
    }

    /**
     * Kubu jahat klik kanan sambil memegang baterai: ganti mode normal / sabotase.
     * Hanya dia sendiri yang diberi tahu.
     *
     * @return true kalau kliknya dipakai untuk ini
     */
    public boolean toggleSabotage(ServerPlayer player) {
        if (!active || !game.isInMissionTeam(player)) return false;
        Role role = game.getRole(player);
        if (role == null || !role.isEvil()) return false;

        UUID id = player.getUUID();
        // Klik kanan yang ditahan berulang tiap beberapa tick: jangan sampai modenya bolak-balik
        long now = player.level().getGameTime();
        Long last = lastToggle.put(id, now);
        if (last != null && now - last < TOGGLE_COOLDOWN_TICKS) return true;

        boolean on = !sabotaging.remove(id);
        if (on) sabotaging.add(id);
        showModeLine(player);
        Fx.sound(player, on ? SoundEvents.BEACON_DEACTIVATE : SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, on ? 0.7f : 1.4f);
        return true;
    }

    /**
     * Baris actionbar kubu jahat. Saat memegang baterai: modenya sekarang. Setelah memasang:
     * baterai macam apa yang dia pasang. Belum mengambil baterai: tidak ada (null).
     */
    public Component modeLine(ServerPlayer player) {
        Boolean sabotaged = placed.get(player.getUUID());
        if (sabotaged != null) {
            return sabotaged
                    ? Txt.t("☠ Kamu telah memasang baterai yang disabotase", ChatFormatting.RED, ChatFormatting.BOLD)
                    : Txt.t("🔋 Kamu telah memasang baterai yang normal", ChatFormatting.GREEN, ChatFormatting.BOLD);
        }
        if (!player.getMainHandItem().is(ModBlocks.BATTERY.get())) return null;

        if (isSabotaging(player)) {
            return Txt.t("☠ Mode: SABOTASE", ChatFormatting.RED, ChatFormatting.BOLD)
                    .append(Txt.t(" | ", ChatFormatting.GRAY))
                    .append(Txt.t("Klik kanan untuk batal", ChatFormatting.YELLOW));
        }
        return Txt.t("🔋 Mode: NORMAL", ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Txt.t(" | ", ChatFormatting.GRAY))
                .append(Txt.t("Klik kanan untuk sabotase", ChatFormatting.RED));
    }

    /** Tampilkan baris actionbar-nya sekarang juga (kalau ada), tanpa menunggu putaran berikutnya. */
    private void showModeLine(ServerPlayer player) {
        Role role = game.getRole(player);
        if (role == null || !role.isEvil()) return;
        Component line = modeLine(player);
        if (line != null) Fx.actionBar(player, line);
    }

    // ── Bot (player offline) ──────────────────────────────────────────────────

    /** Lama bot boleh menempuh satu tujuan sebelum dipindahkan langsung ke sana (tick). */
    private static final int LEG_TIMEOUT_TICKS = 90 * 20;
    /** Seberapa sering kemajuan bot dicek, dan lama ia "menembus" kalau ternyata tersangkut (tick). */
    private static final int PROGRESS_CHECK_TICKS = 100;
    private static final int GLIDE_TICKS = 60;
    private static final double ARRIVE_DISTANCE = 1.3;
    /**
     * Kecepatan bot = anggota tim lain (player berjalan tanpa lari, ±4.3 block/detik).
     * Mob memakai atributnya dua kali (sebagai kecepatan dan sebagai dorongan maju), jadi nilai
     * atributnya akar dari hasil kali kecepatan player (0.1) dan dorongan majunya (0.98).
     */
    private static final double WALK_SPEED_ATTRIBUTE = Math.sqrt(0.1 * 0.98);
    /** Kecepatan yang sama dalam block per tick, dipakai saat bot bergerak lurus tanpa pathfinding. */
    private static final double GLIDE_SPEED = 4.317 / 20.0;
    /** Bot dianggap tersangkut kalau dalam satu pengecekan berpindah kurang dari ini (block). */
    private static final double STUCK_DISTANCE = 1.5;

    private enum BotStage { TO_SOURCE, WAITING, TO_PILLAR, DONE }

    /** Mannequin player offline yang ikut misi: ambil baterai, tunggu pilar dipilih, pasang di sana. */
    private static final class Bot {
        final String name;
        final UUID owner;
        final MannequinEntity entity;
        BotStage stage;
        ItemStack battery = ItemStack.EMPTY;
        /** Rak yang sedang dituju dan tempat berdiri di depannya. */
        BlockPos rack;
        Vec3 goal;
        int legTicks;
        /** Posisi bot di pengecekan kemajuan terakhir. */
        Vec3 mark;
        int glide;

        Bot(String name, UUID owner, MannequinEntity entity) {
            this.name = name;
            this.owner = owner;
            this.entity = entity;
        }

        void head(BlockPos rack, Direction facing) {
            this.rack = rack;
            this.goal = Vec3.atBottomCenterOf(rack.relative(facing));
            this.legTicks = 0;
            this.mark = entity.position();
            this.glide = 0;
        }
    }

    /**
     * Anggota tim yang offline diwakili bot-nya: mannequin-nya berdiri dari kursi dan ikut misi.
     * Bot tidak pernah sabotase.
     */
    public void addBot(String name) {
        if (!active || bots.containsKey(name)) return;
        UUID owner = game.offlineUuid(name);
        MannequinEntity entity = game.standOfflineMannequin(name);
        if (owner == null || entity == null) return;
        if (entity.level().dimension() != AvalonDimensions.AVALON) {
            AvalonLog.warn("Bot " + name + " tidak berada di dimensi Avalon, tidak ikut misi.");
            return;
        }

        entity.setWalking(true);
        AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(WALK_SPEED_ATTRIBUTE);
        Bot bot = new Bot(name, owner, entity);
        // Baterainya sudah terpasang sebelum dia offline: tinggal menunggu
        bot.stage = placed.containsKey(owner) ? BotStage.DONE : BotStage.TO_SOURCE;
        bots.put(name, bot);
        AvalonLog.info("Bot " + name + " ikut misi (" + bot.stage + ").");
    }

    /** Posisi bot milik player ini sekarang, atau null kalau dia tidak punya bot di misi ini. */
    public Vec3 botPosition(String name) {
        Bot bot = bots.get(name);
        return bot == null ? null : bot.entity.position();
    }

    public boolean botCarriesBattery(String name) {
        Bot bot = bots.get(name);
        return bot != null && !bot.battery.isEmpty();
    }

    /**
     * Anggota tim kembali di tengah misi: bot-nya berhenti dan baterai yang sedang dibawa bot pindah
     * ke tangannya. Kalau dia belum punya baterai terpasang maupun di tangan, dia boleh mengambil lagi.
     * Satu player tetap hanya punya satu baterai: yang sudah terpasang (olehnya atau oleh bot-nya)
     * tidak boleh ditambah baterai kedua.
     */
    public void onReturn(ServerPlayer player) {
        Bot bot = bots.remove(player.getGameProfile().getName());
        UUID id = player.getUUID();
        if (placed.containsKey(id)) {
            taken.add(id);
        } else if (bot != null && !bot.battery.isEmpty()) {
            taken.add(id);
            player.getInventory().setItem(0, bot.battery);
        } else {
            taken.remove(id);
        }
    }

    /** Semua bot kembali jadi patung di tempatnya (didudukkan lagi oleh GameManager). */
    private void releaseBots() {
        for (Bot bot : bots.values()) {
            if (!bot.entity.isAlive()) continue;
            bot.entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            bot.entity.setWalking(false);
        }
        bots.clear();
    }

    /** Dipanggil tiap tick server. */
    public void tick(MinecraftServer server) {
        if (!active || bots.isEmpty()) return;
        ServerLevel level = level(server);
        if (level == null) return;
        for (Bot bot : List.copyOf(bots.values())) {
            if (!active) return;
            if (!bot.entity.isAlive()) {
                bots.remove(bot.name);
                continue;
            }
            tickBot(server, level, bot);
        }
    }

    private void tickBot(MinecraftServer server, ServerLevel level, Bot bot) {
        switch (bot.stage) {
            case TO_SOURCE -> {
                if (bot.goal == null && !headToSource(level, bot)) return;
                if (!walk(bot)) return;

                // Sampai di gudang: ambil satu baterai (kalau lubangnya keburu kosong, cari rak lain)
                BlockState state = level.getBlockState(bot.rack);
                int slot = firstSlot(state, BatteryRackBlock.Slot.BATTERY);
                bot.goal = null;
                if (slot < 0) return;
                level.setBlock(bot.rack, state.setValue(BatteryRackBlock.SLOTS.get(slot), BatteryRackBlock.Slot.EMPTY), Block.UPDATE_ALL);
                level.playSound(null, bot.rack, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, 1.0f, 0.8f);
                bot.battery = new ItemStack(ModBlocks.BATTERY.get());
                taken.add(bot.owner);
                AvalonItems.setTag(bot.battery, KEY_OWNER, bot.owner.toString());
                bot.entity.setItemInHand(InteractionHand.MAIN_HAND, bot.battery.copy());
                bot.entity.swing(InteractionHand.MAIN_HAND);
                bot.stage = BotStage.WAITING;
                AvalonLog.info("Bot " + bot.name + " mengambil baterai.");
            }
            case WAITING -> {
                idle(level, bot);
                // Menunggu sampai ada yang memasang baterai pertama, lalu ikut ke pilar itu
                if (chosenSite >= 0) {
                    AvalonPillars.Site site = AvalonPillars.SITES.get(chosenSite);
                    bot.head(site.rackPos(), site.facing());
                    bot.stage = BotStage.TO_PILLAR;
                }
            }
            case TO_PILLAR -> {
                if (chosenSite < 0) {
                    // Baterai pertamanya diambil lagi: tunggu pilihan berikutnya
                    bot.entity.getNavigation().stop();
                    bot.goal = null;
                    bot.stage = BotStage.WAITING;
                    return;
                }
                if (!walk(bot)) return;

                int site = chosenSite;
                BlockState state = level.getBlockState(bot.rack);
                int slot = firstSlot(state, BatteryRackBlock.Slot.EMPTY);
                if (slot < 0) return;
                if (level.getBlockEntity(bot.rack) instanceof BatteryRackBlockEntity rack) rack.put(slot, bot.battery);
                level.setBlock(bot.rack, state.setValue(BatteryRackBlock.SLOTS.get(slot), BatteryRackBlock.Slot.BATTERY), Block.UPDATE_ALL);
                level.playSound(null, bot.rack, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 0.7f, 1.4f + slot * 0.08f);
                bot.battery = ItemStack.EMPTY;
                bot.entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                bot.entity.swing(InteractionHand.MAIN_HAND);
                bot.goal = null;
                bot.stage = BotStage.DONE;
                placed.put(bot.owner, false);
                AvalonLog.info("Bot " + bot.name + " memasang baterai di pilar " + site + ".");
                afterPut(server, site);
            }
            case DONE -> idle(level, bot);
        }
    }

    /** Tuju rak gudang terdekat yang masih ada baterainya. */
    private boolean headToSource(ServerLevel level, Bot bot) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = SOURCE_MIN_X; x <= SOURCE_MAX_X; x++) {
            BlockPos pos = new BlockPos(x, SOURCE_Y, SOURCE_Z);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BatteryRackBlock) || BatteryRackBlock.batteries(state) == 0) continue;
            double distance = pos.distToCenterSqr(bot.entity.position());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        if (best == null) return false;
        bot.head(best, SOURCE_FACING);
        return true;
    }

    private static int firstSlot(BlockState state, BatteryRackBlock.Slot wanted) {
        if (!(state.getBlock() instanceof BatteryRackBlock)) return -1;
        for (int slot = 0; slot < BatteryRackBlock.SLOT_COUNT; slot++) {
            if (state.getValue(BatteryRackBlock.SLOTS.get(slot)) == wanted) return slot;
        }
        return -1;
    }

    /** Diam di tempat sambil memperhatikan player terdekat. */
    private static void idle(ServerLevel level, Bot bot) {
        bot.entity.getNavigation().stop();
        Player near = level.getNearestPlayer(bot.entity, 12.0);
        if (near != null) bot.entity.getLookControl().setLookAt(near, 30f, 30f);
    }

    /**
     * Jalankan bot ke tujuannya. Bawaannya memakai pathfinding biasa; kalau dalam beberapa detik
     * tidak mendekat (tersangkut, jalannya buntu) ia bergerak lurus menembus halangan sebentar,
     * dan kalau terlalu lama dipindahkan langsung. Jadi ia pasti sampai.
     *
     * @return true kalau sudah sampai
     */
    private static boolean walk(Bot bot) {
        MannequinEntity entity = bot.entity;
        Vec3 pos = entity.position();
        double dx = bot.goal.x - pos.x;
        double dz = bot.goal.z - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        if (flat < ARRIVE_DISTANCE && Math.abs(bot.goal.y - pos.y) < 2.5) {
            endGlide(bot);
            entity.getNavigation().stop();
            entity.getLookControl().setLookAt(Vec3.atCenterOf(bot.rack));
            return true;
        }
        if (++bot.legTicks > LEG_TIMEOUT_TICKS) {
            endGlide(bot);
            entity.teleportTo(bot.goal.x, bot.goal.y, bot.goal.z);
            return false;
        }

        if (bot.glide > 0) {
            // Bergerak lurus ke tujuan mengikuti permukaan tanah; tembok yang terlalu tinggi ditembus
            double step = Math.min(GLIDE_SPEED, flat);
            double x = pos.x + dx / flat * step;
            double z = pos.z + dz / flat * step;
            double ground = groundY(entity.level(), x, pos.y, z);
            entity.setPos(x, pos.y + Mth.clamp(ground - pos.y, -0.5, 0.5), z);
            entity.setFacing((float) Math.toDegrees(Mth.atan2(-dx, dz)));
            if (--bot.glide == 0) {
                endGlide(bot);
                bot.mark = entity.position();
            }
            return false;
        }

        if (entity.getNavigation().isDone() && bot.legTicks % 20 == 1) {
            entity.getNavigation().moveTo(bot.goal.x, bot.goal.y, bot.goal.z, 1.0);
        }
        if (bot.legTicks % PROGRESS_CHECK_TICKS == 0) {
            // Hampir tidak berpindah sejak pengecekan terakhir: tersangkut atau tidak ada jalan
            if (pos.distanceTo(bot.mark) < STUCK_DISTANCE) {
                entity.getNavigation().stop();
                entity.setNoGravity(true);
                entity.noPhysics = true;
                entity.setDeltaMovement(Vec3.ZERO);
                bot.glide = GLIDE_TICKS;
            }
            bot.mark = pos;
        }
        return false;
    }

    /**
     * Tinggi pijakan terdekat di kolom (x, z): dicari dari tinggi sekarang, naik paling banyak 3 block
     * dan turun paling banyak 8. Kalau tidak ada (di dalam tembok / tebing tinggi), tinggi sekarang dipertahankan.
     */
    private static double groundY(Level level, double x, double y, double z) {
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        int base = Mth.floor(y + 0.01);
        for (int offset : GROUND_SEARCH) {
            feet.set(Mth.floor(x), base + offset, Mth.floor(z));
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
            if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
            BlockPos below = feet.below();
            VoxelShape floor = level.getBlockState(below).getCollisionShape(level, below);
            if (floor.isEmpty()) continue;
            return below.getY() + floor.max(Direction.Axis.Y);
        }
        return y;
    }

    private static final int[] GROUND_SEARCH = {0, 1, -1, 2, -2, 3, -3, -4, -5, -6, -7, -8};

    private static void endGlide(Bot bot) {
        if (!bot.entity.noPhysics) return;
        bot.glide = 0;
        bot.entity.noPhysics = false;
        bot.entity.setNoGravity(false);
    }

    // ── Aturan rak (BatteryRackBlock.Access) ──────────────────────────────────

    /**
     * Di luar game rak bebas dipakai, misalnya saat membangun. Selama game, aturan misi berlaku untuk
     * semua player game (di mana pun mereka tercatat berada) dan siapa pun yang ada di dimensi Avalon.
     */
    private boolean governs(ServerPlayer player) {
        return game.isGameRunning()
                && (game.isOneSlot(player) || player.level().dimension() == AvalonDimensions.AVALON);
    }

    private boolean onMission(ServerPlayer player) {
        return active && game.isInMissionTeam(player);
    }

    private static void deny(ServerPlayer player, String message) {
        Fx.actionBar(player, Txt.t(message, ChatFormatting.RED));
    }

    @Override
    public boolean mayTake(ServerPlayer player, BlockPos pos, int slot, ItemStack battery) {
        if (!governs(player)) return true;
        if (!onMission(player)) return false;

        if (isSource(pos)) {
            if (taken.contains(player.getUUID())) {
                deny(player, "Kamu sudah mengambil baterai di misi ini.");
                return false;
            }
        } else {
            if (siteOfRack(pos) < 0) return false;
            // Dari rak pilar hanya boleh mengambil baterai sendiri (misalnya salah pilar)
            if (!player.getUUID().toString().equals(AvalonItems.getTag(battery, KEY_OWNER))) {
                deny(player, "Itu bukan bateraimu.");
                return false;
            }
        }
        return player.getInventory().getItem(0).isEmpty();
    }

    @Override
    public ItemStack onTaken(ServerPlayer player, BlockPos pos, int slot, ItemStack battery) {
        if (!governs(player)) return battery;
        if (isSource(pos)) {
            taken.add(player.getUUID());
            AvalonItems.setTag(battery, KEY_OWNER, player.getUUID().toString());
        } else {
            placed.remove(player.getUUID());
        }
        // Setelah baterainya masuk inventory & blockstate rak diperbarui:
        // tampilkan actionbar, dan buka lagi pilar lain kalau rak pilar ini jadi kosong
        MinecraftServer server = player.getServer();
        // (tick berikutnya: server.execute dari thread server jalan saat itu juga)
        Scheduler.next(() -> {
            showModeLine(player);
            reopenIfEmpty(server);
        });
        return battery;
    }

    @Override
    public boolean mayPut(ServerPlayer player, BlockPos pos, int slot, ItemStack battery) {
        if (!governs(player)) return true;
        if (!onMission(player)) return false;
        if (isSource(pos)) {
            deny(player, "Baterai tidak bisa dikembalikan ke sini. Pasang di rak pilar.");
            return false;
        }
        // Satu baterai per orang: yang lama harus diambil dulu sebelum memasang lagi
        if (placed.containsKey(player.getUUID())) {
            deny(player, "Bateraimu sudah terpasang.");
            return false;
        }
        int site = siteOfRack(pos);
        return site >= 0 && !completed.contains(site) && (chosenSite < 0 || site == chosenSite);
    }

    @Override
    public void onPut(ServerPlayer player, BlockPos pos, int slot) {
        if (!governs(player) || !active) return;
        int site = siteOfRack(pos);
        if (site < 0) return;

        // Mode saat dipasang yang berlaku (setelah terpasang, modenya tidak bisa diganti lagi)
        placed.put(player.getUUID(), sabotaging.contains(player.getUUID()));
        showModeLine(player);

        afterPut(player.getServer(), site);
    }

    /**
     * Sebuah baterai baru saja terpasang di rak pilar {@code site} (oleh player atau bot).
     * Baterai pertama mengunci pilihan pilar; rak yang penuh menyelesaikan misi.
     */
    private void afterPut(MinecraftServer server, int site) {
        // Rak pilar selalu di dimensi Avalon (jangan bergantung pada dimensi yang tercatat di player)
        ServerLevel level = level(server);
        if (level == null) return;
        BlockPos pos = AvalonPillars.SITES.get(site).rackPos();

        if (chosenSite < 0) {
            chosenSite = site;
            List<AvalonPillars.Site> sites = AvalonPillars.SITES;
            for (int i = 0; i < sites.size(); i++) {
                if (i != site) BatteryRackBlock.setOpenSlots(level, sites.get(i).rackPos(), 0);
            }
        }
        if (!BatteryRackBlock.isFull(level.getBlockState(pos))) return;

        // Rak penuh: hitung baterai di rak ini yang dipasang dalam mode sabotase
        int sabotages = 0;
        if (level.getBlockEntity(pos) instanceof BatteryRackBlockEntity rack) {
            for (ItemStack stack : rack.items()) {
                String owner = AvalonItems.getTag(stack, KEY_OWNER);
                if (owner != null && Boolean.TRUE.equals(placed.get(UUID.fromString(owner)))) sabotages++;
            }
        }
        active = false;
        game.completeBatteryMission(site, sabotages);
    }

    /** Baterai terakhir di rak pilar yang dipilih diambil lagi: pilar lain dibuka kembali. */
    private void reopenIfEmpty(MinecraftServer server) {
        if (!active || chosenSite < 0) return;
        ServerLevel level = level(server);
        if (level == null) return;
        List<AvalonPillars.Site> sites = AvalonPillars.SITES;
        if (BatteryRackBlock.batteries(level.getBlockState(sites.get(chosenSite).rackPos())) > 0) return;

        chosenSite = -1;
        for (int i = 0; i < sites.size(); i++) {
            if (!completed.contains(i)) BatteryRackBlock.setOpenSlots(level, sites.get(i).rackPos(), teamSize);
        }
    }

    @Override
    public boolean mayEdit(ServerPlayer player, BlockPos pos) {
        return !governs(player);
    }
}
