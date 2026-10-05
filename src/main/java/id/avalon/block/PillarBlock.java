package id.avalon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;

/**
 * Pilar misi: saat menyala memancarkan partikel ke atas sampai bola kristal
 * yang melayang {@link #ORB_HEIGHT} block di atasnya.
 * Dinyalakan / dimatikan lewat {@link #setLit} atau sinyal redstone;
 * {@link #overload} memainkan animasi gagal (energinya meluap lalu pecah).
 */
public class PillarBlock extends BaseEntityBlock {

    /** Jarak bola kristal dari block (y + 12). */
    public static final int ORB_HEIGHT = 12;

    // ── Linimasa animasi (tick), dipakai server (suara, pengumuman) dan client (gambar) ──
    /** Lama tiang cahaya naik sampai puncak. Sama untuk nyala maupun meluap, supaya hasilnya belum ketahuan. */
    public static final int RISE_TICKS = 60;
    /** Lama bola terbentuk setelah tiang sampai puncak. */
    public static final int IGNITE_TICKS = 50;
    /** Saat bola yang meluap pecah, dihitung dari awal {@link #overload}. */
    public static final int OVERLOAD_BURST_TICK = 115;
    /** Lama seluruh animasi meluap; setelah itu pilar kembali mati. */
    public static final int OVERLOAD_TICKS = 155;

    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /** Sinyal redstone terakhir. Dipisah dari LIT supaya update tetangga tidak menimpa {@link #setLit}. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** Sedang memainkan animasi meluap. */
    public static final BooleanProperty OVERLOAD = BooleanProperty.create("overload");

    public PillarBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                // Tidak bisa dihancurkan di survival (seperti bedrock)
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
                .lightLevel(state -> state.getValue(LIT) || state.getValue(OVERLOAD) ? 15 : 0));
        registerDefaultState(stateDefinition.any()
                .setValue(LIT, false).setValue(POWERED, false).setValue(OVERLOAD, false));
    }

    // ── Nyala / mati ──────────────────────────────────────────────────────────

    /**
     * Nyalakan / matikan pilar dari kode.
     *
     * @return false kalau block di {@code pos} bukan pilar
     */
    public static boolean setLit(Level level, BlockPos pos, boolean lit) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PillarBlock)) return false;
        if (state.getValue(LIT) != lit || state.getValue(OVERLOAD)) {
            level.setBlock(pos, state.setValue(LIT, lit).setValue(OVERLOAD, false), Block.UPDATE_ALL);
        }
        return true;
    }

    public static boolean isLit(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PillarBlock && state.getValue(LIT);
    }

    /**
     * Mainkan animasi gagal: tiang naik seperti biasa, lalu energinya meluap dan pecah.
     * Pilar kembali mati sendiri setelah {@link #OVERLOAD_TICKS}.
     *
     * @return false kalau block di {@code pos} bukan pilar
     */
    public static boolean overload(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PillarBlock)) return false;
        level.setBlock(pos, state.setValue(LIT, false).setValue(OVERLOAD, true), Block.UPDATE_ALL);
        level.scheduleTick(pos, state.getBlock(), OVERLOAD_TICKS);
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(OVERLOAD)) {
            level.setBlock(pos, state.setValue(OVERLOAD, false), Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        boolean powered = context.getLevel().hasNeighborSignal(context.getClickedPos());
        return defaultBlockState().setValue(LIT, powered).setValue(POWERED, powered);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) return;
        // Hanya bereaksi saat sinyalnya berubah, bukan tiap ada update tetangga
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered).setValue(LIT, powered), Block.UPDATE_ALL);
        }
    }

    // ── Block ─────────────────────────────────────────────────────────────────

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, POWERED, OVERLOAD);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PillarBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Animasi & partikel murni visual: cukup di client
        if (!level.isClientSide) return null;
        return createTickerHelper(type, ModBlocks.PILLAR_ENTITY.get(), PillarBlockEntity::clientTick);
    }
}
