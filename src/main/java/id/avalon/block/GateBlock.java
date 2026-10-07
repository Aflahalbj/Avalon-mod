package id.avalon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Isi portal raksasa di cutscene akhir game. Bloknya sendiri tidak punya model: semua blok portal
 * di cincin digambar client sebagai satu gambar utuh (GateRenderer). Tidak butuh bingkai, bisa
 * ditembus, memancarkan cahaya, tidak bisa dihancurkan; dipasang dan dicabut oleh AvalonGate.
 */
public class GateBlock extends Block {

    /** Lembaran tipis di bidang X-Y (menghadap utara-selatan), seperti portal nether. */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 6.0, 16.0, 16.0, 10.0);

    public GateBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_PURPLE)
                .noCollission()
                .noOcclusion()
                .strength(-1.0f, 3600000.0f)
                .noLootTable()
                .sound(SoundType.GLASS)
                .lightLevel(state -> 13)
                .pushReaction(PushReaction.BLOCK));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
