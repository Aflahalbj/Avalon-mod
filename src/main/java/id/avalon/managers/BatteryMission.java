package id.avalon.managers;

import id.avalon.block.BatteryRackBlock;
import id.avalon.block.BatteryRackBlockEntity;
import id.avalon.block.ModBlocks;
import id.avalon.block.PillarBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.AvalonItems;
import id.avalon.core.Fx;
import id.avalon.core.Txt;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
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
        taken.clear();
        sabotaging.clear();
        placed.clear();

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
        // Actionbar baru bisa membaca tangannya setelah baterainya masuk inventory
        ServerLevel level = player.serverLevel();
        level.getServer().execute(() -> showModeLine(player));
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
        int site = siteOfRack(pos);
        return site >= 0 && !completed.contains(site);
    }

    @Override
    public void onPut(ServerPlayer player, BlockPos pos, int slot) {
        if (!governs(player) || !active) return;
        int site = siteOfRack(pos);
        if (site < 0) return;

        // Mode saat dipasang yang berlaku (setelah terpasang, modenya tidak bisa diganti lagi)
        placed.put(player.getUUID(), sabotaging.contains(player.getUUID()));
        showModeLine(player);

        // Rak pilar selalu di dimensi Avalon (jangan bergantung pada dimensi yang tercatat di player)
        ServerLevel level = level(player.getServer());
        if (level == null || !BatteryRackBlock.isFull(level.getBlockState(pos))) return;

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

    @Override
    public boolean mayEdit(ServerPlayer player, BlockPos pos) {
        return !governs(player);
    }
}
