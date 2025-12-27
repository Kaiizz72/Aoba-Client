package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.IntegerSetting;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.Random;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_v54").displayName("Delay Totem (ms)").defaultValue(150).build();
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_v54").displayName("Auto Anchor (Chuột Phải)").defaultValue(true).build();

    private final int S_ANCHOR = 6; // Slot 7
    private final int S_GLOW = 7;   // Slot 8

    private boolean isWorking = false; 
    private long nextTotemAction = 0;
    private enum TotemStep { SELECT, OPEN, SWAP, REFILL, CLOSE }
    private TotemStep tStep = TotemStep.SELECT;

    private int anchorStage = 0;
    private int waitTicks = 0;
    private BlockPos targetPos = null;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V54: Right-Click Trigger + Anti-Air Placement");
        addSetting(totemDelay); addSetting(anchorEnable);
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        isWorking = false; anchorStage = 0; waitTicks = 0; targetPos = null;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket p && p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
            if (!isWorking) { isWorking = true; tStep = TotemStep.SELECT; nextTotemAction = System.currentTimeMillis() + 20; }
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // 1. ƯU TIÊN TOTEM (Bơm đồ quan trọng nhất)
        if (isWorking) {
            if (System.currentTimeMillis() >= nextTotemAction) handleTotem();
            return;
        }

        // 2. XỬ LÝ ANCHOR (CHỈ KHI GIỮ CHUỘT PHẢI)
        if (waitTicks > 0) { waitTicks--; return; }

        long win = mc.getWindow().getHandle();
        // ĐỔI SANG CHUỘT PHẢI (RIGHT_BUTTON)
        boolean isRightPressed = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (anchorEnable.getValue()) {
            // Chỉ bắt đầu hoặc tiếp tục nếu đang giữ chuột phải
            if (isRightPressed || anchorStage > 0) {
                runAnchorStrictSequence(isRightPressed);
            }
        }
    }

    private void runAnchorStrictSequence(boolean isPressed) {
        // KIỂM TRA MỤC TIÊU: Nếu không nhìn vào block, dừng ngay để tránh kick
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS) {
            if (anchorStage == 0) return; // Không bắt đầu nếu nhìn vào không khí
        } else {
            // Cập nhật targetPos liên tục theo tâm ngắm
            BlockPos currentLook = hit.getBlockPos();
            if (anchorStage == 0 || anchorStage == 1) {
                targetPos = mc.world.getBlockState(currentLook).isOf(Blocks.RESPAWN_ANCHOR) 
                            ? currentLook : currentLook.offset(hit.getSide());
            }
        }

        switch (anchorStage) {
            case 0 -> { if (isPressed && targetPos != null) anchorStage = 1; }

            case 1 -> { // Bước 1: Đổi sang Anchor + Quay mặt
                lookAt(targetPos);
                forceSetSlot(S_ANCHOR);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(S_ANCHOR));
                anchorStage = 2;
                waitTicks = 2; // Đợi Server đồng bộ Slot
            }

            case 2 -> { // Bước 2: Đặt Block (Chỉ đặt nếu là không khí)
                if (targetPos != null && mc.world.getBlockState(targetPos).isAir()) {
                    lookAt(targetPos);
                    sendInteract(targetPos);
                    waitTicks = 1;
                }
                anchorStage = 3;
            }

            case 3 -> { // Bước 3: Đổi sang Glowstone
                forceSetSlot(S_GLOW);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(S_GLOW));
                anchorStage = 4;
                waitTicks = 2; // Đợi Server đồng bộ Slot
            }

            case 4 -> { // Bước 4: Nạp Glowstone (Chỉ nạp nếu đúng là Anchor)
                if (targetPos != null && mc.world.getBlockState(targetPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                    lookAt(targetPos);
                    sendInteract(targetPos);
                    anchorStage = 5;
                    waitTicks = 1;
                } else anchorStage = 0; // Nếu block bị mất, reset chu kỳ
            }

            case 5 -> { // Bước 5: Nổ
                if (targetPos != null && mc.world.getBlockState(targetPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                    sendInteract(targetPos);
                }
                
                if (isPressed) {
                    anchorStage = 1; // Lặp lại nếu vẫn giữ chuột phải
                    waitTicks = 1;
                } else {
                    anchorStage = 0;
                    targetPos = null;
                }
            }
        }
    }

    private void sendInteract(BlockPos pos) {
        if (pos == null) return;
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        BlockHitResult bhr = new BlockHitResult(center, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // --- CÁC HÀM HỖ TRỢ GIỮ NGUYÊN TỪ V53 ---
    private void handleTotem() {
        long delay = totemDelay.getValue() + random.nextInt(50);
        switch (tStep) {
            case SELECT -> {
                forceSetSlot(8); mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                tStep = TotemStep.OPEN; nextTotemAction = System.currentTimeMillis() + 50;
            }
            case OPEN -> {
                if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                tStep = TotemStep.SWAP; nextTotemAction = System.currentTimeMillis() + delay;
            }
            case SWAP -> {
                int slot = findAnyTotemSlot();
                if (slot != -1) { aimAtSlot(slot); mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 40, SlotActionType.SWAP, mc.player); tStep = TotemStep.REFILL; } 
                else reset();
                nextTotemAction = System.currentTimeMillis() + delay;
            }
            case REFILL -> {
                int slot = findTotemInStorage();
                if (slot != -1 && !isSlotTotem(44)) { aimAtSlot(slot); mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 8, SlotActionType.SWAP, mc.player); }
                tStep = TotemStep.CLOSE; nextTotemAction = System.currentTimeMillis() + delay;
            }
            case CLOSE -> { mc.setScreen(null); reset(); }
        }
    }

    private void forceSetSlot(int slotIndex) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true); f.setInt(inv, slotIndex);
            inv.markDirty();
        } catch (Exception e) {
            try { Field f = PlayerInventory.class.getDeclaredField("field_7545"); f.setAccessible(true); f.setInt(inv, slotIndex); } catch (Exception ignored) {}
        }
    }

    private void lookAt(BlockPos pos) {
        if (pos == null) return;
        Vec3d eye = mc.player.getEyePos();
        double dx = pos.getX() + 0.5 - eye.x, dy = pos.getY() + 0.5 - eye.y, dz = pos.getZ() + 0.5 - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, dist)));
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen screen)) return;
        Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
        double scale = mc.getWindow().getScaleFactor();
        int x = (screen.width - 176) / 2 + slot.x + 8;
        int y = (screen.height - 166) / 2 + slot.y + 8;
        GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x * scale, y * scale);
    }

    private int findAnyTotemSlot() {
        for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i;
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }

    private int findTotemInStorage() {
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }

    private boolean isSlotTotem(int id) {
        try { return mc.player.currentScreenHandler.slots.get(id).getStack().isOf(Items.TOTEM_OF_UNDYING); } catch (Exception e) { return false; }
    }

    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
