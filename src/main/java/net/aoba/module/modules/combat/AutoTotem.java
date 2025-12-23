/*
 * Aoba Hacked Client
 * AutoTotem Improved
 */

package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.PlayerHealthEvent;
import net.aoba.event.listeners.PlayerHealthListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

public class AutoTotem extends Module implements PlayerHealthListener {

    private boolean hadTotemLastTick = false;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Auto swap & refill totem (slot 8 → offhand)");
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(PlayerHealthListener.class, this);
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(PlayerHealthListener.class, this);
    }

    @Override
    public void onHealthChanged(PlayerHealthEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        ItemStack offhand = mc.player.getOffHandStack();
        boolean hasTotemNow = offhand.getItem() == Items.TOTEM_OF_UNDYING;

        // Totem vừa vỡ
        if (hadTotemLastTick && !hasTotemNow) {
            handleTotemBreak();
        }

        hadTotemLastTick = hasTotemNow;
    }

    private void handleTotemBreak() {
        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerInventory inv = mc.player.getInventory();

        int hotbarSlot = 8;

        // 1️⃣ Nếu slot 8 chưa có totem → refill từ inventory
        if (inv.getStack(hotbarSlot).getItem() != Items.TOTEM_OF_UNDYING) {
            int invSlot = findTotemInInventory();
            if (invSlot != -1) {
                moveItem(invSlot, hotbarSlot);
            }
        }

        // 2️⃣ Swap slot 8 ↔ offhand (như nhấn F)
        swapHotbarWithOffhand(hotbarSlot);
    }

    // Tìm totem trong inventory (không tính hotbar)
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

    // Kéo totem về slot 8 (giống mở E rồi kéo)
    private void moveItem(int from, int to) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int syncId = mc.player.currentScreenHandler.syncId;

        mc.interactionManager.clickSlot(syncId, from, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, to, 0, SlotActionType.PICKUP, mc.player);
    }

    // Swap slot 8 ↔ offhand (chuẩn Minecraft)
    private void swapHotbarWithOffhand(int hotbarSlot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int syncId = mc.player.currentScreenHandler.syncId;

        mc.interactionManager.clickSlot(
                syncId,
                45, // offhand slot
                hotbarSlot,
                SlotActionType.SWAP,
                mc.player
        );
    }
}
