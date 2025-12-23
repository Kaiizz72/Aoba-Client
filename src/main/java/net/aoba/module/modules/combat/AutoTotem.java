package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.FloatSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

public class AutoTotem extends Module implements ReceivePacketListener {

    private final FloatSetting delaySetting = FloatSetting.builder()
            .id("autototem_delay")
            .displayName("Delay (ms)")
            .defaultValue(120f)
            .minValue(0f)
            .maxValue(300f)
            .step(20f)
            .build();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC Inventory")
            .description("Automatically ESC out of inventory after refill")
            .defaultValue(true)
            .build();

    private long lastAction = 0;
    private boolean waitingRefill = false;
    private int stage = 0;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Human-like AutoTotem with slot 8 always full and auto ESC");
        addSetting(delaySetting);
        addSetting(autoEsc);
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        waitingRefill = false;
        stage = 0;
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
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
        if (mc.player == null || packet.getEntity(mc.world) != mc.player) return;

        swapHotbarWithOffhand(8); // Swap slot 8 -> offhand
        waitingRefill = true;
        stage = 1;
        lastAction = System.currentTimeMillis();
    }

    /* ===================== MAIN LOOP ===================== */

    @Override
    public void onUpdate() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        PlayerInventory inv = mc.player.getInventory();
        long delay = (long) delaySetting.getValue();

        // Stage machine để refill human-like
        if (stage > 0 && System.currentTimeMillis() - lastAction < delay) return;

        // Nếu slot 8 trống → refill
        if (inv.getStack(8).getItem() != Items.TOTEM_OF_UNDYING) {
            switch (stage) {
                case 0 -> {
                    stage = 1; // bắt đầu refill
                }
                case 1 -> { // Mở inventory
                    if (!(mc.currentScreen instanceof InventoryScreen)) {
                        mc.setScreen(new InventoryScreen(mc.player));
                    }
                    stage = 2;
                }
                case 2 -> { // Kéo totem từ kho xuống slot 8
                    int invSlot = findTotemInInventory();
                    if (invSlot != -1) moveItem(invSlot, 8);
                    stage = 3;
                }
                case 3 -> { // Delay 1 tick giả lập hành động người
                    stage = 4;
                }
                case 4 -> { // ESC tự động nếu bật
                    if (autoEsc.getValue() && mc.currentScreen instanceof InventoryScreen) {
                        mc.setScreen(null);
                    }
                    stage = 0; // Hoàn tất refill
                }
            }
            lastAction = System.currentTimeMillis();
        } else {
            stage = 0; // Slot 8 đầy → stage reset
        }
    }

    /* ===================== UTILS ===================== */

    private int findTotemInInventory() {
        PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return i;
            }
        }
        return -1;
    }

    private void moveItem(int from, int to) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int syncId = mc.player.currentScreenHandler.syncId;

        mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, to, 0, SlotActionType.PICKUP, mc.player);
    }

    private void swapHotbarWithOffhand(int slot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                45, // offhand
                slot,
                SlotActionType.SWAP,
                mc.player
        );
    }
}
