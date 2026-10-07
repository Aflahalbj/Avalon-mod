package id.avalon.managers;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
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
import id.avalon.cutscene.PillarTimeline;
import id.avalon.cutscene.PortalTimeline;
import id.avalon.cutscene.RoleShuffleTimeline;
import id.avalon.entity.MannequinEntity;
import id.avalon.entity.ModEntities;
import id.avalon.gui.TeamSelectionGUI;
import id.avalon.models.Role;
import id.avalon.network.AvalonNetwork;
import id.avalon.world.AvalonPillars;
import id.avalon.world.AvalonPortal;
import id.avalon.world.AvalonSeats;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.joml.Vector3f;

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

    // Flag: sedang dalam fase reveal — dipakai CutsceneListener
    // supaya eject dari avalon_seat tidak di-cancel saat perlu berdiri
    private boolean revealPhaseActive = false;
    private int currentRevealPhase = -1;

    private String currentRevealLabel = "";
    private int currentRevealSeconds = -1;

    private Task cutsceneTask;
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
    /** Misi yang sedang berjalan (1-5). */
    private int currentMission = 1;
    private int currentRound = 1;
    private int evilMissionFails = 0;
    /** Session pemilihan tim per Raja (UUID raja -> list nama yang sudah dipilih). */
    private final Map<UUID, List<String>> teamSelectionSessions = new HashMap<>();

    // ── Mission state ─────────────────────────────────────────────────────────
    /** Task untuk sabotage mechanic (45s timer). */
    private Task sabotageTimerTask;
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
    /** Task proximity checker (player mendekat tanaman → trigger end). */
    private Task proximityTask;
    /** Apakah misi ini sudah disabotase. */
    private boolean missionSabotaged = false;
    private int sabotageCount = 0;
    private final Set<UUID> sabotagedPlayers = new HashSet<>();
    /** Task countdown end-mission. */
    private Task endMissionCountdownTask;

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
    /** Track state visual tiap player secara eksplisit (bukan dari pitch/vehicle). */
    private final Map<UUID, OfflineState> playerStateMap = new HashMap<>();
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

    // ── Item tag keys ─────────────────────────────────────────────────────────
    public static final String KEY_DISCUSSION_SKIP    = "discussion_skip";
    public static final String KEY_ASSASSINATION_SKIP = "assassination_skip";

    // ── Koordinat ────────────────────────────────────────────────────────────

    private static final double MANNEQUIN_X   = -19.5;
    private static final double MANNEQUIN_Y   = 80.6;
    private static final double MANNEQUIN_Z   = -422;
    private static final float  MANNEQUIN_YAW = 270f;

    private static final String QUEEN_TEXTURE =
        "ewogICJ0aW1lc3RhbXAiIDogMTcyNzQ2MzM1MDM4MiwKICAicHJvZmlsZUlkIiA6ICJhNDAxZjEzMTZlMjI0ZTNjOTg0ODk1MmVjMzhjMTEwYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJHcmVlbnNoZWVwaXJhdGUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzVjNWQxOWFhMTQzYThiMTgzMGVlZWE3ODcxM2NhNDI4NTJhMmQ1NjUwYzI0ZjMzZmU3ZTk4YzdhZGUxZjk0NSIKICAgIH0KICB9Cn0=";

    private static final double SPECTATOR_X     = -22.14;
    private static final double SPECTATOR_Y     = 82.686;
    private static final double SPECTATOR_Z     = -418.935;
    private static final float  SPECTATOR_YAW   = -141.2f;
    private static final float  SPECTATOR_PITCH = 40.3f;

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

    public final int BASE_X = -20;
    public final int BASE_Y = 80;
    public final int BASE_Z = -383;

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

    public GameManager() {
    }

    /**
     * Dipanggil setiap tick server (pengganti BukkitRunnable timer 1 tick di constructor plugin).
     * Camera lock + movement lock + step height dekat green wool.
     */
    public void tick() {
        MinecraftServer server = server();
        if (server == null) return;

        // Bot player offline yang ikut misi
        batteryMission.tick(server);

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

            // Green wool step height
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
    public void setGameRunning(boolean v)           { this.gameRunning = v; }
    public boolean isMissionActive()                { return missionActive; }

    /** Dipakai CutsceneListener untuk memutuskan apakah eject dari seat diizinkan. */
    public boolean isRevealPhaseActive()            { return revealPhaseActive; }
    public int getCurrentRevealPhase()              { return currentRevealPhase; }
    public boolean isDiscussionActive()             { return discussionActive; }
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

    public List<Role> getDefaultRolesPublic(int playerCount) {
        return new ArrayList<>(getDefaultRoles(playerCount));
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

    private void assignRoles(List<ServerPlayer> players) {
        List<Role> roles = getRolesForPlayerCount(players.size());
        playerRoles.clear();
        roleNames.clear();

        // Role yang sudah diatur lewat /avalon setrole dipakai dulu; sisanya diacak ke player lain
        List<ServerPlayer> unassigned = new ArrayList<>();
        for (ServerPlayer p : players) {
            Role forced = forcedRoles.get(p.getGameProfile().getName());
            if (forced != null && roles.remove(forced)) {
                playerRoles.put(p.getUUID(), forced);
            } else {
                unassigned.add(p);
            }
            roleNames.put(p.getUUID(), p.getGameProfile().getName());
        }
        Collections.shuffle(roles);
        for (int i = 0; i < unassigned.size(); i++) {
            playerRoles.put(unassigned.get(i).getUUID(), roles.get(i));
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

    private List<Role> getRolesForPlayerCount(int n) {
        List<Role> c = customRoles.get(n);
        return c != null ? new ArrayList<>(c) : getDefaultRoles(n);
    }

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
        // Selama misi (termasuk cutscene pilarnya) mahkota disembunyikan
        if (kingName == null || missionActive) {
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

    /** Nomor misi saat ini (1-5). */
    public int getCurrentMission() {
        return currentMission;
    }

    /** Set nomor misi. */
    public void setCurrentMission(int mission) {
        this.currentMission = mission;
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
        // Reset session
        teamSelectionSessions.remove(king.getUUID());

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
        delayedTasks.add(
            Scheduler.later(40L, () -> {
                if (!gameRunning) return;
                if (votingManager != null) {
                    votingManager.startVoting(teamFinal);
                }
            })
        );
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

    /**
     * Buat player berdiri sebagai PENAMPIL:
     * 1. Set revealPhaseActive = true supaya listener izinkan eject
     * 2. Eject dari seat
     * 3. Set revealPhaseActive = false kembali
     * 4. Clear effect, unlock kamera
     * 5. Rotasi yaw ke meja, pitch 0 (tanpa lock — bebas lihat)
     */
    private void standAsViewer(ServerPlayer p) {

        revealPhaseActive = true;

        Entity vehicle = p.getVehicle();
        if (vehicle != null) {
            vehicle.ejectPassengers();
        }

        Scheduler.next(() -> revealPhaseActive = false);
        PlayerScale.set(p, 1.5);
        clearRevealEffects(p);

        unlockCamera(p);
        lockMovement(p);

        float yaw = yawTowardBase(p.getX(), p.getZ());
        setRotation(p, yaw, 0f);
        playerStateMap.put(p.getUUID(), OfflineState.VIEWER);
        updateOfflineMannequin(p.getGameProfile().getName(), OfflineState.VIEWER);
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

    /**
     * Kembalikan player ke duduk di atas ArmorStand seat baru.
     * Dipanggil setelah tiap fase reveal selesai.
     */
    private void reseatPlayer(ServerPlayer p, ServerLevel world) {
        PlayerScale.set(p, 1.0);
        int index = registeredPlayers.indexOf(p.getGameProfile().getName());
        if (index < 0 || index >= PLAYER_SLAB_POSITIONS.length) return;
        BlockPos slab = AvalonSeats.pos(index);

        double x = slab.getX() + 0.5;
        double y = slab.getY() + SEAT_HEIGHT;
        double z = slab.getZ() + 0.5;
        float yaw = yawTowardBase(x, z);

        // Pastikan revealPhaseActive false saat respawn — listener akan block eject lagi
        revealPhaseActive = false;

        if (p.getVehicle() instanceof ArmorStand oldSeat) {
            oldSeat.discard();
        }

        ArmorStand seat = spawnSeat(world, x, y, z, yaw);
        p.startRiding(seat, true);
        playerStateMap.put(p.getUUID(), OfflineState.SEATED);
        updateOfflineMannequin(p.getGameProfile().getName(), OfflineState.SEATED);
    }

    /**
     * Countdown di action bar.
     * Task disimpan ke revealCountdownTask supaya bisa di-cancel oleh /stopgame.
     */
    private void revealCountdown(List<ServerPlayer> players, String label, Runnable onDone) {
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
    private void startRoleReveal(List<ServerPlayer> players) {
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
                delayedTasks.add(
                    Scheduler.later(150L, () -> {
                        if (!gameRunning) return;
                        runReveal(players);
                    })
                );
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
    private void runReveal(List<ServerPlayer> players) {
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

        revealCountdown(players, "Fase perkenalan", () -> {
            if (!gameRunning) return;
            currentRevealPhase = -1;

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
            delayedTasks.add(
                Scheduler.later(100L, () -> { // 100 ticks = 5 detik
                    if (!gameRunning) return;
                    startKingReveal(players);
                })
            );
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
                    p.sendSystemMessage(Txt.t("  ⚠ " + name + " (" + roleTitle(r) + ") sedang offline.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
                }
            }
        } else if (role == Role.OBERON) {
            p.sendSystemMessage(Txt.t("  Sebagai Oberon, kamu tidak mengenal kubu jahat lainnya.", ChatFormatting.GRAY, ChatFormatting.ITALIC));
        } else {
            p.sendSystemMessage(Txt.t("  Pejamkan matamu sampai fase perkenalan selesai...", ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        p.sendSystemMessage(Txt.blank());
    }

    /** Player kembali online di tengah fase perkenalan: dudukkan lagi & kirim ulang pandangannya. */
    private void restoreRevealState(ServerPlayer player) {
        if (currentRevealPhase != 1) return;
        if (player.getVehicle() == null) {
            reseatPlayer(player, tableWorld());
        }
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

    private boolean hasRole(List<ServerPlayer> players, Role role) {
        return onlineRegistered().stream().anyMatch(p -> getRole(p) == role);
    }

    private ServerPlayer getPlayerWithRole(List<ServerPlayer> players, Role role) {
        return onlineRegistered().stream().filter(p -> getRole(p) == role).findFirst().orElse(null);
    }

    // ===== KING REVEAL =====

    /**
     * Animasi kocok Raja — dipanggil 5 detik setelah fase perkenalan selesai.
     * Mirip startRoleReveal: nama player dikocok cepat di title, lalu reveal Raja.
     */
    private void startKingReveal(List<ServerPlayer> players) {
        if (!gameRunning) return;

        // ── Setup urutan Raja berdasarkan posisi kursi searah jarum jam ────────
        kingOrder.clear();
        teamSelectionSessions.clear();
        currentMission = 1;

        Map<String, Integer> seatIndex = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            seatIndex.put(players.get(i).getGameProfile().getName(), i);
        }

        List<String> sorted = new ArrayList<>();
        for (String name : registeredPlayers) {
            ServerPlayer p = getPlayerExact(name);
            if (p == null) continue;
            sorted.add(p.getGameProfile().getName());
        }
        if (sorted.isEmpty()) return;
        sorted.sort((a, b) -> {
            int idxA = seatIndex.getOrDefault(a, 0);
            int idxB = seatIndex.getOrDefault(b, 0);
            int[] posA = PLAYER_SLAB_POSITIONS[idxA];
            int[] posB = PLAYER_SLAB_POSITIONS[idxB];
            double angleA = Math.toDegrees(Math.atan2(posA[0], posA[1]));
            double angleB = Math.toDegrees(Math.atan2(posB[0], posB[1]));
            if (angleA < 0) angleA += 360;
            if (angleB < 0) angleB += 360;
            return Double.compare(angleB, angleA);
        });

        // Pilih Raja pertama secara acak (atau player "selalu raja" kalau sedang dites)
        int randomStart = (int) (Math.random() * sorted.size());
        if (alwaysKing != null && sorted.contains(alwaysKing)) randomStart = sorted.indexOf(alwaysKing);
        for (int i = 0; i < sorted.size(); i++) {
            kingOrder.add(sorted.get((randomStart + i) % sorted.size()));
        }

        currentKingIndex = 0;
        String kingName = kingOrder.get(0);

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
                                TeamSelectionGUI.getTeamSize(players.size(), 1) + " orang",
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
                startTeamSelectionActionBar(kingName);
                // Mahkota sudah terbentuk di client; ini untuk yang baru masuk / tertinggal paketnya
                sendCrown(false);
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

    private void startCountdown(List<ServerPlayer> activePlayers, ServerPlayer initiator) {
        countdownTask = new Task() {
            int seconds = 5;

            @Override
            public void run() {
                if (seconds <= 0) {
                    for (ServerPlayer p : activePlayers) Fx.title(p, "§a§lMULAI!", "", 0, 20, 10);
                    countdownTask = null;
                    enterAvalon(activePlayers, initiator);
                    cancel();
                    return;
                }
                for (ServerPlayer p : activePlayers) {
                    Fx.title(p, "Game dimulai dalam...", "§e§l" + seconds, 0, 25, 0);
                    Fx.sound(p, SoundEvents.NOTE_BLOCK_PLING, 1f, 1f);
                }
                seconds--;
            }
        }.runTimer(0L, 20L);
    }

    public void startGame(ServerPlayer initiator) {
        if (gameRunning) { initiator.sendSystemMessage(Txt.t("Game sudah berjalan!", ChatFormatting.RED)); return; }
        List<ServerPlayer> activePlayers = getOnlinePlayers();
        if (activePlayers.size() < 5) { initiator.sendSystemMessage(Txt.t("Minimal 5 player!", ChatFormatting.RED)); return; }
        if (activePlayers.size() > PLAYER_SLAB_POSITIONS.length) { initiator.sendSystemMessage(Txt.t("Terlalu banyak player!", ChatFormatting.RED)); return; }
        for (ServerPlayer p : activePlayers) {
            p.getInventory().clearContent();
        }
        gameRunning = true;
        for (ServerPlayer p : activePlayers) syncOneSlot(p);
        ServerLevel world = getGameWorld();

        world.getServer().setPvpAllowed(false);
        world.setDayTime(18000); // midnight
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, world.getServer());
        spawnMannequin(initiator.serverLevel());
        startCountdown(activePlayers, initiator);
    }

    // ===== MASUK KE AVALON =====

    /**
     * Bawa semua player ke lingkaran kursi di dimensi Avalon, lalu lanjut ke game.
     * Cutscene aktif: lewat cutscene portal (portal terbuka di arah pandang {@code initiator}).
     * Cutscene mati: langsung dipindahkan.
     */
    private void enterAvalon(List<ServerPlayer> activePlayers, ServerPlayer initiator) {
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
        for (ServerPlayer p : activePlayers) {
            if (!isOnline(p)) continue;
            if (p.serverLevel() != tableWorld()) {
                travel = true;
                // Ke sinilah ia dikembalikan setelah cutscene akhir game
                returnPoints.put(p.getUUID(), new ReturnPoint(p.serverLevel().dimension(), p.position(), p.getYRot(), p.getXRot()));
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
                startGamePhase(getOnlinePlayers());
            })
        );
    }

    /**
     * Pindahkan player ke kursinya (crimson slab) di dimensi Avalon, menghadap tengah lingkaran,
     * dudukkan, lalu mainkan animasi buka mata di client-nya.
     */
    private void seatAtTable(ServerPlayer p) {
        if (!gameRunning) return;
        ServerLevel world = tableWorld();
        if (world == null) return;

        int index = registeredPlayers.indexOf(p.getGameProfile().getName());
        if (index < 0 || index >= PLAYER_SLAB_POSITIONS.length) return;
        BlockPos slab = AvalonSeats.pos(index);

        final double x = slab.getX() + 0.5, z = slab.getZ() + 0.5;
        final int y = slab.getY();
        final float yaw = yawTowardBase(x, z);

        p.setGameMode(GameType.ADVENTURE);
        teleport(p, world, x, y, z, yaw, 0);
        // Dikirim setelah teleport, supaya tiba di client sesudah ia masuk dimensi Avalon
        AvalonNetwork.sendTo(p, new AvalonNetwork.EyeOpen());

        delayedTasks.add(
            Scheduler.later(5L, () -> {
                if (!gameRunning) return;
                ArmorStand seat = spawnSeat(world, x, y + SEAT_HEIGHT, z, yaw);
                if (isOnline(p)) p.startRiding(seat, true);
            })
        );
    }

    /** Spawn Ratu Amaryn (mannequin tidur). Queen lama dihapus dulu. */
    public void spawnMannequin(ServerLevel world) {
        removeQueen(world);

        MannequinEntity m = ModEntities.MANNEQUIN.get().create(world);
        if (m == null) return;

        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("avalon_queen".getBytes()), "Amaryn");
        profile.getProperties().put("textures", new Property("textures", QUEEN_TEXTURE));
        m.setProfile(profile);

        m.moveTo(MANNEQUIN_X, MANNEQUIN_Y, MANNEQUIN_Z, MANNEQUIN_YAW, 0f);
        m.setFacing(MANNEQUIN_YAW);
        m.setInvulnerable(true);
        m.setNoGravity(true);
        m.setPersistent(true);
        m.setPose(Pose.SLEEPING);
        m.setCustomNameVisible(false);
        m.addTag("avalon_queen");
        world.addFreshEntity(m);
    }

    /** Cek apakah queen sudah ada di world. */
    public boolean hasQueen(ServerLevel world) {
        for (Entity e : world.getAllEntities()) {
            if (e instanceof MannequinEntity && e.getTags().contains("avalon_queen")) return true;
        }
        return false;
    }

    public void removeQueen(ServerLevel world) {
        List<Entity> toRemove = new ArrayList<>();
        for (Entity e : world.getAllEntities()) {
            if (e instanceof MannequinEntity && e.getTags().contains("avalon_queen")) {
                toRemove.add(e);
            }
        }
        toRemove.forEach(Entity::discard);
    }

    // ===== STOP GAME =====

    public void stopGame(ServerPlayer initiator) {
        if (!gameRunning) { initiator.sendSystemMessage(Txt.t("Tidak ada game yang berjalan!", ChatFormatting.RED)); return; }
        cleanup();
        broadcast(Txt.t("Game dihentikan oleh admin.", ChatFormatting.RED, ChatFormatting.BOLD));
        initiator.sendSystemMessage(Txt.t("Game berhasil dihentikan. Player masih terdaftar.", ChatFormatting.GREEN));
    }

    // ===== CUTSCENE =====

    private void playCutscene(ServerLevel world, List<ServerPlayer> activePlayers) {
        cutsceneRunning = true;
        for (ServerPlayer p : activePlayers) {
            if (!isOnline(p)) continue;
            p.setGameMode(GameType.SPECTATOR);
            teleport(p, world, SPECTATOR_X, SPECTATOR_Y, SPECTATOR_Z, SPECTATOR_YAW, SPECTATOR_PITCH);
            lockCamera(p, SPECTATOR_YAW, SPECTATOR_PITCH);
            lockMovement(p);
        }
        Component[] lines = {
            Txt.t("Sudah satu bulan lamanya ratu amaryn tidak sadarkan diri", ChatFormatting.YELLOW),
            Txt.t("Konon katanya ada satu ramuan yang dapat menyembuhkannya", ChatFormatting.YELLOW),
            Txt.t("Ramuan yang dibuat dengan 3 tanaman langka", ChatFormatting.YELLOW),
            Txt.t("Pitcher plant, Torch flower, Spore Blossom", ChatFormatting.GOLD, ChatFormatting.ITALIC),
            Txt.t("Hanya ada satu orang yang dapat menyembuhkannya", ChatFormatting.YELLOW),
            Txt.t("MERLIN", ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Txt.t(", sang penyihir terhebat", ChatFormatting.GOLD)),
        };
        cutsceneTask = new Task() {
            int index = 0;
            @Override
            public void run() {
                if (index >= lines.length) {
                    cancel(); cutsceneRunning = false;
                    for (ServerPlayer p : getOnlinePlayers()) {
                        unlockMovement(p);
                        unlockCamera(p);
                    }
                    delayedTasks.add(
                        Scheduler.later(10L, () -> startGamePhase(getOnlinePlayers()))
                    );
                    return;
                }
                for (ServerPlayer p : getOnlinePlayers()) p.sendSystemMessage(lines[index]);
                index++;
            }
        }.runTimer(0L, 180L);
    }

    // ===== GAME PHASE =====

    private void startGamePhase(List<ServerPlayer> activePlayers) {
        ServerLevel world = getGameWorld();
        if (world == null) return;

        broadcast(Txt.t("═══════════════════════", ChatFormatting.GOLD));
        broadcast(Txt.blank());
        broadcast(Txt.t("  🤫 GAME DIMULAI 🤫", ChatFormatting.GREEN, ChatFormatting.BOLD));
        broadcast(Txt.t("  Jaga & bantu merlin menyalakan 3 pilar untuk menang!", ChatFormatting.YELLOW));
        broadcast(Txt.t("  Jangan biarkan kubu jahat menggagalkan misi!", ChatFormatting.RED));
        broadcast(Txt.t("  Plugin By ", ChatFormatting.GREEN).append(Txt.t("Aflahal", ChatFormatting.WHITE, ChatFormatting.BOLD)));
        broadcast(Txt.blank());
        broadcast(Txt.t("═══════════════════════", ChatFormatting.GOLD));

        world.setBlock(new BlockPos(BASE_X, BASE_Y, BASE_Z), Blocks.WATER_CAULDRON.defaultBlockState(), 3);
        world.setBlock(new BlockPos(BASE_X, BASE_Y - 1, BASE_Z), Blocks.CAMPFIRE.defaultBlockState(), 3);
        batteryMission.reset(server());
        delayedTasks.add(
            Scheduler.later(100L, () -> {
                if (!gameRunning) return;
                assignRoles(activePlayers);
                startRoleReveal(activePlayers);
            })
        );
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
     *         Player terpilih → Survival + Slowness 1, tangan kosong.
     * Rule 3: Misi baterai (lihat BatteryMission).
     * Rule 4: Sabotage mechanic untuk kubu jahat.
     * Rule 5: End mission & teleport.
     */
    public void startMissionPhase(List<String> team) {
        if (!gameRunning) return;
        missionActive   = true;
        missionSabotaged = false;
        sabotageCount = 0;
        sabotagedPlayers.clear();
        currentMissionTeam = new ArrayList<>(team);

        missionResolving = false;
        sendCrown(false);

        ServerLevel world = getGameWorld();

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
                // ── Player terpilih → Survival + Slowness 1 + Shears ─────────
                unseatPlayer(p);
                playerStateMap.put(p.getUUID(), OfflineState.FREE);
                p.setGameMode(GameType.SURVIVAL);

                // Slowness 1 (amplifier=0 = level 1), tanpa efek/ikon
                p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));

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
        startSabotageMechanic(team, world);
    }

    /** Setara PlayerInventory#setHeldItemSlot. */
    private void setHeldItemSlot(ServerPlayer p, int slot) {
        p.getInventory().selected = slot;
        p.connection.send(new ClientboundSetCarriedItemPacket(slot));
    }

    /**
     * Keluarkan player dari seat tanpa animasi reveal.
     */
    private void unseatPlayer(ServerPlayer p) {
        revealPhaseActive = true;
        Entity vehicle = p.getVehicle();
        if (vehicle != null) {
            vehicle.ejectPassengers();

            if (vehicle.getTags().contains("avalon_seat")) {
                vehicle.discard();
            }
        }
        revealPhaseActive = false;

        clearRevealEffects(p);
        unlockCamera(p);
        unlockMovement(p);
    }

    // ── Hotbar Lock ───────────────────────────────────────────────────────────

    private Task hotbarLockTask;

    /**
     * Setiap tick, paksa team member kembali ke slot 0 (Shears).
     * Rule 2: "tidak bisa pindah hotbar biar megang shears terus"
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
                }
            }
        }.runTimer(0L, 1L);
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
    private void startSabotageMechanic(List<String> team, ServerLevel world) {
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
        if (sabotageTimerTask != null) {
            sabotageTimerTask.cancel();
            sabotageTimerTask = null;
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
        missionSabotaged = sabotages >= (needsTwoFails ? 2 : 1);
        boolean success = !missionSabotaged;

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
                finishMission(true);
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
     * Dipanggil saat pilar yang raknya penuh ternyata disabotase (bolanya pecah).
     * Tampilkan pesan, lalu langsung teleport semua player ke seat.
     */
    public void triggerSabotageCountdown() {
        if (!missionActive) return;
        missionActive = false;
        batteryMission.stop(server());
        String teamMembers = String.join(", ", currentMissionTeam);
        stopHotbarLock();
        stopSabotageMechanic();
        stopProximityChecker();

        // Reset slowness
        for (String name : currentMissionTeam) {
            ServerPlayer p = getPlayerExact(name);
            if (p != null) p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }

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

        for (ServerPlayer p : getOnlinePlayers()) {
            p.setGameMode(GameType.ADVENTURE);
        }

        // Cutscene pilar sudah menunjukkan hasilnya: langsung kembali ke kursi
        teleportAllToSeat();
        if (evilMissionFails + 1 >= 3) {
            triggerEvilWin("3 misi telah disabotase");
            return;
        }
        startDiscussionPhase(false);
    }

    // ── Proximity Checker ─────────────────────────────────────────────────────

    private void stopProximityChecker() {
        if (proximityTask != null) {
            proximityTask.cancel();
            proximityTask = null;
        }
    }

    // ── End Mission ───────────────────────────────────────────────────────────

    /**
     * Akhiri misi sukses (pilar menyala).
     * Sabotase ditangani oleh triggerSabotageCountdown().
     */
    private void finishMission(boolean success) {
        String teamMembers = String.join(", ", currentMissionTeam);
        if (!missionActive) return;
        missionActive = false;
        batteryMission.stop(server());
        stopHotbarLock();
        stopSabotageMechanic();
        stopProximityChecker();

        // Reset slowness untuk semua team member
        for (String name : currentMissionTeam) {
            ServerPlayer p = getPlayerExact(name);
            if (p != null) {
                p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            }
        }

        if (success) {
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

            for (ServerPlayer p : getOnlinePlayers()) {
                p.setGameMode(GameType.ADVENTURE);
            }
            // Cutscene pilar sudah menunjukkan hasilnya: langsung kembali ke kursi
            returnAfterSuccess();
        }
    }

    /** Misi sukses: semua player kembali ke kursi, lalu lanjut ke diskusi (atau fase Assassin). */
    private void returnAfterSuccess() {
        teleportAllToSeat();

        // Delay 15L: tunggu seat spawn (5L) + 1 server tick settle,
        // lalu konfirmasi rotasi ke base dan lanjut
        Scheduler.later(15L, () -> {
            if (!gameRunning) return;
            for (ServerPlayer p : getOnlinePlayers()) {
                float yaw = yawTowardBase(p.getX(), p.getZ());
                setRotation(p, yaw, 0);
            }
            if (currentMission >= 3) {
                ServerPlayer assassin = getPlayerWithRole(getOnlinePlayers(), Role.ASSASSIN);

                if (assassin == null) {
                    triggerGoodWin();
                    return;
                }
                startAssassinationPhase();
            } else {
                startDiscussionPhase(true);
            }
        });
    }

    /**
     * Teleport semua player ke seat (Adventure mode) setelah misi selesai.
     * Rule 5: All Players → Seat + Adventure
     */
    private void teleportAllToSeat() {
        ServerLevel world = tableWorld();
        if (world == null) return;

        // Misi sudah selesai: mahkota raja muncul lagi
        sendCrown(false);

        for (int i = 0; i < Math.min(registeredPlayers.size(), PLAYER_SLAB_POSITIONS.length); i++) {

            ServerPlayer p = getPlayerExact(registeredPlayers.get(i));

            if (p == null) {
                // Bot-nya (kalau tadi ikut misi) kembali duduk di kursinya
                reseatOfflineMannequin(i, registeredPlayers.get(i));
                continue;
            }

            BlockPos slab = AvalonSeats.pos(i);
            int x = slab.getX(), z = slab.getZ();
            final int y = slab.getY();

            final float yaw = yawTowardBase(x + 0.5, z + 0.5);

            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.getInventory().clearContent();
            p.setGameMode(GameType.ADVENTURE);
            teleport(p, world, x + 0.5, y, z + 0.5, yaw, 0);
            Fx.actionBar(p, Txt.blank());

            final ServerPlayer fp = p;
            final int fx = x, fz = z;
            delayedTasks.add(
                Scheduler.later(5L, () -> {
                    if (!gameRunning) return;
                    ArmorStand seat = spawnSeat(world, fx + 0.5, y + SEAT_HEIGHT, fz + 0.5, yaw);
                    if (isOnline(fp)) fp.startRiding(seat, true);
                })
            );
        }
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
        broadcast(Txt.t("  Diskusikan strategi selama 10 menit.", ChatFormatting.WHITE));
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

        ServerLevel world = getGameWorld();
        world.getServer().setPvpAllowed(true);

        // Eject & bebaskan kubu jahat, kubu baik tetap di seat (lockMovement)
        for (ServerPlayer p : getOnlinePlayers()) {
            Role role = playerRoles.get(p.getUUID());
            if (role != null && role.isEvil()) {
                // Eject dari seat
                if (p.getVehicle() != null) {
                    revealPhaseActive = true;
                    p.getVehicle().ejectPassengers();
                    revealPhaseActive = false;
                }
                unlockMovement(p);
                playerStateMap.put(p.getUUID(), OfflineState.FREE);
                // Beri item skip
                giveAssassinationSkipItem(p);

                Fx.title(p,
                    "§4§l☠ FASE ASSASSINATION",
                    "§cDiskusikan siapa Merlin — 10 menit!",
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
            voter.getX(), voter.getY() + headHeight(voter), voter.getZ(), voter.getYRot(), false, true, false);
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

        if (evilOnline > 0
                && assassinationSkipVotes.size() >= evilOnline) {

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
            break; // Hanya satu assassin
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

        // Cek apakah shooter adalah assassin
        if (!(arrow.getOwner() instanceof ServerPlayer shooter)) return;
        Role shooterRole = playerRoles.get(shooter.getUUID());
        if (shooterRole != Role.ASSASSIN) return;

        assassinShotFired = true;

        if (!(target instanceof ServerPlayer targetPlayer)) {
            // Kena entity bukan player → salah
            triggerAssassinFail();
            return;
        }

        Role targetRole = playerRoles.get(targetPlayer.getUUID());
        String targetName = targetPlayer.getGameProfile().getName();

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
        playEnding(EndingTimeline.WIN, "§b§lKUBU BAIK MENANG", "§3Mereka pulang lewat portal", this::announceGoodWin);
    }

    private void announceGoodWin() {
        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.AQUA));
        broadcast(Txt.t("  🏆 KUBU BAIK MENANG!", ChatFormatting.AQUA, ChatFormatting.BOLD));
        broadcast(Txt.t("  Merlin berhasil menyembuhkan ratu amaryn!", ChatFormatting.GREEN));
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
        broadcast(Txt.t("  Alasan: " + reason, ChatFormatting.RED));
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
     * Dudukkan player di kursi nomor {@code index} (untuk cutscene akhir; bisa dipakai di luar game).
     * Kursi lamanya, kalau ada, dihapus.
     */
    public void seatForCutscene(ServerPlayer p, int index) {
        ServerLevel world = tableWorld();
        if (world == null || index < 0 || index >= PLAYER_SLAB_POSITIONS.length) return;
        releaseFromSeat(p);
        BlockPos slab = AvalonSeats.pos(index);
        double x = slab.getX() + 0.5, z = slab.getZ() + 0.5;
        float yaw = yawTowardBase(x, z);
        p.teleportTo(world, x, slab.getY(), z, yaw, 0f);
        ArmorStand seat = spawnSeat(world, x, slab.getY() + SEAT_HEIGHT, z, yaw);
        p.startRiding(seat, true);
    }

    /** Turunkan player dari kursinya (kursinya ikut dihapus), melewati larangan turun selama game. */
    public void releaseFromSeat(ServerPlayer p) {
        Entity vehicle = p.getVehicle();
        if (vehicle == null) return;
        revealPhaseActive = true;
        vehicle.ejectPassengers();
        revealPhaseActive = false;
        if (vehicle.getTags().contains("avalon_seat")) vehicle.discard();
    }

    /**
     * Mainkan cutscene akhir, lalu umumkan pemenang, bereskan game, dan kembalikan semua player
     * ke tempat asalnya (overworld).
     */
    private void playEnding(int type, String title, String subtitle, Runnable announce) {
        endingStarted = true;
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
            MinecraftServer server = server();
            for (ServerPlayer p : getOnlinePlayers()) {
                // Apa pun hasilnya (menang, kalah, Merlin terbunuh, 5x ditolak) semua pulang ke overworld:
                // ke tempat asalnya, atau ke titik spawn dunia kalau ia memulai game dari dimensi Avalon
                ReturnPoint point = points.get(p.getUUID());
                ServerLevel home = point == null || server == null ? null : server.getLevel(point.dimension());
                if (home != null) {
                    p.teleportTo(home, point.pos().x, point.pos().y, point.pos().z, point.yaw(), point.pitch());
                } else if (server != null) {
                    ServerLevel overworld = server.overworld();
                    BlockPos spawn = overworld.getSharedSpawnPos();
                    p.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, overworld.getSharedSpawnAngle(), 0f);
                }
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

    public boolean isAssassinArrow(AbstractArrow arrow) {

        if (!(arrow.getOwner() instanceof Player shooter))
            return false;

        Role role = playerRoles.get(shooter.getUUID());

        return role == Role.ASSASSIN;
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

    private List<ServerPlayer> onlineRegistered() {
        return getOnlinePlayers();
    }

    private List<String> offlineRegisteredNames() {
        List<String> list = new ArrayList<>();
        for (String name : registeredPlayers) {
            if (getPlayerExact(name) == null) list.add(name);
        }
        return list;
    }

    public ServerLevel getGameWorld() {
        for (String name : registeredPlayers) {
            ServerPlayer p = getPlayerExact(name);
            if (p != null) return p.serverLevel();
        }
        MinecraftServer server = server();
        return server != null ? server.overworld() : null;
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

        // 1. Spawn mannequin di posisi terakhir
        Vec3 loc = player.position();

        OfflineState state = detectOfflineState(player);
        if (state == OfflineState.SEATED && player.getVehicle() != null) {
            loc = player.getVehicle().position();
        }

        OfflineMannequinData data = new OfflineMannequinData(
            player.getUUID(),
            name,
            player.getGameProfile(),
            player.serverLevel(),
            loc
        );

        offlinePlayerRefs.put(name, data);

        spawnOfflineMannequin(data, state);
        // Fase perkenalan: aura & pose pindah ke mannequin-nya
        Scheduler.next(this::refreshRevealViews);

        // 2. Broadcast
        broadcast(
            Txt.t("  ⚠ ", ChatFormatting.YELLOW)
                .append(Txt.t(name, ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Txt.t(" terputus dari server.", ChatFormatting.YELLOW))
        );

        // 3. Fase misi — cek apakah seluruh tim offline (setelah player benar-benar keluar)
        if (missionActive) {
            Scheduler.next(() -> {
                checkAllMissionTeamOffline();
                // Masih ada anggota tim yang online: bot-nya melanjutkan misi menggantikan dia
                if (missionActive && currentMissionTeam.contains(name)) batteryMission.addBot(name);
            });
            return;
        }

        // 4. Fase voting — cek apakah semua yang online sudah vote
        if (votingManager != null && votingManager.isVotingActive()) {
            Scheduler.next(() -> votingManager.checkIfComplete());
            return;
        }

        if (discussionActive) {
            // Hapus vote skip player yang DC agar tidak menggantung hitungan
            discussionSkipVotes.remove(player.getUUID());
            ArmorStand skipHead = discussionSkipHeads.remove(player.getUUID());
            if (skipHead != null && skipHead.isAlive()) skipHead.discard();
            Scheduler.next(this::checkDiscussionSkipComplete);
            return;
        }

        if (assassinationActive) {
            // Hapus vote skip player yang DC agar tidak menggantung hitungan
            assassinationSkipVotes.remove(player.getUUID());
            ArmorStand skipHead = assassinationSkipHeads.remove(player.getUUID());
            if (skipHead != null && skipHead.isAlive()) skipHead.discard();
            Scheduler.next(this::checkAssassinationSkipComplete);
            return;
        }

        // 5. Fase bow assassin — jika assassin offline → grace timer
        if (assassinBowActive) {
            Role role = playerRoles.get(player.getUUID());
            if (role == Role.ASSASSIN && assassinOfflineGraceTask == null) {
                broadcast(
                    Txt.t("  ☠ Assassin offline! Kubu Baik menang dalam " + OFFLINE_GRACE_SECONDS + " detik jika tidak kembali.", ChatFormatting.RED)
                );
                scheduleAssassinOfflineGrace();
            }
            return;
        }

        // 6. Fase pemilihan tim (raja) — jika raja offline → grace timer
        if ((votingManager == null || !votingManager.isVotingActive()) && !missionActive && !discussionActive && !assassinationActive) {
            String kingName = getCurrentKingName();
            if (name.equals(kingName) && kingOfflineGraceTask == null) {
                broadcast(
                    Txt.t("  👑 Raja offline! Raja berikutnya dipilih dalam " + OFFLINE_GRACE_SECONDS + " detik jika tidak kembali.", ChatFormatting.YELLOW)
                );
                scheduleKingOfflineGrace(name);
            }
        }
    }

    /**
     * Dipanggil oleh PlayerOfflineHandler saat registered player reconnect.
     * Hapus mannequin, cancel grace timer, bersihkan inventory lama, restore
     * pose + item sesuai fase yang sedang aktif.
     */
    public void handlePlayerOnline(ServerPlayer player) {
        if (!gameRunning) return;
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
        }

        // Mahkota raja yang sedang aktif
        sendCrownTo(player, false);

        // 4. Cancel grace timer jika pemain yang bersangkutan kembali
        String kingName = getCurrentKingName();
        if (name.equals(kingName) && kingOfflineGraceTask != null) {
            cancelKingOfflineGrace();
            broadcast(Txt.t("  👑 Raja " + name + " kembali. Pemilihan tim dilanjutkan.", ChatFormatting.GOLD));
        }
        if (role == Role.ASSASSIN && assassinBowActive && assassinOfflineGraceTask != null) {
            cancelAssassinOfflineGrace();
        }

        // 5. Bersihkan sisa inventory dari fase sebelumnya
        player.getInventory().clearContent();

        // 6. Reset scale ke 1.0 (bisa saja bawa scale 1.5 dari fase sebelumnya)
        PlayerScale.set(player, 1.0);

        ServerLevel world = player.serverLevel();

        // ── Fase misi (anggota tim) ──────────────────────────────────────────
        if (missionActive && !missionResolving && currentMissionTeam.contains(name)) {
            // Dia bisa saja keluar saat masih duduk: turunkan dari kursinya
            unseatPlayer(player);
            playerStateMap.put(player.getUUID(), OfflineState.FREE);
            player.setGameMode(GameType.SURVIVAL);
            setHeldItemSlot(player, 0);
            // Menggantikan bot-nya: berdiri di posisi terakhir bot, membawa baterai yang sudah diambil bot
            batteryMission.onReturn(player);
            ServerLevel avalon = world.getServer().getLevel(AvalonDimensions.AVALON);
            if (botPosition != null && avalon != null) {
                teleport(player, avalon, botPosition.x, botPosition.y, botPosition.z, player.getYRot(), 0f);
            }
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
            player.sendSystemMessage(
                Txt.t("  🔋 Kamu kembali ke misi! Lanjutkan memasang baterai.", ChatFormatting.GREEN)
            );
            return;
        }

        // ── Fase voting ──────────────────────────────────────────────────────
        if (votingManager != null && votingManager.isVotingActive()) {
            reseatPlayer(player, world);
            votingManager.giveVoteItemsPublic(player);
            player.sendSystemMessage(
                Txt.t("  🗳 Voting sedang berlangsung. Gunakan item di hotbar untuk memberikan suara.", ChatFormatting.AQUA)
            );
            return;
        }

        // ── Fase bow assassin ────────────────────────────────────────────────
        if (assassinBowActive && role == Role.ASSASSIN) {
            // Assassin tidak duduk di fase bow — tetap berdiri sebagai viewer
            standAsViewer(player);
            giveAssassinBow();
            return;
        }

        // ── Fase diskusi assassination ───────────────────────────────────────
        if (assassinationActive) {
            if (role != null && role.isEvil()) {
                // Kubu jahat berdiri sebagai viewer saat assassination discussion
                standAsViewer(player);
                if (!assassinationSkipVotes.contains(player.getUUID())) {
                    giveAssassinationSkipItem(player);
                }
                player.sendSystemMessage(
                    Txt.t("  ☠ Kubu jahat sedang berdiskusi. Gunakan item untuk vote skip.", ChatFormatting.RED)
                );
            } else {
                // Kubu baik: duduk kembali, gerakan dikunci
                reseatPlayer(player, world);
                lockMovement(player);
            }
            return;
        }

        // ── Fase diskusi biasa ───────────────────────────────────────────────
        if (discussionActive) {
            reseatPlayer(player, world);
            if (!discussionSkipVotes.contains(player.getUUID())) {
                giveDiscussionSkipItem(player);
            }
            player.sendSystemMessage(
                Txt.t("  💬 Diskusi sedang berlangsung. Gunakan item untuk vote skip.", ChatFormatting.YELLOW)
            );
            return;
        }

        // ── Fase pemilihan tim ───────────────────────────────────────────────
        if (currentRevealPhase == -1) {
            unlockMovement(player);
            unlockCamera(player);
            clearRevealEffects(player);
            PlayerScale.set(player, 1.0);
        }
        if (currentRevealPhase != -1) {
            Scheduler.later(2L, () -> {
                if (isOnline(player)) restoreRevealState(player);
            });
            return;
        }
        reseatPlayer(player, world);
        if (name.equals(kingName)) {
            removeTeamBook(player);
            giveTeamBook(player);
            player.sendSystemMessage(
                Txt.t("  👑 Kamu adalah Raja. Gunakan Buku Pemilihan Tim untuk memilih tim.", ChatFormatting.GOLD)
            );
        }
    }

    // ── Mannequin ─────────────────────────────────────────────────────────────

    /** Seat ArmorStand palsu untuk mannequin yang sedang duduk. */
    private final Map<String, Entity> offlineMannequinSeats = new HashMap<>();

    /** State visual player saat disconnect. */
    private enum OfflineState { SEATED, VIEWER, TARGET, FREE }

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
     * Deteksi state visual player berdasarkan playerStateMap yang diupdate
     * di setiap transisi fase (standAsViewer, reseatPlayer, dll).
     */
    private OfflineState detectOfflineState(ServerPlayer player) {
        OfflineState mapped = playerStateMap.get(player.getUUID());
        if (mapped != null) return mapped;

        // Fallback jika playerStateMap belum diset (misal disconnect sangat awal)
        if (player.getVehicle() instanceof ArmorStand) return OfflineState.SEATED;
        boolean isScaled = Math.abs(PlayerScale.get(player) - 1.5) < 0.05;
        return isScaled ? OfflineState.VIEWER : OfflineState.FREE;
    }

    /**
     * Spawn mannequin "Bot <nama>" dengan skin player, duduk di atas ArmorStand palsu,
     * menghadap ke tengah meja.
     */
    private void spawnOfflineMannequin(OfflineMannequinData data, OfflineState state) {

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
        spawnOfflineMannequin(data, OfflineState.SEATED);
    }

    private void updateOfflineMannequin(String playerName, OfflineState state) {

        if (getPlayerExact(playerName) != null) {
            return;
        }

        OfflineMannequinData data = offlinePlayerRefs.get(playerName);

        if (data == null) {
            return;
        }

        Entity mannequin = offlineMannequins.get(playerName);
        if (mannequin != null) {
            data.location = mannequin.position();
        }
        removeOfflineMannequin(playerName);
        spawnOfflineMannequin(data, state);
    }

    // ── Grace timers ──────────────────────────────────────────────────────────

    /**
     * Jadwalkan auto-rotasi raja setelah grace period jika raja masih offline.
     */
    private void scheduleKingOfflineGrace(String kingName) {
        if (kingOfflineGraceTask != null) return;
        kingOfflineGraceTask = Scheduler.later(OFFLINE_GRACE_SECONDS * 20L, () -> {
            kingOfflineGraceTask = null;
            if (!gameRunning) return;
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

        // Tidak ada satupun raja yang online
        broadcast(Txt.t("⚠ Tidak ada pemain online untuk menjadi Raja.", ChatFormatting.RED));
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
        if (!missionActive) return;
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

        missionActive = false;
        batteryMission.stop(server());
        stopHotbarLock();
        stopSabotageMechanic();
        stopProximityChecker();

        for (ServerPlayer p : getOnlinePlayers()) p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        for (ServerPlayer p : getOnlinePlayers()) p.setGameMode(GameType.ADVENTURE);

        Scheduler.later(60L, () -> {
            if (!gameRunning) return;
            teleportAllToSeat();
            Scheduler.later(40L, () -> {
                if (!gameRunning) return;
                rotateKing();
            });
        });
    }

    public void cleanup() {
        // Cutscene portal pembuka / cutscene akhir (kalau masih berjalan) ikut dihentikan
        PortalCutscene.stop(this);
        EndingCutscene.stop();
        endingStarted = false;
        returnPoints.clear();
        if (AvalonMod.getInstance() != null && AvalonMod.getInstance().getTeamSelectionListener() != null) {
            AvalonMod.getInstance().getTeamSelectionListener().clearAllFloatingHeads();
        }
        // Hentikan semua task aktif
        if (cutsceneTask != null)               { cutsceneTask.cancel();               cutsceneTask = null; }
        if (countdownTask != null)              { countdownTask.cancel();              countdownTask = null; }
        if (revealCountdownTask != null)        { revealCountdownTask.cancel();        revealCountdownTask = null; }
        if (teamSelectionActionBarTask != null) { teamSelectionActionBarTask.cancel(); teamSelectionActionBarTask = null; }
        if (endMissionCountdownTask != null)    { endMissionCountdownTask.cancel();    endMissionCountdownTask = null; }

        stopDiscussionPhase();
        stopHotbarLock();
        stopSabotageMechanic();
        stopProximityChecker();

        stopAssassinationPhase();

        // Cutscene pilar yang masih berjalan: kembalikan player, lalu bawa ke titik datang
        // (mereka sedang diparkir tinggi di atas pilar)
        if (pillarCutsceneRunning) {
            endPillarCutscene();
            for (ServerPlayer p : getOnlinePlayers()) AvalonPortal.teleportToSpawn(p);
        }

        // Pilar mati, rak pilar kosong & tertutup, gudang penuh
        // (hanya kalau memang ada game: cleanup juga dipanggil saat server berhenti)
        if (gameRunning) batteryMission.reset(server());

        // Cancel voting jika sedang berjalan
        if (votingManager != null) {
            votingManager.cancelVoting();
        }

        // Set gameRunning false SEBELUM operasi lain supaya semua task
        // yang cek gameRunning langsung berhenti di iterasi berikutnya
        gameRunning       = false;
        cutsceneRunning   = false;
        revealPhaseActive = false;
        currentRevealPhase = -1;
        currentRevealSeconds = -1;
        missionActive     = false;
        missionSabotaged  = false;
        missionResolving = false;
        currentMissionTeam.clear();
        discussionActive = false;
        discussionAfterSuccess = false;
        discussionSkipVotes.clear();
        removeDiscussionSkipItems();
        assassinationActive = false;
        assassinShotFired = false;
        assassinBowActive = false;
        assassinationSkipVotes.clear();

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
        playerStateMap.clear();

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
            AvalonNetwork.sendTo(p, new AvalonNetwork.OneSlot(false));
        }
        lockedYaw.clear();
        lockedPitch.clear();
        movementLocked.clear();
        movementAnchors.clear();

        // Reset King state
        kingOrder.clear();
        currentKingIndex = -1;
        currentMission   = 1;
        currentRound     = 1;
        evilMissionFails = 0;
        teamSelectionSessions.clear();

        // Clear effect reveal + turunkan semua player dari seat
        for (ServerPlayer p : getOnlinePlayers()) {
            p.getInventory().clearContent();
            clearRevealEffects(p);

            PlayerScale.set(p, 1.0);
            if (p.getVehicle() != null) {
                Entity v = p.getVehicle();
                // Bypass listener untuk eject
                revealPhaseActive = true;
                v.ejectPassengers();
                revealPhaseActive = false;
                if (v.getTags().contains("avalon_seat")) v.discard();
            }
            p.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            p.setGameMode(GameType.ADVENTURE);
        }

        // Hapus entity dan blok arena
        ServerLevel gameWorld = getGameWorld();
        if (gameWorld != null) {
            gameWorld.getServer().setPvpAllowed(true);
            gameWorld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, gameWorld.getServer());
            List<Entity> toRemove = new ArrayList<>();
            for (Entity e : gameWorld.getAllEntities()) {
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
            gameWorld.setBlock(new BlockPos(BASE_X, BASE_Y, BASE_Z), Blocks.AIR.defaultBlockState(), 3);
            gameWorld.setBlock(new BlockPos(BASE_X, BASE_Y - 1, BASE_Z), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
        }
    }
}
