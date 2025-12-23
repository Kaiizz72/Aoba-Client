package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.FloatSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final FloatSetting delaySetting = FloatSetting.builder()
            .id("autototem_delay")
            .displayName("Delay (ms)")
            .defaultValue(150f)
            .minValue(50f)
            .maxValue(300f)
            .step(10f)
            .build();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Automatically ESC after refill")
            .defaultValue(true)
            .build();

    private long lastAction = 0;
    private int stage = 0; // stage machine
    private boolean inventoryOpen = false;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem pop → swap offhand → open E → refill slot 8 → close E");
        addSetting(delaySetting);
        addSetting(autoEsc);
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this); // đăng ký tick
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

        long delay = Math.round(delaySetting.getValue());
        if (System.currentTimeMillis() - lastAction < delay) return;

        // Stage 1: swap slot 8 -> offhand
        swapHotbarWithOffhand(8);
        mc.player.swingHand(Hand.MAIN_HAND); // nhấn F human-like
        stage = 2;
        lastAction = System.currentTimeMillis();
    }

    /* ===================== TICK UPDATE ===================== */

    @Override
    public void onTick(TickEvent.Pre event) {
        // Không cần làm gì ở Pre, chỉ để override
    }

    @Override
    public void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long delay = Math.round(delaySetting.getValue());
        if (System.currentTimeMillis() - lastAction < delay) return;

        PlayerInventory inv = mc.player.getInventory();

        switch (stage) {
            case 2: // Open inventory human-like
                if (!inventoryOpen) {
                    mc.options.keyInventory.setPressed(true); // nhấn E thật
                    inventoryOpen = true;
                    lastAction = System.currentTimeMillis();
                }
                stage = 3;
                break;

            case 3: // Refill slot 8 nếu trống
                if (inv.getStack(8).getItem() != Items.TOTEM_OF_UNDYING) {
                    int totemSlot = findTotemInInventory();
                    if (totemSlot != -1) moveItem(totemSlot, 8);
                }
                stage = 4;
                lastAction = System.currentTimeMillis();
                break;

            case 4: // Thả phím E và ESC nếu bật
                if (inventoryOpen) {
                    mc.options.keyInventory.setPressed(false); // thả phím E
                    if (autoEsc.getValue()) mc.setScreen(null); // ESC inventory
                    inventoryOpen = false;
                }
                stage = 0; // reset stage
                lastAction = System.currentTimeMillis();
                break;
        }
    }

    /* ===================== UTILS ===================== */

    private int findTotemInInventory() {
        PlayerInventory inv = MinecraftClient.getInstance().player.getInventory();
        for (int i = 9; i < 36; i++) { // kiểm tra kho
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
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, 45, slot, SlotActionType.SWAP, mc.player);
    }
}
