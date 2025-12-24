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

    private long lastAction = 0;
    private int stage = 0;
    private boolean inventoryOpen = false;

    private final long delay = 200; // human-like delay

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem offhand vỡ → swap slot 8 → refill slot 8 → auto ESC bật/tắt");
        addSetting(autoEsc);
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        stage = 0;
        inventoryOpen = false;
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        stage = 0;
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

        if (System.currentTimeMillis() - lastAction < delay) return;

        // Stage 1: swap slot 8 → offhand
        swapSlot8ToOffhand();
        // Stage 2: mở inventory refill slot 8
        mc.setScreen(new InventoryScreen(mc.player));
        inventoryOpen = true;
        stage = 2;
        lastAction = System.currentTimeMillis();
    }

    /* ===================== TICK UPDATE ===================== */

    @Override
    public void onTick(TickEvent.Pre event) {}

    @Override
    public void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        if (stage == 2 && System.currentTimeMillis() - lastAction >= delay && inventoryOpen) {
            refillSlot8();
            // Auto ESC nếu bật
            if (autoEsc.getValue()) mc.setScreen(null);
            inventoryOpen = false;
            stage = 0;
            lastAction = System.currentTimeMillis();
        }
    }

    /* ===================== UTILS ===================== */

    private void swapSlot8ToOffhand() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();
        if (inv.getStack(8).isEmpty()) return; // không có totem trong slot 8
        int syncId = mc.player.currentScreenHandler.syncId;
        // Swap slot 8 → offhand (slot 45)
        mc.interactionManager.clickSlot(syncId, 45, 8, SlotActionType.SWAP, mc.player);
    }

    private void refillSlot8() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();
        if (!inv.getStack(8).isEmpty()) return; // slot 8 đã có totem
        int totemSlot = findTotemInInventory();
        if (totemSlot == -1) return; // không có totem backup
        int syncId = mc.player.currentScreenHandler.syncId;
        // Move totem từ kho → slot 8
        mc.interactionManager.clickSlot(syncId, totemSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, 8, 0, SlotActionType.PICKUP, mc.player);
    }

    private int findTotemInInventory() {
        PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return i;
            }
        }
        return -1;
    }
}
