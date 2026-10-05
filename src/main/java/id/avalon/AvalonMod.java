package id.avalon;

import id.avalon.block.BatteryRackBlock;
import id.avalon.block.ModBlocks;
import id.avalon.commands.AvalonCommands;
import id.avalon.core.AvalonLog;
import id.avalon.core.PlayerScale;
import id.avalon.core.Scheduler;
import id.avalon.cutscene.PortalCutscene;
import id.avalon.entity.MannequinEntity;
import id.avalon.entity.ModEntities;
import id.avalon.listeners.AssassinationListener;
import id.avalon.listeners.CommandBlockListener;
import id.avalon.listeners.CustomRoleListener;
import id.avalon.listeners.CutsceneListener;
import id.avalon.listeners.ItemGuard;
import id.avalon.listeners.MissionListener;
import id.avalon.listeners.OneSlotListener;
import id.avalon.listeners.PlayerOfflineHandler;
import id.avalon.listeners.PvPProtectionListener;
import id.avalon.listeners.TeamBookListener;
import id.avalon.listeners.TeamSelectionListener;
import id.avalon.listeners.VotingListener;
import id.avalon.managers.BatteryMission;
import id.avalon.managers.GameManager;
import id.avalon.managers.VotingManager;
import id.avalon.network.AvalonNetwork;
import id.avalon.network.ClientState;
import id.avalon.world.AvalonPillars;
import id.avalon.world.AvalonPortal;
import id.avalon.world.AvalonSeats;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.List;

/**
 * Entry point mod Avalon (pengganti AvalonPlugin extends JavaPlugin).
 */
@Mod(AvalonMod.MOD_ID)
public class AvalonMod {

    public static final String MOD_ID = "avalon";

    private static AvalonMod instance;
    private final GameManager gameManager;
    private final TeamSelectionListener teamSelectionListener;
    private final CustomRoleListener customRoleListener;
    private final VotingManager votingManager;
    private final List<ItemGuard.InventoryClickRule> inventoryClickRules;

    public AvalonMod() {
        instance = this;

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITIES.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModBlocks.ITEMS.register(modBus);
        ModBlocks.BLOCK_ENTITIES.register(modBus);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onEntityAttributes);
        modBus.addListener(this::onCreativeTabContents);

        gameManager = new GameManager();
        teamSelectionListener = new TeamSelectionListener(gameManager);
        customRoleListener = new CustomRoleListener(gameManager);

        // VotingManager — dibuat setelah gameManager siap
        votingManager = new VotingManager(gameManager);
        gameManager.setVotingManager(votingManager);

        // Selama game, rak baterai mengikuti aturan misi
        BatteryRackBlock.access = gameManager.getBatteryMission();

        AssassinationListener assassinationListener = new AssassinationListener(gameManager);
        MissionListener missionListener = new MissionListener(gameManager);
        TeamBookListener teamBookListener = new TeamBookListener(gameManager);
        VotingListener votingListener = new VotingListener(gameManager, votingManager);

        MinecraftForge.EVENT_BUS.register(new CommandBlockListener(gameManager));
        MinecraftForge.EVENT_BUS.register(new CutsceneListener(gameManager));
        MinecraftForge.EVENT_BUS.register(teamBookListener);
        MinecraftForge.EVENT_BUS.register(votingListener);
        MinecraftForge.EVENT_BUS.register(missionListener);
        MinecraftForge.EVENT_BUS.register(assassinationListener);
        MinecraftForge.EVENT_BUS.register(new PvPProtectionListener(gameManager));
        MinecraftForge.EVENT_BUS.register(new PlayerOfflineHandler(gameManager));
        OneSlotListener oneSlotListener = new OneSlotListener(gameManager);
        MinecraftForge.EVENT_BUS.register(oneSlotListener);
        MinecraftForge.EVENT_BUS.register(this);

        // Aturan klik inventory (InventoryClickEvent / InventoryDragEvent)
        inventoryClickRules = List.of(
                assassinationListener,
                missionListener,
                teamBookListener,
                votingListener,
                oneSlotListener
        );
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(AvalonNetwork::register);
    }

    private void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.MANNEQUIN.get(), MannequinEntity.createAttributes().build());
    }

    private void onCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(ModBlocks.PILLAR_ITEM);
            event.accept(ModBlocks.BATTERY_RACK_ITEM);
            event.accept(ModBlocks.BATTERY);
        }
    }

    // ── Forge bus ─────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AvalonCommands.register(event.getDispatcher(), gameManager);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        teamSelectionListener.startAnimation();
        AvalonPortal.ensurePlaced(event.getServer());
        AvalonPillars.ensurePlaced(event.getServer());
        BatteryMission.placeSourceRacks(event.getServer());
        AvalonSeats.sync(event.getServer(), gameManager.getRegisteredPlayers().size());
        AvalonLog.info("Avalon mod enabled!");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (gameManager != null) {
            gameManager.cleanup();
        }
        Scheduler.clear();
        PortalCutscene.reset();
        PlayerScale.clear();
        AvalonLog.info("Avalon mod disabled!");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        gameManager.tick();
        Scheduler.tick();
    }

    /** Kirim skala player saat mulai terlihat oleh player lain. */
    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof Player target && event.getEntity() instanceof ServerPlayer tracker) {
            PlayerScale.syncTo(tracker, target);
        }
    }

    /** Terapkan skala player ke hitbox & tinggi mata (pengganti Attribute.SCALE). */
    @SubscribeEvent
    @SuppressWarnings("removal")
    public void onEntitySize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level() == null) return;

        float scale = player.level().isClientSide
                ? ClientState.getScale(player.getId())
                : PlayerScale.get(player);
        if (scale == 1.0f) return;

        event.setNewSize(event.getNewSize().scale(scale));
        event.setNewEyeHeight(event.getNewEyeHeight() * scale);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public static AvalonMod getInstance() {
        return instance;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public TeamSelectionListener getTeamSelectionListener() {
        return teamSelectionListener;
    }

    public CustomRoleListener getCustomRoleListener() {
        return customRoleListener;
    }

    public VotingManager getVotingManager() {
        return votingManager;
    }

    public List<ItemGuard.InventoryClickRule> getInventoryClickRules() {
        return inventoryClickRules;
    }
}
