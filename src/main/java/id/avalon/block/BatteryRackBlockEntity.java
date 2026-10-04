package id.avalon.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Menyimpan item baterai di tiap lubang rak. Tampilannya sendiri dari blockstate;
 * item disimpan utuh supaya tag-nya (misalnya penanda misi) tidak hilang.
 */
public class BatteryRackBlockEntity extends BlockEntity {

    private final NonNullList<ItemStack> items = NonNullList.withSize(BatteryRackBlock.SLOT_COUNT, ItemStack.EMPTY);

    public BatteryRackBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.BATTERY_RACK_ENTITY.get(), pos, state);
    }

    public NonNullList<ItemStack> items() {
        return items;
    }

    public ItemStack get(int slot) {
        return items.get(slot);
    }

    public void put(int slot, ItemStack stack) {
        items.set(slot, stack);
        setChanged();
    }

    public ItemStack take(int slot) {
        ItemStack stack = items.set(slot, ItemStack.EMPTY);
        setChanged();
        return stack;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, items);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag, items);
    }
}
