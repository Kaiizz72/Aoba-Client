package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Automatically ESC after refill")
            .defaultValue(true)
            .build();

    private boolean inventoryOpen = false;
    private boolean totemPopDetected = false;

    private long lastSwap = 0;
    private long lastRefill = 0;

    private final long swapDelay = 300;   // ms
    private final long refillDelay = 400; // ms
    private final long escDelay = 200;    // ms

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem offhand vỡ → swap slot 8 → refill slot 8 → auto ESC bật/tắt, lặp lại liên tục");
        addSetting(autoEsc);
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        totemPopDetected = false;
        inventoryOpen = false;
        lastSwap = lastRefill = 0;
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        totemPopDetected = false;
        inventoryOpen = false;
    }

    @Override
    public void onToggle() {}

    /* ===================== TOTEM POP ===================== */

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!(event.GetPacket() instanceof EntityStatusS2CPacket packet)) return;
        if (packet.getStatus() != 35) return; // Totem pop

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || packet.getEntity(mc.world) != mc.player) return;

        totemPopDetected = true;
        lastSwap = System.currentTimeMillis();
        lastRefill = 0;
    }

    /* ===================== TICK UPDATE ===================== */

    @Override
    public void onTick(TickEvent.Pre event) {}

    @Override
    public void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (!totemPopDetected) return;

        long now = System.currentTimeMillis();

        // Stage 1: Swap slot 8 → offhand
        if (now - lastSwap >= swapDelay) {
            swapSlot8ToOffhand();
            lastRefill = now;
            lastSwap = now;
        }

        // Stage 2: Refill slot 8 từ inventory
        if (lastRefill > 0 && now - lastRefill >= refillDelay) {
            mc.setScreen(new InventoryScreen(mc.player));
            inventoryOpen = true;
            refillSlot8();
            lastRefill = now;
        }

        // Stage 3: Auto ESC
        if (inventoryOpen && autoEsc.getValue() && now - lastRefill >= escDelay) {
            mc.setScreen(null);
            inventoryOpen = false;
            totemPopDetected = false; // reset để lặp lại
        }
    }

    /* ===================== UTILS ===================== */

    private void swapSlot8ToOffhand() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();
        if (inv.getStack(8).isEmpty()) return;
        int syncId = mc.player.currentScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, 45, 8, SlotActionType.SWAP, mc.player);
    }

    private void refillSlot8() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();
        if (!inv.getStack(8).isEmpty()) return;
        int backupSlot = findTotemInInventory();
        if (backupSlot == -1) return;
        int syncId = mc.player.currentScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, backupSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, 8, 0, SlotActionType.PICKUP, mc.player);
    }

    private int findTotemInInventory() {
        PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }
}
