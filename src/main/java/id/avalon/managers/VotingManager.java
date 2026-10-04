package id.avalon.managers;

import id.avalon.AvalonMod;
import id.avalon.core.AvalonItems;
import id.avalon.core.Fx;
import id.avalon.core.Scheduler;
import id.avalon.core.Task;
import id.avalon.core.Txt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Mengelola fase voting setelah Raja mengonfirmasi tim.
 *
 * Alur:
 *  1. startVoting() — bagikan item Setuju/Tolak, mulai countdown
 *  2. Saat player klik kanan item → catat vote, tampilkan kepala melayang di atas diri sendiri
 *  3. Jika semua sudah vote → langsung selesai (hentikan waktu)
 *  4. Jika waktu habis → player yang belum vote dianggap abstain
 *  5. Evaluasi: mayoritas Setuju → fase misi; seri / lebih banyak Tolak → raja berikutnya
 *
 * Rule 1: 5x Tolak berturut-turut → Kubu Jahat menang. Warning setiap ditolak.
 */
public class VotingManager {

    // ── Texture URLs ──────────────────────────────────────────────────────────
    public static final String TEXTURE_TOLAK =
        "http://textures.minecraft.net/texture/7a254fc044efb84cd576a6c8f1144f83acdb14991232060ab486691a09b";
    public static final String TEXTURE_SETUJU =
        "http://textures.minecraft.net/texture/b5a3b49beec3ab23ae0b60dab56e9cc8fa16769a25830b5d8d6c46378f54430";

    // ── Tag Keys ──────────────────────────────────────────────────────────────
    public static final String PDC_KEY_VOTE_TYPE = "vote_type";
    public static final String VOTE_SETUJU        = "setuju";
    public static final String VOTE_TOLAK         = "tolak";
    private static final int MAX_REJECT_STREAK   = 5;   // Rule 1

    private final GameManager  gameManager;

    // State voting
    private boolean votingActive = false;
    private List<String> currentTeam = new ArrayList<>();

    /** vote: playerName → "setuju" | "tolak" */
    private final Map<String, String> votes = new LinkedHashMap<>();

    /** ArmorStand kepala melayang per player */
    private final Map<UUID, ArmorStand> voteHeads = new HashMap<>();

    private Task countdownTask;
    private Task animationTask;
    private int secondsLeft;

    // ── Rule 1: Reject Streak ─────────────────────────────────────────────────
    /** Berapa kali berturut-turut tim ditolak dalam ronde saat ini. */
    private int rejectStreak = 0;

    public VotingManager(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isVotingActive() { return votingActive; }

    /** Reset reject streak — dipanggil saat misi selesai. */
    public void resetRejectStreak() { rejectStreak = 0; }

    /**
     * Mulai fase voting.
     * @param team daftar nama player yang dipilih Raja untuk misi
     */
    public void startVoting(List<String> team) {
        if (votingActive) return;
        votingActive  = true;
        currentTeam   = new ArrayList<>(team);
        votes.clear();
        voteHeads.clear();
        secondsLeft = gameManager.getVotingSeconds();

        List<ServerPlayer> allPlayers = getRegisteredOnlinePlayers();

        // Umumkan fase voting
        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_AQUA));
        broadcast(Txt.t("  🗳 FASE VOTING DIMULAI!", ChatFormatting.AQUA, ChatFormatting.BOLD));
        broadcast(
            Txt.t("  Tim yang dipilih: ", ChatFormatting.WHITE)
                .append(Txt.t(String.join(", ", team), ChatFormatting.GREEN, ChatFormatting.BOLD))
        );
        broadcast(Txt.t("  Klik kanan untuk memberikan suara.", ChatFormatting.GRAY));
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.DARK_AQUA));
        broadcast(Txt.blank());

        // Bagikan item ke semua player
        for (ServerPlayer p : allPlayers) {
            giveVoteItems(p);
        }

        // Animasi kepala
        startAnimation();

        // Countdown
        startCountdown();
    }

    /**
     * Dipanggil dari VotingListener saat player klik kanan item Setuju/Tolak.
     * Player bisa ganti suara kapan saja selama waktu belum habis.
     */
    public void castVote(ServerPlayer player, String voteType) {
        if (!votingActive) return;
        String name = player.getGameProfile().getName();
        if (!gameManager.getRegisteredPlayers().contains(name)) return;

        boolean isChangingVote = votes.containsKey(name);
        String oldVote = votes.get(name);

        // Jika pilihan sama persis, abaikan
        if (isChangingVote && oldVote.equals(voteType)) {
            player.sendSystemMessage(Txt.t("Kamu sudah memilih itu!", ChatFormatting.GRAY));
            return;
        }

        votes.put(name, voteType);

        // ITEM TIDAK DIHAPUS — biarkan player bisa ganti suara kapan saja

        // Update kepala melayang (hapus lama, spawn baru sesuai pilihan baru)
        spawnVoteHead(player, voteType);

        // Feedback
        if (voteType.equals(VOTE_SETUJU)) {
            player.sendSystemMessage(Txt.t(isChangingVote ? "↺ Suara diubah ke " : "✔ Kamu memilih ", ChatFormatting.GREEN)
                .append(Txt.t("SETUJU", ChatFormatting.GREEN, ChatFormatting.BOLD)));
            Fx.sound(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        } else {
            player.sendSystemMessage(Txt.t(isChangingVote ? "↺ Suara diubah ke " : "✘ Kamu memilih ", ChatFormatting.RED)
                .append(Txt.t("TOLAK", ChatFormatting.RED, ChatFormatting.BOLD)));
            Fx.sound(player, SoundEvents.VILLAGER_NO, 1f, 1.0f);
        }

        // Broadcast ke semua hanya saat pertama kali vote (bukan ganti suara)
        List<ServerPlayer> onlinePlayers = getRegisteredOnlinePlayers();
        long onlineVoted = onlinePlayers.stream()
            .filter(p -> votes.containsKey(p.getGameProfile().getName()))
            .count();
        int totalPlayers = onlinePlayers.size();
        if (!isChangingVote) {
            broadcast(
                Txt.t("  » ", ChatFormatting.GRAY)
                    .append(Txt.t(name, ChatFormatting.YELLOW))
                    .append(Txt.t(" telah memberikan suara. (" + onlineVoted + "/"
                        + totalPlayers + ")", ChatFormatting.GRAY))
            );
        }

        // Cek apakah semua sudah vote
        if (onlineVoted >= totalPlayers) {
            finishVoting();
        }
    }

    /** Hentikan voting (cleanup) — dipanggil saat stopGame. */
    public void cancelVoting() {
        votingActive = false;
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (animationTask != null) { animationTask.cancel(); animationTask = null; }
        clearAllVoteHeads();
        removeVoteItemsFromAll();
        votes.clear();
        currentTeam.clear();
        rejectStreak = 0;
    }

    /**
     * Cek ulang apakah semua player online sudah vote.
     * Dipanggil saat ada player disconnect agar voting tidak hang.
     */
    public void checkIfComplete() {
        if (!votingActive) return;
        List<ServerPlayer> onlinePlayers = getRegisteredOnlinePlayers();
        int totalOnline = onlinePlayers.size();
        if (totalOnline == 0) return; // jangan finish jika tidak ada yang online
        // Hitung hanya vote dari player yang sekarang online
        long onlineVoted = onlinePlayers.stream()
            .filter(p -> votes.containsKey(p.getGameProfile().getName()))
            .count();
        if (onlineVoted >= totalOnline) {
            finishVoting();
        }
    }

    /**
     * Berikan item voting ke player tertentu (untuk restore saat reconnect).
     */
    public void giveVoteItemsPublic(ServerPlayer player) {
        giveVoteItems(player);
    }

    // ── Countdown ────────────────────────────────────────────────────────────

    private void startCountdown() {
        if (countdownTask != null) countdownTask.cancel();

        countdownTask = new Task() {
            @Override
            public void run() {
                if (!votingActive || !gameManager.isGameRunning()) {
                    cancel();
                    return;
                }

                if (secondsLeft < 0) {
                    cancel();
                    finishVoting();
                    return;
                }

                // Format waktu mm:ss
                int minutes = secondsLeft / 60;
                int seconds = secondsLeft % 60;
                String timeStr = String.format("%d:%02d", minutes, seconds);

                // Warna berdasarkan sisa waktu
                ChatFormatting timeColor = secondsLeft > 60
                    ? ChatFormatting.GREEN
                    : (secondsLeft > 30 ? ChatFormatting.YELLOW : ChatFormatting.RED);

                List<ServerPlayer> onlinePlayers = getRegisteredOnlinePlayers();
                long onlineVoted = onlinePlayers.stream()
                    .filter(p -> votes.containsKey(p.getGameProfile().getName()))
                    .count();

                Component actionBar = Txt.t("🗳 Voting | ", ChatFormatting.AQUA)
                    .append(Txt.t(timeStr, timeColor, ChatFormatting.BOLD))
                    .append(Txt.t(" | Vote: " + onlineVoted + "/"
                        + onlinePlayers.size(), ChatFormatting.GRAY));

                for (ServerPlayer p : onlinePlayers) {
                    Fx.actionBar(p, actionBar);
                }

                secondsLeft--;
            }
        }.runTimer(0L, 20L);
    }

    // ── Finish Voting ────────────────────────────────────────────────────────

    private void finishVoting() {
        if (!votingActive) return;
        votingActive = false;

        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (animationTask != null) { animationTask.cancel(); animationTask = null; }

        // Bersihkan kepala vote & item
        clearAllVoteHeads();
        removeVoteItemsFromAll();

        // Hapus floating head king dari TeamSelectionListener
        AvalonMod.getInstance().getTeamSelectionListener().clearAllFloatingHeads();

        // Clear actionbar
        for (ServerPlayer p : getRegisteredOnlinePlayers()) {
            Fx.actionBar(p, Txt.blank());
        }

        // Hitung suara
        int setuju = 0, tolak = 0;
        for (String v : votes.values()) {
            if (v.equals(VOTE_SETUJU)) setuju++;
            else tolak++;
        }

        int totalVoted = votes.size();

        broadcast(Txt.blank());
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        broadcast(Txt.t("  📊 HASIL VOTING", ChatFormatting.YELLOW, ChatFormatting.BOLD));
        broadcast(
            Txt.t("  ✔ Setuju: ", ChatFormatting.GREEN)
                .append(Txt.t(setuju, ChatFormatting.WHITE))
        );
        broadcast(
            Txt.t("  ✘ Tolak: ", ChatFormatting.RED)
                .append(Txt.t(tolak, ChatFormatting.WHITE))
        );
        if (totalVoted == 0) {
            broadcast(Txt.t("  ⚠ Tidak ada yang memberikan suara.", ChatFormatting.GRAY));
        } else {
            // Tampilkan siapa vote apa
            for (Map.Entry<String, String> entry : votes.entrySet()) {
                ChatFormatting c = entry.getValue().equals(VOTE_SETUJU)
                    ? ChatFormatting.GREEN : ChatFormatting.RED;
                String label = entry.getValue().equals(VOTE_SETUJU) ? "✔ Setuju" : "✘ Tolak";
                broadcast(
                    Txt.t("  " + entry.getKey() + ": ", ChatFormatting.GRAY)
                        .append(Txt.t(label, c))
                );
            }
        }
        broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        broadcast(Txt.blank());

        // Evaluasi hasil
        if (totalVoted == 0 || setuju <= tolak) {
            // Tim ditolak
            rejectStreak++;

            // ── Rule 1: Warning setiap ditolak ────────────────────────────────
            int remaining = MAX_REJECT_STREAK - rejectStreak;
            broadcast(Txt.blank());
            broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.RED));
            broadcast(
                Txt.t("  ❌ Tim ditolak! Giliran raja berikutnya.", ChatFormatting.RED, ChatFormatting.BOLD)
            );

            if (rejectStreak >= MAX_REJECT_STREAK) {
                // ── Rule 1: 5x berturut-turut → Kubu Jahat menang ────────────
                broadcast(
                    Txt.t("  ☠ 5 PENOLAKAN BERTURUT-TURUT!", ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                );
                broadcast(
                    Txt.t("  KUBU JAHAT MENANG!", ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                );
                broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.RED));
                broadcast(Txt.blank());

                // Trigger evil win
                Scheduler.later(60L, () -> {
                    if (!gameManager.isGameRunning()) return;
                    gameManager.triggerEvilWin("5x penolakan berturut-turut");
                });
            } else {
                // Warning sisa penolakan
                broadcast(
                    Txt.t("  ⚠ Penolakan ke-" + rejectStreak + " dari " + MAX_REJECT_STREAK + ".",
                        ChatFormatting.YELLOW)
                );
                broadcast(
                    Txt.t("  Jika ditolak " + remaining + "x lagi, Kubu Jahat menang!",
                        ChatFormatting.YELLOW)
                );
                broadcast(Txt.t("━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.RED));
                broadcast(Txt.blank());

                // Delay 3 detik lalu rotasi raja
                Scheduler.later(60L, () -> {
                    if (!gameManager.isGameRunning()) return;
                    gameManager.rotateKing();
                });
            }
        } else {
            // Tim disetujui — reset reject streak
            rejectStreak = 0;

            broadcast(
                Txt.t("  ✅ Tim disetujui! Memulai fase misi...", ChatFormatting.GREEN, ChatFormatting.BOLD)
            );
            broadcast(Txt.blank());

            final List<String> team = new ArrayList<>(currentTeam);
            Scheduler.later(60L, () -> {
                if (!gameManager.isGameRunning()) return;
                gameManager.startMissionPhase(team);
            });
        }
    }

    // ── Item Voting ──────────────────────────────────────────────────────────

    private void giveVoteItems(ServerPlayer player) {
        // Hotbar 1 (slot 0) = Tolak, Hotbar 2 (slot 1) = Setuju
        player.getInventory().setItem(0, makeTolakHead());
        player.getInventory().setItem(1, makeSetujuHead());
    }

    private void removeVoteItems(ServerPlayer player) {
        ItemStack s0 = player.getInventory().getItem(0);
        ItemStack s1 = player.getInventory().getItem(1);
        if (isVoteItem(s0)) player.getInventory().setItem(0, ItemStack.EMPTY);
        if (isVoteItem(s1)) player.getInventory().setItem(1, ItemStack.EMPTY);
    }

    private void removeVoteItemsFromAll() {
        for (ServerPlayer p : getRegisteredOnlinePlayers()) {
            removeVoteItems(p);
        }
    }

    public boolean isVoteItem(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return false;
        return AvalonItems.hasTag(item, PDC_KEY_VOTE_TYPE);
    }

    public String getVoteType(ItemStack item) {
        if (item == null || item.isEmpty() || !item.is(Items.PLAYER_HEAD)) return null;
        return AvalonItems.getTag(item, PDC_KEY_VOTE_TYPE);
    }

    private ItemStack makeTolakHead() {
        return makeTextureHead(
            TEXTURE_TOLAK,
            Txt.t("✘ TOLAK", ChatFormatting.RED, ChatFormatting.BOLD),
            List.of(
                Txt.t("Klik kanan untuk menolak tim.", ChatFormatting.GRAY),
                Txt.t("Tim tidak akan menjalankan misi.", ChatFormatting.DARK_RED),
                Txt.t("Kamu bisa mengganti suara kapan saja.", ChatFormatting.GRAY)
            ),
            VOTE_TOLAK
        );
    }

    private ItemStack makeSetujuHead() {
        return makeTextureHead(
            TEXTURE_SETUJU,
            Txt.t("✔ SETUJU", ChatFormatting.GREEN, ChatFormatting.BOLD),
            List.of(
                Txt.t("Klik kanan untuk menyetujui tim.", ChatFormatting.GRAY),
                Txt.t("Tim akan menjalankan misi.", ChatFormatting.DARK_GREEN),
                Txt.t("Kamu bisa mengganti suara kapan saja.", ChatFormatting.GRAY)
            ),
            VOTE_SETUJU
        );
    }

    private ItemStack makeTextureHead(String textureUrl, Component displayName,
                                      List<Component> lore, String voteType) {
        ItemStack skull = AvalonItems.texturedHead(textureUrl, displayName, lore);
        AvalonItems.setTag(skull, PDC_KEY_VOTE_TYPE, voteType);
        return skull;
    }

    // ── Kepala Melayang ──────────────────────────────────────────────────────

    /**
     * Spawn kepala melayang di atas player yang sudah vote.
     * Raja: Y+2.15, player lain: Y+1.5
     */
    private void spawnVoteHead(ServerPlayer voter, String voteType) {
        // Hapus kepala lama jika ada
        ArmorStand old = voteHeads.remove(voter.getUUID());
        if (old != null && old.isAlive()) {
            old.discard();
        }

        boolean isKing = gameManager.isKing(voter);
        double heightOffset = isKing ? GameManager.KING_VOTE_HEAD_HEIGHT : 1.5;

        ArmorStand stand = GameManager.spawnStand(voter.serverLevel(),
            voter.getX(), voter.getY() + heightOffset, voter.getZ(), voter.getYRot(), true, true, false);
        stand.addTag("avalon_vote_head");

        ItemStack headItem = voteType.equals(VOTE_SETUJU) ? makeSetujuHead() : makeTolakHead();
        stand.setItemSlot(EquipmentSlot.HEAD, headItem);

        voteHeads.put(voter.getUUID(), stand);
    }

    private void startAnimation() {
        if (animationTask != null) animationTask.cancel();

        animationTask = new Task() {
            double tick = 0;

            @Override
            public void run() {
                if (!votingActive && voteHeads.isEmpty()) {
                    cancel();
                    return;
                }

                tick += 0.25;

                // Update posisi semua kepala agar mengikuti player dan animasi
                List<UUID> uuids = new ArrayList<>(voteHeads.keySet());
                for (UUID uid : uuids) {
                    ArmorStand stand = voteHeads.get(uid);
                    if (stand == null || !stand.isAlive()) {
                        voteHeads.remove(uid);
                        continue;
                    }

                    ServerPlayer owner = gameManager.getPlayer(uid);
                    if (owner == null) continue;

                    boolean isKing = gameManager.isKing(owner);
                    double heightOffset = isKing ? GameManager.KING_VOTE_HEAD_HEIGHT : 1.5;

                    // Kepala mengikuti posisi player (naik-turun + rotasi)
                    double offsetY = Math.sin(tick + uid.hashCode() * 0.1) * 0.08;
                    float yaw = Mth.wrapDegrees(stand.getYRot() + 3.0f);

                    GameManager.moveStand(stand, owner.serverLevel(),
                        owner.getX(), owner.getY() + heightOffset + offsetY, owner.getZ(), yaw);
                }
            }
        }.runTimer(0L, 1L);
    }

    private void clearAllVoteHeads() {
        for (ArmorStand stand : voteHeads.values()) {
            if (stand != null && stand.isAlive()) stand.discard();
        }
        voteHeads.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<ServerPlayer> getRegisteredOnlinePlayers() {
        return gameManager.getOnlinePlayers();
    }

    private void broadcast(Component message) {
        for (ServerPlayer p : getRegisteredOnlinePlayers()) p.sendSystemMessage(message);
    }

    /** Untuk kompatibilitas: cek apakah player sudah vote. */
    public boolean hasVoted(Player player) {
        return votes.containsKey(player.getGameProfile().getName());
    }
}
