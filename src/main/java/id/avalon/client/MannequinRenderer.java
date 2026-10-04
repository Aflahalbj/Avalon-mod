package id.avalon.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import id.avalon.entity.MannequinEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;

/**
 * Render MannequinEntity dengan model player + skin dari GameProfile-nya.
 */
public class MannequinRenderer extends LivingEntityRenderer<MannequinEntity, PlayerModel<MannequinEntity>> {

    private final PlayerModel<MannequinEntity> wideModel;
    private final PlayerModel<MannequinEntity> slimModel;

    public MannequinRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public void render(MannequinEntity entity, float yaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        this.model = isSlim(entity) ? slimModel : wideModel;
        this.model.setAllVisible(true);
        super.render(entity, yaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(MannequinEntity entity) {
        GameProfile profile = entity.getProfile();
        if (profile == null) {
            return DefaultPlayerSkin.getDefaultSkin(entity.getUUID());
        }
        SkinManager skins = Minecraft.getInstance().getSkinManager();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures = skins.getInsecureSkinInformation(profile);
        MinecraftProfileTexture skin = textures.get(MinecraftProfileTexture.Type.SKIN);
        if (skin != null) {
            return skins.registerTexture(skin, MinecraftProfileTexture.Type.SKIN);
        }
        return DefaultPlayerSkin.getDefaultSkin(profileUuid(profile, entity));
    }

    private boolean isSlim(MannequinEntity entity) {
        GameProfile profile = entity.getProfile();
        if (profile == null) {
            return "slim".equals(DefaultPlayerSkin.getSkinModelName(entity.getUUID()));
        }
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures =
                Minecraft.getInstance().getSkinManager().getInsecureSkinInformation(profile);
        MinecraftProfileTexture skin = textures.get(MinecraftProfileTexture.Type.SKIN);
        if (skin != null) {
            return "slim".equals(skin.getMetadata("model"));
        }
        return "slim".equals(DefaultPlayerSkin.getSkinModelName(profileUuid(profile, entity)));
    }

    private static UUID profileUuid(GameProfile profile, MannequinEntity entity) {
        if (profile.getId() != null) return profile.getId();
        if (profile.getName() != null) return UUIDUtil.createOfflinePlayerUUID(profile.getName());
        return entity.getUUID();
    }

    @Override
    protected void scale(MannequinEntity entity, PoseStack poseStack, float partialTick) {
        // Ukuran sama dengan player (0.9375)
        poseStack.scale(0.9375f, 0.9375f, 0.9375f);
    }

    @Override
    protected boolean shouldShowName(MannequinEntity entity) {
        return super.shouldShowName(entity)
                && (entity.shouldShowName()
                    || entity.hasCustomName() && entity == this.entityRenderDispatcher.crosshairPickEntity);
    }
}
