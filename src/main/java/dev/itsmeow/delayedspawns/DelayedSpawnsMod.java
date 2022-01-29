package dev.itsmeow.delayedspawns;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Predicate;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(DelayedSpawnsMod.MOD_ID)
@Mod.EventBusSubscriber(modid = DelayedSpawnsMod.MOD_ID)
public class DelayedSpawnsMod {

    public static final String MOD_ID = "delayedspawns";

    public static ServerConfig SERVER_CONFIG = null;
    private static ForgeConfigSpec SERVER_CONFIG_SPEC = null;

    public static final String CONFIG_FIELD_NAME = "spawn_teleport_delay";
    public static final String CONFIG_FIELD_COMMENT = "Sets the teleportation delay, in seconds.";
    public static final int CONFIG_FIELD_VALUE = 5;
    public static final int CONFIG_FIELD_MIN = 0;
    public static final int CONFIG_FIELD_MAX = Integer.MAX_VALUE;

    public static HashMap<UUID, Integer> activeSpawnTPRequests = new HashMap<>();
    public static HashMap<UUID, BlockPos> activeSpawnTPRequestersPositions = new HashMap<>();

    public DelayedSpawnsMod() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (s, b) -> true));
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
    }

    private void loadComplete(final FMLLoadCompleteEvent event) {
        final Pair<ServerConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_CONFIG_SPEC = specPair.getRight();
        SERVER_CONFIG = specPair.getLeft();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_CONFIG_SPEC);
    }

    public static class ServerConfig {
        public ForgeConfigSpec.Builder builder;
        public final ForgeConfigSpec.IntValue teleportDelay;

        ServerConfig(ForgeConfigSpec.Builder builder) {
            this.builder = builder;
            this.teleportDelay = builder.comment(DelayedSpawnsMod.CONFIG_FIELD_COMMENT
                    + " Place a copy of this config in the defaultconfigs/ folder in the main server/.minecraft directory (or make the folder if it's not there) to copy this to new worlds.")
                    .defineInRange(DelayedSpawnsMod.CONFIG_FIELD_NAME,
                            DelayedSpawnsMod.CONFIG_FIELD_VALUE,
                            DelayedSpawnsMod.CONFIG_FIELD_MIN,
                            DelayedSpawnsMod.CONFIG_FIELD_MAX);
            builder.build();
        }
    }

    private static int getTeleportTimeout() {
        return SERVER_CONFIG.teleportDelay.get();
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        Predicate<CommandSource> isPlayer = source -> {
            try {
                return source.getPlayerOrException() != null;
            } catch(CommandSyntaxException e) {
                return false;
            }
        };

        CommandDispatcher<CommandSource> dispatcher = event.getDispatcher();

        // spawn
        dispatcher.register(Commands.literal("spawn").requires(isPlayer).executes(command -> {
            ServerPlayerEntity player = command.getSource().getPlayerOrException();
            CompoundNBT data = QSWorldStorage.get(player.getCommandSenderWorld()).data;
            if(data == null
                    || !data.contains("dimension")
                    || !data.contains("x")
                    || !data.contains("y")
                    || !data.contains("z")
                    || !data.contains("yaw")
                    || !data.contains("pitch")) {
                player.sendMessage(new StringTextComponent("No spawn has been set!").setStyle(Style.EMPTY.withColor(TextFormatting.RED)), Util.NIL_UUID);
                return 0;
            } else {

                activeSpawnTPRequests.put(player.getUUID(), getTeleportTimeout() * 20);
                activeSpawnTPRequestersPositions.put(player.getUUID(), player.blockPosition());
                player.sendMessage(new StringTextComponent("Teleporting to spawn. Stay still.").setStyle(Style.EMPTY.withColor(TextFormatting.GOLD)), Util.NIL_UUID);

            }
            return 1;
        }));

        // setspawn
        dispatcher.register(Commands.literal("setspawn").requires(isPlayer).requires(s -> s.hasPermission(3)).executes(command -> {
            ServerPlayerEntity player = command.getSource().getPlayerOrException();
            QSWorldStorage sd = QSWorldStorage.get(player.getCommandSenderWorld());
            sd.data.putDouble("x", player.getX());
            sd.data.putDouble("y", player.getY());
            sd.data.putDouble("z", player.getZ());
            sd.data.putFloat("yaw", player.yHeadRot);
            sd.data.putFloat("pitch", player.xRot);
            sd.data.putString("dimension", player.getCommandSenderWorld().dimension().location().toString());
            sd.setDirty();
            player.sendMessage(new StringTextComponent("Spawn set.").setStyle(Style.EMPTY.withColor(TextFormatting.GREEN)), Util.NIL_UUID);
            return 1;
        }));
    }


    public static void teleportPlayerToSpawn(ServerPlayerEntity player) {
        CompoundNBT data = QSWorldStorage.get(player.getCommandSenderWorld()).data;
        ServerWorld destWorld = player.getCommandSenderWorld()
                .getServer()
                .getLevel(RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(data.getString("dimension"))));
        player.teleportTo(destWorld,
                data.getDouble("x"),
                data.getDouble("y"),
                data.getDouble("z"),
                data.getFloat("yaw"),
                data.getFloat("pitch"));
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        HashSet<UUID> toRemove = new HashSet<>();

        for (UUID uuid : activeSpawnTPRequests.keySet()) {
            ServerPlayerEntity player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                if (!activeSpawnTPRequestersPositions.get(uuid).equals(player.blockPosition()) || player.hurtTime > 0) {
                    toRemove.add(uuid);
                    player.sendMessage(new StringTextComponent("Teleport to spawn canceled.").setStyle(Style.EMPTY.withColor(TextFormatting.RED)), Util.NIL_UUID);

                } else {
                    int delay = activeSpawnTPRequests.get(uuid);
                    if (delay > 0) {
                        delay--;
                        activeSpawnTPRequests.put(uuid, delay);
                        if (delay % 20 == 0) {
                            player.sendMessage(new StringTextComponent("Teleporting in " + delay / 20 + " seconds.").setStyle(Style.EMPTY.withColor(TextFormatting.GREEN)), Util.NIL_UUID);
                        }
                    } else {
                        teleportPlayerToSpawn(player);
                        toRemove.add(uuid);
                        player.sendMessage(new StringTextComponent("Teleported to spawn.").setStyle(Style.EMPTY.withColor(TextFormatting.GREEN)), Util.NIL_UUID);

                    }
                }
            }
        }
        for (UUID uuid : toRemove) {
            activeSpawnTPRequests.remove(uuid);
            activeSpawnTPRequestersPositions.remove(uuid);
        }
    }


    public static class QSWorldStorage extends WorldSavedData {
        private static final String DATA_NAME = DelayedSpawnsMod.MOD_ID + "_SpawnData";
        public CompoundNBT data = new CompoundNBT();

        public QSWorldStorage() {
            super(DATA_NAME);
        }

        public QSWorldStorage(String s) {
            super(s);
        }

        public static QSWorldStorage get(World world) {
            return world.getServer().overworld().getDataStorage().computeIfAbsent(QSWorldStorage::new, DATA_NAME);
        }

        @Override
        public void load(CompoundNBT nbt) {
            data = nbt;
        }

        @Override
        public CompoundNBT save(CompoundNBT compound) {
            compound = data;
            return compound;
        }
    }

}
