package id.avalon.block;

import id.avalon.AvalonMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AvalonMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AvalonMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, AvalonMod.MOD_ID);

    public static final RegistryObject<PillarBlock> PILLAR = BLOCKS.register("pillar", PillarBlock::new);

    public static final RegistryObject<Item> PILLAR_ITEM = ITEMS.register("pillar",
            () -> new BlockItem(PILLAR.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<PillarBlockEntity>> PILLAR_ENTITY =
            BLOCK_ENTITIES.register("pillar",
                    () -> BlockEntityType.Builder.of(PillarBlockEntity::new, PILLAR.get()).build(null));

    public static final RegistryObject<BatteryRackBlock> BATTERY_RACK =
            BLOCKS.register("battery_rack", BatteryRackBlock::new);

    public static final RegistryObject<Item> BATTERY_RACK_ITEM = ITEMS.register("battery_rack",
            () -> new BlockItem(BATTERY_RACK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<BatteryRackBlockEntity>> BATTERY_RACK_ENTITY =
            BLOCK_ENTITIES.register("battery_rack",
                    () -> BlockEntityType.Builder.of(BatteryRackBlockEntity::new, BATTERY_RACK.get()).build(null));

    /** Isi portal raksasa di cutscene akhir; tidak punya item (hanya dipasang oleh kode). */
    public static final RegistryObject<GateBlock> GATE = BLOCKS.register("gate", GateBlock::new);

    public static final RegistryObject<Item> BATTERY =ITEMS.register("battery",
            () -> new Item(new Item.Properties().stacksTo(16)));

    private ModBlocks() {}
}
