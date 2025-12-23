package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

public class AutoTotem extends Module implements ReceivePacketListener {

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Instant totem swap & refill (packet based)");
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
    }

    @Override
    public void onToggle() {
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!(event.GetPacket() instanceof EntityStatusS2CPacket packet)) return;

        // 35 = Totem pop
        if (packet.getStatus() != 35) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Chỉ xử lý khi chính mình pop totem
        if (packet.getEntity(mc.world) != mc.player) return;

        handleTotemPop();
    }

    private void handleTotemPop() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();

        int hotbarSlot = 8;

        // 1️⃣ Refill slot 8 nếu trống
        if (inv.getStack(hotbarSlot).getItem() != Items.TOTEM_OF_UNDYING) {
            int invSlot = findTotemInInventory();
            if (invSlot != -1) {
                moveItem(invSlot, hotbarSlot);
            }
        }

        // 2️⃣ Swap slot 8 ↔ offhand (như nhấn F)
        swapHotbarWithOffhand(hotbarSlot);
    }

    private int findTotemInInventory() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();

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

    private void swapHotbarWithOffhand(int hotbarSlot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int syncId = mc.player.currentScreenHandler.syncId;

        mc.interactionManager.clickSlot(
                syncId,
                45, // offhand
                hotbarSlot,
                SlotActionType.SWAP,
                mc.player
        );
    }
}
