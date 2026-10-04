package id.avalon.block;

import net.minecraft.core.BlockPos;
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
 * Dinyalakan / dimatikan lewat {@link #setLit} atau sinyal redstone.
 */
public class PillarBlock extends BaseEntityBlock {

    /** Jarak bola kristal dari block (y + 12). */
    public static final int ORB_HEIGHT = 12;

    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    /** Sinyal redstone terakhir. Dipisah dari LIT supaya update tetangga tidak menimpa {@link #setLit}. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public PillarBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                // Tidak bisa dihancurkan di survival (seperti bedrock)
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
                .lightLevel(state -> state.getValue(LIT) ? 15 : 0));
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(POWERED, false));
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
        if (state.getValue(LIT) != lit) {
            level.setBlock(pos, state.setValue(LIT, lit), Block.UPDATE_ALL);
        }
        return true;
    }

    public static boolean isLit(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof PillarBlock && state.getValue(LIT);
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
        builder.add(LIT, POWERED);
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
        // Partikel murni visual: cukup di client
        if (!level.isClientSide) return null;
        return createTickerHelper(type, ModBlocks.PILLAR_ENTITY.get(), PillarBlockEntity::clientTick);
    }
}
