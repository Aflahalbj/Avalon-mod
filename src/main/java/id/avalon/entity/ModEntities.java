package id.avalon.entity;

import id.avalon.AvalonMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AvalonMod.MOD_ID);

    public static final RegistryObject<EntityType<MannequinEntity>> MANNEQUIN = ENTITIES.register("mannequin",
            () -> EntityType.Builder.<MannequinEntity>of(MannequinEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .clientTrackingRange(10)
                    .build(AvalonMod.MOD_ID + ":mannequin"));

    private ModEntities() {}
}
