package com.geoda1;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Geoda1.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent ev) {
        ev.registerAboveAll("vidas", (gui, g, pt, w, h) -> {
            if (Minecraft.getInstance().options.hideGui) return;
            int x = w / 2 - 91 - 72;
            int y = h - 18;
            g.drawString(gui.getFont(), "Vidas:", x, y, 0xFFFFFF);
            for (int i = 0; i < LifeData.MAX_LIVES; i++) {
                g.drawString(gui.getFont(), "♥", x + 38 + i * 11, y, i < ClientState.lives ? 0xFF3333 : 0x555555);
            }
        });
    }
}
