package id.avalon.client;

import id.avalon.AvalonMod;
import id.avalon.block.ModBlocks;
import id.avalon.core.AvalonDimensions;
import id.avalon.entity.ModEntities;
import id.avalon.mixin.EntityRendererAccessor;
import id.avalon.network.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * Event client: lock kamera, lock gerakan, render skala player, renderer mannequin, cutscene portal.
 */
public final class ClientEvents {

    private ClientEvents() {}

    @Mod.EventBusSubscriber(modid = AvalonMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(ModEntities.MANNEQUIN.get(), MannequinRenderer::new);
            event.registerBlockEntityRenderer(ModBlocks.PILLAR_ENTITY.get(), PillarRenderer::new);
        }

        @SubscribeEvent
        public static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
            event.register(AvalonDimensions.AVALON_ID, new AvalonSkyEffects());
        }
    }

    @Mod.EventBusSubscriber(modid = AvalonMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {

        // ── Camera lock ───────────────────────────────────────────────────────

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            enforceCameraLock();
            if (event.phase == TickEvent.Phase.END) {
                PortalCutsceneClient.tick();
                EyeOpenOverlay.tick();
                RoleShuffleClient.tick();
                RevealClient.tick();
                KingRouletteClient.tick();
                CrownClient.tick();
                LadyClient.tick();
                OneSlotHud.tick();
                RackClient.tick();
                PillarCutsceneClient.tick();
                EndingClient.tick();
            }
        }

        @SubscribeEvent
        public static void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase != TickEvent.Phase.START) return;
            enforceCameraLock();
            PortalCutsceneClient.setPartialTick(event.renderTickTime);
            PillarCutsceneClient.setPartialTick(event.renderTickTime);
            EndingClient.setPartialTick(event.renderTickTime);
        }

        @SubscribeEvent
        public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            // Kamera cutscene menggantikan kamera player sepenuhnya
            if (PortalCutsceneClient.applyCamera(event)) return;
            if (PillarCutsceneClient.applyCamera(event)) return;
            if (EndingClient.applyCamera(event)) return;
            if (ClientState.lockedYaw == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.getCameraType().isFirstPerson()) {
                event.setYaw(ClientState.lockedYaw);
                event.setPitch(ClientState.lockedPitch);
            }
        }

        private static void enforceCameraLock() {
            if (ClientState.lockedYaw == null) return;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            ClientPacketHandler.applyRotation(ClientState.lockedYaw, ClientState.lockedPitch);
        }

        // ── Movement lock ─────────────────────────────────────────────────────

        @SubscribeEvent
        public static void onMovementInput(MovementInputUpdateEvent event) {
            if (!ClientState.movementLocked) return;
            var input = event.getInput();
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = false;
            input.down = false;
            input.left = false;
            input.right = false;
            input.jumping = false;
            input.shiftKeyDown = false;
        }

        // ── Scale render ──────────────────────────────────────────────────────

        /** Radius bayangan asli tiap renderer player yang bayangannya sedang disembunyikan. */
        private static final Map<PlayerRenderer, Float> hiddenShadows = new HashMap<>();

        // LOWEST supaya kalau mod lain membatalkan render, pushPose tidak terjadi tanpa popPose
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
            // Cutscene portal: badan digambar di posisi animasinya, bayangan di tanah disembunyikan
            EntityRendererAccessor renderer = (EntityRendererAccessor) event.getRenderer();
            if (PortalCutsceneClient.hidesShadow(event.getEntity()) || EndingClient.hidesShadow(event.getEntity())) {
                hiddenShadows.putIfAbsent(event.getRenderer(), renderer.avalon$getShadowRadius());
                renderer.avalon$setShadowRadius(0f);
            } else {
                Float original = hiddenShadows.remove(event.getRenderer());
                if (original != null) renderer.avalon$setShadowRadius(original);
            }
            if (!PortalCutsceneClient.isVisible(event.getEntity()) || !EndingClient.isVisible(event.getEntity())) {
                // Sudah masuk portal. Batalkan sebelum pushPose: Post tidak dipanggil untuk render yang batal.
                event.setCanceled(true);
                return;
            }

            RevealClient.beforeRender(event.getEntity());
            float s = ClientState.getScale(event.getEntity().getId());
            event.getPoseStack().pushPose();
            PortalCutsceneClient.applyTransform(event.getEntity(), event.getPoseStack(), event.getPartialTick());
            EndingClient.applyTransform(event.getEntity(), event.getPoseStack(), event.getPartialTick());
            if (s != 1.0f) {
                event.getPoseStack().scale(s, s, s);
            }
        }

        @SubscribeEvent
        public static void onRenderPlayerPost(RenderPlayerEvent.Post event) {
            event.getPoseStack().popPose();
            RevealClient.afterRender(event.getEntity());
        }

        // ── Cutscene portal ───────────────────────────────────────────────────

        @SubscribeEvent
        public static void onComputeFov(ViewportEvent.ComputeFov event) {
            if (PortalCutsceneClient.hasCamera()) {
                event.setFOV(PortalCutsceneClient.fov());
            } else if (PillarCutsceneClient.hasCamera()) {
                event.setFOV(PillarCutsceneClient.fov());
            } else if (EndingClient.hasCamera()) {
                event.setFOV(EndingClient.fov());
            }
        }

        @SubscribeEvent
        public static void onRenderLevelStage(RenderLevelStageEvent event) {
            PortalRenderer.render(event);
            RoleShuffleClient.render(event);
            RevealClient.render(event);
            KingRouletteClient.render(event);
            CrownClient.render(event);
            LadyClient.render(event);
            PillarOrbRenderer.render(event);
            GateRenderer.render(event);
            EndingClient.render(event);
        }

        /** Kamera cutscene bukan dari mata player: tangan first person jangan ikut digambar. */
        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            if (PortalCutsceneClient.hasCamera() || PillarCutsceneClient.hasCamera() || EndingClient.hasCamera()) {
                event.setCanceled(true);
            }
        }

        /** Sembunyikan seluruh HUD (hotbar, crosshair, chat, ...) selama menonton cutscene. */
        @SubscribeEvent
        public static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
            if (PortalCutsceneClient.hasCamera() || PillarCutsceneClient.hasCamera() || EndingClient.hasCamera()) {
                event.setCanceled(true);
                return;
            }
            // Bar lapar tidak dipakai selama game (anggota tim misi sengaja dibuat lapar supaya tidak bisa lari)
            if (OneSlotHud.active() && event.getOverlay() == VanillaGuiOverlay.FOOD_LEVEL.type()) {
                event.setCanceled(true);
                return;
            }
            // Inventory 1 slot: hotbar vanilla diganti satu slot berbingkai
            if (OneSlotHud.active() && event.getOverlay() == VanillaGuiOverlay.HOTBAR.type()) {
                event.setCanceled(true);
                OneSlotHud.render(event.getGuiGraphics(),
                        event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight());
            }
        }

        // ── Inventory 1 slot ──────────────────────────────────────────────────

        /** Roda mouse tidak mengganti slot. */
        @SubscribeEvent
        public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
            if (OneSlotHud.active() && Minecraft.getInstance().screen == null) event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
            if (!event.isAttack()) return;
            // Klik kiri lubang rak baterai = ambil / pasang; klik kiri biasanya dibatalkan
            if (RackClient.tryClick()) {
                event.setCanceled(true);
                return;
            }
            OneSlotHud.onAttackKey();
        }

        /** Kelopak mata digambar paling bawah: HUD (chat, hotbar, title) tetap terlihat di atasnya. */
        @SubscribeEvent
        public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
            EyeOpenOverlay.render(event.getGuiGraphics(),
                    event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight(), event.getPartialTick());
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            int width = event.getWindow().getGuiScaledWidth();
            int height = event.getWindow().getGuiScaledHeight();
            RoleShuffleClient.renderOverlay(event.getGuiGraphics(), width, height, event.getPartialTick());
            LadyClient.renderOverlay(event.getGuiGraphics(), width, height, event.getPartialTick());
            PortalCutsceneClient.renderOverlay(event.getGuiGraphics(), width, height);
            PillarCutsceneClient.renderOverlay(event.getGuiGraphics(), width, height);
            EndingClient.renderOverlay(event.getGuiGraphics(), width, height);
        }

        /** Sisa layar putih memudar di atas layar loading Avalon. */
        @SubscribeEvent
        public static void onScreenRender(ScreenEvent.Render.Post event) {
            PortalCutsceneClient.renderAfterglow(event.getGuiGraphics(),
                    event.getScreen().width, event.getScreen().height);
        }

        @SubscribeEvent
        public static void onRenderNameTag(RenderNameTagEvent event) {
            if (PortalCutsceneClient.isActor(event.getEntity()) || EndingClient.isActor(event.getEntity())) {
                event.setResult(Event.Result.DENY);
            }
        }

        // ── Layar loading dimensi Avalon ──────────────────────────────────────

        /** Ganti layar "Loading terrain" vanilla dengan layar Avalon saat tujuan = dimensi Avalon. */
        @SubscribeEvent
        public static void onScreenOpening(ScreenEvent.Opening event) {
            if (OneSlotHud.blocks(event.getNewScreen())) {
                event.setCanceled(true);
                return;
            }
            if (event.getNewScreen() == null || event.getNewScreen().getClass() != ReceivingLevelScreen.class) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.dimension() == AvalonDimensions.AVALON) {
                event.setNewScreen(new AvalonLoadingScreen());
            }
        }

        // ── Reset saat keluar server ──────────────────────────────────────────

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            PortalCutsceneClient.stop();
            EyeOpenOverlay.stop();
            RoleShuffleClient.stop();
            RevealClient.stop(false);
            KingRouletteClient.stop();
            CrownClient.set("", -1, false);
            LadyClient.set("", -1, false);
            PillarOrbRenderer.clear();
            PillarCutsceneClient.reset();
            EndingClient.reset();
            ClientState.reset();
        }
    }
}
