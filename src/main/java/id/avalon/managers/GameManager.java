package id.avalon.managers;

import com.mojang.authlib.GameProfile;
import id.avalon.AvalonMod;
import id.avalon.block.PillarBlock;
import id.avalon.core.AvalonDimensions;
import id.avalon.core.AvalonItems;
import id.avalon.core.AvalonLog;
import id.avalon.core.Fx;
import id.avalon.core.PlayerScale;
import id.avalon.core.Scheduler;
import id.avalon.core.Task;
import id.avalon.core.Txt;
import id.avalon.cutscene.PortalCutscene;
import id.avalon.cutscene.EndingCutscene;
import id.avalon.cutscene.EndingTimeline;
import id.avalon.cutscene.KingRouletteTimeline;
import id.avalon.cutscene.LadyTimeline;
import id.avalon.cutscene.PillarTimeline;
import id.avalon.cutscene.PortalTimeline;
import id.avalon.cutscene.RoleShuffleTimeline;
import id.avalon.entity.MannequinEntity;
import id.avalon.entity.ModEntities;
import id.avalon.gui.AvalonMenu;
import id.avalon.gui.TeamSelectionGUI;
import id.avalon.models.Role;
import id.avalon.network.AvalonNetwork;
import id.avalon.world.AvalonPillars;
import id.avalon.world.AvalonPortal;
import id.avalon.world.AvalonSeats;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

public class GameManager {

    private final List<String> registeredPlayers = new ArrayList<>();
    private final List<Task> delayedTasks = new ArrayList<>();

    private boolean cutsceneEnabled  = true;
    private boolean cutsceneRunning  = false;
    private boolean gameRunning      = false;

    //timer
    private int revealSeconds = 20;
    private int votingSeconds = 600;
    private int discussionSeconds = 600;
    private int evilDiscussionSeconds = 600;

    // Flag: game sendiri sedang menurunkan player dari kursinya — dipakai CutsceneListener
    // supaya eject dari avalon_seat tidak di-cancel
    private boolean dismountAllowed = false;
    private int currentRevealPhase = -1;
    /** Fase perkenalan sudah lewat: yang masuk lagi diberi tahu lewat chat siapa yang boleh ia kenali. */
    private boolean revealFinished = false;

    private String currentRevealLabel = "";
    private int currentRevealSeconds = -1;

    private Task countdownTask;
    // Task countdown reveal — supaya bisa di-cancel saat /stopgame
    private Task revealCountdownTask;
    // Task action bar "Menunggu raja memilih tim"
    private Task teamSelectionActionBarTask;
    // VotingManager — diinisialisasi setelah mod siap
    private VotingManager votingManager;

    private final Map<UUID, Float> lockedYaw   = new HashMap<>();
    private final Map<UUID, Float> lockedPitch = new HashMap<>();
    private final Set<UUID> movementLocked = new HashSet<>();
    /** Posisi jangkar player yang gerakannya dikunci (pengganti cancel PlayerMoveEvent di server). */
    private final Map<UUID, Vec3> movementAnchors = new HashMap<>();
    private final Map<UUID, Role>  playerRoles = new HashMap<>();
    /** UUID → nama player yang mendapat role (untuk referensi saat player offline). */
    private final Map<UUID, String> roleNames = new HashMap<>();

    // ── King (Raja) mechanism ─────────────────────────────────────────────────
    /** Urutan player untuk rotasi Raja. Diset saat game dimulai dan tidak berubah. */
    private final List<String> kingOrder = new ArrayList<>();
    /** Index di kingOrder yang saat ini menjadi Raja. */
    private int currentKingIndex = -1;
    /** Animasi pemilihan raja pertama masih berjalan: rajanya belum boleh ketahuan. */
    private boolean kingRouletteRunning = false;
    /** Jumlah misi sukses + 1: tiga misi sukses membuka fase Assassin. */
    private int currentMission = 1;
    /** Nomor ronde (1-5), naik tiap misi selesai; menentukan ukuran tim dan aturan 2 sabotase. */
    private int currentRound = 1;
    private int evilMissionFails = 0;
    /** Session pemilihan tim per Raja (UUID raja -> list nama yang sudah dipilih). */
    private final Map<UUID, List<String>> teamSelectionSessions = new HashMap<>();
    /** Raja sedang memegang Buku Pemilihan Tim (dari diumumkan sampai timnya dikonfirmasi). */
    private boolean teamSelectionActive = false;
    /** Nama → UUID semua player terdaftar, dicatat saat game dimulai (tetap berlaku saat ia offline). */
    private final Map<String, UUID> rosterUuids = new HashMap<>();
    /** Tag di data player: ia sedang ikut game (untuk yang keluar lalu baru kembali setelah game selesai). */
    private static final String IN_GAME_TAG = "avalon_in_game";

    // ── Mission state ─────────────────────────────────────────────────────────
    /** Task untuk actionbar kubu jahat di fase misi. */
    private Task missionEvilActionBarTask;
    /** Apakah misi sudah berakhir (dicegah double-finish). */
    private boolean missionActive = false;
    /** Tim yang sedang menjalankan misi (nama player). */
    private List<String> currentMissionTeam = new ArrayList<>();
    /** Rak pilar sudah penuh dan animasinya sedang berjalan: hasil misi tinggal diumumkan. */
    private boolean missionResolving = false;
    /** Aturan & keadaan misi baterai (gudang, rak pilar, mode sabotase). */
    private final BatteryMission batteryMission = new BatteryMission(this);
    /** Jumlah baterai sabotase di misi terakhir. */
    private int sabotageCount = 0;

    // ── Offline player handling ───────────────────────────────────────────────
    /** Grace period (detik) sebelum raja/assassin auto-diganti saat offline. */
    private static final int OFFLINE_GRACE_SECONDS = 90;
    /** Grace task raja offline → auto-rotasi. */
    private Task kingOfflineGraceTask = null;
    /** Grace task assassin offline (fase bow) → auto-kubu-baik-menang. */
    private Task assassinOfflineGraceTask = null;
    /** True saat fase bow assassin aktif (setelah endAssassinationDiscussion). */
    private boolean assassinBowActive = false;
    /** Mannequin yang dispawn untuk player offline (nama → entity). */
    private final Map<String, Entity> offlineMannequins = new HashMap<>();
    private final Map<String, OfflineMannequinData> offlinePlayerRefs = new HashMap<>();

    // ── Discussion state ──────────────────────────────────────────────────────
    private static final String SKIP_TEXTURE =
        "http://textures.minecraft.net/texture/65a84e6394baf8bd795fe747efc582cde9414fccf2f1c8608f1be18c0e079138";
    private Task discussionTask;
    /** UUID player yang sudah vote skip di fase diskusi. */
    private final Set<UUID> discussionSkipVotes = new HashSet<>();
    private boolean discussionActive = false;
    private boolean discussionAfterSuccess = false;
    /** ArmorStand floating head per player yang sudah vote skip diskusi. */
    private final Map<UUID, ArmorStand> discussionSkipHeads = new HashMap<>();
    private Task discussionHeadAnimTask;

    // ── Lady of the Lake ──────────────────────────────────────────────────────
    /** AUTO = hanya untuk game berisi {@link #LADY_AUTO_MIN_PLAYERS} player atau lebih. */
    public enum LadyMode { AUTO, ON, OFF }

    private static final int LADY_AUTO_MIN_PLAYERS = 7;
    /** Tag key untuk item Lady of the Lake. */
    public static final String KEY_LADY_TOKEN = "lady_token";
    private LadyMode ladyMode = LadyMode.AUTO;
    private int ladySeconds = 60;
    /** Pemegang Lady saat ini; null = game ini tidak memakai Lady of the Lake. */
    private String ladyHolder = null;
    /** Yang pernah memegang Lady: tidak bisa diperiksa lagi. */
    private final List<String> ladyPastHolders = new ArrayList<>();
    /** Pemegang sedang memilih siapa yang diperiksa. */
    private boolean ladyActive = false;
    /** Target sudah dipilih dan animasi pemeriksaannya sedang berjalan. */
    private boolean ladyResolving = false;
    /** Bagian awal {@link #ladyResolving}: Lady belum berpindah dari pemegang lama ke targetnya. */
    private boolean ladyLeaving = false;
    /** Yang sudah diklik pemegang di GUI tapi belum dikonfirmasi; dipakai kalau waktunya habis. */
    private String ladySelection = null;
    private boolean ladyAfterSuccess = false;
    private Task ladyTask;
    /** Hasil pemeriksaan tiap pemegang (nama → hasil), dikirim ulang kalau ia masuk lagi. */
    private final Map<String, List<LadyResult>> ladyResults = new HashMap<>();

    private record LadyResult(String target, boolean evil) {}

    // ── Assassination state ───────────────────────────────────────────────────
    /** Apakah fase assassination sedang aktif. */
    private boolean assassinationActive = false;
    /** Task countdown fase assassination. */
    private Task assassinationTask;
    /** UUID player yang sudah vote skip di fase assassination. */
    private final Set<UUID> assassinationSkipVotes = new HashSet<>();
    /** ArmorStand floating head per kubu jahat yang vote skip assassination. */
    private final Map<UUID, ArmorStand> assassinationSkipHeads = new HashMap<>();
    /** Task animasi floating head assassination. */
    private Task assassinationHeadAnimTask;
    /** Tag key untuk bow assassin. */
    public static final String ASSASSIN_BOW_KEY = "assassin_bow";
    /** Apakah assassin sudah menembak (untuk prevent double trigger). */
    private boolean assassinShotFired = false;
    /** Panah assassin yang sedang terbang; dipantau supaya panah yang tidak pernah mendarat tetap dihitung. */
    private AbstractArrow assassinArrow;
    private int assassinArrowTicks;
    /** Panah yang belum mengenai apa pun selama ini dianggap meleset (tick). */
    private static final int ASSASSIN_ARROW_TIMEOUT = 200;
    /** Setelan PvP server sebelum game, dikembalikan saat game selesai. */
    private boolean pvpBeforeGame = true;

    // ── Item tag keys ─────────────────────────────────────────────────────────
    public static final String KEY_DISCUSSION_SKIP    = "discussion_skip";
    public static final String KEY_ASSASSINATION_SKIP = "assassination_skip";

    // ── Koordinat ────────────────────────────────────────────────────────────

    /** Kursi para player: crimson slab melingkar di dimensi Avalon (lihat AvalonSeats). */
    private static final int[][] PLAYER_SLAB_POSITIONS = AvalonSeats.OFFSETS;

    /** Jeda dari player didudukkan sampai game lanjut: layar loading dimensi + animasi buka mata. */
    private static final long WAKE_TICKS_AFTER_TRAVEL = 200L;
    /** Sama, tapi tanpa pindah dimensi (tidak ada layar loading). */
    private static final long WAKE_TICKS = 110L;

    /**
     * Tinggi ArmorStand kursi dari dasar blok slab. Player yang duduk berada 0.35 di bawah kursinya
     * dan pantatnya ~0.58 di atas posisinya, jadi 0.27 pas menempel di permukaan bottom slab (0.5).
     */
    private static final double SEAT_HEIGHT = 0.27;

    // ── Constructor ──────────────────────────────────────────────────────────

    public void setVotingManager(VotingManager votingManager) {
        this.votingManager = votingManager;
    }

    public VotingManager getVotingManager() {
        return votingManager;
    }

    public int getRevealSeconds() {
        return revealSeconds;
    }

    public void setRevealSeconds(int revealSeconds) {
        this.revealSeconds = revealSeconds;
    }

    public int getVotingSeconds() {
        return votingSeconds;
    }

    public void setVotingSeconds(int votingSeconds) {
        this.votingSeconds = votingSeconds;
    }

    public int getDiscussionSeconds() {
        return discussionSeconds;
    }

    public void setDiscussionSeconds(int discussionSeconds) {
        this.discussionSeconds = discussionSeconds;
    }

    public int getEvilDiscussionSeconds() {
        return evilDiscussionSeconds;
    }

    public void setEvilDiscussionSeconds(int evilDiscussionSeconds) {
        this.evilDiscussionSeconds = evilDiscussionSeconds;
    }

    public int getLadySeconds() {
        return ladySeconds;
    }

    public void setLadySeconds(int ladySeconds) {
        this.ladySeconds = ladySeconds;
    }

    public GameManager() {
    }

    /**
     * Dipanggil setiap tick server (pengganti BukkitRunnable timer 1 tick di constructor plugin).
     * Camera lock + movement lock + step height dekat green wool.
     */
    public void tick() {
        MinecraftServer server = server();
        if (server == null) return;

        if (!isFrozen()) {
            // Bot player offline yang ikut misi
            batteryMission.tick(server);
            watchAssassinArrow();
        }

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {

            // Camera lock (sinkronkan rotasi server; client mengunci sendiri via packet)
            if (isCameraLocked(p)) {
                float yaw = getLockedYaw(p);
                float pitch = getLockedPitch(p);
                p.setYRot(yaw);
                p.setXRot(pitch);
                p.setYHeadRot(yaw);
            }

            // Movement lock (fallback server-side)
            enforceMovementLock(p);
            enforceOneSlot(p);

            // Green wool step height (hanya pemain game; player lain di server tidak disentuh)
            if (!isOneSlot(p)) continue;
            AttributeInstance step = p.getAttribute(ForgeMod.STEP_HEIGHT_ADDITION.get());

            if (step == null)
                continue;

            boolean nearGreenWool = false;
            Vec3 loc = p.position();

            for (int x = -1; x <= 1 && !nearGreenWool; x++) {
                for (int y = -1; y <= 2 && !nearGreenWool; y++) {
                    for (int z = -1; z <= 1; z++) {

                        BlockPos pos = BlockPos.containing(loc.x + x, loc.y + y, loc.z + z);
                        if (p.level().getBlockState(pos).is(Blocks.GREEN_WOOL)) {
                            nearGreenWool = true;
                            break;
                        }
                    }
                }
            }

            // Plugin: STEP_HEIGHT 10.0 dekat green wool, 0.6 normal. Forge: tambahan dari 0.6.
            step.setBaseValue(nearGreenWool ? 10.0 - 0.6 : 0.0);
        }
    }

    private static void resetStepHeight(ServerPlayer p) {
        AttributeInstance step = p.getAttribute(ForgeMod.STEP_HEIGHT_ADDITION.get());
        if (step != null) step.setBaseValue(0.0);
    }

    /**
     * Game berjalan tapi tidak ada satu pun pemainnya yang online: semua timer dibekukan
     * (lihat Scheduler#setPaused) sampai ada yang masuk lagi.
     */
    public boolean isFrozen() {
        return gameRunning && getOnlinePlayers().isEmpty();
    }

    /**
     * Jadwalkan kelanjutan game. Task-nya ikut dibatalkan saat game dihentikan, jadi tidak bisa
     * meletus di game berikutnya.
     */
    public Task later(long delay, Runnable action) {
        delayedTasks.removeIf(Task::isCancelled);
        Task task = Scheduler.later(delay, () -> {
            if (gameRunning) action.run();
        });
        delayedTasks.add(task);
        return task;
    }

    // ── Inventory 1 slot ──────────────────────────────────────────────────────

    /** Selama game, inventory player terdaftar hanya slot hotbar pertama. */
    public boolean isOneSlot(Player player) {
        return gameRunning && registeredPlayers.contains(player.getGameProfile().getName());
    }

    private void syncOneSlot(ServerPlayer player) {
        AvalonNetwork.sendTo(player, new AvalonNetwork.OneSlot(isOneSlot(player)));
    }

    private void enforceOneSlot(ServerPlayer p) {
        if (!isOneSlot(p)) return;
        Inventory inv = p.getInventory();
        if (inv.selected != 0) setHeldItemSlot(p, 0);
        // Barang yang nyasar ke slot lain: pindah ke slot 0 kalau kosong, selain itu dibuang
        for (int i = 1; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            inv.setItem(i, ItemStack.EMPTY);
            if (inv.getItem(0).isEmpty()) inv.setItem(0, stack);
        }
    }

    private void enforceMovementLock(ServerPlayer p) {
        if (!movementLocked.contains(p.getUUID())) return;
        if (p.isPassenger()) {
            movementAnchors.remove(p.getUUID());
            return;
        }
        Vec3 anchor = movementAnchors.get(p.getUUID());
        if (anchor == null) {
            movementAnchors.put(p.getUUID(), p.position());
            return;
        }
        double dx = p.getX() - anchor.x;
        double dz = p.getZ() - anchor.z;
        double dy = p.getY() - anchor.y;
        if (dx * dx + dz * dz > 0.25 || Math.abs(dy) > 1.0) {
            p.connection.teleport(anchor.x, anchor.y, anchor.z, p.getYRot(), p.getXRot());
        }
    }

    public void lockMovement(ServerPlayer player) {
        movementLocked.add(player.getUUID());
        movementAnchors.put(player.getUUID(), player.position());
        AvalonNetwork.sendTo(player, new AvalonNetwork.MovementLock(true));
    }

    public void unlockMovement(ServerPlayer player) {
        movementLocked.remove(player.getUUID());
        movementAnchors.remove(player.getUUID());
        AvalonNetwork.sendTo(player, new AvalonNetwork.MovementLock(false));
    }

    public boolean isMovementLocked(Player player) {
        return movementLocked.contains(player.getUUID());
    }

    /** Kirim ulang state lock ke client (dipanggil saat player login). */
    public void resendLocks(ServerPlayer player) {
        if (isCameraLocked(player)) {
            AvalonNetwork.sendTo(player, new AvalonNetwork.CameraLock(true, getLockedYaw(player), getLockedPitch(player)));
        } else {
            AvalonNetwork.sendTo(player, new AvalonNetwork.CameraLock(false, 0, 0));
        }
        if (isMovementLocked(player)) {
            movementAnchors.remove(player.getUUID());
            AvalonNetwork.sendTo(player, new AvalonNetwork.MovementLock(true));
        } else {
            AvalonNetwork.sendTo(player, new AvalonNetwork.MovementLock(false));
        }
        syncOneSlot(player);
    }

    // ===== REGISTER =====

    public boolean registerPlayer(String playerName) {
        if (registeredPlayers.size() >= PLAYER_SLAB_POSITIONS.length) return false;
        if (registeredPlayers.contains(playerName)) return false;
        registeredPlayers.add(playerName);
        return true;
    }

    public boolean unregisterPlayer(String playerName) {
        forcedRoles.remove(playerName);
        return registeredPlayers.remove(playerName);
    }

    public List<String> getRegisteredPlayers() {
        return Collections.unmodifiableList(registeredPlayers);
    }

    public int getMaxPlayers() {
        return PLAYER_SLAB_POSITIONS.length;
    }

    // ===== FLAGS =====

    public void setCutsceneEnabled(boolean enabled) { this.cutsceneEnabled = enabled; }
    public boolean isCutsceneEnabled()              { return cutsceneEnabled; }
    public boolean isCutsceneRunning()              { return cutsceneRunning; }
    public boolean isGameRunning()                  { return gameRunning; }
    public boolean isMissionActive()                { return missionActive; }
    public boolean isTeamSelectionActive()          { return teamSelectionActive; }

    /** Dipakai CutsceneListener untuk memutuskan apakah eject dari seat diizinkan. */
    public boolean isDismountAllowed()              { return dismountAllowed; }
    public int getCurrentRevealPhase()              { return currentRevealPhase; }
    public boolean isDiscussionActive()             { return discussionActive; }
    public boolean isLadyActive()                   { return ladyActive; }
    public boolean isAssassinationActive()          { return assassinationActive; }
    public boolean isAssassinBowActive()            { return assassinBowActive; }
    public int getCurrentRound()                    { return currentRound; }
    public int getEvilMissionFails()                { return evilMissionFails; }
    public List<String> getCurrentMissionTeam()     { return Collections.unmodifiableList(currentMissionTeam); }
    public int getOfflineMannequinCount()           { return offlineMannequins.size(); }

    // ===== ROLE MANAGEMENT =====

    public Role getRole(Player player) {
        return playerRoles.get(player.getUUID());
    }

    public Map<UUID, Role> getPlayerRoles() {
        return new HashMap<>(playerRoles);
    }

    private List<Role> getDefaultRoles(int playerCount) {
        List<Role> r = new ArrayList<>();
        switch (playerCount) {
            case 5  -> { r.add(Role.MERLIN); r.add(Role.PERCIVAL); r.add(Role.LOYAL_SERVANT); r.add(Role.ASSASSIN); r.add(Role.MORGANA); }
            case 6  -> { r.add(Role.MERLIN); r.add(Role.PERCIVAL); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.ASSASSIN); r.add(Role.MORDRED); }
            case 7  -> { r.add(Role.MERLIN); r.add(Role.PERCIVAL); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.ASSASSIN); r.add(Role.MORGANA); r.add(Role.OBERON); }
            case 8  -> { r.add(Role.MERLIN); r.add(Role.PERCIVAL); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.ASSASSIN); r.add(Role.MORGANA); r.add(Role.MORDRED); }
            case 9  -> { r.add(Role.MERLIN); r.add(Role.PERCIVAL); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.ASSASSIN); r.add(Role.MORGANA); r.add(Role.MORDRED); }
            case 10 -> { r.add(Role.MERLIN); r.add(Role.PERCIVAL); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.LOYAL_SERVANT); r.add(Role.ASSASSIN); r.add(Role.MORGANA); r.add(Role.MORDRED); r.add(Role.OBERON); }
            default -> throw new IllegalArgumentException("playerCount tidak valid: " + playerCount);
        }
        return r;
    }

    /** Bagikan role ke semua player terdaftar, termasuk yang sedang offline (lihat rosterUuids). */
    private void assignRoles() {
        List<Role> roles = getCustomRoles(registeredPlayers.size());
        playerRoles.clear();
        roleNames.clear();

        // Role yang sudah diatur lewat /avalon setrole dipakai dulu; sisanya diacak ke player lain
        List<UUID> unassigned = new ArrayList<>();
        for (String name : registeredPlayers) {
            UUID id = rosterUuids.get(name);
            if (id == null) continue;
            Role forced = forcedRoles.get(name);
            if (forced != null && roles.remove(forced)) {
                playerRoles.put(id, forced);
            } else {
                unassigned.add(id);
            }
            roleNames.put(id, name);
        }
        Collections.shuffle(roles);
        for (int i = 0; i < unassigned.size(); i++) {
            playerRoles.put(unassigned.get(i), roles.get(i));
        }
    }

    // ===== ROLE YANG DIATUR MANUAL (/avalon setrole) =====

    /** Nama player → role yang dipastikan didapatnya di game berikutnya. */
    private final Map<String, Role> forcedRoles = new LinkedHashMap<>();

    public enum SetRoleResult { OK, GAME_RUNNING, NOT_REGISTERED, BAD_PLAYER_COUNT, NOT_ACTIVE, TAKEN }

    /** Role yang aktif untuk jumlah player terdaftar saat ini (kosong kalau jumlahnya belum 5-10). */
    public List<Role> getActiveRoles() {
        int n = registeredPlayers.size();
        return n < 5 || n > PLAYER_SLAB_POSITIONS.length ? List.of() : getCustomRoles(n);
    }

    public Map<String, Role> getForcedRoles() {
        return Collections.unmodifiableMap(forcedRoles);
    }

    /**
     * Pastikan {@code playerName} mendapat {@code role} di game berikutnya; null = kembali diacak.
     * Hanya role yang aktif untuk jumlah player terdaftar yang bisa dipilih, dan tidak melebihi
     * jumlah role itu di susunan (jadi tidak bisa dobel, kecuali role yang memang ada beberapa
     * seperti Loyal Servant / Minion).
     */
    public SetRoleResult setForcedRole(String playerName, Role role) {
        if (gameRunning) return SetRoleResult.GAME_RUNNING;
        if (!registeredPlayers.contains(playerName)) return SetRoleResult.NOT_REGISTERED;
        if (role == null) {
            forcedRoles.remove(playerName);
            return SetRoleResult.OK;
        }
        List<Role> active = getActiveRoles();
        if (active.isEmpty()) return SetRoleResult.BAD_PLAYER_COUNT;
        int available = Collections.frequency(active, role);
        if (available == 0) return SetRoleResult.NOT_ACTIVE;

        int taken = 0;
        for (Map.Entry<String, Role> entry : forcedRoles.entrySet()) {
            if (entry.getValue() == role && !entry.getKey().equals(playerName)) taken++;
        }
        if (taken >= available) return SetRoleResult.TAKEN;

        forcedRoles.put(playerName, role);
        return SetRoleResult.OK;
    }

    // ===== DEBUG: SELALU RAJA (/avalon debug alwaysking) =====

    /** Nama player yang selalu jadi raja (untuk tes); null = raja bergilir seperti biasa. */
    private String alwaysKing = null;

    public void setAlwaysKing(String playerName) {
        this.alwaysKing = playerName;
    }

    public String getAlwaysKing() {
        return alwaysKing;
    }

    private final Map<Integer, List<Role>> customRoles = new HashMap<>();

    public List<Role> getCustomRoles(int n) {
        List<Role> c = customRoles.get(n);
        return c != null ? new ArrayList<>(c) : getDefaultRoles(n);
    }

    public void setCustomRoles(int n, List<Role> roles) {
        customRoles.put(n, new ArrayList<>(roles));
    }

    /** Role berdasarkan nama player (juga untuk player offline). */
    private Role getRoleByName(String name) {
        for (Map.Entry<UUID, String> e : roleNames.entrySet()) {
            if (e.getValue().equals(name)) return playerRoles.get(e.getKey());
        }
        return null;
    }

    // ===== KING (RAJA) MANAGEMENT =====

    /** Umumkan siapa Raja saat ini ke semua player. */
    private void announceKing() {
        String kingName = getCurrentKingName();
        if (kingName == null) return;
        teamSelectionActive = true;
        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        broadcast(
            Txt.t("  👑 Raja saat ini: ", ChatFormatting.YELLOW)
                .append(Txt.t(kingName, ChatFormatting.GOLD, ChatFormatting.BOLD))
        );
        broadcast(
            Txt.t("  Misi ke-" + currentRound + " | Gunakan Buku Pemilihan Tim (klik kanan) untuk memilih anggota tim.", ChatFormatting.GRAY)
        );
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));

        // Title ke Raja
        ServerPlayer king = getPlayerExact(kingName);
        if (king != null) {
            Fx.title(king,
                "§6§l👑 KAMU ADALAH RAJA",
                "§eGunakan Buku Pemilihan Tim untuk memilih tim misi ke-" + currentRound,
                10, 60, 20
            );
            Fx.sound(king, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            giveTeamBook(king);
        }

        // Mahkota terbang ke kepala raja baru
        sendCrown(true);

        // Mulai action bar "Menunggu raja memilih tim"
        startTeamSelectionActionBar(kingName);
    }

    /** Kirim mahkota raja aktif ke semua player ({@code animate} = terbang dari raja sebelumnya). */
    private void sendCrown(boolean animate) {
        for (ServerPlayer p : getOnlinePlayers()) sendCrownTo(p, animate);
    }

    private void sendCrownTo(ServerPlayer p, boolean animate) {
        String kingName = getCurrentKingName();
        // Selama misi (termasuk cutscene pilarnya) mahkota disembunyikan; begitu juga selagi
        // animasi pemilihan raja pertama belum selesai
        if (kingName == null || missionActive || kingRouletteRunning) {
            AvalonNetwork.sendTo(p, AvalonNetwork.Crown.NONE);
            return;
        }
        AvalonNetwork.sendTo(p, new AvalonNetwork.Crown(kingName, registeredPlayers.indexOf(kingName), animate));
    }

    /**
     * Mulai action bar berulang "Menunggu raja memilih tim" untuk semua player
     * selama fase pemilihan tim berlangsung.
     */
    private void startTeamSelectionActionBar(String kingName) {
        stopTeamSelectionActionBar();

        teamSelectionActionBarTask = new Task() {
            @Override
            public void run() {
                if (!gameRunning) { cancel(); return; }

                Component bar = Txt.t("👑 ", ChatFormatting.GOLD)
                    .append(Txt.t("Menunggu ", ChatFormatting.YELLOW))
                    .append(Txt.t(kingName, ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Txt.t(" memilih tim...", ChatFormatting.YELLOW));

                for (ServerPlayer p : getOnlinePlayers()) {
                    Fx.actionBar(p, bar);
                }
            }
        }.runTimer(0L, 20L);
    }

    /** Hentikan action bar "Menunggu raja memilih tim". */
    public void stopTeamSelectionActionBar() {
        if (teamSelectionActionBarTask != null) {
            teamSelectionActionBarTask.cancel();
            teamSelectionActionBarTask = null;
        }
        // Clear actionbar di semua player
        for (ServerPlayer p : getOnlinePlayers()) {
            Fx.actionBar(p, Txt.blank());
        }
    }

    /** Nama Raja aktif. */
    public String getCurrentKingName() {
        if (currentKingIndex < 0 || kingOrder.isEmpty()) return null;
        return kingOrder.get(currentKingIndex);
    }

    /** Cek apakah player adalah Raja aktif. */
    public boolean isKing(Player player) {
        return player.getGameProfile().getName().equals(getCurrentKingName());
    }

    // ── Buku Pemilihan Tim ──────────────────────────────────────────────────────

    /** Tag key untuk menandai item Buku Pemilihan Tim. */
    public static final String PDC_KEY_TEAM_BOOK = "team_book";

    /** Berikan item Buku Pemilihan Tim ke Raja aktif. */
    public void giveTeamBook(ServerPlayer king) {
        if (king == null || king.hasDisconnected()) return;

        ItemStack book = AvalonItems.named(
            Items.WRITTEN_BOOK,
            Txt.t("Buku Pemilihan Tim", ChatFormatting.GOLD, ChatFormatting.BOLD),
            List.of(
                Txt.t("Klik kanan untuk membuka", ChatFormatting.GRAY),
                Txt.t("menu pemilihan tim misi.", ChatFormatting.GRAY)
            )
        );
        AvalonItems.setTag(book, PDC_KEY_TEAM_BOOK, "true");

        king.getInventory().setItem(0, book);
    }

    /** Hapus item Buku Pemilihan Tim dari inventory Raja. */
    public void removeTeamBook(ServerPlayer king) {
        if (king == null) return;

        Inventory inv = king.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isTeamBook(inv.getItem(i))) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /** Cek apakah item adalah Buku Pemilihan Tim. */
    public static boolean isTeamBook(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.WRITTEN_BOOK)) return false;
        return AvalonItems.hasTag(item, PDC_KEY_TEAM_BOOK);
    }

    /** Jumlah misi sukses + 1. */
    public int getCurrentMission() {
        return currentMission;
    }

    /** Jumlah anggota tim untuk ronde yang sedang berjalan. */
    public int getTeamSize() {
        return TeamSelectionGUI.getTeamSize(registeredPlayers.size(), currentRound);
    }

    /**
     * Berapa player offline yang boleh masuk tim: hanya sebanyak kekurangannya kalau player yang
     * online tidak cukup untuk mengisi tim.
     */
    public int offlinePicksAllowed() {
        return Math.max(0, getTeamSize() - getOnlinePlayers().size());
    }

    public int countOffline(List<String> names) {
        int count = 0;
        for (String name : names) {
            if (getPlayerExact(name) == null) count++;
        }
        return count;
    }

    // ── Team Selection Session ─────────────────────────────────────────────────

    /**
     * Mendapatkan list player yang sudah dipilih Raja di session saat ini.
     * Jika belum ada session, kembalikan list kosong.
     */
    public List<String> getTeamSelectionSession(Player king) {
        return teamSelectionSessions.getOrDefault(king.getUUID(), new ArrayList<>());
    }

    /** Set list player yang sudah dipilih Raja. */
    public void setTeamSelectionSession(Player king, List<String> selected) {
        teamSelectionSessions.put(king.getUUID(), new ArrayList<>(selected));
    }

    /**
     * Konfirmasi pilihan tim oleh Raja.
     * Mengumumkan tim yang dipilih ke semua player.
     */
    public void confirmTeamSelection(ServerPlayer king, List<String> team) {
        // GUI yang masih terbuka setelah gilirannya lewat tidak boleh memulai voting
        if (!gameRunning || !teamSelectionActive || !isKing(king)) return;

        // Reset session
        teamSelectionSessions.remove(king.getUUID());
        teamSelectionActive = false;

        // Hapus Buku Pemilihan Tim dan mainkan sound konfirmasi
        removeTeamBook(king);
        Fx.sound(king, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);

        // Hentikan action bar "Menunggu raja memilih tim"
        stopTeamSelectionActionBar();

        int playerCount = registeredPlayers.size();
        if (TeamSelectionGUI.requiresTwoFails(playerCount, currentRound)) {
            broadcast(Txt.blank());
            broadcast(
                Txt.t("  ⚠ Misi ini butuh 2 sabotase untuk digagalkan!", ChatFormatting.RED, ChatFormatting.ITALIC)
            );
            broadcast(Txt.blank());
        }

        // Mulai fase voting setelah 2 detik
        final List<String> teamFinal = new ArrayList<>(team);
        later(40L, () -> {
            if (votingManager != null) {
                votingManager.startVoting(teamFinal);
            }
        });
    }

    // ===== CAMERA LOCK =====

    public void lockCamera(ServerPlayer player, float yaw, float pitch) {
        lockedYaw.put(player.getUUID(), yaw);
        lockedPitch.put(player.getUUID(), pitch);
        AvalonNetwork.sendTo(player, new AvalonNetwork.CameraLock(true, yaw, pitch));
    }

    public void unlockCamera(ServerPlayer player) {
        lockedYaw.remove(player.getUUID());
        lockedPitch.remove(player.getUUID());
        AvalonNetwork.sendTo(player, new AvalonNetwork.CameraLock(false, 0, 0));
    }

    public boolean isCameraLocked(Player player) { return lockedYaw.containsKey(player.getUUID()); }
    public float   getLockedYaw(Player player)   { return lockedYaw.get(player.getUUID()); }
    public float   getLockedPitch(Player player) { return lockedPitch.get(player.getUUID()); }

    /** Setara Player#setRotation(yaw, pitch). */
    public void setRotation(ServerPlayer p, float yaw, float pitch) {
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
        AvalonNetwork.sendTo(p, new AvalonNetwork.Rotate(yaw, pitch));
    }

    /** Setara Player#teleport(Location). */
    private void teleport(ServerPlayer p, ServerLevel world, double x, double y, double z, float yaw, float pitch) {
        p.teleportTo(world, x, y, z, yaw, pitch);
        if (movementLocked.contains(p.getUUID())) {
            movementAnchors.put(p.getUUID(), new Vec3(x, y, z));
        }
    }

    // ===== ROLE REVEAL HELPERS =====

    /**
     * Hitung yaw dari posisi player ke tengah lingkaran kursi.
     * Sama persis rumus yang dipakai di seatAtTable().
     */
    private float yawTowardBase(double fromX, double fromZ) {
        double dx = (AvalonSeats.CENTER.getX() + 0.5) - fromX;
        double dz = (AvalonSeats.CENTER.getZ() + 0.5) - fromZ;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Dimensi tempat lingkaran kursi berada. */
    private ServerLevel tableWorld() {
        MinecraftServer server = server();
        return server == null ? null : server.getLevel(AvalonDimensions.AVALON);
    }

    /** Hapus slowness dan blindness. */
    private void clearRevealEffects(ServerPlayer p) {
        p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        p.removeEffect(MobEffects.BLINDNESS);
    }

    /** Spawn ArmorStand kursi tak terlihat (tag avalon_seat). */
    private ArmorStand spawnSeat(ServerLevel world, double x, double y, double z, float yaw) {
        ArmorStand seat = spawnStand(world, x, y, z, yaw, true, false, false);
        seat.setCustomNameVisible(false);
        seat.addTag("avalon_seat");
        return seat;
    }

    /**
     * Spawn ArmorStand dengan konfigurasi dasar
     * (setVisible(false), setGravity(false), setInvulnerable(true), setMarker, setSmall, setArms).
     */
    public static ArmorStand spawnStand(ServerLevel world, double x, double y, double z, float yaw,
                                        boolean marker, boolean small, boolean arms) {
        ArmorStand stand = new ArmorStand(world, x, y, z);
        CompoundTag cfg = new CompoundTag();
        cfg.putBoolean("Invisible", true);
        cfg.putBoolean("Marker", marker);
        cfg.putBoolean("Small", small);
        cfg.putBoolean("ShowArms", arms);
        stand.readAdditionalSaveData(cfg);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setInvisible(true);
        stand.moveTo(x, y, z, yaw, 0f);
        stand.setYBodyRot(yaw);
        stand.setYHeadRot(yaw);
        world.addFreshEntity(stand);
        return stand;
    }

    /** Dudukkan player di kursinya sendiri (lihat {@link #seatPlayerAt}). */
    private void seatPlayer(ServerPlayer p) {
        PlayerScale.set(p, 1.0);
        seatPlayerAt(p, registeredPlayers.indexOf(p.getGameProfile().getName()));
    }

    /**
     * Pindahkan player ke kursi nomor {@code index} di dimensi Avalon, dari mana pun ia berada, lalu
     * dudukkan di sana. Ini satu-satunya jalur mendudukkan player: awal game, kembali online, seusai
     * misi, dan cutscene akhir (yang juga dipakai di luar game).
     */
    public void seatPlayerAt(ServerPlayer p, int index) {
        ServerLevel world = tableWorld();
        if (world == null || index < 0 || index >= PLAYER_SLAB_POSITIONS.length) return;
        BlockPos slab = AvalonSeats.pos(index);
        double x = slab.getX() + 0.5, z = slab.getZ() + 0.5;
        float yaw = yawTowardBase(x, z);

        // Kursi lamanya dilepas dulu: turun dari kursi diblokir selama game (CutsceneListener),
        // jadi tanpa ini teleport-nya gagal dan ia terdaftar sebagai penumpang dua kursi
        releaseFromSeat(p);
        setMode(p, GameType.ADVENTURE);
        boolean travels = p.serverLevel() != world;
        if (travels && gameRunning) rememberReturnPoint(p);
        teleport(p, world, x, slab.getY(), z, yaw, 0);

        Runnable sit = () -> {
            releaseFromSeat(p);
            p.startRiding(spawnSeat(world, x, slab.getY() + SEAT_HEIGHT, z, yaw), true);
        };
        if (travels) {
            // Baru pindah dimensi: kursinya menyusul. Keluar dalam jeda ini: didudukkan saat ia masuk lagi
            later(5L, () -> {
                if (isOnline(p)) sit.run();
            });
        } else {
            sit.run();
        }
    }

    /** Catat tempat asal player (sekali per game) untuk dipulangkan di akhir game. */
    private void rememberReturnPoint(ServerPlayer p) {
        returnPoints.putIfAbsent(p.getUUID(),
            new ReturnPoint(p.serverLevel().dimension(), p.position(), p.getYRot(), p.getXRot()));
    }

    private static boolean isInGame(ServerPlayer p) {
        return p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG).getBoolean(IN_GAME_TAG);
    }

    /** Disimpan di data yang ikut terbawa saat player mati lalu respawn. */
    private static void setInGame(ServerPlayer p, boolean inGame) {
        CompoundTag kept = p.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
        if (inGame) kept.putBoolean(IN_GAME_TAG, true);
        else kept.remove(IN_GAME_TAG);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, kept);
    }

    /**
     * Player yang masih di dunia lain (keluar sebelum sempat dipindahkan) dibawa berdiri di kursinya di
     * dimensi Avalon; tempat asalnya dicatat untuk dipulangkan di akhir game.
     */
    private void ensureInAvalon(ServerPlayer p) {
        ServerLevel world = tableWorld();
        int index = registeredPlayers.indexOf(p.getGameProfile().getName());
        if (world == null || p.serverLevel() == world || index < 0 || index >= PLAYER_SLAB_POSITIONS.length) return;

        rememberReturnPoint(p);
        BlockPos slab = AvalonSeats.pos(index);
        double x = slab.getX() + 0.5, z = slab.getZ() + 0.5;
        teleport(p, world, x, slab.getY(), z, yawTowardBase(x, z), 0);
    }

    /**
     * Ganti gamemode sekaligus menyamakan ability-nya: izin terbang dari cutscene pilar ikut
     * tersimpan kalau player keluar di tengahnya, dan setGameMode tidak menyentuhnya kalau
     * gamemode-nya tidak berubah.
     */
    private void setMode(ServerPlayer p, GameType mode) {
        p.setGameMode(mode);
        mode.updatePlayerAbilities(p.getAbilities());
        p.onUpdateAbilities();
    }

    /**
     * Countdown di action bar.
     * Task disimpan ke revealCountdownTask supaya bisa di-cancel oleh /stopgame.
     */
    private void revealCountdown(String label, Runnable onDone) {
        if (revealCountdownTask != null) {
            revealCountdownTask.cancel();
            revealCountdownTask = null;
        }

        revealCountdownTask = new Task() {
            int seconds = revealSeconds;

            @Override
            public void run() {
                // Kalau game sudah dihentikan, hentikan countdown ini juga
                if (!gameRunning) {
                    cancel();
                    return;
                }

                if (seconds < 0) {
                    cancel();
                    revealCountdownTask = null;
                    onDone.run();
                    return;
                }

                currentRevealLabel = label;
                currentRevealSeconds = seconds;
                for (ServerPlayer p : getOnlinePlayers()) {
                    if (seconds == 0) {
                        Fx.actionBar(p, Txt.t("✔ " + label + " — selesai", ChatFormatting.GREEN));
                    } else {
                        Fx.actionBar(p,
                            Txt.t(label + " — ", ChatFormatting.YELLOW)
                                .append(Txt.t(seconds + "s", ChatFormatting.WHITE))
                        );
                    }
                }
                seconds--;
            }
        }.runTimer(0L, 20L);
    }

    // ===== ROLE REVEAL PHASES =====

    /**
     * Entry: animasi kocok peran (roh-roh cahaya dari bola di bawah lantai, lihat RoleShuffleClient),
     * lalu tiap player diberi tahu perannya → lanjut ke fase reveal phase 0.
     */
    private void startRoleReveal() {
        int seats = Math.min(registeredPlayers.size(), PLAYER_SLAB_POSITIONS.length);
        for (int i = 0; i < seats; i++) {
            ServerPlayer p = getPlayerExact(registeredPlayers.get(i));
            if (p == null) continue;
            Role role = getRole(p);
            // Hanya kubu si penerima yang dikirim; peran player lain tidak pernah sampai ke client-nya
            AvalonNetwork.sendTo(p, new AvalonNetwork.RoleShuffle(seats, i, role != null && role.isEvil()));
        }

        delayedTasks.add(
            Scheduler.later(RoleShuffleTimeline.REVEAL, () -> {
                if (!gameRunning) return;

                for (String name : registeredPlayers) {
                    ServerPlayer p = getPlayerExact(name);
                    if (p == null) continue;
                    Role realRole = getRole(p);
                    if (realRole == null) continue;
                    Fx.title(p, (realRole.isEvil() ? "§c§l" : "§b§l") + roleTitle(realRole),
                            realRole.isEvil() ? "§4Kubu Jahat" : "§3Kubu Baik", 5, 70, 20);
                    Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                    p.sendSystemMessage(Txt.blank());
                    p.sendSystemMessage(Txt.t("══════════════════════", ChatFormatting.GOLD));
                    for (Component line : getRoleDescription(realRole)) p.sendSystemMessage(line);
                    p.sendSystemMessage(Txt.t("══════════════════════", ChatFormatting.GOLD));
                }

                // 7.5 detik kemudian mulai fase reveal
                later(150L, this::runReveal);
            })
        );
    }

    /** Nama peran untuk ditampilkan (sama dengan yang dipakai di deskripsi peran). */
    private static String roleTitle(Role role) {
        return switch (role) {
            case MERLIN -> "Merlin";
            case PERCIVAL -> "Percival";
            case LOYAL_SERVANT -> "Loyal Servant";
            case ASSASSIN -> "Assassin";
            case MORGANA -> "Morgana";
            case MORDRED -> "Mordred";
            case OBERON -> "Oberon";
            case MINION_OF_MORDRED -> "Minion of Mordred";
        };
    }

    /**
     * Fase perkenalan: satu kali, semua bersamaan, semua tetap duduk.
     * Tiap client hanya diberi tahu apa yang boleh dilihat pemiliknya (lihat revealViewFor):
     *   Merlin   — kubu jahat (kecuali Mordred) beraura merah, semua orang menunduk
     *   Percival — Merlin & Morgana beraura ungu, semua orang menunduk
     *   Jahat    — sesama jahat (kecuali Oberon) beraura merah & tidak menunduk, sisanya menunduk
     *   Lainnya  — mata terpejam (layar gelap) sampai fase selesai
     */
    private void runReveal() {
        if (!gameRunning) return;
        currentRevealPhase = 1;

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_GRAY));
        broadcast(Txt.t("  🔮 FASE PERKENALAN DIMULAI", ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        broadcast(Txt.t("  Yang berhak melihat, membuka matanya...", ChatFormatting.GRAY));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_GRAY));
        broadcast(Txt.blank());

        for (ServerPlayer p : getOnlinePlayers()) {
            AvalonNetwork.sendTo(p, revealViewFor(p));
            sendRevealHint(p);
        }

        revealCountdown("Fase perkenalan", () -> {
            if (!gameRunning) return;
            currentRevealPhase = -1;
            revealFinished = true;

            for (String name : registeredPlayers) {
                ServerPlayer p = getPlayerExact(name);
                if (p == null) continue;
                AvalonNetwork.sendTo(p, AvalonNetwork.Reveal.END);
                PlayerScale.set(p, 1.0);
                clearRevealEffects(p);
                unlockCamera(p);
                unlockMovement(p);
                setRotation(p, yawTowardBase(p.getX(), p.getZ()), 0);
                Fx.actionBar(p, Txt.blank());
            }

            broadcast(Txt.blank());
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_GRAY));
            broadcast(Txt.t("  ✅ Fase perkenalan selesai!", ChatFormatting.GREEN, ChatFormatting.BOLD));
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_GRAY));
            broadcast(Txt.blank());

            // 5 detik setelah fase perkenalan, mulai animasi kocok Raja
            later(100L, this::startKingReveal); // 100 ticks = 5 detik
        });
    }

    /** Apa yang boleh dilihat {@code viewer} selama fase perkenalan. */
    private AvalonNetwork.Reveal revealViewFor(ServerPlayer viewer) {
        Role role = getRole(viewer);
        String self = viewer.getGameProfile().getName();
        List<Integer> red = new ArrayList<>();
        List<Integer> purple = new ArrayList<>();
        List<Integer> upright = new ArrayList<>();

        if (role == Role.MERLIN) {
            for (String name : registeredPlayers) {
                Role r = getRoleByName(name);
                if (r != null && r.isEvil() && r != Role.MORDRED) addRevealEntity(red, name);
            }
        } else if (role == Role.PERCIVAL) {
            for (String name : registeredPlayers) {
                Role r = getRoleByName(name);
                if (r == Role.MERLIN || r == Role.MORGANA) addRevealEntity(purple, name);
            }
        } else if (role != null && role.isEvil() && role != Role.OBERON) {
            for (String name : registeredPlayers) {
                Role r = getRoleByName(name);
                if (name.equals(self) || r == null || !r.isEvil() || r == Role.OBERON) continue;
                addRevealEntity(red, name);
                addRevealEntity(upright, name);
            }
        } else {
            // Loyal Servant & Oberon tidak melihat apa pun
            return new AvalonNetwork.Reveal(true, true, List.of(), List.of(), List.of());
        }
        return new AvalonNetwork.Reveal(true, false, red, purple, upright);
    }

    /** Entity yang mewakili player di dunia: dirinya sendiri, atau mannequin-nya kalau sedang offline. */
    private void addRevealEntity(List<Integer> ids, String name) {
        ServerPlayer p = getPlayerExact(name);
        if (p != null) {
            ids.add(p.getId());
            return;
        }
        Entity mannequin = offlineMannequins.get(name);
        if (mannequin != null) ids.add(mannequin.getId());
    }

    /** Kirim ulang pandangan semua player (mis. ada yang keluar/masuk, entity-nya berganti). */
    private void refreshRevealViews() {
        if (!gameRunning || currentRevealPhase != 1) return;
        for (ServerPlayer p : getOnlinePlayers()) AvalonNetwork.sendTo(p, revealViewFor(p));
    }

    /** Petunjuk di chat sesuai peran. */
    private void sendRevealHint(ServerPlayer p) {
        Role role = getRole(p);
        if (role == null) return;
        String self = p.getGameProfile().getName();

        p.sendSystemMessage(Txt.blank());
        if (role == Role.MERLIN) {
            p.sendSystemMessage(Txt.t("  👁 Yang beraura merah adalah kubu jahat", ChatFormatting.RED, ChatFormatting.BOLD));
            p.sendSystemMessage(Txt.t("  (Mordred tidak terlihat olehmu)", ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
            for (String name : offlineRegisteredNames()) {
                Role r = getRoleByName(name);
                if (r != null && r.isEvil() && r != Role.MORDRED) {
                    p.sendSystemMessage(Txt.t("  ⚠ " + name + " (kubu jahat) sedang offline.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }
        } else if (role == Role.PERCIVAL) {
            p.sendSystemMessage(Txt.t("  👁 Yang beraura ungu adalah Merlin & Morgana.", ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
            for (String name : offlineRegisteredNames()) {
                Role r = getRoleByName(name);
                if (r == Role.MERLIN || r == Role.MORGANA) {
                    p.sendSystemMessage(Txt.t("  ⚠ " + name + " (Merlin/Morgana) sedang offline.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }
        } else if (role.isEvil() && role != Role.OBERON) {
            p.sendSystemMessage(Txt.t("  🗡 Yang beraura merah & tidak menunduk adalah rekan kubu jahatmu.", ChatFormatting.RED, ChatFormatting.BOLD));
            for (String name : offlineRegisteredNames()) {
                Role r = getRoleByName(name);
                if (r != null && r.isEvil() && r != Role.OBERON && !name.equals(self)) {
                    // Tanpa nama perannya: rekan yang online pun hanya terlihat sebagai aura merah
                    p.sendSystemMessage(Txt.t("  ⚠ " + name + " (kubu jahat) sedang offline.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }
        } else if (role == Role.OBERON) {
            p.sendSystemMessage(Txt.t("  Sebagai Oberon, kamu tidak mengenal kubu jahat lainnya.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            p.sendSystemMessage(Txt.t("  Pejamkan matamu sampai fase perkenalan selesai...", ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        p.sendSystemMessage(Txt.blank());
    }

    /**
     * Player masuk lagi setelah fase perkenalan lewat: nama-nama yang seharusnya ia lihat di fase itu
     * dikirim lewat chat (aturannya sama dengan {@link #revealViewFor}).
     */
    private void sendRevealNames(ServerPlayer p) {
        Role role = getRole(p);
        if (role == null) return;
        String self = p.getGameProfile().getName();
        List<String> names = new ArrayList<>();
        String label;

        if (role == Role.MERLIN) {
            label = "Kubu jahat";
            for (String name : registeredPlayers) {
                Role r = getRoleByName(name);
                if (r != null && r.isEvil() && r != Role.MORDRED) names.add(name);
            }
        } else if (role == Role.PERCIVAL) {
            label = "Merlin & Morgana";
            for (String name : registeredPlayers) {
                Role r = getRoleByName(name);
                if (r == Role.MERLIN || r == Role.MORGANA) names.add(name);
            }
        } else if (role.isEvil() && role != Role.OBERON) {
            label = "Rekan kubu jahatmu";
            for (String name : registeredPlayers) {
                Role r = getRoleByName(name);
                if (!name.equals(self) && r != null && r.isEvil() && r != Role.OBERON) names.add(name);
            }
        } else {
            return;
        }
        if (names.isEmpty()) return;

        p.sendSystemMessage(
            Txt.t("  👁 " + label + ": ", ChatFormatting.LIGHT_PURPLE)
                .append(Txt.t(String.join(", ", names), ChatFormatting.WHITE, ChatFormatting.BOLD))
        );
        p.sendSystemMessage(Txt.blank());
    }

    /** Player kembali online di tengah fase perkenalan: dudukkan lagi & kirim ulang pandangannya. */
    private void restoreRevealState(ServerPlayer player) {
        if (currentRevealPhase != 1) return;
        seatPlayer(player);
        // Entity player ini baru (id berubah), jadi pandangan semua orang ikut diperbarui
        refreshRevealViews();
        sendRevealHint(player);

        if (currentRevealSeconds >= 0) {
            Fx.actionBar(player,
                Txt.t(currentRevealLabel + " — " + currentRevealSeconds + "s", ChatFormatting.YELLOW)
            );
        }
    }

    // ── Helpers role reveal ──────────────────────────────────────────────────

    // ===== KING REVEAL =====

    /**
     * Animasi kocok Raja — dipanggil 5 detik setelah fase perkenalan selesai.
     * Mirip startRoleReveal: nama player dikocok cepat di title, lalu reveal Raja.
     */
    private void startKingReveal() {
        if (!gameRunning) return;

        // ── Setup urutan Raja berdasarkan posisi kursi searah jarum jam ────────
        kingOrder.clear();
        teamSelectionSessions.clear();
        currentMission = 1;

        // Semua player terdaftar ikut giliran (yang sedang offline juga), urut menurut kursinya
        List<String> sorted = new ArrayList<>(registeredPlayers);
        if (sorted.isEmpty()) return;
        sorted.sort((a, b) -> {
            int[] posA = PLAYER_SLAB_POSITIONS[registeredPlayers.indexOf(a)];
            int[] posB = PLAYER_SLAB_POSITIONS[registeredPlayers.indexOf(b)];
            double angleA = Math.toDegrees(Math.atan2(posA[0], posA[1]));
            double angleB = Math.toDegrees(Math.atan2(posB[0], posB[1]));
            if (angleA < 0) angleA += 360;
            if (angleB < 0) angleB += 360;
            return Double.compare(angleB, angleA);
        });

        // Pilih Raja pertama secara acak (atau player "selalu raja" kalau sedang dites)
        int randomStart = (int) (Math.random() * sorted.size());
        if (alwaysKing != null && sorted.contains(alwaysKing)) randomStart = sorted.indexOf(alwaysKing);
        // Raja pertama harus sedang online
        for (int i = 0; i < sorted.size() && getPlayerExact(sorted.get(randomStart)) == null; i++) {
            randomStart = (randomStart + 1) % sorted.size();
        }
        for (int i = 0; i < sorted.size(); i++) {
            kingOrder.add(sorted.get((randomStart + i) % sorted.size()));
        }

        currentKingIndex = 0;
        kingRouletteRunning = true;
        String kingName = kingOrder.get(0);
        // Lady of the Lake mulai di pemain di kanan raja pertama (yang paling akhir mendapat giliran raja)
        ladyHolder = isLadyEnabled() ? kingOrder.get(kingOrder.size() - 1) : null;

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        broadcast(Txt.t("  👑 MEMILIH RAJA PERTAMA...", ChatFormatting.YELLOW, ChatFormatting.BOLD));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        broadcast(Txt.blank());

        // Animasi pedang penunjuk raja (lihat KingRouletteClient), lalu umumkan rajanya
        List<Integer> seats = new ArrayList<>();
        for (int i = 0; i < Math.min(registeredPlayers.size(), PLAYER_SLAB_POSITIONS.length); i++) seats.add(i);
        AvalonNetwork.KingRoulette roulette =
            new AvalonNetwork.KingRoulette(registeredPlayers.indexOf(kingName), seats, kingName);
        for (ServerPlayer p : getOnlinePlayers()) AvalonNetwork.sendTo(p, roulette);

        delayedTasks.add(
            Scheduler.later(KingRouletteTimeline.REVEAL, () -> {
                if (!gameRunning) return;
                kingRouletteRunning = false;

                for (String name : registeredPlayers) {
                    ServerPlayer p = getPlayerExact(name);
                    if (p == null) continue;

                    boolean isKingPlayer = p.getGameProfile().getName().equals(kingName);

                    if (isKingPlayer) {
                        Fx.title(p,
                            "§6§l👑 KAMU ADALAH RAJA",
                            "§eKlik kanan §bBuku Pemilihan Tim §euntuk memilih anggota tim!",
                            10, 80, 20
                        );
                        Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                        giveTeamBook(p);
                    } else {
                        Fx.title(p,
                            "§6§l👑 RAJA TELAH DIPILIH",
                            "§e" + kingName + " §fadalah Raja Misi 1",
                            10, 80, 20
                        );
                        Fx.sound(p, SoundEvents.NOTE_BLOCK_CHIME, 1f, 1.2f);
                    }

                    p.sendSystemMessage(Txt.blank());
                    p.sendSystemMessage(Txt.t("══════════════════════", ChatFormatting.GOLD));
                    p.sendSystemMessage(
                        Txt.t("  👑 Raja Misi 1: ", ChatFormatting.YELLOW)
                            .append(Txt.t(kingName, ChatFormatting.GOLD, ChatFormatting.BOLD))
                    );
                    p.sendSystemMessage(Txt.t("  Urutan raja berikutnya searah jarum jam.", ChatFormatting.GRAY));
                    p.sendSystemMessage(
                        Txt.t("  Misi ke-1 | Kuota Tim: ", ChatFormatting.AQUA)
                            .append(Txt.t(
                                TeamSelectionGUI.getTeamSize(registeredPlayers.size(), 1) + " orang",
                                ChatFormatting.WHITE, ChatFormatting.BOLD)
                            )
                    );
                    if (isKingPlayer) {
                        p.sendSystemMessage(
                            Txt.t("  ➤ Klik kanan ", ChatFormatting.GREEN)
                                .append(Txt.t("Buku Pemilihan Tim", ChatFormatting.AQUA, ChatFormatting.BOLD))
                                .append(Txt.t(" untuk membuka menu pemilihan tim.", ChatFormatting.GREEN))
                        );
                    }
                    p.sendSystemMessage(Txt.t("══════════════════════", ChatFormatting.GOLD));
                    p.sendSystemMessage(Txt.blank());
                }
                teamSelectionActive = true;
                startTeamSelectionActionBar(kingName);
                // Mahkota sudah terbentuk di client; ini untuk yang baru masuk / tertinggal paketnya
                sendCrown(false);
                announceLadyHolder();
                // Rajanya keluar selagi animasi berjalan: tanpa ini game menunggunya tanpa batas waktu
                ensureKingGrace();
            })
        );
    }

    // ===== ROLE DESCRIPTION =====

    private List<Component> getRoleDescription(Role role) {
        return switch (role) {
            case MERLIN -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Merlin", ChatFormatting.AQUA)),
                Txt.t("  Anda dapat melihat semua kubu jahat kecuali Mordred.", ChatFormatting.WHITE),
                Txt.t("  Tuntun kubu baik dalam memilih orang yang akan menjalankan misi!", ChatFormatting.WHITE),
                Txt.t("  Jangan sampai kubu jahat mengetahui siapa Anda!", ChatFormatting.RED)
            );
            case PERCIVAL -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Percival", ChatFormatting.AQUA)),
                Txt.t("  Anda melihat Merlin dan Morgana", ChatFormatting.WHITE),
                Txt.t("  tetapi tidak tahu siapa Merlin yang asli.", ChatFormatting.WHITE)
            );
            case LOYAL_SERVANT -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Loyal Servant", ChatFormatting.AQUA)),
                Txt.t("  Bantu kubu baik menyelesaikan misi.", ChatFormatting.WHITE)
            );
            case ASSASSIN -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Assassin", ChatFormatting.RED)),
                Txt.t("  Gagalkan misi kubu baik!", ChatFormatting.WHITE),
                Txt.t("  Jika kubu baik menang, bunuh Merlin untuk mencuri kemenangan.", ChatFormatting.WHITE)
            );
            case MORGANA -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Morgana", ChatFormatting.RED)),
                Txt.t("  Gagalkan misi kubu baik!", ChatFormatting.WHITE),
                Txt.t("  Anda terlihat seperti Merlin bagi Percival.", ChatFormatting.WHITE)
            );
            case MORDRED -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Mordred", ChatFormatting.RED)),
                Txt.t("  Gagalkan misi kubu baik!", ChatFormatting.WHITE),
                Txt.t("  Merlin tidak dapat melihat Anda.", ChatFormatting.WHITE)
            );
            case OBERON -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Oberon", ChatFormatting.RED)),
                Txt.t("  Gagalkan misi kubu baik!", ChatFormatting.WHITE),
                Txt.t("  Anda tidak tahu kubu jahat lainnya.", ChatFormatting.WHITE),
                Txt.t("  Kubu jahat lainnya pun tidak tahu bahwa anda bagian dari mereka.", ChatFormatting.WHITE)
            );
            case MINION_OF_MORDRED -> List.of(
                Txt.t("  Anda adalah ", ChatFormatting.GREEN).append(Txt.t("Minion of Mordred", ChatFormatting.RED)),
                Txt.t("  Gagalkan misi kubu baik!", ChatFormatting.WHITE)
            );
        };
    }

    // ===== START GAME =====

    private void startCountdown(ServerPlayer initiator) {
        countdownTask = new Task() {
            int seconds = 5;

            @Override
            public void run() {
                if (seconds <= 0) {
                    for (ServerPlayer p : getOnlinePlayers()) Fx.title(p, "§a§lMULAI!", "", 0, 20, 10);
                    countdownTask = null;
                    enterAvalon(initiator);
                    cancel();
                    return;
                }
                for (ServerPlayer p : getOnlinePlayers()) {
                    Fx.title(p, "Game dimulai dalam...", "§e§l" + seconds, 0, 25, 0);
                    Fx.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 1f);
                }
                seconds--;
            }
        }.runTimer(0L, 20L);
    }

    public void startGame(ServerPlayer initiator) {
        if (gameRunning) { initiator.sendSystemMessage(Txt.t("Game sudah berjalan!", ChatFormatting.RED)); return; }
        // Semua yang terdaftar harus online: role, kursi dan giliran raja dibagi untuk seluruh daftar
        List<String> offline = offlineRegisteredNames();
        if (!offline.isEmpty()) {
            initiator.sendSystemMessage(Txt.t("Tidak bisa mulai, player terdaftar masih offline: "
                + String.join(", ", offline), ChatFormatting.RED));
            initiator.sendSystemMessage(Txt.t("Tunggu mereka masuk, atau /avalon unregis dulu.", ChatFormatting.GRAY));
            return;
        }
        List<ServerPlayer> activePlayers = getOnlinePlayers();
        if (activePlayers.size() < 5) { initiator.sendSystemMessage(Txt.t("Minimal 5 player!", ChatFormatting.RED)); return; }
        if (activePlayers.size() > PLAYER_SLAB_POSITIONS.length) { initiator.sendSystemMessage(Txt.t("Terlalu banyak player!", ChatFormatting.RED)); return; }
        // Tes cutscene yang masih berjalan dihentikan: saat selesai ia akan menurunkan player dari kursi game
        EndingCutscene.stop();
        PortalCutscene.stop(this);
        rosterUuids.clear();
        for (ServerPlayer p : activePlayers) {
            p.getInventory().clearContent();
            rosterUuids.put(p.getGameProfile().getName(), p.getUUID());
            setInGame(p, true);
        }
        gameRunning = true;
        for (ServerPlayer p : activePlayers) syncOneSlot(p);

        MinecraftServer server = initiator.getServer();
        pvpBeforeGame = server.isPvpAllowed();
        server.setPvpAllowed(false);
        startCountdown(initiator);
    }

    /** Ada yang keluar selagi hitung mundur: game dibatalkan, semua tetap terdaftar. */
    private void abortCountdown(String leaver) {
        if (countdownTask == null) return;
        cleanup();
        broadcast(Txt.t("Game dibatalkan: " + leaver + " keluar sebelum game dimulai.", ChatFormatting.RED, ChatFormatting.BOLD));
    }

    // ===== MASUK KE AVALON =====

    /**
     * Bawa semua player ke lingkaran kursi di dimensi Avalon, lalu lanjut ke game.
     * Cutscene aktif: lewat cutscene portal (portal terbuka di arah pandang {@code initiator}).
     * Cutscene mati: langsung dipindahkan.
     */
    private void enterAvalon(ServerPlayer initiator) {
        MinecraftServer server = server();
        if (server == null || tableWorld() == null) {
            broadcast(Txt.t("Dimensi Avalon tidak ditemukan, game dibatalkan.", ChatFormatting.RED));
            cleanup();
            return;
        }
        AvalonSeats.sync(server, registeredPlayers.size());
        cutsceneRunning = true;

        long wait = 0L;
        boolean travel = false;
        List<ServerPlayer> pulled = new ArrayList<>();
        returnPoints.clear();
        for (ServerPlayer p : getOnlinePlayers()) {
            if (p.serverLevel() != tableWorld()) {
                travel = true;
                // Ke sinilah ia dikembalikan setelah cutscene akhir game
                rememberReturnPoint(p);
            }
            // Hanya yang sedunia dengan pemulai game yang bisa tersedot portal yang sama
            if (cutsceneEnabled && isOnline(initiator) && p.serverLevel() == initiator.serverLevel()) {
                pulled.add(p);
            } else {
                seatAtTable(p);
            }
        }

        if (!pulled.isEmpty()) {
            PortalCutscene.stop(this);
            PortalCutscene.play(this, initiator.serverLevel(), pulled, initiator.getYRot(), -1, this::seatAtTable);
            wait = PortalTimeline.delay(pulled.size() - 1) + PortalTimeline.PLAYER_TICKS + PortalTimeline.TELEPORT_DELAY;
        }

        delayedTasks.add(
            Scheduler.later(wait + (travel ? WAKE_TICKS_AFTER_TRAVEL : WAKE_TICKS), () -> {
                if (!gameRunning) return;
                cutsceneRunning = false;
                startGamePhase();
            })
        );
    }

    /**
     * Pindahkan player ke kursinya (crimson slab) di dimensi Avalon, menghadap tengah lingkaran,
     * dudukkan, lalu mainkan animasi buka mata di client-nya.
     */
    private void seatAtTable(ServerPlayer p) {
        if (!gameRunning) return;
        seatPlayer(p);
        // Dikirim setelah teleport, supaya tiba di client sesudah ia masuk dimensi Avalon
        AvalonNetwork.sendTo(p, new AvalonNetwork.EyeOpen());
    }

    // ===== STOP GAME =====

    /**
     * Hentikan game dan pulangkan semua player ke tempat asalnya; mereka tetap terdaftar.
     *
     * @return false kalau tidak ada game yang berjalan
     */
    public boolean stopGame() {
        if (!gameRunning) return false;
        Map<UUID, ReturnPoint> points = new HashMap<>(returnPoints);
        cleanup();
        sendHome(points);
        broadcast(Txt.t("Game dihentikan oleh admin.", ChatFormatting.RED, ChatFormatting.BOLD));
        return true;
    }

    /**
     * Pulangkan semua player yang online: ke tempat asalnya, atau ke titik spawn dunia kalau ia
     * memulai game dari dimensi Avalon. Yang sedang offline dibereskan saat masuk lagi (releaseLeftover).
     */
    private void sendHome(Map<UUID, ReturnPoint> points) {
        MinecraftServer server = server();
        if (server == null) return;
        for (ServerPlayer p : getOnlinePlayers()) {
            ReturnPoint point = points.get(p.getUUID());
            ServerLevel home = point == null ? null : server.getLevel(point.dimension());
            if (home != null) {
                p.teleportTo(home, point.pos().x, point.pos().y, point.pos().z, point.yaw(), point.pitch());
            } else if (p.serverLevel() == tableWorld()) {
                toWorldSpawn(p);
            }
        }
    }

    private static void toWorldSpawn(ServerPlayer p) {
        ServerLevel overworld = p.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, overworld.getSharedSpawnAngle(), 0f);
    }

    // ===== GAME PHASE =====

    /** Font ikon sendiri (assets/avalon/font/icons.json); U+E000 = logo Discord. */
    private static final net.minecraft.resources.ResourceLocation ICON_FONT =
        new net.minecraft.resources.ResourceLocation(AvalonMod.MOD_ID, "icons");

    /** Logo Discord; warnanya putih supaya warna asli teksturnya yang tampil. */
    private static Component discordIcon() {
        return Component.literal("")
            .withStyle(style -> style.withFont(ICON_FONT).withColor(ChatFormatting.WHITE));
    }

    private void startGamePhase() {
        broadcast(Txt.t("═══════════════════════", ChatFormatting.GOLD));
        broadcast(Txt.blank());
        broadcast(Txt.t("  🤫 GAME DIMULAI 🤫", ChatFormatting.GREEN, ChatFormatting.BOLD));
        broadcast(Txt.t("  Jaga & bantu merlin menyalakan 3 pilar untuk menang!", ChatFormatting.YELLOW));
        broadcast(Txt.t("  Jangan biarkan kubu jahat menggagalkan misi!", ChatFormatting.RED));
        broadcast(Txt.t("  ").append(discordIcon()).append(Txt.t(" @aflahall", ChatFormatting.AQUA))
            .append(Txt.t(" Dev Of ", ChatFormatting.WHITE)).append(Txt.t("@corazonid", ChatFormatting.AQUA)));
        broadcast(Txt.blank());
        broadcast(Txt.t("═══════════════════════", ChatFormatting.GOLD));

        batteryMission.reset(server());
        later(100L, () -> {
            assignRoles();
            startRoleReveal();
        });
    }

    // ===== MISSION PHASE =====

    /** Cek apakah player termasuk tim misi yang sedang berjalan. */
    public boolean isInMissionTeam(Player player) {
        return currentMissionTeam.contains(player.getGameProfile().getName());
    }

    /**
     * Dipanggil VotingManager saat voting berhasil.
     * Implementasi lengkap fase misi.
     *
     * Rule 2: Player tak terpilih → Unseat + Spectator.
     *         Player terpilih → Survival, tidak bisa lari, tangan kosong.
     * Rule 3: Misi baterai (lihat BatteryMission).
     * Rule 4: Sabotage mechanic untuk kubu jahat.
     * Rule 5: End mission & teleport.
     */
    public void startMissionPhase(List<String> team) {
        if (!gameRunning) return;
        missionActive   = true;
        sabotageCount = 0;
        currentMissionTeam = new ArrayList<>(team);

        missionResolving = false;
        sendCrown(false);
        sendLady(false);

        // ── Gudang diisi penuh, rak tiap pilar dibuka sebanyak anggota tim ────
        batteryMission.start(server(), team.size());

        for (String playerName : getRegisteredPlayers()) {
            ServerPlayer p = getPlayerExact(playerName);
            if (p == null) continue;

            boolean inTeam = team.contains(playerName);

            if (!inTeam) {
                // ── Player tak terpilih → Unseat + Spectator ─────────────────
                unseatPlayer(p);
                p.setGameMode(GameType.SPECTATOR);
                p.sendSystemMessage(Txt.t("  Kamu tidak terpilih dalam misi ini. Mode penonton.", ChatFormatting.GRAY));
            } else {
                // ── Player terpilih → Survival, tidak bisa lari (lihat blockSprint), tangan kosong ───
                unseatPlayer(p);
                p.setGameMode(GameType.SURVIVAL);

                // Tangan kosong: baterainya diambil sendiri dari gudang
                p.getInventory().clearContent();
                setHeldItemSlot(p, 0);

                Role role = getRole(p);
                boolean isEvil = role != null && role.isEvil();

                p.sendSystemMessage(Txt.blank());
                p.sendSystemMessage(Txt.t("  🔋 Misi ke-" + currentRound + " dimulai!", ChatFormatting.GREEN, ChatFormatting.BOLD));
                p.sendSystemMessage(Txt.t("  Anggota tim: ", ChatFormatting.WHITE).append(Txt.t(String.join(", ", team), ChatFormatting.GREEN, ChatFormatting.BOLD)));
                p.sendSystemMessage(Txt.t("  Ambil 1 baterai di gudang, lalu pasang di rak salah satu pilar.", ChatFormatting.YELLOW));
                p.sendSystemMessage(Txt.t("  Semua anggota tim harus memasang di pilar yang sama.", ChatFormatting.YELLOW));
                if (isEvil) {
                    p.sendSystemMessage(Txt.t("  Klik kanan sambil memegang baterai untuk ganti ke mode sabotase.", ChatFormatting.RED));
                }
                p.sendSystemMessage(Txt.blank());
            }
        }

        // ── Anggota tim yang offline diwakili bot-nya ────────────────────────
        for (String name : team) {
            if (getPlayerExact(name) == null) batteryMission.addBot(name);
        }

        // ── Lock hotbar untuk team member ────────────────────────────────────
        startHotbarLock(team);

        // ── Sabotage mechanic (actionbar) ────────────────────────────────────
        startSabotageMechanic(team);

        // Seluruh tim sudah offline sebelum misi dimulai: bot tidak pernah memilih pilar sendiri,
        // jadi tanpa ini misinya tidak akan selesai
        checkAllMissionTeamOffline();
    }

    /** Setara PlayerInventory#setHeldItemSlot. */
    private void setHeldItemSlot(ServerPlayer p, int slot) {
        p.getInventory().selected = slot;
        p.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }

    /** Turunkan player dari kursinya dan bebaskan dari efek serta kunci fase duduk. */
    private void unseatPlayer(ServerPlayer p) {
        releaseFromSeat(p);
        clearRevealEffects(p);
        unlockCamera(p);
        unlockMovement(p);
    }

    // ── Hotbar Lock ───────────────────────────────────────────────────────────

    private Task hotbarLockTask;

    /**
     * Setiap tick, paksa team member kembali ke slot 0.
     */
    private void startHotbarLock(List<String> team) {
        stopHotbarLock();
        hotbarLockTask = new Task() {
            @Override
            public void run() {
                if (!missionActive || !gameRunning) { cancel(); return; }
                for (String name : team) {
                    ServerPlayer p = getPlayerExact(name);
                    if (p == null) continue;
                    if (p.getInventory().selected != 0) {
                        setHeldItemSlot(p, 0);
                    }
                    blockSprint(p);
                }
            }
        }.runTimer(0L, 1L);
    }

    /**
     * Lapar 6 ke bawah, vanilla tidak mengizinkan lari (jalan & lompat tetap bisa). Ditahan di 4,
     * bukan 6: di difficulty Peaceful lapar naik sendiri 1 tiap 10 tick, dan begitu client melihat 7
     * ia sempat lari sebentar sebelum diturunkan lagi.
     */
    private static final int NO_SPRINT_FOOD = 4;

    /**
     * Anggota tim misi tidak bisa lari, supaya misinya tidak selesai terlalu cepat. Dipanggil tiap
     * tick: laparnya ditahan di angka itu (tidak turun sampai kelaparan); bar laparnya
     * disembunyikan client selama game.
     */
    private static void blockSprint(ServerPlayer p) {
        if (p.getFoodData().getFoodLevel() != NO_SPRINT_FOOD) p.getFoodData().setFoodLevel(NO_SPRINT_FOOD);
        p.setSprinting(false);
        p.getFoodData().setSaturation(0f);
    }

    private static void restoreFood(ServerPlayer p) {
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5f);
    }

    private void stopHotbarLock() {
        if (hotbarLockTask != null) {
            hotbarLockTask.cancel();
            hotbarLockTask = null;
        }
    }

    // ── Sabotage Mechanic ─────────────────────────────────────────────────────

    /**
     * Rule 4: Kubu Jahat bisa sabotase selama misi.
     * - Actionbar jahat: mode baterainya (normal / sabotase)
     * - Ganti mode: lihat MissionListener (klik kanan sambil memegang baterai)
     */
    private void startSabotageMechanic(List<String> team) {
        stopSabotageMechanic();

        // Actionbar jahat
        missionEvilActionBarTask = new Task() {

            @Override
            public void run() {
                if (!missionActive || !gameRunning) { cancel(); return; }

                for (String name : team) {
                    ServerPlayer p = getPlayerExact(name);
                    if (p == null) continue;
                    Role role = getRole(p);
                    if (role != null && role.isEvil()) {
                        // Rule 4: mode baterainya sekarang (hanya terlihat oleh dia sendiri)
                        Component line = batteryMission.isActive() ? batteryMission.modeLine(p) : null;
                        if (line != null) Fx.actionBar(p, line);
                    }
                }
            }
        }.runTimer(0L, 20L);
    }

    private void stopSabotageMechanic() {
        if (missionEvilActionBarTask != null) {
            missionEvilActionBarTask.cancel();
            missionEvilActionBarTask = null;
        }
    }

    // ── Misi baterai ──────────────────────────────────────────────────────────

    public BatteryMission getBatteryMission() {
        return batteryMission;
    }

    /**
     * Rak salah satu pilar sudah penuh: mainkan cutscene pilarnya untuk semua player, lalu langsung
     * kembali ke kursi. Tiang cahayanya naik sama persis untuk sukses maupun gagal, jadi hasilnya
     * baru ketahuan saat tiang sampai di puncak.
     *
     * @param site      indeks pilar di {@link AvalonPillars#SITES}
     * @param sabotages jumlah baterai yang dipasang dalam mode sabotase
     */
    public void completeBatteryMission(int site, int sabotages) {
        if (!missionActive || missionResolving) return;
        missionResolving = true;
        sabotageCount = sabotages;

        boolean needsTwoFails = TeamSelectionGUI.requiresTwoFails(registeredPlayers.size(), currentRound);
        boolean success = sabotages < (needsTwoFails ? 2 : 1);

        MinecraftServer server = server();
        ServerLevel level = server == null ? null : server.getLevel(AvalonDimensions.AVALON);
        if (level == null) return;
        AvalonPillars.Site pillarSite = AvalonPillars.SITES.get(site);
        BlockPos pillar = pillarSite.pillarPos();
        double x = pillar.getX() + 0.5;
        double z = pillar.getZ() + 0.5;
        double orbY = pillar.getY() + PillarBlock.ORB_HEIGHT + 0.5;
        int lead = PillarTimeline.LEAD_TICKS;

        if (success) batteryMission.markCompleted(site);
        startPillarCutscene(level, pillarSite, success ? PillarTimeline.SUCCESS_TICKS : PillarTimeline.FAIL_TICKS);

        // Kamera sudah di tempat: pilar mulai menyala
        delayedTasks.add(Scheduler.later(lead, () -> {
            if (!gameRunning) return;
            Fx.worldSound(level, x, pillar.getY(), z, SoundEvents.BEACON_ACTIVATE, 4f, 0.7f);
            if (success) {
                PillarBlock.setLit(level, pillar, true);
            } else {
                PillarBlock.overload(level, pillar);
            }
        }));

        if (success) {
            // Tiang sampai di puncak: bola terbentuk
            delayedTasks.add(Scheduler.later(lead + PillarBlock.RISE_TICKS, () -> {
                if (!gameRunning) return;
                Fx.worldSound(level, x, orbY, z, SoundEvents.BEACON_POWER_SELECT, 4f, 1.0f);
                for (ServerPlayer p : getOnlinePlayers()) Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            }));
            delayedTasks.add(Scheduler.later(PillarTimeline.SUCCESS_TICKS, () -> {
                if (!gameRunning) return;
                endPillarCutscene();
                finishMission();
            }));
        } else {
            // Tiang sampai di puncak: energinya meluap
            delayedTasks.add(Scheduler.later(lead + PillarBlock.RISE_TICKS, () -> {
                if (!gameRunning) return;
                Fx.worldSound(level, x, orbY, z, SoundEvents.BEACON_DEACTIVATE, 4f, 0.6f);
                Fx.worldSound(level, x, orbY, z, SoundEvents.WARDEN_SONIC_CHARGE, 4f, 0.7f);
            }));
            // Bolanya pecah
            delayedTasks.add(Scheduler.later(lead + PillarBlock.OVERLOAD_BURST_TICK, () -> {
                if (!gameRunning) return;
                Fx.worldSound(level, x, orbY, z, SoundEvents.WARDEN_SONIC_BOOM, 4f, 0.9f);
                Fx.worldSound(level, x, orbY, z, SoundEvents.GLASS_BREAK, 4f, 0.5f);
                Fx.particle(level, ParticleTypes.FLASH, x, orbY, z, 1, 0, 0, 0, 0);
                Fx.particle(level, ParticleTypes.END_ROD, x, orbY, z, 120, 0.2, 0.2, 0.2, 0.45);
                Fx.particle(level, ParticleTypes.LARGE_SMOKE, x, orbY, z, 40, 0.6, 0.6, 0.6, 0.08);
            }));
            delayedTasks.add(Scheduler.later(PillarTimeline.FAIL_TICKS, () -> {
                if (!gameRunning) return;
                endPillarCutscene();
                triggerSabotageCountdown();
            }));
        }
    }

    // ── Cutscene pilar ────────────────────────────────────────────────────────

    /** Tinggi tempat para player diparkir selama cutscene, di atas bola pilar. */
    private static final int CUTSCENE_PARK_HEIGHT = PillarBlock.ORB_HEIGHT + 12;
    private boolean pillarCutsceneRunning = false;

    /**
     * Semua player dibawa ke dekat pilar (tak terlihat, melayang di atasnya, tidak bisa bergerak)
     * supaya area itu dimuat client-nya; kameranya sendiri digerakkan client (PillarCutsceneClient).
     */
    private void startPillarCutscene(ServerLevel level, AvalonPillars.Site site, int duration) {
        pillarCutsceneRunning = true;
        BlockPos pillar = site.pillarPos();
        AvalonNetwork.PillarCutscene start = new AvalonNetwork.PillarCutscene(true, pillar,
                site.facing().getStepX(), site.facing().getStepZ(), duration);

        for (ServerPlayer p : getOnlinePlayers()) {
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.setGameMode(GameType.ADVENTURE);
            p.getAbilities().mayfly = true;
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            // Efek, bukan setInvisible: flag-nya dihitung ulang dari efek tiap ada efek yang berubah
            // (mis. Slowness yang baru dicabut di atas), jadi tanpa efek player-nya terlihat lagi
            p.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, duration + 40, 0, false, false, false));
            teleport(p, level, pillar.getX() + 0.5, pillar.getY() + CUTSCENE_PARK_HEIGHT, pillar.getZ() + 0.5, 0f, 0f);
            lockMovement(p);
            AvalonNetwork.sendTo(p, start);
        }
    }

    /** Kembalikan player seperti semula; setelah ini mereka dipindahkan ke kursi. */
    private void endPillarCutscene() {
        if (!pillarCutsceneRunning) return;
        pillarCutsceneRunning = false;
        for (ServerPlayer p : getOnlinePlayers()) {
            unlockMovement(p);
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.removeEffect(MobEffects.INVISIBILITY);
            AvalonNetwork.sendTo(p, AvalonNetwork.PillarCutscene.STOP);
        }
    }

    /** Klik kiri dari client (lihat AvalonNetwork.LeftClick): dipakai item voting untuk "Tolak". */
    public void handleLeftClick(ServerPlayer player) {
        if (!gameRunning || votingManager == null || !votingManager.isVotingActive()) return;
        if (!votingManager.isVoteItem(player.getMainHandItem())) return;
        // Klik kiri sering terjadi: jangan ulangi suara yang sama
        if (VotingManager.VOTE_TOLAK.equals(votingManager.getVote(player))) return;
        votingManager.castVote(player, VotingManager.VOTE_TOLAK);
    }

    /**
     * Bongkar misi yang sedang berjalan, apa pun hasilnya (sukses, disabotase, atau dibatalkan):
     * rak pilar ditutup, bot berhenti, dan semua player kembali ke Adventure.
     *
     * @return false kalau memang tidak ada misi yang berjalan
     */
    private boolean endMission() {
        if (!missionActive) return false;
        missionActive = false;
        batteryMission.stop(server());
        stopHotbarLock();
        stopSabotageMechanic();
        for (ServerPlayer p : getOnlinePlayers()) {
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            restoreFood(p);
            setMode(p, GameType.ADVENTURE);
        }
        return true;
    }

    /**
     * Dipanggil saat pilar yang raknya penuh ternyata disabotase (bolanya pecah).
     * Tampilkan pesan, lalu langsung teleport semua player ke seat.
     */
    private void triggerSabotageCountdown() {
        String teamMembers = String.join(", ", currentMissionTeam);
        if (!endMission()) return;

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.t("  ☠ Misi telah di sabotase!", ChatFormatting.RED, ChatFormatting.BOLD));
        broadcast(Txt.t("  Kembali lagi nanti!", ChatFormatting.DARK_RED));
        broadcast(
            Txt.t("  Jumlah sabotase: ", ChatFormatting.GRAY)
                .append(Txt.t(sabotageCount, ChatFormatting.RED, ChatFormatting.BOLD))
        );
        broadcast(
            Txt.t("  Anggota tim: ", ChatFormatting.WHITE)
                .append(Txt.t(teamMembers, ChatFormatting.GREEN, ChatFormatting.BOLD))
        );
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.blank());

        // Cutscene pilar sudah menunjukkan hasilnya: langsung kembali ke kursi
        teleportAllToSeat();
        if (evilMissionFails + 1 >= 3) {
            triggerEvilWin("3 misi telah disabotase");
            return;
        }
        startAfterMissionPhase(false);
    }

    // ── End Mission ───────────────────────────────────────────────────────────

    /** Akhiri misi sukses (pilar menyala). Sabotase ditangani oleh triggerSabotageCountdown(). */
    private void finishMission() {
        String teamMembers = String.join(", ", currentMissionTeam);
        if (!endMission()) return;

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GREEN));
        broadcast(Txt.t("  ✅ Misi ke-" + currentRound + " berhasil!", ChatFormatting.GREEN, ChatFormatting.BOLD));
        broadcast(Txt.t("  Pilar berhasil dinyalakan oleh tim.", ChatFormatting.YELLOW));
        broadcast(
            Txt.t("  Jumlah sabotase: ", ChatFormatting.GRAY)
                .append(Txt.t(sabotageCount, ChatFormatting.RED, ChatFormatting.BOLD))
        );
        broadcast(
            Txt.t("  Anggota tim: ", ChatFormatting.WHITE)
                .append(Txt.t(teamMembers, ChatFormatting.GREEN, ChatFormatting.BOLD))
        );
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GREEN));
        broadcast(Txt.blank());

        // Cutscene pilar sudah menunjukkan hasilnya: langsung kembali ke kursi
        teleportAllToSeat();

        // Delay 15L: beri waktu client menerima posisi kursinya, lalu konfirmasi rotasi ke base dan lanjut
        later(15L, () -> {
            for (ServerPlayer p : getOnlinePlayers()) {
                float yaw = yawTowardBase(p.getX(), p.getZ());
                setRotation(p, yaw, 0);
            }
            if (currentMission >= 3) {
                if (!playerRoles.containsValue(Role.ASSASSIN)) {
                    // Susunan role tanpa Assassin (customrole): tidak ada yang bisa menebak Merlin,
                    // kubu baik langsung menang
                    triggerGoodWin();
                    return;
                }
                // Assassin yang sedang offline tetap diberi kesempatan (lihat grace di giveAssassinBow)
                startAssassinationPhase();
            } else {
                startAfterMissionPhase(true);
            }
        });
    }

    /**
     * Semua player kembali duduk di kursinya setelah misi selesai; bot player yang offline juga.
     * Rule 5: All Players → Seat + Adventure
     */
    private void teleportAllToSeat() {
        // Misi sudah selesai: mahkota raja & token Lady muncul lagi
        sendCrown(false);
        sendLady(false);

        for (int i = 0; i < Math.min(registeredPlayers.size(), PLAYER_SLAB_POSITIONS.length); i++) {
            ServerPlayer p = getPlayerExact(registeredPlayers.get(i));
            if (p == null) {
                reseatOfflineMannequin(i, registeredPlayers.get(i));
                continue;
            }
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.getInventory().clearContent();
            Fx.actionBar(p, Txt.blank());
            seatPlayer(p);
        }
    }

    // ── Lady of the Lake ──────────────────────────────────────────────────────

    public LadyMode getLadyMode() {
        return ladyMode;
    }

    /** Dibaca saat raja pertama dipilih (lihat {@link #isLadyDecided()}): sesudah itu baru berlaku di game berikutnya. */
    public void setLadyMode(LadyMode ladyMode) {
        this.ladyMode = ladyMode;
    }

    /** Game yang sedang berjalan sudah menentukan pemegang Lady pertamanya (atau tidak memakainya). */
    public boolean isLadyDecided() {
        return gameRunning && !kingOrder.isEmpty();
    }

    public String getLadySelection() {
        return ladySelection;
    }

    public void setLadySelection(String name) {
        ladySelection = name;
    }

    /** Apakah game dengan jumlah player terdaftar saat ini memakai Lady of the Lake. */
    public boolean isLadyEnabled() {
        return ladyMode == LadyMode.ON
            || (ladyMode == LadyMode.AUTO && registeredPlayers.size() >= LADY_AUTO_MIN_PLAYERS);
    }

    public String getLadyHolder() {
        return ladyHolder;
    }

    public boolean isLadyHolder(Player player) {
        return player.getGameProfile().getName().equals(ladyHolder);
    }

    public List<String> getLadyPastHolders() {
        return Collections.unmodifiableList(ladyPastHolders);
    }

    /** Berapa pemeriksaan yang hasilnya sudah dicatat untuk {@code holderName}. */
    public int getLadyResultCount(String holderName) {
        List<LadyResult> results = ladyResults.get(holderName);
        return results == null ? 0 : results.size();
    }

    /** Yang bisa diperiksa: semua kecuali pemegangnya sendiri dan yang pernah memegang Lady (offline pun boleh). */
    public List<String> getLadyCandidates() {
        List<String> list = new ArrayList<>();
        for (String name : registeredPlayers) {
            if (!name.equals(ladyHolder) && !ladyPastHolders.contains(name)) list.add(name);
        }
        return list;
    }

    /** Umumkan pemegang Lady pertama (bersamaan dengan raja pertama); tokennya terbentuk di badannya. */
    private void announceLadyHolder() {
        if (ladyHolder == null) return;
        broadcast(
            Txt.t("  🌊 Lady of the Lake dipegang oleh ", ChatFormatting.AQUA)
                .append(Txt.t(ladyHolder, ChatFormatting.WHITE, ChatFormatting.BOLD))
        );
        broadcast(Txt.t("  Setelah misi ke-2, 3 dan 4 ia memeriksa kesetiaan satu pemain.", ChatFormatting.GRAY));
        broadcast(Txt.blank());
        sendLady(true);
    }

    /** Kirim token Lady ke semua player ({@code animate} = muncul / terbang dari pemegang sebelumnya). */
    private void sendLady(boolean animate) {
        for (ServerPlayer p : getOnlinePlayers()) sendLadyTo(p, animate);
    }

    private void sendLadyTo(ServerPlayer p, boolean animate) {
        // Disembunyikan bersama mahkota raja: selama misi dan selagi raja pertama belum diumumkan
        if (ladyHolder == null || missionActive || kingRouletteRunning) {
            AvalonNetwork.sendTo(p, AvalonNetwork.Lady.NONE);
            return;
        }
        AvalonNetwork.sendTo(p, new AvalonNetwork.Lady(ladyHolder, registeredPlayers.indexOf(ladyHolder), animate));
    }

    /** Berikan item Lady of the Lake ke pemegangnya. */
    public void giveLadyItem(ServerPlayer player) {
        ItemStack token = AvalonItems.named(
            Items.HEART_OF_THE_SEA,
            Txt.t("Lady of the Lake", ChatFormatting.AQUA, ChatFormatting.BOLD),
            List.of(
                Txt.t("Klik kanan untuk memilih pemain", ChatFormatting.GRAY),
                Txt.t("yang kesetiaannya kamu periksa.", ChatFormatting.GRAY)
            )
        );
        AvalonItems.setTag(token, KEY_LADY_TOKEN, "true");
        player.getInventory().setItem(0, token);
    }

    /** Cek apakah item adalah item Lady of the Lake. */
    public static boolean isLadyItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.HEART_OF_THE_SEA)) return false;
        return AvalonItems.hasTag(item, KEY_LADY_TOKEN);
    }

    private void stopLadyTask() {
        if (ladyTask != null) {
            ladyTask.cancel();
            ladyTask = null;
        }
    }

    /**
     * Seusai misi (semua sudah kembali duduk): Lady of the Lake dulu kalau ini gilirannya (misi ke-2,
     * 3 dan 4), baru fase diskusi. Game yang sudah ditentukan pemenangnya tidak lewat sini.
     */
    private void startAfterMissionPhase(boolean afterSuccess) {
        if (!gameRunning) return;
        if (ladyHolder != null && currentRound >= 2 && currentRound <= 4 && !getLadyCandidates().isEmpty()) {
            startLadyPhase(afterSuccess);
        } else {
            startDiscussionPhase(afterSuccess);
        }
    }

    /**
     * Pemegang Lady memilih satu pemain lewat GUI. Waktunya habis (termasuk kalau ia sedang offline):
     * targetnya dipilih acak.
     */
    private void startLadyPhase(boolean afterSuccess) {
        ladyActive = true;
        ladySelection = null;
        ladyAfterSuccess = afterSuccess;
        final String holderName = ladyHolder;

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.t("  🌊 LADY OF THE LAKE", ChatFormatting.AQUA, ChatFormatting.BOLD));
        broadcast(
            Txt.t("  ", ChatFormatting.WHITE)
                .append(Txt.t(holderName, ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(" akan memeriksa kesetiaan satu pemain.", ChatFormatting.WHITE))
        );
        broadcast(Txt.t("  Waktu memilih " + ladySeconds + " detik. Kalau habis, dipilih acak.", ChatFormatting.GRAY));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.blank());

        ServerPlayer holder = getPlayerExact(holderName);
        if (holder != null) {
            Fx.title(holder,
                "§b§l🌊 LADY OF THE LAKE",
                "§fKlik kanan untuk memilih pemain yang kamu periksa",
                10, 70, 20
            );
            Fx.sound(holder, SoundEvents.CONDUIT_ACTIVATE, 1f, 1.2f);
            giveLadyItem(holder);
        } else {
            broadcast(
                Txt.t("  🌊 Pemegang Lady offline! Target dipilih acak jika ia tidak kembali sebelum waktu habis.", ChatFormatting.AQUA)
            );
        }

        stopLadyTask();
        ladyTask = new Task() {
            int seconds = ladySeconds;

            @Override
            public void run() {
                if (!gameRunning || !ladyActive) { cancel(); return; }

                if (seconds <= 0) {
                    cancel();
                    List<String> candidates = getLadyCandidates();
                    // Sudah memilih di GUI tapi belum menekan konfirmasi: pilihannya yang dipakai
                    if (ladySelection != null && candidates.contains(ladySelection)) {
                        resolveLady(ladySelection, false);
                    } else {
                        resolveLady(candidates.get((int) (Math.random() * candidates.size())), true);
                    }
                    return;
                }

                ChatFormatting timeColor = seconds > 30
                    ? ChatFormatting.GREEN
                    : (seconds > 10 ? ChatFormatting.YELLOW : ChatFormatting.RED);

                Component bar = Txt.t("🌊 Lady of the Lake | ", ChatFormatting.AQUA)
                    .append(Txt.t(String.format("%d:%02d", seconds / 60, seconds % 60), timeColor, ChatFormatting.BOLD))
                    .append(Txt.t(" | Menunggu " + holderName + " memilih...", ChatFormatting.GRAY));

                for (ServerPlayer p : getOnlinePlayers()) {
                    Fx.actionBar(p, bar);
                }

                seconds--;
            }
        }.runTimer(0L, 20L);
    }

    /** Dipanggil LadyListener saat pemegang mengonfirmasi pilihannya di GUI. */
    public void confirmLadyTarget(ServerPlayer holder, String target) {
        // GUI yang masih terbuka setelah fasenya lewat tidak boleh memulai pemeriksaan
        if (!gameRunning || !ladyActive || !isLadyHolder(holder)) return;
        if (!getLadyCandidates().contains(target)) return;
        resolveLady(target, false);
    }

    /**
     * Target sudah ditentukan: mainkan animasinya (lihat LadyTimeline), beri tahu hasilnya hanya ke
     * pemegang, pindahkan Lady ke target, lalu lanjut ke fase diskusi.
     */
    private void resolveLady(String target, boolean random) {
        final String holderName = ladyHolder;
        Role role = getRoleByName(target);
        final boolean evil = role != null && role.isEvil();

        ladyActive = false;
        ladyResolving = true;
        ladyLeaving = true;
        ladySelection = null;
        stopLadyTask();

        ServerPlayer holder = getPlayerExact(holderName);
        if (holder != null) {
            // Waktunya habis selagi GUI-nya masih terbuka
            if (holder.containerMenu instanceof AvalonMenu) holder.closeContainer();
            Inventory inv = holder.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (isLadyItem(inv.getItem(i))) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        for (ServerPlayer p : getOnlinePlayers()) {
            Fx.actionBar(p, Txt.blank());
        }

        broadcast(
            Txt.t("  🌊 ", ChatFormatting.AQUA)
                .append(Txt.t(holderName, ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(" memeriksa kesetiaan ", ChatFormatting.AQUA))
                .append(Txt.t(target, ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(random ? " (waktu habis, dipilih acak)." : ".", ChatFormatting.AQUA))
        );

        // Kubu target hanya dikirim ke client si pemegang; yang lain melihat roh yang sama polosnya
        int targetSeat = registeredPlayers.indexOf(target);
        int holderSeat = registeredPlayers.indexOf(holderName);
        for (ServerPlayer p : getOnlinePlayers()) {
            int result = p != holder ? AvalonNetwork.LadyInspect.HIDDEN
                : (evil ? AvalonNetwork.LadyInspect.EVIL : AvalonNetwork.LadyInspect.GOOD);
            AvalonNetwork.sendTo(p, new AvalonNetwork.LadyInspect(target, targetSeat, holderName, holderSeat, result));
        }

        later(LadyTimeline.ARRIVE, () -> {
            // Dicatat juga untuk pemegang yang sedang offline: ia diberi tahu saat masuk lagi
            ladyResults.computeIfAbsent(holderName, k -> new ArrayList<>()).add(new LadyResult(target, evil));
            ServerPlayer h = getPlayerExact(holderName);
            if (h != null) {
                Fx.title(h,
                    evil ? "§c§lKUBU JAHAT" : "§b§lKUBU BAIK",
                    "§f" + target + " §7— hanya kamu yang tahu",
                    5, 80, 20
                );
                sendLadyResult(h, new LadyResult(target, evil));
                h.sendSystemMessage(Txt.t("  Hanya kamu yang tahu. Kamu boleh jujur, boleh juga berbohong.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
                h.sendSystemMessage(Txt.blank());
            }
            ServerPlayer t = getPlayerExact(target);
            if (t != null) {
                Fx.title(t, "§b§l🌊 KAMU DIPERIKSA", "§f" + holderName + " §7kini tahu kubumu", 5, 60, 20);
            }
        });

        later(LadyTimeline.TOKEN, () -> {
            ladyPastHolders.add(holderName);
            ladyHolder = target;
            ladyLeaving = false;
            sendLady(true);
            broadcast(
                Txt.t("  🌊 Lady of the Lake berpindah ke ", ChatFormatting.AQUA)
                    .append(Txt.t(target, ChatFormatting.WHITE, ChatFormatting.BOLD))
            );
        });

        later(LadyTimeline.END, () -> {
            ladyResolving = false;
            startDiscussionPhase(ladyAfterSuccess);
        });
    }

    private void sendLadyResult(ServerPlayer p, LadyResult result) {
        p.sendSystemMessage(
            Txt.t("  🌊 ", ChatFormatting.AQUA)
                .append(Txt.t(result.target(), ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(" adalah ", ChatFormatting.AQUA))
                .append(result.evil()
                    ? Txt.t("Kubu Jahat", ChatFormatting.RED, ChatFormatting.BOLD)
                    : Txt.t("Kubu Baik", ChatFormatting.AQUA, ChatFormatting.BOLD))
        );
    }

    /** Player masuk lagi: semua hasil pemeriksaannya dikirim ulang (bisa saja ia offline saat itu). */
    private void sendLadyResults(ServerPlayer p) {
        List<LadyResult> results = ladyResults.get(p.getGameProfile().getName());
        if (results == null) return;
        for (LadyResult result : results) sendLadyResult(p, result);
        p.sendSystemMessage(Txt.blank());
    }

    // ── Discussion Phase ──────────────────────────────────────────────────────

    /**
     * Mulai fase diskusi.
     * Dipanggil setelah misi gagal (sabotase) dan setelah misi sukses.
     * @param afterSuccess true = lanjut ke onMissionSuccess, false = onMissionFailed
     */
    private void startDiscussionPhase(boolean afterSuccess) {
        if (!gameRunning) return;
        discussionActive = true;
        discussionAfterSuccess = afterSuccess;
        discussionSkipVotes.clear();

        // Bagikan item skip ke semua player yang online
        for (ServerPlayer p : getOnlinePlayers()) {
            giveDiscussionSkipItem(p);
        }

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.YELLOW));
        broadcast(Txt.t("  💬 FASE DISKUSI DIMULAI!", ChatFormatting.YELLOW, ChatFormatting.BOLD));
        // broadcast(Txt.t("  Diskusikan strategi selama 10 menit.", ChatFormatting.WHITE));
        broadcast(Txt.t("  Klik kanan untuk vote skip.", ChatFormatting.GRAY));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.YELLOW));
        broadcast(Txt.blank());

        stopDiscussionPhase(); // pastikan task lama bersih
        startDiscussionHeadAnimation();

        discussionTask = new Task() {
            int seconds = discussionSeconds;

            @Override
            public void run() {
                if (!gameRunning || !discussionActive) { cancel(); return; }

                if (seconds <= 0) {
                    cancel();
                    endDiscussion(afterSuccess);
                    return;
                }

                int minutes = seconds / 60;
                int secs    = seconds % 60;
                String timeStr = String.format("%d:%02d", minutes, secs);

                int skipCount   = discussionSkipVotes.size();
                int totalOnline = getOnlinePlayers().size();

                ChatFormatting timeColor = seconds > 300
                    ? ChatFormatting.GREEN
                    : (seconds > 120 ? ChatFormatting.YELLOW : ChatFormatting.RED);

                Component bar = Txt.t("💬 Diskusi | ", ChatFormatting.YELLOW)
                    .append(Txt.t(timeStr, timeColor, ChatFormatting.BOLD))
                    .append(Txt.t(" | Skip: " + skipCount + "/" + totalOnline, ChatFormatting.GRAY));

                for (ServerPlayer p : getOnlinePlayers()) {
                    Fx.actionBar(p, bar);
                }

                seconds--;
            }
        }.runTimer(0L, 20L);
    }

    /** Hentikan discussion task dan bersihkan state. */
    private void stopDiscussionPhase() {
        if (discussionTask != null) {
            discussionTask.cancel();
            discussionTask = null;
        }
        if (discussionHeadAnimTask != null) {
            discussionHeadAnimTask.cancel();
            discussionHeadAnimTask = null;
        }
        clearDiscussionSkipHeads();
    }

    /** Item kepala skip (tanpa nama) untuk floating head. */
    private ItemStack skipHeadItem() {
        return AvalonItems.texturedHead(SKIP_TEXTURE, null, null);
    }

    /** Tinggi kepala melayang di atas player: raja lebih tinggi supaya tidak menabrak mahkotanya. */
    public static final double KING_HEAD_HEIGHT = 1.95;
    /** Kepala vote raja (centang / silang) di atas barisan kepala pilihan timnya. */
    public static final double KING_VOTE_HEAD_HEIGHT = 2.6;

    private double headHeight(ServerPlayer player) {
        return isKing(player) ? KING_HEAD_HEIGHT : 1.5;
    }

    /** Spawn / update floating head skip di atas player yang vote. */
    private void spawnDiscussionSkipHead(ServerPlayer voter) {
        // Hapus head lama kalau ada
        ArmorStand old = discussionSkipHeads.remove(voter.getUUID());
        if (old != null && old.isAlive()) old.discard();

        ArmorStand stand = spawnStand(voter.serverLevel(),
            voter.getX(), voter.getY() + headHeight(voter), voter.getZ(), voter.getYRot(), true, true, false);
        stand.addTag("avalon_discussion_head");

        // Pakai item skip sebagai helm
        stand.setItemSlot(EquipmentSlot.HEAD, skipHeadItem());

        discussionSkipHeads.put(voter.getUUID(), stand);
    }

    /** Hapus semua floating head diskusi. */
    private void clearDiscussionSkipHeads() {
        for (ArmorStand stand : discussionSkipHeads.values()) {
            if (stand != null && stand.isAlive()) stand.discard();
        }
        discussionSkipHeads.clear();
    }

    /** Mulai animasi floating (naik-turun + rotasi) untuk head diskusi. */
    private void startDiscussionHeadAnimation() {
        if (discussionHeadAnimTask != null) {
            discussionHeadAnimTask.cancel();
        }
        discussionHeadAnimTask = new Task() {
            double tick = 0;

            @Override
            public void run() {
                if (!discussionActive && discussionSkipHeads.isEmpty()) {
                    cancel();
                    return;
                }
                tick += 0.25;
                animateHeads(discussionSkipHeads, tick);
            }
        }.runTimer(0L, 1L);
    }

    /** Animasi head: ikuti player (Y+1.5, raja lebih tinggi), naik-turun sin, rotasi +3°/tick. */
    private void animateHeads(Map<UUID, ArmorStand> heads, double tick) {
        for (Map.Entry<UUID, ArmorStand> entry : new HashMap<>(heads).entrySet()) {
            ArmorStand stand = entry.getValue();
            if (stand == null || !stand.isAlive()) {
                heads.remove(entry.getKey());
                continue;
            }
            ServerPlayer owner = getPlayer(entry.getKey());
            if (owner == null) continue;

            double offsetY = Math.sin(tick + entry.getKey().hashCode() * 0.1) * 0.08;
            float yaw = net.minecraft.util.Mth.wrapDegrees(stand.getYRot() + 3.0f);
            moveStand(stand, owner.serverLevel(), owner.getX(), owner.getY() + headHeight(owner) + offsetY, owner.getZ(), yaw);
        }
    }

    /** Pindahkan armor stand (setara stand.teleport(loc)), termasuk antar dimensi. */
    public static void moveStand(ArmorStand stand, ServerLevel level, double x, double y, double z, float yaw) {
        if (stand.level() != level) {
            stand.teleportTo(level, x, y, z, Set.of(), yaw, 0f);
            return;
        }
        stand.moveTo(x, y, z, yaw, 0f);
        stand.setYBodyRot(yaw);
        stand.setYHeadRot(yaw);
    }

    /** Beri item skip kepala ke player. */
    public void giveDiscussionSkipItem(ServerPlayer player) {
        ItemStack skull = AvalonItems.texturedHead(
            SKIP_TEXTURE,
            Txt.t("⏩ SKIP DISKUSI", ChatFormatting.AQUA, ChatFormatting.BOLD),
            List.of(
                Txt.t("Klik kanan untuk vote skip.", ChatFormatting.GRAY),
                Txt.t("Jika semua player vote, diskusi langsung selesai.", ChatFormatting.GRAY)
            )
        );
        AvalonItems.setTag(skull, KEY_DISCUSSION_SKIP, "true");

        // Taruh di slot 0 (hotbar 1)
        player.getInventory().setItem(0, skull);
    }

    /** Hapus item skip dari semua player. */
    private void removeDiscussionSkipItems() {
        for (ServerPlayer p : getOnlinePlayers()) {
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (isDiscussionSkipItem(inv.getItem(i))) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    /**
     * Cek apakah item adalah item skip diskusi.
     */
    public boolean isDiscussionSkipItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return false;
        return AvalonItems.hasTag(item, KEY_DISCUSSION_SKIP);
    }

    /**
     * Dipanggil listener saat player klik kanan item skip.
     * Jika semua player online sudah vote → langsung end diskusi.
     */
    public void handleDiscussionSkip(ServerPlayer player) {
        if (!discussionActive) return;
        if (!gameRunning) return;

        UUID uid = player.getUUID();
        if (discussionSkipVotes.contains(uid)) {
            player.sendSystemMessage(Txt.t("Kamu sudah vote skip!", ChatFormatting.GRAY));
            return;
        }

        discussionSkipVotes.add(uid);
        spawnDiscussionSkipHead(player);
        int skipCount   = discussionSkipVotes.size();
        int totalOnline = getOnlinePlayers().size();

        broadcast(
            Txt.t("  » ", ChatFormatting.GRAY)
                .append(Txt.t(player.getGameProfile().getName(), ChatFormatting.AQUA))
                .append(Txt.t(" vote skip. (" + skipCount + "/" + totalOnline + ")", ChatFormatting.GRAY))
        );
        Fx.sound(player, SoundEvents.UI_BUTTON_CLICK, 1f, 1.2f);

        if (skipCount >= totalOnline) {
            stopDiscussionPhase();
            endDiscussion(discussionAfterSuccess);
        }
    }

    private void checkDiscussionSkipComplete() {
        if (!discussionActive) return;

        int totalOnline = getOnlinePlayers().size();

        if (totalOnline > 0
                && discussionSkipVotes.size() >= totalOnline) {

            stopDiscussionPhase();
            endDiscussion(discussionAfterSuccess);
        }
    }

    /** Akhiri diskusi dan lanjut ke fase berikutnya. */
    private void endDiscussion(boolean afterSuccess) {
        if (!discussionActive) return;
        discussionActive = false;
        stopDiscussionPhase();
        removeDiscussionSkipItems();
        discussionSkipVotes.clear();

        // Clear actionbar
        for (ServerPlayer p : getOnlinePlayers()) {
            Fx.actionBar(p, Txt.blank());
        }

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.YELLOW));
        broadcast(Txt.t("  ✅ Fase diskusi selesai! Lanjut ke babak berikutnya.", ChatFormatting.GREEN, ChatFormatting.BOLD));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.YELLOW));
        broadcast(Txt.blank());

        delayedTasks.add(
            Scheduler.later(20L, () -> {
                if (!gameRunning) return;
                if (afterSuccess) {
                    onMissionSuccess();
                } else {
                    onMissionFailed();
                }
            })
        );
    }

    private void onMissionFailed() {

        evilMissionFails++;
        currentRound++;

        if (evilMissionFails >= 3) {
            triggerEvilWin("3 misi telah disabotase");
            return;
        }

        // Reset reject streak (misi baru = bukan akibat vote reject)
        if (votingManager != null) votingManager.resetRejectStreak();

        delayedTasks.add(
            Scheduler.later(60L, () -> {
                if (!gameRunning) return;
                rotateKing();
            })
        );
    }

    /**
     * Dipanggil setelah misi sukses.
     * Lanjut ke misi berikutnya.
     */
    private void onMissionSuccess() {

        if (votingManager != null)
            votingManager.resetRejectStreak();

        currentMission++;
        currentRound++;

        delayedTasks.add(
            Scheduler.later(60L, () -> {
                if (!gameRunning) return;
                rotateKing();
            })
        );
    }

    // ── Assassination Phase ───────────────────────────────────────────────────

    /**
     * Mulai fase diskusi assassin:
     * - Semua kubu baik tetap duduk di seat
     * - Kubu jahat dieject dari seat, bebas berjalan
     * - Semua kubu jahat dapat item skip
     * - Timer; setelah habis / semua skip → assassin dapat bow
     */
    private void startAssassinationPhase() {
        if (!gameRunning) return;
        assassinationActive = true;
        assassinShotFired = false;
        assassinationSkipVotes.clear();

        MinecraftServer server = server();
        if (server != null) server.setPvpAllowed(true);

        // Eject & bebaskan kubu jahat, kubu baik tetap di seat (lockMovement)
        for (ServerPlayer p : getOnlinePlayers()) {
            Role role = playerRoles.get(p.getUUID());
            if (role != null && role.isEvil()) {
                releaseFromSeat(p);
                unlockMovement(p);
                // Beri item skip
                giveAssassinationSkipItem(p);

                Fx.title(p,
                    "§4§l☠ FASE ASSASSINATION",
                    "§cDiskusikan siapa Merlin!",
                    10, 80, 20
                );
                Fx.sound(p, SoundEvents.WITHER_SPAWN, 0.6f, 1.2f);
            } else {
                // Kubu baik: tetap duduk, lock movement
                lockMovement(p);
                Fx.title(p,
                    "§6§l⚠ KUBU JAHAT BERDISKUSI",
                    "§eAssassin sedang mencari Merlin...",
                    10, 80, 20
                );
            }
        }

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.t("  ☠ FASE ASSASSINATION!", ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        broadcast(Txt.t("  Kubu jahat berdiskusi selama 10 menit.", ChatFormatting.RED));
        broadcast(Txt.t("  Kubu jahat: klik kanan untuk vote skip.", ChatFormatting.GRAY));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.blank());

        startAssassinationHeadAnimation();

        // Timer diskusi kubu jahat
        assassinationTask = new Task() {
            int seconds = evilDiscussionSeconds;

            @Override
            public void run() {
                if (!gameRunning || !assassinationActive) { cancel(); return; }

                if (seconds <= 0) {
                    cancel();
                    endAssassinationDiscussion();
                    return;
                }

                int minutes = seconds / 60;
                int secs    = seconds % 60;
                String timeStr = String.format("%d:%02d", minutes, secs);

                int skipCount = assassinationSkipVotes.size();
                // Hitung total kubu jahat online
                long evilCount = getOnlinePlayers().stream()
                    .filter(p -> { Role r = playerRoles.get(p.getUUID()); return r != null && r.isEvil(); })
                    .count();

                ChatFormatting timeColor = seconds > 300
                    ? ChatFormatting.RED
                    : (seconds > 120 ? ChatFormatting.DARK_RED : ChatFormatting.WHITE);

                Component bar = Txt.t("☠ Assassination | ", ChatFormatting.DARK_RED)
                    .append(Txt.t(timeStr, timeColor, ChatFormatting.BOLD))
                    .append(Txt.t(" | Skip: " + skipCount + "/" + evilCount, ChatFormatting.GRAY));

                for (ServerPlayer p : getOnlinePlayers()) {
                    Fx.actionBar(p, bar);
                }

                seconds--;
            }
        }.runTimer(0L, 20L);

        // Tidak ada kubu jahat yang online: tidak ada yang berdiskusi, langsung ke fase busur
        checkAssassinationSkipComplete();
    }

    /** Beri item skip assassination kepada player kubu jahat. */
    public void giveAssassinationSkipItem(ServerPlayer player) {
        ItemStack skull = AvalonItems.texturedHead(
            SKIP_TEXTURE,
            Txt.t("⏩ SKIP DISKUSI", ChatFormatting.RED, ChatFormatting.BOLD),
            List.of(
                Txt.t("Klik kanan untuk vote skip.", ChatFormatting.GRAY),
                Txt.t("Jika semua kubu jahat vote, diskusi langsung selesai.", ChatFormatting.GRAY)
            )
        );
        AvalonItems.setTag(skull, KEY_ASSASSINATION_SKIP, "true");
        player.getInventory().setItem(0, skull);
    }

    /** Cek apakah item adalah skip assassination. */
    public boolean isAssassinationSkipItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return false;
        return AvalonItems.hasTag(item, KEY_ASSASSINATION_SKIP);
    }

    /** Cek apakah item adalah bow assassin. */
    public boolean isAssassinBowItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.BOW)) return false;
        return AvalonItems.hasTag(item, ASSASSIN_BOW_KEY);
    }

    /**
     * Dipanggil listener saat kubu jahat klik kanan item skip assassination.
     */
    public void handleAssassinationSkip(ServerPlayer player) {
        if (!assassinationActive) return;
        if (!gameRunning) return;

        Role role = playerRoles.get(player.getUUID());
        if (role == null || !role.isEvil()) {
            player.sendSystemMessage(Txt.t("Hanya kubu jahat yang bisa vote skip!", ChatFormatting.RED));
            return;
        }

        UUID uid = player.getUUID();
        if (assassinationSkipVotes.contains(uid)) {
            player.sendSystemMessage(Txt.t("Kamu sudah vote skip!", ChatFormatting.GRAY));
            return;
        }

        assassinationSkipVotes.add(uid);
        spawnAssassinationSkipHead(player);

        long evilCount = getOnlinePlayers().stream()
            .filter(p -> { Role r = playerRoles.get(p.getUUID()); return r != null && r.isEvil(); })
            .count();
        int skipCount = assassinationSkipVotes.size();

        broadcast(
            Txt.t("  » ", ChatFormatting.GRAY)
                .append(Txt.t(player.getGameProfile().getName(), ChatFormatting.RED))
                .append(Txt.t(" vote skip. (" + skipCount + "/" + evilCount + ")", ChatFormatting.GRAY))
        );
        Fx.sound(player, SoundEvents.UI_BUTTON_CLICK, 1f, 1.2f);

        if (skipCount >= evilCount) {
            stopAssassinationPhase();
            endAssassinationDiscussion();
        }
    }

    /** Spawn floating head di atas player kubu jahat yang sudah vote skip. */
    private void spawnAssassinationSkipHead(ServerPlayer voter) {
        ArmorStand old = assassinationSkipHeads.remove(voter.getUUID());
        if (old != null && old.isAlive()) old.discard();

        ArmorStand stand = spawnStand(voter.serverLevel(),
            voter.getX(), voter.getY() + headHeight(voter), voter.getZ(), voter.getYRot(), true, true, false);
        stand.addTag("avalon_assassination_head");

        stand.setItemSlot(EquipmentSlot.HEAD, skipHeadItem());

        assassinationSkipHeads.put(voter.getUUID(), stand);
    }

    /** Hapus semua floating head assassination. */
    private void clearAssassinationSkipHeads() {
        for (ArmorStand stand : assassinationSkipHeads.values()) {
            if (stand != null && stand.isAlive()) stand.discard();
        }
        assassinationSkipHeads.clear();
    }

    /** Animasi floating head assassination — mengikuti player yang berjalan-jalan. */
    private void startAssassinationHeadAnimation() {
        if (assassinationHeadAnimTask != null) {
            assassinationHeadAnimTask.cancel();
        }
        assassinationHeadAnimTask = new Task() {
            double tick = 0;

            @Override
            public void run() {
                if (!assassinationActive && assassinationSkipHeads.isEmpty()) {
                    cancel();
                    return;
                }
                tick += 0.25;
                animateHeads(assassinationSkipHeads, tick);
            }
        }.runTimer(0L, 1L);
    }

    /** Hentikan dan bersihkan semua state assassination. */
    private void stopAssassinationPhase() {
        assassinationActive = false;
        if (assassinationTask != null) {
            assassinationTask.cancel();
            assassinationTask = null;
        }
        if (assassinationHeadAnimTask != null) {
            assassinationHeadAnimTask.cancel();
            assassinationHeadAnimTask = null;
        }
        clearAssassinationSkipHeads();

        // Hapus item skip dari kubu jahat
        for (ServerPlayer p : getOnlinePlayers()) {
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (isAssassinationSkipItem(inv.getItem(i))) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private void checkAssassinationSkipComplete() {
        if (!assassinationActive) return;

        long evilOnline = getOnlinePlayers().stream()
            .filter(p -> {
                Role r = playerRoles.get(p.getUUID());
                return r != null && r.isEvil();
            })
            .count();

        // Juga saat tidak ada kubu jahat yang online: diskusinya tidak perlu ditunggu
        if (assassinationSkipVotes.size() >= evilOnline) {

            stopAssassinationPhase();
            endAssassinationDiscussion();
        }
    }

    /** Timer habis / semua kubu jahat skip → kasih bow ke assassin. */
    private void endAssassinationDiscussion() {
        if (!gameRunning) return;
        stopAssassinationPhase();

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.t("  🏹 WAKTU HABIS! ASSASSIN, TEMUKAN MERLIN!", ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        broadcast(Txt.t("  Panah player yang menurutmu adalah Merlin.", ChatFormatting.RED));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.blank());

        // Kasih bow + arrow ke assassin
        giveAssassinBow();
    }

    /** Berikan bow 1-durability ke player dengan role ASSASSIN. */
    public void giveAssassinBow() {
        assassinBowActive = true;
        for (ServerPlayer p : getOnlinePlayers()) {
            Role role = playerRoles.get(p.getUUID());
            if (role != Role.ASSASSIN) continue;

            p.getInventory().clearContent();

            // Bow dengan durability 1 (hampir rusak = pecah setelah 1 tembakan)
            ItemStack bow = AvalonItems.named(
                Items.BOW,
                Txt.t("🏹 Panah Assassin", ChatFormatting.DARK_RED, ChatFormatting.BOLD),
                List.of(Txt.t("Panah satu kali. Pilih dengan bijak.", ChatFormatting.GRAY))
            );
            // Set damage agar durability tinggal 1 (max durability bow = 384)
            bow.setDamageValue(383); // 384 - 1 = 383 damage → sisa 1 durability
            // Tag agar listener tahu ini bow assassin
            AvalonItems.setTag(bow, ASSASSIN_BOW_KEY, "true");

            // Inventory cuma 1 slot: tidak ada item arrow. Infinity + AssassinationListener#onArrowNock
            // membuat bow tetap bisa ditarik dan menembak.
            bow.enchant(Enchantments.INFINITY_ARROWS, 1);

            p.getInventory().setItem(0, bow);
            setHeldItemSlot(p, 0);

            Fx.title(p,
                "§4§l🏹 TEMBAK MERLIN!",
                "§cPanah player yang menurutmu Merlin.",
                10, 100, 20
            );
            Fx.sound(p, SoundEvents.CROSSBOW_LOADING_MIDDLE, 1f, 0.8f);

            broadcast(
                Txt.t("  🏹 ", ChatFormatting.DARK_RED)
                    .append(Txt.t(p.getGameProfile().getName(), ChatFormatting.RED, ChatFormatting.BOLD))
                    .append(Txt.t(" (Assassin) kini memegang busur!", ChatFormatting.DARK_RED))
            );
            return; // Hanya satu assassin
        }

        // Assassin sedang offline: tanpa ini fase busur menunggu selamanya
        if (assassinOfflineGraceTask == null) {
            broadcast(
                Txt.t("  ☠ Assassin offline! Kubu Baik menang dalam " + OFFLINE_GRACE_SECONDS + " detik jika tidak kembali.", ChatFormatting.RED)
            );
            scheduleAssassinOfflineGrace();
        }
    }

    /**
     * Dipanggil dari listener saat arrow mengenai entity.
     * Jika arrow adalah assassin arrow → tentukan menang/kalah.
     *
     * @param arrow  Arrow yang ditembakkan
     * @param target Entity yang kena panah
     */
    public void handleAssassinArrowHit(AbstractArrow arrow, Entity target) {
        if (!gameRunning) return;
        if (assassinShotFired) return; // Prevent double trigger
        if (!isAssassinArrow(arrow)) return;

        assassinShotFired = true;

        // Mannequin player offline mewakili pemiliknya: keluar dari server tidak membuat Merlin kebal
        String targetName = null;
        if (target instanceof ServerPlayer targetPlayer) {
            targetName = targetPlayer.getGameProfile().getName();
        } else {
            for (Map.Entry<String, Entity> entry : offlineMannequins.entrySet()) {
                if (entry.getValue() == target) targetName = entry.getKey();
            }
        }
        if (targetName == null) {
            // Kena entity bukan player → salah
            triggerAssassinFail();
            return;
        }

        Role targetRole = getRoleByName(targetName);

        if (targetRole == Role.MERLIN) {
            // BENAR: Assassin berhasil menemukan Merlin → kubu jahat menang
            broadcast(Txt.blank());
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
            broadcast(Txt.t("  ☠ ASSASSIN MENEMUKAN MERLIN!", ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            broadcast(
                Txt.t("  🏹 ", ChatFormatting.RED)
                    .append(Txt.t(targetName, ChatFormatting.GOLD, ChatFormatting.BOLD))
                    .append(Txt.t(" adalah MERLIN!", ChatFormatting.RED))
            );
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
            broadcast(Txt.blank());

            delayedTasks.add(
                Scheduler.later(30L, () -> {
                    if (!gameRunning) return;
                    triggerEvilWin("Assassin berhasil menemukan Merlin!");
                })
            );
        } else {
            // SALAH: Bukan Merlin → kubu jahat kalah
            broadcast(Txt.blank());
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
            broadcast(Txt.t("  🏆 ASSASSIN SALAH MENEBAK!", ChatFormatting.AQUA, ChatFormatting.BOLD));
            broadcast(
                Txt.t("  ", ChatFormatting.WHITE)
                    .append(Txt.t(targetName, ChatFormatting.YELLOW, ChatFormatting.BOLD))
                    .append(Txt.t(" bukan Merlin. Kubu baik menang!", ChatFormatting.GREEN))
            );
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
            broadcast(Txt.blank());

            triggerAssassinFail();
        }
    }

    /**
     * Dipanggil saat arrow assassin meleset (jatuh ke tanah tanpa kena player).
     * Dianggap salah tebak.
     */
    public void handleAssassinArrowMiss() {
        if (!gameRunning) return;
        if (assassinShotFired) return;
        assassinShotFired = true;

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.t("  🏆 ASSASSIN MELESET!", ChatFormatting.AQUA, ChatFormatting.BOLD));
        broadcast(Txt.t("  Panah tidak mengenai siapapun. Kubu baik menang!", ChatFormatting.GREEN));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.blank());

        triggerAssassinFail();
    }

    /** Panah yang baru ditembakkan assassin mulai dipantau (lihat AssassinationListener). */
    public void trackAssassinArrow(AbstractArrow arrow) {
        if (assassinShotFired) return;
        assassinArrow = arrow;
        assassinArrowTicks = 0;
    }

    /**
     * Panah yang tidak pernah mendarat (jatuh ke void, terbang keluar area yang dimuat) tidak memicu
     * event tumbukan; busurnya sudah hancur, jadi tanpa ini fase busur tidak akan pernah selesai.
     */
    private void watchAssassinArrow() {
        if (assassinArrow == null || assassinShotFired || !gameRunning) return;
        if (assassinArrow.isRemoved() || ++assassinArrowTicks > ASSASSIN_ARROW_TIMEOUT) {
            handleAssassinArrowMiss();
        }
    }

    /** Assassin salah tebak / meleset → kubu baik menang (langsung ke cutscene akhir). */
    private void triggerAssassinFail() {
        if (!gameRunning) return;
        delayedTasks.add(Scheduler.later(40L, () -> {
            if (!gameRunning) return;
            triggerGoodWin();
        }));
    }

    /**
     * Kubu baik menang: cutscene akhir (segitiga pilar, portal, perpisahan), lalu pengumuman.
     */
    private void triggerGoodWin() {
        if (!gameRunning || endingStarted) return;
        playEnding(EndingTimeline.WIN, "§b§lKUBU BAIK MENANG", "§3Mereka berhasil kembali ke overworld", this::announceGoodWin);
    }

    private void announceGoodWin() {
        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.t("  🏆 KUBU BAIK MENANG!", ChatFormatting.AQUA, ChatFormatting.BOLD));
        broadcast(Txt.t("  Merlin berhasil mengaktifkan portal!", ChatFormatting.GREEN));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.blank());

        // Reveal semua role
        for (Map.Entry<UUID, Role> entry : playerRoles.entrySet()) {
            String name = roleNames.get(entry.getKey());
            if (name != null) {
                ChatFormatting color = entry.getValue().isGood() ? ChatFormatting.AQUA : ChatFormatting.RED;
                broadcast(
                    Txt.t("  ✨ ", color)
                        .append(Txt.t(name, ChatFormatting.WHITE, ChatFormatting.BOLD))
                        .append(Txt.t(" adalah " + entry.getValue().name(), color))
                );
            }
        }
        broadcast(Txt.blank());
    }

    /**
     * Kubu jahat menang (5x reject, 3 misi disabotase, atau Merlin terbunuh): cutscene akhir, lalu pengumuman.
     */
    public void triggerEvilWin(String reason) {
        if (!gameRunning || endingStarted) return;
        int type = reason.contains("Merlin") ? EndingTimeline.MERLIN : EndingTimeline.EVIL;
        playEnding(type, "§4§lKUBU JAHAT MENANG", "§c" + reason, () -> announceEvilWin(reason));
    }

    private void announceEvilWin(String reason) {
        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.t("  ☠ KUBU JAHAT MENANG!", ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        broadcast(Txt.t("  " + reason, ChatFormatting.RED));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_RED));
        broadcast(Txt.blank());

        // Reveal siapa saja kubu jahat
        for (Map.Entry<UUID, Role> entry : playerRoles.entrySet()) {
            if (entry.getValue().isEvil()) {
                String name = roleNames.get(entry.getKey());
                if (name != null) {
                    broadcast(
                        Txt.t("  🗡 ", ChatFormatting.RED)
                            .append(Txt.t(name, ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                            .append(Txt.t(" adalah " + entry.getValue().name(), ChatFormatting.RED))
                    );
                }
            }
        }
        broadcast(Txt.blank());
    }

    // ── Cutscene akhir game ───────────────────────────────────────────────────

    /** Tempat asal player sebelum dibawa ke dimensi Avalon. */
    private record ReturnPoint(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}

    private final Map<UUID, ReturnPoint> returnPoints = new HashMap<>();
    /** Pemenang sudah ditentukan; cutscene akhir sedang (atau akan) berjalan. */
    private boolean endingStarted = false;

    /**
     * Turunkan player dari kursinya (kursinya ikut dihapus), melewati larangan turun selama game.
     * Satu-satunya jalur menurunkan player dari kursi.
     */
    public void releaseFromSeat(ServerPlayer p) {
        Entity vehicle = p.getVehicle();
        if (vehicle == null) return;
        dismountAllowed = true;
        vehicle.ejectPassengers();
        dismountAllowed = false;
        if (vehicle.getTags().contains("avalon_seat")) vehicle.discard();
    }

    /**
     * Mainkan cutscene akhir, lalu umumkan pemenang, bereskan game, dan kembalikan semua player
     * ke tempat asalnya (overworld).
     */
    private void playEnding(int type, String title, String subtitle, Runnable announce) {
        endingStarted = true;
        teamSelectionActive = false;
        ServerLevel avalon = tableWorld();

        List<ServerPlayer> good = new ArrayList<>();
        List<ServerPlayer> evil = new ArrayList<>();
        ServerPlayer merlin = null;
        ServerPlayer assassin = null;
        for (ServerPlayer p : getOnlinePlayers()) {
            Role role = getRole(p);
            if (role == null) continue;
            (role.isGood() ? good : evil).add(p);
            if (role == Role.MERLIN) merlin = p;
            if (role == Role.ASSASSIN) assassin = p;
        }

        Runnable finish = () -> {
            if (!gameRunning) return;
            announce.run();
            Map<UUID, ReturnPoint> points = new HashMap<>(returnPoints);
            cleanup();
            // Apa pun hasilnya (menang, kalah, Merlin terbunuh, 5x ditolak) semua pulang
            sendHome(points);
            for (ServerPlayer p : getOnlinePlayers()) {
                Fx.title(p, title, subtitle, 10, 80, 30);
                Fx.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        };

        if (avalon == null || (good.isEmpty() && evil.isEmpty())) {
            // Tidak bisa memainkan cutscene: langsung umumkan seperti biasa
            delayedTasks.add(Scheduler.later(20L, finish));
            return;
        }

        // Sisa fase terakhir tidak boleh mengganggu cutscene. Tugas yang masih tertunda dibatalkan:
        // misalnya "dudukkan semua player" seusai misi, yang baru jalan beberapa tick kemudian dan
        // akan menarik kubu jahat kembali ke kursinya di tengah cutscene.
        for (Task task : delayedTasks) task.cancel();
        delayedTasks.clear();
        MinecraftServer server = server();
        if (server != null) server.setPvpAllowed(false);
        stopTeamSelectionActionBar();
        for (ServerPlayer p : getOnlinePlayers()) {
            p.getInventory().clearContent();
            PlayerScale.set(p, 1.0);
            unlockCamera(p);
            AvalonNetwork.sendTo(p, AvalonNetwork.Crown.NONE);
            AvalonNetwork.sendTo(p, AvalonNetwork.Lady.NONE);
        }

        EndingCutscene.play(this, avalon, type, good, evil, merlin, assassin, false, finish);
    }

    // ===== UTILS =====

    private MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    /** Setara Bukkit.getPlayerExact(name): player online dengan nama tersebut, atau null. */
    public ServerPlayer getPlayerExact(String name) {
        MinecraftServer server = server();
        if (server == null || name == null) return null;
        return server.getPlayerList().getPlayerByName(name);
    }

    /** Setara Bukkit.getPlayer(uuid). */
    public ServerPlayer getPlayer(UUID uuid) {
        MinecraftServer server = server();
        if (server == null) return null;
        return server.getPlayerList().getPlayer(uuid);
    }

    /** Setara Player#isOnline() untuk referensi player yang mungkin sudah lama. */
    public boolean isOnline(ServerPlayer p) {
        if (p == null || p.hasDisconnected()) return false;
        return getPlayer(p.getUUID()) == p;
    }

    private void broadcast(Component message) {
        for (ServerPlayer p : getOnlinePlayers()) p.sendSystemMessage(message);
    }

    /**
     * Panah yang ditembakkan assassin di fase busur. Panah yang sedang dipantau tetap dikenali walau
     * penembaknya sudah keluar dari server (pemilik panahnya jadi tidak diketahui).
     */
    public boolean isAssassinArrow(AbstractArrow arrow) {
        if (arrow == assassinArrow) return true;
        if (!gameRunning || !assassinBowActive) return false;
        return arrow.getOwner() instanceof Player shooter && playerRoles.get(shooter.getUUID()) == Role.ASSASSIN;
    }

    /** Player terdaftar yang sedang online. */
    public List<ServerPlayer> getOnlinePlayers() {
        List<ServerPlayer> list = new ArrayList<>();
        for (String name : registeredPlayers) {
            ServerPlayer p = getPlayerExact(name);
            if (p != null) list.add(p);
        }
        return list;
    }

    private List<String> offlineRegisteredNames() {
        List<String> list = new ArrayList<>();
        for (String name : registeredPlayers) {
            if (getPlayerExact(name) == null) list.add(name);
        }
        return list;
    }

    // =========================================================================
    // ===== OFFLINE PLAYER HANDLING ===========================================
    // =========================================================================

    /**
     * Dipanggil oleh PlayerOfflineHandler saat registered player disconnect.
     * Spawn mannequin, broadcast, lalu jalankan logika khusus per fase.
     */
    public void handlePlayerOffline(ServerPlayer player) {
        if (!gameRunning) return;
        String name = player.getGameProfile().getName();

        // Masih hitung mundur: game belum benar-benar jalan, batalkan saja. Hitung mundurnya dihentikan
        // sekarang juga, supaya tidak sempat memulai game di tick yang sama
        if (countdownTask != null) {
            countdownTask.cancel();
            later(1L, () -> abortCountdown(name));
            return;
        }

        // 1. Spawn mannequin di posisi terakhir (di kursinya kalau ia sedang duduk)
        Vec3 loc = player.position();
        ServerLevel world = player.serverLevel();
        Entity vehicle = player.getVehicle();
        if (vehicle != null && vehicle.getTags().contains("avalon_seat")) {
            loc = vehicle.position();
        }

        // Posisi terakhirnya bukan tempat yang wajar untuk bot-nya: belum sampai di Avalon (keluar saat
        // cutscene portal), penonton misi yang sedang melayang, atau sedang diparkir di atas pilar
        // untuk cutscene-nya. Bot-nya langsung menunggu di kursinya.
        ServerLevel table = tableWorld();
        int seatIndex = registeredPlayers.indexOf(name);
        boolean adrift = world != table || pillarCutsceneRunning
            || (missionActive && !currentMissionTeam.contains(name));
        if (table != null && seatIndex >= 0 && adrift) {
            BlockPos slab = AvalonSeats.pos(seatIndex);
            world = table;
            loc = new Vec3(slab.getX() + 0.5, slab.getY() + SEAT_HEIGHT, slab.getZ() + 0.5);
        }

        OfflineMannequinData data = new OfflineMannequinData(
            player.getUUID(),
            name,
            player.getGameProfile(),
            world,
            loc
        );

        offlinePlayerRefs.put(name, data);

        spawnOfflineMannequin(data);
        // Fase perkenalan: aura & pose pindah ke mannequin-nya
        later(1L, this::refreshRevealViews);

        // 2. Broadcast
        broadcast(
            Txt.t("  ⚠ ", ChatFormatting.YELLOW)
                .append(Txt.t(name, ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(" terputus dari server.", ChatFormatting.YELLOW))
        );

        // Pemenang sudah ditentukan: tidak ada lagi yang perlu ditunggu dari siapa pun
        if (endingStarted) return;

        // 3. Fase misi — cek apakah seluruh tim offline (setelah player benar-benar keluar)
        if (missionActive) {
            later(1L, () -> {
                checkAllMissionTeamOffline();
                // Masih ada anggota tim yang online: bot-nya melanjutkan misi menggantikan dia
                if (missionActive && currentMissionTeam.contains(name)) batteryMission.addBot(name);
            });
            return;
        }

        // 4. Fase voting — cek apakah semua yang online sudah vote
        if (votingManager != null && votingManager.isVotingActive()) {
            later(1L, () -> votingManager.checkIfComplete());
            return;
        }

        // Fase Lady — pemegangnya offline: timernya tetap jalan, habis = dipilih acak
        if (ladyActive) {
            if (name.equals(ladyHolder)) {
                broadcast(
                    Txt.t("  🌊 Pemegang Lady offline! Target dipilih acak jika ia tidak kembali sebelum waktu habis.", ChatFormatting.AQUA)
                );
            }
            return;
        }

        if (discussionActive) {
            // Hapus vote skip player yang DC agar tidak menggantung hitungan
            discussionSkipVotes.remove(player.getUUID());
            ArmorStand skipHead = discussionSkipHeads.remove(player.getUUID());
            if (skipHead != null && skipHead.isAlive()) skipHead.discard();
            later(1L, this::checkDiscussionSkipComplete);
            return;
        }

        if (assassinationActive) {
            // Hapus vote skip player yang DC agar tidak menggantung hitungan
            assassinationSkipVotes.remove(player.getUUID());
            ArmorStand skipHead = assassinationSkipHeads.remove(player.getUUID());
            if (skipHead != null && skipHead.isAlive()) skipHead.discard();
            later(1L, this::checkAssassinationSkipComplete);
            return;
        }

        // 5. Fase bow assassin — jika assassin offline → grace timer
        //    (kalau ia sudah menembak, hasilnya tinggal diumumkan)
        if (assassinBowActive) {
            Role role = playerRoles.get(player.getUUID());
            if (role == Role.ASSASSIN && !assassinShotFired && assassinOfflineGraceTask == null) {
                broadcast(
                    Txt.t("  ☠ Assassin offline! Kubu Baik menang dalam " + OFFLINE_GRACE_SECONDS + " detik jika tidak kembali.", ChatFormatting.RED)
                );
                scheduleAssassinOfflineGrace();
            }
            return;
        }

        // 6. Fase pemilihan tim (raja) — jika raja offline → grace timer
        //    (dicek setelah ia benar-benar keluar)
        if (teamSelectionActive && name.equals(getCurrentKingName())) {
            later(1L, this::ensureKingGrace);
        }
    }

    /**
     * Raja yang sedang mendapat giliran memilih tim ternyata offline: mulai hitung mundur penggantinya.
     * Hanya selagi ia memegang buku (teamSelectionActive): di jeda antar fase timernya akan meletus di
     * fase lain. Dipanggil tiap kali giliran memilih dimulai dan tiap kali rajanya keluar.
     */
    private void ensureKingGrace() {
        String kingName = getCurrentKingName();
        if (!teamSelectionActive || kingName == null || kingOfflineGraceTask != null) return;
        if (getPlayerExact(kingName) != null) return;
        broadcast(
            Txt.t("  👑 Raja offline! Raja berikutnya dipilih dalam " + OFFLINE_GRACE_SECONDS + " detik jika tidak kembali.", ChatFormatting.YELLOW)
        );
        scheduleKingOfflineGrace(kingName);
    }

    /**
     * Dipanggil oleh PlayerOfflineHandler saat registered player reconnect.
     * Hapus mannequin, cancel grace timer, bersihkan inventory lama, restore
     * pose + item sesuai fase yang sedang aktif.
     */
    public void handlePlayerOnline(ServerPlayer player) {
        // Masih hitung mundur: belum ada yang perlu disamakan (ia dibawa ke Avalon bersama yang lain)
        if (!gameRunning || countdownTask != null) return;
        String name = player.getGameProfile().getName();

        offlinePlayerRefs.remove(name);

        // 1. Hapus mannequin (kalau sedang jadi bot misi, ingat dulu posisinya)
        Vec3 botPosition = batteryMission.botPosition(name);
        removeOfflineMannequin(name);

        // 2. Broadcast
        broadcast(
            Txt.t("  ✅ ", ChatFormatting.GREEN)
                .append(Txt.t(name, ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(" kembali ke server.", ChatFormatting.GREEN))
        );

        // 3. Kirim ulang info role
        Role role = playerRoles.get(player.getUUID());
        if (role != null) {
            player.sendSystemMessage(Txt.blank());
            player.sendSystemMessage(Txt.t("══════════════════════", ChatFormatting.GOLD));
            for (Component line : getRoleDescription(role)) player.sendSystemMessage(line);
            player.sendSystemMessage(Txt.t("══════════════════════", ChatFormatting.GOLD));
            // Bisa saja ia offline selama fase perkenalan: tanpa ini ia tidak pernah tahu
            if (revealFinished) sendRevealNames(player);
            sendLadyResults(player);
            // Bisa saja ia diperiksa (dan menerima Lady) selagi offline. Selagi gilirannya memilih,
            // petunjuknya datang bersama itemnya (lihat syncToPhase). Pemegang pertama yang belum
            // diumumkan dan pemegang yang Lady-nya sedang berpindah tidak diberi tahu.
            if (name.equals(ladyHolder) && !ladyActive && !kingRouletteRunning && !ladyLeaving) {
                player.sendSystemMessage(Txt.t("  🌊 Kamu memegang Lady of the Lake.", ChatFormatting.AQUA));
                player.sendSystemMessage(Txt.blank());
            }
        }

        // Mahkota raja yang sedang aktif & token Lady
        sendCrownTo(player, false);
        sendLadyTo(player, false);

        // 4. Cancel grace timer jika pemain yang bersangkutan kembali
        String kingName = getCurrentKingName();
        if (name.equals(kingName) && kingOfflineGraceTask != null) {
            cancelKingOfflineGrace();
            broadcast(Txt.t("  👑 Raja " + name + " kembali. Pemilihan tim dilanjutkan.", ChatFormatting.GOLD));
        }
        if (role == Role.ASSASSIN && assassinBowActive && assassinOfflineGraceTask != null) {
            cancelAssassinOfflineGrace();
        }

        // 5. Samakan keadaannya dengan fase yang sedang berjalan
        syncToPhase(player, botPosition);
    }

    // ── Respawn di Avalon ─────────────────────────────────────────────────────

    /** Titik respawn asli (bed, dsb.) player yang mati di tengah game; dikembalikan begitu ia respawn. */
    private record RespawnPoint(ResourceKey<Level> dimension, BlockPos pos, float angle, boolean forced) {}

    private final Map<UUID, RespawnPoint> savedRespawns = new HashMap<>();

    /**
     * Pemain game mati di dimensi Avalon: ia respawn di kursinya sendiri di sana, bukan di overworld
     * (entity yang mengikutinya, mis. kepala melayang, tidak ikut terseret ke dimensi lain).
     */
    public void handlePlayerDeath(ServerPlayer p) {
        ServerLevel table = tableWorld();
        int index = registeredPlayers.indexOf(p.getGameProfile().getName());
        if (!gameRunning || table == null || p.serverLevel() != table) return;
        if (index < 0 || index >= PLAYER_SLAB_POSITIONS.length) return;

        savedRespawns.putIfAbsent(p.getUUID(), new RespawnPoint(
            p.getRespawnDimension(), p.getRespawnPosition(), p.getRespawnAngle(), p.isRespawnForced()));
        BlockPos seat = AvalonSeats.pos(index).above();
        p.setRespawnPosition(table.dimension(), seat, yawTowardBase(seat.getX() + 0.5, seat.getZ() + 0.5), true, false);
    }

    /** Dipanggil saat player respawn: titik respawn aslinya dikembalikan. */
    public void restoreRespawnPoint(ServerPlayer p) {
        RespawnPoint saved = savedRespawns.remove(p.getUUID());
        if (saved == null) return;
        p.setRespawnPosition(saved.dimension(), saved.pos(), saved.angle(), saved.forced(), false);
        // Game-nya sudah selesai selagi ia di layar kematian: jangan tertinggal di Avalon
        if (!isOneSlot(p) && p.serverLevel() == tableWorld()) toWorldSpawn(p);
    }

    /** Player terdaftar mati lalu respawn di tengah game: objeknya baru, keadaannya disamakan lagi. */
    public void handlePlayerRespawn(ServerPlayer player) {
        if (!gameRunning || countdownTask != null) return;
        sendCrownTo(player, false);
        sendLadyTo(player, false);
        syncToPhase(player, null);
    }

    /**
     * Samakan dimensi, gamemode, efek, kursi dan item {@code player} dengan fase yang sedang berjalan.
     *
     * @param botPosition posisi terakhir bot misinya, atau null kalau ia tidak punya bot
     */
    private void syncToPhase(ServerPlayer player, Vec3 botPosition) {
        String name = player.getGameProfile().getName();
        String kingName = getCurrentKingName();
        Role role = playerRoles.get(player.getUUID());

        // Bersihkan sisa fase sebelumnya (item, skala, efek, kunci); fase aktif memasangnya lagi di bawah
        player.getInventory().clearContent();
        PlayerScale.set(player, 1.0);
        clearRevealEffects(player);
        restoreFood(player);
        player.removeEffect(MobEffects.INVISIBILITY);
        unlockCamera(player);
        unlockMovement(player);

        // ── Cutscene akhir: menunggu di kursinya sampai dipulangkan bersama yang lain ──
        if (endingStarted) {
            seatPlayer(player);
            return;
        }

        // ── Fase misi ────────────────────────────────────────────────────────
        if (missionActive) {
            boolean inTeam = currentMissionTeam.contains(name);
            // Menggantikan bot-nya: baterai yang sudah diambil bot pindah ke tangannya
            if (inTeam) batteryMission.onReturn(player);

            if (missionResolving) {
                // Cutscene pilar sedang berjalan: tunggu di kursi, sebentar lagi semua kembali duduk
                player.getInventory().clearContent();
                seatPlayer(player);
                return;
            }

            // Dia bisa saja keluar saat masih duduk: turunkan dari kursinya
            unseatPlayer(player);
            ensureInAvalon(player);
            if (!inTeam) {
                setMode(player, GameType.SPECTATOR);
                player.sendSystemMessage(Txt.t("  Misi sedang berjalan dan kamu tidak terpilih. Mode penonton.", ChatFormatting.GRAY));
                return;
            }

            setMode(player, GameType.SURVIVAL);
            setHeldItemSlot(player, 0);
            // Berdiri di posisi terakhir bot-nya
            ServerLevel avalon = tableWorld();
            if (botPosition != null && avalon != null) {
                teleport(player, avalon, botPosition.x, botPosition.y, botPosition.z, player.getYRot(), 0f);
            }
            player.sendSystemMessage(batteryMission.hasPlaced(player)
                ? Txt.t("  🔋 Kamu kembali ke misi! Bateraimu sudah terpasang, tunggu anggota tim lain.", ChatFormatting.GREEN)
                : Txt.t("  🔋 Kamu kembali ke misi! Lanjutkan memasang baterai.", ChatFormatting.GREEN)
            );
            return;
        }

        // ── Fase voting ──────────────────────────────────────────────────────
        if (votingManager != null && votingManager.isVotingActive()) {
            seatPlayer(player);
            votingManager.giveVoteItemsPublic(player);
            player.sendSystemMessage(
                Txt.t("  🗳 Voting sedang berlangsung. Gunakan item di hotbar untuk memberikan suara.", ChatFormatting.AQUA)
            );
            return;
        }

        // ── Fase assassination (diskusi kubu jahat, lalu busur assassin) ─────
        if (assassinationActive || assassinBowActive) {
            if (role != null && role.isEvil()) {
                // Kubu jahat bebas berjalan di fase ini (lihat startAssassinationPhase)
                unseatPlayer(player);
                ensureInAvalon(player);
                setMode(player, GameType.ADVENTURE);
                if (assassinBowActive) {
                    if (role == Role.ASSASSIN && !assassinShotFired) giveAssassinBow();
                } else {
                    if (!assassinationSkipVotes.contains(player.getUUID())) {
                        giveAssassinationSkipItem(player);
                    }
                    player.sendSystemMessage(
                        Txt.t("  ☠ Kubu jahat sedang berdiskusi. Gunakan item untuk vote skip.", ChatFormatting.RED)
                    );
                }
            } else {
                // Kubu baik: duduk kembali, gerakan dikunci
                seatPlayer(player);
                lockMovement(player);
            }
            return;
        }

        // ── Lady of the Lake (memilih, lalu animasi pemeriksaannya) ──────────
        if (ladyActive || ladyResolving) {
            seatPlayer(player);
            if (ladyActive && name.equals(ladyHolder)) {
                giveLadyItem(player);
                player.sendSystemMessage(
                    Txt.t("  🌊 Kamu memegang Lady of the Lake. Klik kanan untuk memilih pemain yang diperiksa.", ChatFormatting.AQUA)
                );
            }
            return;
        }

        // ── Fase diskusi biasa ───────────────────────────────────────────────
        if (discussionActive) {
            seatPlayer(player);
            if (!discussionSkipVotes.contains(player.getUUID())) {
                giveDiscussionSkipItem(player);
            }
            player.sendSystemMessage(
                Txt.t("  💬 Diskusi sedang berlangsung. Gunakan item untuk vote skip.", ChatFormatting.YELLOW)
            );
            return;
        }

        // ── Fase perkenalan ──────────────────────────────────────────────────
        if (currentRevealPhase != -1) {
            later(2L, () -> {
                if (!isOnline(player)) return;
                // Fasenya bisa saja berakhir dalam jeda ini: samakan dengan fase yang sekarang
                if (currentRevealPhase == 1) restoreRevealState(player);
                else syncToPhase(player, null);
            });
            return;
        }

        // ── Sisanya (perjalanan ke Avalon, kocok peran, pemilihan raja & tim, jeda antar fase):
        //    semua orang sedang duduk di kursinya ──
        seatPlayer(player);
        if (teamSelectionActive && name.equals(kingName)) {
            giveTeamBook(player);
            player.sendSystemMessage(
                Txt.t("  👑 Kamu adalah Raja. Gunakan Buku Pemilihan Tim untuk memilih tim.", ChatFormatting.GOLD)
            );
        }
    }

    /**
     * Player yang keluar di tengah game dan baru kembali setelah game itu selesai (termasuk yang
     * sudah di-unregister dan masuk saat game lain berjalan): sisa keadaan game-nya (kursi, item,
     * efek, gamemode) dibereskan, dan dari dimensi Avalon ia dipulangkan ke titik spawn dunia.
     */
    public void releaseLeftover(ServerPlayer p) {
        if (!isInGame(p) || isOneSlot(p)) return;
        setInGame(p, false);

        releaseFromSeat(p);
        p.getInventory().clearContent();
        clearRevealEffects(p);
        p.removeEffect(MobEffects.INVISIBILITY);
        restoreFood(p);
        PlayerScale.set(p, 1.0);
        resetStepHeight(p);
        setMode(p, GameType.ADVENTURE);

        if (p.serverLevel() == tableWorld()) toWorldSpawn(p);
    }

    // ── Mannequin ─────────────────────────────────────────────────────────────

    /** Seat ArmorStand palsu untuk mannequin yang sedang duduk. */
    private final Map<String, Entity> offlineMannequinSeats = new HashMap<>();

    private static class OfflineMannequinData {
        final UUID uuid;
        final String name;
        final GameProfile profile;
        final ServerLevel world;
        Vec3 location;

        OfflineMannequinData(
                UUID uuid,
                String name,
                GameProfile profile,
                ServerLevel world,
                Vec3 location) {

            this.uuid = uuid;
            this.name = name;
            this.profile = profile;
            this.world = world;
            this.location = location;
        }
    }

    /**
     * Spawn mannequin "Bot <nama>" dengan skin player, duduk di atas ArmorStand palsu,
     * menghadap ke tengah meja.
     */
    private void spawnOfflineMannequin(OfflineMannequinData data) {

        removeOfflineMannequin(data.name);

        ServerLevel world = data.world;
        Vec3 spawnLoc = data.location;
        float yaw = yawTowardBase(spawnLoc.x, spawnLoc.z);

        try {
            // Spawn ArmorStand tak terlihat dulu sebagai "kursi"
            ArmorStand seat = spawnStand(world, spawnLoc.x, spawnLoc.y, spawnLoc.z, yaw, true, false, false);
            seat.setCustomNameVisible(false);
            seat.addTag("avalon_mannequin");

            MannequinEntity mannequin = ModEntities.MANNEQUIN.get().create(world);
            if (mannequin == null) return;

            mannequin.setProfile(data.profile);
            mannequin.moveTo(spawnLoc.x, spawnLoc.y, spawnLoc.z, yaw, 0f);
            mannequin.setFacing(yaw);
            mannequin.setInvulnerable(true);
            mannequin.setNoGravity(true);
            mannequin.setPersistent(false);
            mannequin.setSilent(true);
            mannequin.setCustomName(
                Txt.t("Bot ", ChatFormatting.GRAY)
                    .append(Txt.t(data.name, ChatFormatting.WHITE))
            );
            mannequin.setCustomNameVisible(true);
            mannequin.addTag("avalon_mannequin");
            world.addFreshEntity(mannequin);

            // Dudukkan mannequin di atas ArmorStand → pose duduk otomatis
            mannequin.startRiding(seat, true);

            offlineMannequinSeats.put(data.name, seat);
            offlineMannequins.put(data.name, mannequin);

        } catch (Exception e) {
            AvalonLog.warn("Gagal spawn mannequin untuk " + data.name + ": " + e.getMessage());
        }
    }

    /**
     * Hapus mannequin (+ fake seat jika ada) milik player yang reconnect.
     */
    private void removeOfflineMannequin(String playerName) {
        Entity seat = offlineMannequinSeats.remove(playerName);
        if (seat != null && seat.isAlive()) seat.discard();

        Entity mannequin = offlineMannequins.remove(playerName);
        if (mannequin != null && mannequin.isAlive()) mannequin.discard();
    }

    /** UUID player offline yang punya mannequin, atau null. */
    public UUID offlineUuid(String playerName) {
        OfflineMannequinData data = offlinePlayerRefs.get(playerName);
        return data == null ? null : data.uuid;
    }

    /**
     * Turunkan mannequin player offline dari kursi palsunya supaya bisa berjalan (bot misi).
     *
     * @return mannequin-nya, atau null kalau player itu tidak punya mannequin
     */
    public MannequinEntity standOfflineMannequin(String playerName) {
        if (!(offlineMannequins.get(playerName) instanceof MannequinEntity mannequin) || !mannequin.isAlive()) return null;
        Entity seat = offlineMannequinSeats.remove(playerName);
        mannequin.stopRiding();
        if (seat != null && seat.isAlive()) seat.discard();
        return mannequin;
    }

    /** Dudukkan lagi mannequin player offline di kursinya sendiri (setelah misi). */
    private void reseatOfflineMannequin(int seatIndex, String playerName) {
        OfflineMannequinData data = offlinePlayerRefs.get(playerName);
        if (data == null || !offlineMannequins.containsKey(playerName)) return;
        BlockPos slab = AvalonSeats.pos(seatIndex);
        data.location = new Vec3(slab.getX() + 0.5, slab.getY() + SEAT_HEIGHT, slab.getZ() + 0.5);
        removeOfflineMannequin(playerName);
        spawnOfflineMannequin(data);
    }

    // ── Grace timers ──────────────────────────────────────────────────────────

    /**
     * Jadwalkan auto-rotasi raja setelah grace period jika raja masih offline.
     */
    private void scheduleKingOfflineGrace(String kingName) {
        if (kingOfflineGraceTask != null) return;
        kingOfflineGraceTask = Scheduler.later(OFFLINE_GRACE_SECONDS * 20L, () -> {
            kingOfflineGraceTask = null;
            // Sudah bukan gilirannya memilih tim (timnya terkonfirmasi / rajanya sudah berganti): biarkan
            if (!gameRunning || !teamSelectionActive || !kingName.equals(getCurrentKingName())) return;
            ServerPlayer king = getPlayerExact(kingName);
            if (king == null) {
                broadcast(
                    Txt.t("  👑 Raja " + kingName + " tidak kembali. Rotasi raja otomatis.", ChatFormatting.YELLOW)
                );
                rotateKing();
            }
        });
    }

    public void rotateKing() {

        if (kingOrder.isEmpty()) return;

        teamSelectionSessions.clear();
        // Kepala pilihan tim raja sebelumnya (kalau ia diganti sebelum sempat mengonfirmasi)
        clearTeamHeads();

        // Debug: raja tidak bergilir, selalu player yang sama selama ia online
        if (alwaysKing != null && kingOrder.contains(alwaysKing) && getPlayerExact(alwaysKing) != null) {
            currentKingIndex = kingOrder.indexOf(alwaysKing);
            announceKing();
            return;
        }

        for (int i = 0; i < kingOrder.size(); i++) {

            currentKingIndex = (currentKingIndex + 1) % kingOrder.size();

            String kingName = getCurrentKingName();
            ServerPlayer king = getPlayerExact(kingName);

            if (king != null) {
                announceKing();
                return;
            }
        }

        // Tidak ada satu pun yang online. Timer game dibekukan selama itu (lihat isFrozen), jadi ini
        // hanya pengaman: dicoba lagi sampai ada yang bisa jadi raja, supaya game tidak mati.
        later(20L, this::rotateKing);
    }

    /** Hapus kepala anggota tim yang melayang di atas raja. */
    private void clearTeamHeads() {
        AvalonMod mod = AvalonMod.getInstance();
        if (mod != null && mod.getTeamSelectionListener() != null) {
            mod.getTeamSelectionListener().clearAllFloatingHeads();
        }
    }

    private void cancelKingOfflineGrace() {
        if (kingOfflineGraceTask != null) {
            kingOfflineGraceTask.cancel();
            kingOfflineGraceTask = null;
        }
    }

    /**
     * Jadwalkan kemenangan kubu baik secara default jika assassin tidak kembali.
     */
    private void scheduleAssassinOfflineGrace() {
        if (assassinOfflineGraceTask != null) return;
        assassinOfflineGraceTask = Scheduler.later(OFFLINE_GRACE_SECONDS * 20L, () -> {
            assassinOfflineGraceTask = null;
            if (!gameRunning) return;
            // Cek apakah assassin masih offline
            boolean assassinOnline = getOnlinePlayers().stream()
                .anyMatch(p -> playerRoles.get(p.getUUID()) == Role.ASSASSIN);
            if (!assassinOnline) {
                broadcast(
                    Txt.t("  ☠ Assassin tidak kembali. Kubu Baik menang secara default!", ChatFormatting.GREEN)
                );
                triggerGoodWin();
            }
        });
    }

    private void cancelAssassinOfflineGrace() {
        if (assassinOfflineGraceTask != null) {
            assassinOfflineGraceTask.cancel();
            assassinOfflineGraceTask = null;
        }
    }

    // ── Mission: semua tim offline ────────────────────────────────────────────

    /**
     * Jika seluruh anggota tim misi offline → batalkan misi, rotasi raja.
     */
    private void checkAllMissionTeamOffline() {
        // Rak sudah penuh (cutscene pilar berjalan): hasilnya tetap dihitung walau timnya keluar semua
        if (!missionActive || missionResolving) return;
        for (String name : currentMissionTeam) {
            ServerPlayer p = getPlayerExact(name);
            if (p != null) return; // masih ada yang online
        }

        // Semua offline → batalkan misi
        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.RED));
        broadcast(
            Txt.t("  ⚠ Semua anggota tim offline! Misi dibatalkan.", ChatFormatting.RED, ChatFormatting.BOLD)
        );
        broadcast(Txt.t("  Raja berikutnya akan memilih tim baru.", ChatFormatting.GRAY));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.RED));
        broadcast(Txt.blank());

        endMission();

        // Langsung didudukkan: penonton baru saja jadi Adventure di tempatnya melayang
        teleportAllToSeat();
        later(100L, this::rotateKing);
    }

    /** Server berhenti: game yang sedang berjalan dibereskan. Di luar game tidak ada yang disentuh. */
    public void shutdown() {
        // Tag in-game dibiarkan: saat masuk lagi setelah server hidup, mereka dipulangkan (releaseLeftover)
        if (gameRunning) cleanup(true);
    }

    private void cleanup() {
        cleanup(false);
    }

    /**
     * Akhiri game dan kembalikan semua keadaannya seperti sebelum game. Hanya dipanggil selagi ada
     * game yang berjalan.
     *
     * @param keepInGameTag true = player yang online tetap ditandai ikut game (server akan berhenti)
     */
    private void cleanup(boolean keepInGameTag) {
        // Cutscene portal pembuka / cutscene akhir (kalau masih berjalan) ikut dihentikan
        PortalCutscene.stop(this);
        EndingCutscene.stop();
        endingStarted = false;
        returnPoints.clear();
        clearTeamHeads();
        // Hentikan semua task aktif
        if (countdownTask != null)              { countdownTask.cancel();              countdownTask = null; }
        if (revealCountdownTask != null)        { revealCountdownTask.cancel();        revealCountdownTask = null; }
        if (teamSelectionActionBarTask != null) { teamSelectionActionBarTask.cancel(); teamSelectionActionBarTask = null; }

        stopDiscussionPhase();
        stopLadyTask();
        stopHotbarLock();
        stopSabotageMechanic();

        stopAssassinationPhase();

        // Cutscene pilar yang masih berjalan: kembalikan player, lalu bawa ke titik datang
        // (mereka sedang diparkir tinggi di atas pilar)
        if (pillarCutsceneRunning) {
            endPillarCutscene();
            for (ServerPlayer p : getOnlinePlayers()) AvalonPortal.teleportToSpawn(p);
        }

        // Pilar mati, rak pilar kosong & tertutup, gudang penuh
        batteryMission.reset(server());

        // Cancel voting jika sedang berjalan
        if (votingManager != null) {
            votingManager.cancelVoting();
        }

        // Set gameRunning false SEBELUM operasi lain supaya semua task
        // yang cek gameRunning langsung berhenti di iterasi berikutnya
        gameRunning       = false;
        cutsceneRunning   = false;
        dismountAllowed = false;
        currentRevealPhase = -1;
        revealFinished = false;
        currentRevealSeconds = -1;
        missionActive     = false;
        missionResolving = false;
        currentMissionTeam.clear();
        discussionActive = false;
        discussionAfterSuccess = false;
        discussionSkipVotes.clear();
        removeDiscussionSkipItems();
        ladyActive = false;
        ladyResolving = false;
        ladyLeaving = false;
        ladySelection = null;
        ladyHolder = null;
        ladyPastHolders.clear();
        ladyResults.clear();
        assassinationActive = false;
        assassinShotFired = false;
        assassinBowActive = false;
        assassinArrow = null;
        assassinationSkipVotes.clear();

        // Role game ini tidak berlaku lagi (tanpa ini player yang masuk di awal game berikutnya
        // masih dikirimi role lamanya)
        playerRoles.clear();
        roleNames.clear();
        rosterUuids.clear();

        // Cancel grace timers offline
        cancelKingOfflineGrace();
        cancelAssassinOfflineGrace();
        // Hapus semua mannequin offline
        for (Entity e : offlineMannequins.values()) {
            if (e != null && e.isAlive()) e.discard();
        }
        for (Entity e : offlineMannequinSeats.values()) {
            if (e != null && e.isAlive()) e.discard();
        }
        offlineMannequins.clear();
        offlineMannequinSeats.clear();
        offlinePlayerRefs.clear();

        for (Task task : delayedTasks) {
            task.cancel();
        }
        delayedTasks.clear();

        // Kirim unlock ke client yang masih online (sekalian akhiri fase perkenalan kalau sedang berjalan)
        for (ServerPlayer p : getOnlinePlayers()) {
            unlockCamera(p);
            unlockMovement(p);
            AvalonNetwork.sendTo(p, AvalonNetwork.Reveal.END);
            AvalonNetwork.sendTo(p, AvalonNetwork.Crown.NONE);
            AvalonNetwork.sendTo(p, AvalonNetwork.Lady.NONE);
            AvalonNetwork.sendTo(p, new AvalonNetwork.OneSlot(false));
        }
        lockedYaw.clear();
        lockedPitch.clear();
        movementLocked.clear();
        movementAnchors.clear();

        // Reset King state
        kingOrder.clear();
        currentKingIndex = -1;
        kingRouletteRunning = false;
        currentMission   = 1;
        currentRound     = 1;
        evilMissionFails = 0;
        teamSelectionSessions.clear();
        teamSelectionActive = false;

        // Clear effect reveal + turunkan semua player dari seat
        for (ServerPlayer p : getOnlinePlayers()) {
            // Yang sedang offline tetap membawa tag ini: dibereskan saat ia masuk lagi (releaseLeftover)
            if (!keepInGameTag) setInGame(p, false);
            p.getInventory().clearContent();
            clearRevealEffects(p);

            PlayerScale.set(p, 1.0);
            releaseFromSeat(p);
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            restoreFood(p);
            resetStepHeight(p);
            setMode(p, GameType.ADVENTURE);
        }

        MinecraftServer server = server();
        if (server != null) server.setPvpAllowed(pvpBeforeGame);

        // Hapus entity game: di dunia para player dan di dimensi Avalon (bisa berbeda, mis. keluar saat hitung mundur)
        Set<ServerLevel> levels = new HashSet<>();
        for (ServerPlayer p : getOnlinePlayers()) levels.add(p.serverLevel());
        if (tableWorld() != null) levels.add(tableWorld());
        for (ServerLevel level : levels) {
            List<Entity> toRemove = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                Set<String> tags = e.getTags();
                if (tags.contains("avalon_seat")
                        || tags.contains("avalon_mannequin")
                        || tags.contains("avalon_vote_head")
                        || tags.contains("avalon_discussion_head")
                        || tags.contains("avalon_assassination_head")
                        || tags.contains("avalon_team_head")) {
                    toRemove.add(e);
                }
            }
            toRemove.forEach(Entity::discard);
        }
    }
}
