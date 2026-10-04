package id.avalon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Rak baterai: seperti chiseled bookshelf, tapi 6 lubang di sisi depannya diisi baterai.
 * Tiap lubang bisa ditutup supaya jumlah lubang yang terbuka sama dengan jumlah anggota tim misi.
 *
 * <pre>
 *   0 1 2      ← nomor lubang, dilihat dari depan
 *   3 4 5
 * </pre>
 */
public class BatteryRackBlock extends BaseEntityBlock {

    public enum Slot implements StringRepresentable {
        EMPTY("empty"),
        BATTERY("battery"),
        CLOSED("closed");

        private final String name;

        Slot(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final int SLOT_COUNT = 6;
    private static final int COLUMNS = 3;

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final List<EnumProperty<Slot>> SLOTS = List.of(
            EnumProperty.create("slot_0", Slot.class),
            EnumProperty.create("slot_1", Slot.class),
            EnumProperty.create("slot_2", Slot.class),
            EnumProperty.create("slot_3", Slot.class),
            EnumProperty.create("slot_4", Slot.class),
            EnumProperty.create("slot_5", Slot.class));

    /** Lubang yang dibuka untuk tiap jumlah (indeks = jumlah lubang terbuka), dipilih supaya simetris. */
    private static final int[][] OPEN_PATTERNS = {
            {},
            {1},
            {0, 2},
            {0, 1, 2},
            {0, 2, 3, 5},
            {0, 1, 2, 3, 5},
            {0, 1, 2, 3, 4, 5},
    };

    public BatteryRackBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.DIAMOND)
                // Tidak bisa dihancurkan di survival (seperti pilar)
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
                // Bukan block padat penuh: tanpa ini isi lubangnya (baterai, tutup) dirender gelap total,
                // karena cahaya di posisi block padat selalu 0
                .noOcclusion()
                .lightLevel(state -> batteries(state) * 2));
        BlockState state = stateDefinition.any().setValue(FACING, Direction.NORTH);
        for (EnumProperty<Slot> slot : SLOTS) {
            state = state.setValue(slot, Slot.EMPTY);
        }
        registerDefaultState(state);
    }

    // ── Isi rak ───────────────────────────────────────────────────────────────

    public static int batteries(BlockState state) {
        return count(state, Slot.BATTERY);
    }

    /** Jumlah lubang yang tidak ditutup. */
    public static int openSlots(BlockState state) {
        return SLOT_COUNT - count(state, Slot.CLOSED);
    }

    /** Semua lubang yang terbuka sudah terisi baterai. */
    public static boolean isFull(BlockState state) {
        int open = openSlots(state);
        return open > 0 && batteries(state) == open;
    }

    private static int count(BlockState state, Slot value) {
        int count = 0;
        for (EnumProperty<Slot> slot : SLOTS) {
            if (state.getValue(slot) == value) count++;
        }
        return count;
    }

    /**
     * Buka {@code count} lubang (0..6) dan tutup sisanya, misalnya sesuai jumlah anggota tim misi.
     * Baterai di lubang yang ditutup dikeluarkan ke depan rak.
     *
     * @return false kalau block di {@code pos} bukan rak baterai
     */
    public static boolean setOpenSlots(Level level, BlockPos pos, int count) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BatteryRackBlock)) return false;

        boolean[] open = new boolean[SLOT_COUNT];
        for (int slot : OPEN_PATTERNS[Math.max(0, Math.min(SLOT_COUNT, count))]) {
            open[slot] = true;
        }
        BlockState updated = state;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            Slot current = state.getValue(SLOTS.get(slot));
            if (open[slot]) {
                if (current == Slot.CLOSED) updated = updated.setValue(SLOTS.get(slot), Slot.EMPTY);
            } else if (current != Slot.CLOSED) {
                if (current == Slot.BATTERY) {
                    Block.popResourceFromFace(level, pos, state.getValue(FACING), takeBattery(level, pos, slot));
                }
                updated = updated.setValue(SLOTS.get(slot), Slot.CLOSED);
            }
        }
        if (updated != state) level.setBlock(pos, updated, Block.UPDATE_ALL);
        return true;
    }

    /** Kosongkan semua lubang yang terisi tanpa mengeluarkan baterainya (misalnya saat misi selesai). */
    public static boolean clearBatteries(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BatteryRackBlock)) return false;

        BlockState updated = state;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (state.getValue(SLOTS.get(slot)) == Slot.BATTERY) {
                takeBattery(level, pos, slot);
                updated = updated.setValue(SLOTS.get(slot), Slot.EMPTY);
            }
        }
        if (updated != state) level.setBlock(pos, updated, Block.UPDATE_ALL);
        return true;
    }

    /** Ambil item baterai dari block entity. Lubang yang diisi lewat /setblock tidak punya item: beri baterai baru. */
    private static ItemStack takeBattery(Level level, BlockPos pos, int slot) {
        ItemStack stack = level.getBlockEntity(pos) instanceof BatteryRackBlockEntity rack
                ? rack.take(slot)
                : ItemStack.EMPTY;
        return stack.isEmpty() ? new ItemStack(ModBlocks.BATTERY.get()) : stack;
    }

    // ── Interaksi ─────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        int slot = hitSlot(state, pos, hit);
        if (slot < 0) return InteractionResult.PASS;

        EnumProperty<Slot> property = SLOTS.get(slot);
        Slot current = state.getValue(property);
        ItemStack held = player.getItemInHand(hand);

        if (current == Slot.BATTERY) {
            // Lubang terisi: ambil baterainya
            if (!level.isClientSide) {
                ItemStack battery = takeBattery(level, pos, slot);
                if (!player.getInventory().add(battery)) player.drop(battery, false);
                level.setBlock(pos, state.setValue(property, Slot.EMPTY), Block.UPDATE_ALL);
                sound(level, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, 1.0f, 0.8f);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (current == Slot.EMPTY && held.is(ModBlocks.BATTERY.get())) {
            if (!level.isClientSide) {
                // Simpan itemnya utuh (termasuk tag), supaya misi bisa membedakan baterai
                ItemStack battery = player.getAbilities().instabuild ? held.copyWithCount(1) : held.split(1);
                if (level.getBlockEntity(pos) instanceof BatteryRackBlockEntity rack) rack.put(slot, battery);
                level.setBlock(pos, state.setValue(property, Slot.BATTERY), Block.UPDATE_ALL);
                sound(level, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.7f, 1.4f + slot * 0.08f);
                Vec3 at = slotCenter(state, pos, slot);
                ((ServerLevel) level).sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 8, 0.08, 0.12, 0.08, 0.4);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Creative, tangan kosong: tutup / buka lubang
        if (player.getAbilities().instabuild && held.isEmpty() && hand == InteractionHand.MAIN_HAND) {
            if (!level.isClientSide) {
                boolean close = current == Slot.EMPTY;
                level.setBlock(pos, state.setValue(property, close ? Slot.CLOSED : Slot.EMPTY), Block.UPDATE_ALL);
                sound(level, pos, close ? SoundEvents.IRON_TRAPDOOR_CLOSE : SoundEvents.IRON_TRAPDOOR_OPEN, 0.8f, 1.3f);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    private static void sound(Level level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
    }

    /** Lubang yang diklik, atau -1 kalau yang diklik bukan sisi depan. */
    private static int hitSlot(BlockState state, BlockPos pos, BlockHitResult hit) {
        Direction facing = state.getValue(FACING);
        if (hit.getDirection() != facing) return -1;

        Vec3 local = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        // Kiri orang yang melihat sisi depan
        Direction left = facing.getClockWise();
        double fromLeft = (0.5 - (local.x * left.getStepX() + local.z * left.getStepZ())) * 16.0;
        // Lubang 4 piksel, pembatas 1 piksel: batas kolom di tengah pembatas
        int column = fromLeft < 5.5 ? 0 : fromLeft < 10.5 ? 1 : 2;
        int row = local.y >= 0.0 ? 0 : 1;
        return row * COLUMNS + column;
    }

    /** Titik tengah lubang di dunia, sedikit di depan rak. */
    private static Vec3 slotCenter(BlockState state, BlockPos pos, int slot) {
        Direction facing = state.getValue(FACING);
        Direction left = facing.getClockWise();
        double side = (1 - slot % COLUMNS) * 5.0 / 16.0;
        double up = slot < COLUMNS ? 0.25 : -0.25;
        return Vec3.atCenterOf(pos).add(
                facing.getStepX() * 0.55 + left.getStepX() * side,
                up,
                facing.getStepZ() * 0.55 + left.getStepZ() * side);
    }

    // ── Block ─────────────────────────────────────────────────────────────────

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof BatteryRackBlockEntity rack) {
                Containers.dropContents(level, pos, rack.items());
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** Comparator: jumlah baterai yang terpasang (0..6). */
    @Override
    @SuppressWarnings("deprecation")
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return batteries(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        SLOTS.forEach(builder::add);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** Tetap terlihat seperti block padat: jangan beri bayangan sudut ke block di sekitarnya. */
    @Override
    @SuppressWarnings("deprecation")
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0f;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BatteryRackBlockEntity(pos, state);
    }
}
