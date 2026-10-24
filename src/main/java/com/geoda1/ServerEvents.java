package com.geoda1;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

public class ServerEvents {
    static final long WAIT_MS = 60L * 60L * 1000L;

    static void say(MinecraftServer s, Component c) { s.getPlayerList().broadcastSystemMessage(c, false); }

    static String fmt(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format("%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        MinecraftServer srv = p.getServer();
        LifeData d = LifeData.get(srv);
        LifeData.Entry e = d.entry(p);
        if (e.state != LifeData.ALIVE) { ev.setCanceled(true); p.setHealth(p.getMaxHealth()); return; }

        e.lives--;
        e.bonus = 0;
        applyHearts(p, 0);
        d.setDirty();
        if (e.lives > 0) {
            say(srv, Component.literal("☠ " + e.name + " perdió una vida. Le queda " + e.lives + ".").withStyle(ChatFormatting.RED));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Has perdido una vida").withStyle(ChatFormatting.RED)));
            Net.sync(p, e);
            return;
        }

        // Última vida: no muere, queda esperando que lo revivan.
        ev.setCanceled(true);
        p.getInventory().dropAll();
        p.setHealth(p.getMaxHealth());
        p.clearFire();
        p.removeAllEffects();
        p.fallDistance = 0;
        e.lives = 0;
        e.state = LifeData.WAITING;
        e.deadline = System.currentTimeMillis() + WAIT_MS;
        e.x = p.getX();
        e.y = Math.max(p.getY(), p.level().getMinBuildHeight() + 1);
        e.z = p.getZ();
        p.setGameMode(GameType.SPECTATOR);
        Net.sync(p, e);
        say(srv, Component.literal("☠ " + e.name + " perdió su última vida. Tienen 1 hora para revivirlo.").withStyle(ChatFormatting.DARK_RED));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Esperá a que te revivan").withStyle(ChatFormatting.DARK_RED)));
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || ev.player.level().isClientSide) return;
        if (!(ev.player instanceof ServerPlayer p)) return;
        LifeData.Entry e = LifeData.get(p.getServer()).peek(p.getUUID());
        if (e == null || e.state != LifeData.WAITING) return;
        if (p.distanceToSqr(e.x, e.y, e.z) > 4) p.teleportTo(e.x, e.y, e.z);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END) return;
        MinecraftServer s = ServerLifecycleHooks.getCurrentServer();
        if (s == null || s.getTickCount() % 20 != 0) return;
        LifeData d = LifeData.get(s);
        long now = System.currentTimeMillis();
        for (ServerPlayer p : new ArrayList<>(s.getPlayerList().getPlayers())) {
            LifeData.Entry e = d.peek(p.getUUID());
            if (e == null || e.state != LifeData.WAITING) continue;
            long left = e.deadline - now;
            if (left <= 0) {
                p.serverLevel().explode(null, e.x, e.y, e.z, 4.0f, Level.ExplosionInteraction.MOB);
                e.state = LifeData.ELIMINATED;
                d.setDirty();
                Net.sync(p, e);
                say(s, Component.literal("💥 " + e.name + " murió definitivamente. [geoda1]").withStyle(ChatFormatting.DARK_RED));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("geoda1: has muerto definitivamente").withStyle(ChatFormatting.DARK_RED)));
            } else {
                p.connection.send(new ClientboundSetActionBarTextPacket(
                        Component.literal("Te pueden revivir durante: " + fmt(left)).withStyle(ChatFormatting.GOLD)));
            }
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        LifeData.Entry e = LifeData.get(p.getServer()).entry(p);
        if (e.state != LifeData.ALIVE) p.setGameMode(GameType.SPECTATOR);
        applyHearts(p, e.bonus);
        Net.sync(p, e);
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        LifeData.Entry e = LifeData.get(p.getServer()).entry(p);
        applyHearts(p, e.bonus);
        Net.sync(p, e);
    }

    @SubscribeEvent
    public void onDrops(LivingDropsEvent ev) {
        if (ev.getEntity() instanceof WitherBoss w) {
            ev.getDrops().add(new ItemEntity(w.level(), w.getX(), w.getY(), w.getZ(), new ItemStack(Geoda1.GOLDEN_HEART.get(), 3)));
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent ev) {
        ev.getDispatcher().register(Commands.literal("geoda1")
                .then(Commands.literal("jugadores").executes(ctx -> {
                    LifeData d = LifeData.get(ctx.getSource().getServer());
                    long now = System.currentTimeMillis();
                    for (Map.Entry<UUID, LifeData.Entry> en : d.all().entrySet()) {
                        LifeData.Entry e = en.getValue();
                        String txt = switch (e.state) {
                            case LifeData.ALIVE -> "§a" + e.name + " — vivo (" + e.lives + " vidas)";
                            case LifeData.WAITING -> "§6" + e.name + " — esperando revivir: " + fmt(e.deadline - now);
                            default -> "§4" + e.name + " — muerto definitivamente";
                        };
                        ctx.getSource().sendSuccess(() -> Component.literal(txt), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("revivir").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("jugador", EntityArgument.player()).executes(ctx -> {
                            ServerPlayer t = EntityArgument.getPlayer(ctx, "jugador");
                            boolean ok = Revival.revive(null, t, null);
                            ctx.getSource().sendSuccess(() -> Component.literal(ok ? "Revivido." : "Ese jugador no está esperando revivir."), true);
                            return ok ? 1 : 0;
                        }))));
    }

    // ---------- corazones extra (max 25 corazones = 10 base + 15 extra) ----------
    static final java.util.UUID HEART_UUID = java.util.UUID.fromString("5b0a3f0e-1c2d-4e5f-8a9b-0c1d2e3f4a5b");

    static void applyHearts(ServerPlayer p, int bonus) {
        AttributeInstance a = p.getAttribute(Attributes.MAX_HEALTH);
        if (a == null) return;
        a.removeModifier(HEART_UUID);
        if (bonus > 0) a.addPermanentModifier(new AttributeModifier(HEART_UUID, "geoda1_hearts", bonus * 2.0, AttributeModifier.Operation.ADDITION));
    }

    public static void addHeart(ServerPlayer p) {
        LifeData d = LifeData.get(p.getServer());
        LifeData.Entry e = d.entry(p);
        if (e.bonus >= 15) {
            p.displayClientMessage(Component.literal("Ya tenés el máximo: 25 corazones."), true);
            return;
        }
        e.bonus++;
        d.setDirty();
        applyHearts(p, e.bonus);
        p.heal(2f);
    }

    @SubscribeEvent
    public void onPlace(BlockEvent.EntityPlaceEvent ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p) || p.hasPermissions(2)) return;
        var b = ev.getPlacedBlock().getBlock();
        if (b == Geoda1.GEODA_BLOCK.get() || b == Geoda1.GEODA_PUNT.get()) {
            ev.setCanceled(true);
            p.displayClientMessage(Component.literal("Solo el administrador puede colocar estos bloques."), true);
        }
    }

    // ---------- /esmeralda <color> ----------
    static void moment(MinecraftServer s, String item, String name) {
        String label = name.equals("verde") ? "Momento esmeralda" : "Momento esmeralda " + name;
        Net.CH.send(PacketDistributor.ALL.noArg(), new Net.PlayEmerald(item));
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("✦ " + label + " ✦").withStyle(ChatFormatting.GREEN)));
            p.level().playSound(null, p.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);
        }
        say(s, Component.literal(label + "!").withStyle(ChatFormatting.GREEN));
    }

    @SubscribeEvent
    public void onCommandsEsmeralda(RegisterCommandsEvent ev) {
        String[][] c = {
                {"verde", "minecraft:emerald"}, {"azul", "geoda1:emerald_blue"}, {"roja", "geoda1:emerald_red"},
                {"rosa", "geoda1:emerald_pink"}, {"violeta", "geoda1:emerald_violet"}, {"rainbow", "geoda1:emerald_rainbow"}};
        var root = Commands.literal("esmeralda").requires(s -> s.hasPermission(2));
        for (String[] x : c) {
            root = root.then(Commands.literal(x[0]).executes(ctx -> {
                moment(ctx.getSource().getServer(), x[1], x[0]);
                return 1;
            }));
        }
        ev.getDispatcher().register(root);
    }
}
