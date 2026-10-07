package id.avalon.network;

import id.avalon.AvalonMod;
import id.avalon.block.BatteryRackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Channel jaringan Avalon. Dipakai untuk fitur yang di plugin dikerjakan server
 * (setRotation, lock kamera/gerak, Attribute.SCALE) tapi di Forge perlu kerja sama client.
 */
public final class AvalonNetwork {

    private static final String PROTOCOL = "4";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AvalonMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private AvalonNetwork() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, CameraLock.class, CameraLock::encode, CameraLock::decode,
                CameraLock::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MovementLock.class, MovementLock::encode, MovementLock::decode,
                MovementLock::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, Rotate.class, Rotate::encode, Rotate::decode,
                Rotate::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, Scale.class, Scale::encode, Scale::decode,
                Scale::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PortalStart.class, PortalStart::encode, PortalStart::decode,
                PortalStart::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, PortalStop.class, PortalStop::encode, PortalStop::decode,
                PortalStop::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, EyeOpen.class, EyeOpen::encode, EyeOpen::decode,
                EyeOpen::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, RoleShuffle.class, RoleShuffle::encode, RoleShuffle::decode,
                RoleShuffle::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, Reveal.class, Reveal::encode, Reveal::decode,
                Reveal::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, KingRoulette.class, KingRoulette::encode, KingRoulette::decode,
                KingRoulette::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, Crown.class, Crown::encode, Crown::decode,
                Crown::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, OneSlot.class, OneSlot::encode, OneSlot::decode,
                OneSlot::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, LeftClick.class, LeftClick::encode, LeftClick::decode,
                LeftClick::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, RackClick.class, RackClick::encode, RackClick::decode,
                RackClick::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, PillarCutscene.class, PillarCutscene::encode, PillarCutscene::decode,
                PillarCutscene::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, EndingStart.class, EndingStart::encode, EndingStart::decode,
                EndingStart::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, EndingStop.class, EndingStop::encode, EndingStop::decode,
                EndingStop::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    // ── Helpers kirim ─────────────────────────────────────────────────────────

    public static void sendTo(ServerPlayer player, Object msg) {
        if (player == null || player.connection == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void sendTrackingAndSelf(Entity entity, Object msg) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), msg);
    }

    // ── Packets ───────────────────────────────────────────────────────────────

    /** Kunci / buka rotasi kamera client. */
    public record CameraLock(boolean locked, float yaw, float pitch) {
        static void encode(CameraLock m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.locked);
            buf.writeFloat(m.yaw);
            buf.writeFloat(m.pitch);
        }

        static CameraLock decode(FriendlyByteBuf buf) {
            return new CameraLock(buf.readBoolean(), buf.readFloat(), buf.readFloat());
        }

        static void handle(CameraLock m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.cameraLock(m.locked, m.yaw, m.pitch)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Kunci / buka gerakan client (pengganti cancel PlayerMoveEvent). */
    public record MovementLock(boolean locked) {
        static void encode(MovementLock m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.locked);
        }

        static MovementLock decode(FriendlyByteBuf buf) {
            return new MovementLock(buf.readBoolean());
        }

        static void handle(MovementLock m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.movementLock(m.locked)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Set rotasi player (pengganti Player#setRotation). */
    public record Rotate(float yaw, float pitch) {
        static void encode(Rotate m, FriendlyByteBuf buf) {
            buf.writeFloat(m.yaw);
            buf.writeFloat(m.pitch);
        }

        static Rotate decode(FriendlyByteBuf buf) {
            return new Rotate(buf.readFloat(), buf.readFloat());
        }

        static void handle(Rotate m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.rotate(m.yaw, m.pitch)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Skala entity (pengganti Attribute.SCALE). */
    public record Scale(int entityId, float scale) {
        static void encode(Scale m, FriendlyByteBuf buf) {
            buf.writeInt(m.entityId);
            buf.writeFloat(m.scale);
        }

        static Scale decode(FriendlyByteBuf buf) {
            return new Scale(buf.readInt(), buf.readFloat());
        }

        static void handle(Scale m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.scale(m.entityId, m.scale)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Satu player yang tersedot di cutscene portal: kapan mulai, gaya animasi, dan posisi awalnya. */
    public record PortalActor(int entityId, int delay, int style, float scale, double x, double y, double z) {
        static void encode(FriendlyByteBuf buf, PortalActor a) {
            buf.writeInt(a.entityId);
            buf.writeVarInt(a.delay);
            buf.writeVarInt(a.style);
            buf.writeFloat(a.scale);
            buf.writeDouble(a.x);
            buf.writeDouble(a.y);
            buf.writeDouble(a.z);
        }

        static PortalActor decode(FriendlyByteBuf buf) {
            return new PortalActor(buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
    }

    /**
     * Mulai cutscene portal: posisi portal, arah mendatar dari para player ke portal,
     * titik tengah para player, dan daftar player yang tersedot.
     */
    public record PortalStart(double x, double y, double z, float dirX, float dirZ, float radius,
                              double centerX, double centerY, double centerZ, List<PortalActor> actors) {
        static void encode(PortalStart m, FriendlyByteBuf buf) {
            buf.writeDouble(m.x);
            buf.writeDouble(m.y);
            buf.writeDouble(m.z);
            buf.writeFloat(m.dirX);
            buf.writeFloat(m.dirZ);
            buf.writeFloat(m.radius);
            buf.writeDouble(m.centerX);
            buf.writeDouble(m.centerY);
            buf.writeDouble(m.centerZ);
            buf.writeCollection(m.actors, PortalActor::encode);
        }

        static PortalStart decode(FriendlyByteBuf buf) {
            return new PortalStart(buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readList(PortalActor::decode));
        }

        static void handle(PortalStart m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.portalStart(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Akhiri cutscene portal di client. {@code arrived} = player client ini sudah masuk portal tapi tidak
     * dipindahkan: kameranya dikembalikan, sisa cutscene tetap terlihat dari matanya sendiri.
     * false = cutscene dibatalkan seluruhnya.
     */
    public record PortalStop(boolean arrived) {
        static void encode(PortalStop m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.arrived);
        }

        static PortalStop decode(FriendlyByteBuf buf) {
            return new PortalStop(buf.readBoolean());
        }

        static void handle(PortalStop m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.portalStop(m.arrived)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Mainkan animasi buka mata di client (layar gelap yang membuka seperti kelopak mata). */
    public record EyeOpen() {
        static void encode(EyeOpen m, FriendlyByteBuf buf) {
        }

        static EyeOpen decode(FriendlyByteBuf buf) {
            return new EyeOpen();
        }

        static void handle(EyeOpen m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.eyeOpen()));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Mainkan animasi kocok peran. Hanya berisi jumlah kursi terisi, kursi penerima, dan kubu
     * penerima sendiri: peran player lain tidak pernah dikirim ke client.
     */
    public record RoleShuffle(int seats, int ownSeat, boolean evil) {
        static void encode(RoleShuffle m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.seats);
            buf.writeInt(m.ownSeat);
            buf.writeBoolean(m.evil);
        }

        static RoleShuffle decode(FriendlyByteBuf buf) {
            return new RoleShuffle(buf.readVarInt(), buf.readInt(), buf.readBoolean());
        }

        static void handle(RoleShuffle m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.roleShuffle(m.seats, m.ownSeat, m.evil)));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Pandangan satu player di fase perkenalan: entity yang beraura merah / ungu, entity yang tetap
     * tegak (sisanya terlihat menunduk), atau mata terpejam. {@code active=false} = fase selesai.
     */
    public record Reveal(boolean active, boolean eyesClosed,
                         List<Integer> red, List<Integer> purple, List<Integer> upright) {
        public static final Reveal END = new Reveal(false, false, List.of(), List.of(), List.of());

        static void encode(Reveal m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.active);
            buf.writeBoolean(m.eyesClosed);
            buf.writeCollection(m.red, FriendlyByteBuf::writeInt);
            buf.writeCollection(m.purple, FriendlyByteBuf::writeInt);
            buf.writeCollection(m.upright, FriendlyByteBuf::writeInt);
        }

        static Reveal decode(FriendlyByteBuf buf) {
            return new Reveal(buf.readBoolean(), buf.readBoolean(),
                    buf.readList(FriendlyByteBuf::readInt),
                    buf.readList(FriendlyByteBuf::readInt),
                    buf.readList(FriendlyByteBuf::readInt));
        }

        static void handle(Reveal m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.reveal(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Mainkan animasi kocok raja: pedang berhenti di kursi {@code targetSeat} milik {@code king}. */
    public record KingRoulette(int targetSeat, List<Integer> seats, String king) {
        static void encode(KingRoulette m, FriendlyByteBuf buf) {
            buf.writeInt(m.targetSeat);
            buf.writeCollection(m.seats, FriendlyByteBuf::writeVarInt);
            buf.writeUtf(m.king);
        }

        static KingRoulette decode(FriendlyByteBuf buf) {
            return new KingRoulette(buf.readInt(), buf.readList(FriendlyByteBuf::readVarInt), buf.readUtf());
        }

        static void handle(KingRoulette m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.kingRoulette(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Satu player di cutscene akhir: kubunya, gaya berpamitannya, dan urutannya di kubunya. */
    public record EndingActor(int entityId, boolean good, int style, int order) {
        static void encode(FriendlyByteBuf buf, EndingActor a) {
            buf.writeInt(a.entityId);
            buf.writeBoolean(a.good);
            buf.writeVarInt(a.style);
            buf.writeVarInt(a.order);
        }

        static EndingActor decode(FriendlyByteBuf buf) {
            return new EndingActor(buf.readInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /**
     * Mulai cutscene akhir game (lihat EndingTimeline): jenisnya, lamanya, jumlah player kubu baik,
     * entity Merlin & assassin (-1 = tidak ada), pilar mana saja yang sedang menyala (indeks AvalonPillars.SITES),
     * dan daftar player yang tampil.
     */
    public record EndingStart(int type, int total, int goodCount, int merlinId, int assassinId,
                              List<Integer> litPillars, List<EndingActor> actors) {
        static void encode(EndingStart m, FriendlyByteBuf buf) {
            buf.writeVarInt(m.type);
            buf.writeVarInt(m.total);
            buf.writeVarInt(m.goodCount);
            buf.writeInt(m.merlinId);
            buf.writeInt(m.assassinId);
            buf.writeCollection(m.litPillars, FriendlyByteBuf::writeVarInt);
            buf.writeCollection(m.actors, EndingActor::encode);
        }

        static EndingStart decode(FriendlyByteBuf buf) {
            return new EndingStart(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt(),
                    buf.readList(FriendlyByteBuf::readVarInt), buf.readList(EndingActor::decode));
        }

        static void handle(EndingStart m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.endingStart(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Akhiri cutscene akhir game di client. */
    public record EndingStop() {
        public static final EndingStop INSTANCE = new EndingStop();

        static void encode(EndingStop m, FriendlyByteBuf buf) {
        }

        static EndingStop decode(FriendlyByteBuf buf) {
            return INSTANCE;
        }

        static void handle(EndingStop m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.endingStop()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Mahkota raja: {@code king} kosong = hapus; {@code animate} = terbang dari kepala raja sebelumnya. */
    public record Crown(String king, int seat, boolean animate) {
        public static final Crown NONE = new Crown("", -1, false);

        static void encode(Crown m, FriendlyByteBuf buf) {
            buf.writeUtf(m.king);
            buf.writeInt(m.seat);
            buf.writeBoolean(m.animate);
        }

        static Crown decode(FriendlyByteBuf buf) {
            return new Crown(buf.readUtf(), buf.readInt(), buf.readBoolean());
        }

        static void handle(Crown m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.crown(m)));
            ctx.get().setPacketHandled(true);
        }
    }

    /** Inventory 1 slot aktif / tidak (selama game): client mengganti hotbar & mengunci inventory. */
    public record OneSlot(boolean active) {
        static void encode(OneSlot m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.active);
        }

        static OneSlot decode(FriendlyByteBuf buf) {
            return new OneSlot(buf.readBoolean());
        }

        static void handle(OneSlot m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.oneSlot(m.active)));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Client → server: player menekan tombol serang (klik kiri). Server tidak punya event untuk
     * klik kiri di udara, padahal item voting memakainya untuk "Tolak".
     */
    public record LeftClick() {
        static void encode(LeftClick m, FriendlyByteBuf buf) {
        }

        static LeftClick decode(FriendlyByteBuf buf) {
            return new LeftClick();
        }

        static void handle(LeftClick m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                AvalonMod mod = AvalonMod.getInstance();
                if (sender != null && mod != null) mod.getGameManager().handleLeftClick(sender);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** Client → server: klik kiri di lubang rak baterai (ambil / pasang), dengan titik persis yang diklik. */
    public record RackClick(BlockPos pos, Direction face, Vec3 hit) {
        /** Jarak terjauh mata player ke titik yang diklik (sedikit di atas jangkauan creative). */
        private static final double MAX_REACH = 7.0;

        static void encode(RackClick m, FriendlyByteBuf buf) {
            buf.writeBlockPos(m.pos);
            buf.writeEnum(m.face);
            buf.writeDouble(m.hit.x);
            buf.writeDouble(m.hit.y);
            buf.writeDouble(m.hit.z);
        }

        static RackClick decode(FriendlyByteBuf buf) {
            return new RackClick(buf.readBlockPos(), buf.readEnum(Direction.class),
                    new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }

        static void handle(RackClick m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer sender = ctx.get().getSender();
                if (sender == null || sender.isSpectator()) return;
                // Titiknya harus di block itu dan dalam jangkauan player
                if (!sender.level().isLoaded(m.pos)) return;
                if (m.hit.distanceToSqr(Vec3.atCenterOf(m.pos)) > 1.0) return;
                if (m.hit.distanceToSqr(sender.getEyePosition()) > MAX_REACH * MAX_REACH) return;
                BatteryRackBlock.leftClick(sender, m.pos, m.face, m.hit);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * Cutscene pilar di akhir misi. {@code active=true}: kamera client menyorot pilar di {@code pos}
     * selama {@code duration} tick, mulai dari sisi rak baterainya ({@code faceX}, {@code faceZ}).
     * {@code active=false}: cutscene selesai, kamera kembali ke player.
     */
    public record PillarCutscene(boolean active, BlockPos pos, int faceX, int faceZ, int duration) {
        public static final PillarCutscene STOP = new PillarCutscene(false, BlockPos.ZERO, 0, 0, 0);

        static void encode(PillarCutscene m, FriendlyByteBuf buf) {
            buf.writeBoolean(m.active);
            buf.writeBlockPos(m.pos);
            buf.writeByte(m.faceX);
            buf.writeByte(m.faceZ);
            buf.writeVarInt(m.duration);
        }

        static PillarCutscene decode(FriendlyByteBuf buf) {
            return new PillarCutscene(buf.readBoolean(), buf.readBlockPos(), buf.readByte(), buf.readByte(), buf.readVarInt());
        }

        static void handle(PillarCutscene m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> id.avalon.client.ClientPacketHandler.pillarCutscene(m)));
            ctx.get().setPacketHandled(true);
        }
    }
}
