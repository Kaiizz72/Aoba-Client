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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.Random;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    private final IntegerSetting baseDelay = IntegerSetting.builder().id("totem_delay_v51").displayName("Delay gốc (ms)").defaultValue(130).build();
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_v51").displayName("Auto Anchor (Stealth)").defaultValue(true).build();

    private final int S_ANCHOR = 6; 
    private final int S_GLOW = 7;   

    private boolean isWorking = false; 
    private long nextActionTime = 0;
    private int anchorStage = 0;
    private int waitTicks = 0;
    private BlockPos targetPos = null;
    private boolean lastLeft = false;

    private enum Step { NONE, SELECT, OPEN, SWAP, REFILL, CLOSE }
    private Step currentStep = Step.NONE;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V51: Stealth Bypass (Jitter & Sync)");
        addSetting(baseDelay); addSetting(anchorEnable);
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        currentStep = Step.NONE; isWorking = false;
        anchorStage = 0; targetPos = null; waitTicks = 0;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket p && p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
            if (!isWorking) {
                isWorking = true;
                currentStep = Step.SELECT;
                // Thêm jitter ngay từ đầu
                nextActionTime = System.currentTimeMillis() + random.nextInt(30);
            }
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // --- TOTEM LOGIC ---
        if (isWorking) {
            if (System.currentTimeMillis() >= nextActionTime) {
                handleTotemStealth();
            }
            return;
        }

        // --- ANCHOR LOGIC (TICK SYNC) ---
        if (waitTicks > 0) { waitTicks--; return; }

        long win = mc.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (anchorEnable.getValue() && left && !lastLeft && anchorStage == 0) anchorStage = 1;
        if (anchorStage > 0) handleAnchorStealth();
        lastLeft = left;
    }

    private void handleTotemStealth() {
        // Tạo delay biến thiên để đánh lừa pattern recognition
        long jitter = baseDelay.getValue() + random.nextInt(45);

        switch (currentStep) {
            case SELECT -> {
                forceSetSlot(8);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                currentStep = Step.OPEN;
                nextActionTime = System.currentTimeMillis() + jitter;
            }
            case OPEN -> {
                if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                currentStep = Step.SWAP;
                nextActionTime = System.currentTimeMillis() + jitter;
            }
            case SWAP -> {
                int slot = findAnyTotemSlot();
                if (slot != -1) {
                    aimAtSlot(slot);
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 40, SlotActionType.SWAP, mc.player);
                    currentStep = Step.REFILL;
                } else reset();
                nextActionTime = System.currentTimeMillis() + jitter;
            }
            case REFILL -> {
                int slot = findTotemInStorage();
                if (slot != -1 && !isSlotTotem(44)) {
                    aimAtSlot(slot);
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 8, SlotActionType.SWAP, mc.player);
                }
                currentStep = Step.CLOSE;
                nextActionTime = System.currentTimeMillis() + jitter;
            }
            case CLOSE -> {
                mc.setScreen(null);
                reset();
            }
        }
    }

    private void handleAnchorStealth() {
        switch (anchorStage) {
            case 1 -> { // Step 1: Chọn Anchor & Quay đầu
                if (mc.crosshairTarget instanceof BlockHitResult hit) {
                    BlockPos p = hit.getBlockPos();
                    lookAt(p);
                    forceSetSlot(S_ANCHOR);
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(S_ANCHOR));
                    targetPos = mc.world.getBlockState(p).isOf(Blocks.RESPAWN_ANCHOR) ? p : p.offset(hit.getSide());
                    anchorStage = 2;
                    waitTicks = 2; // Đợi 2 tick cho chắc chắn server nhận diện cầm Anchor
                } else anchorStage = 0;
            }
            case 2 -> { // Step 2: Đặt & Nạp
                if (targetPos != null) {
                    lookAt(targetPos);
                    BlockHitResult bhr = new BlockHitResult(new Vec3d(targetPos.getX()+0.5, targetPos.getY()+0.5, targetPos.getZ()+0.5), Direction.UP, targetPos, false);
                    
                    // Nếu chưa có block thì đặt
                    if (!mc.world.getBlockState(targetPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        waitTicks = 2; // Nghỉ để server xử lý block mới xuất hiện
                    }
                    
                    // Chuyển sang Glowstone ngay sau đó (vẫn trong stage này nhưng nghỉ tick)
                    anchorStage = 3;
                } else anchorStage = 0;
            }
            case 3 -> { // Step 3: Đổi Glowstone
                forceSetSlot(S_GLOW);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(S_GLOW));
                anchorStage = 4;
                waitTicks = 2; // Rất quan trọng: Phải đợi để Server xác nhận đang cầm Glowstone
            }
            case 4 -> { // Step 4: Nạp & Nổ
                if (targetPos != null) {
                    lookAt(targetPos);
                    BlockHitResult bhr = new BlockHitResult(new Vec3d(targetPos.getX()+0.5, targetPos.getY()+0.5, targetPos.getZ()+0.5), Direction.UP, targetPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr); // Nạp
                    
                    // Nổ ngay sau đó (Server FDI thường check tốc độ nạp nổ)
                    // Ta nổ ở stage sau để an toàn
                    anchorStage = 5;
                    waitTicks = 1; 
                } else anchorStage = 0;
            }
            case 5 -> { // Step 5: Kết thúc
                if (targetPos != null) {
                    BlockHitResult bhr = new BlockHitResult(new Vec3d(targetPos.getX()+0.5, targetPos.getY()+0.5, targetPos.getZ()+0.5), Direction.UP, targetPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr); // Kích nổ
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                anchorStage = 0;
                targetPos = null;
            }
        }
    }

    // --- UTILS (Giữ nguyên từ bản của bạn nhưng tối ưu hóa) ---
    private void forceSetSlot(int slotIndex) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true); f.set(inv, slotIndex);
        } catch (Exception e) {
            // Mapping dự phòng cho môi trường Obfuscated
            try { Field f = PlayerInventory.class.getDeclaredField("field_7545"); f.setAccessible(true); f.set(inv, slotIndex); } catch (Exception ignored) {}
        }
    }

    private void lookAt(BlockPos pos) {
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, dist)));
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen screen)) return;
        Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
        int x = (screen.width - 176) / 2 + slot.x + 8;
        int y = (screen.height - 166) / 2 + slot.y + 8;
        GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x * mc.getWindow().getScaleFactor(), y * mc.getWindow().getScaleFactor());
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
        try {
            ItemStack stack = mc.player.currentScreenHandler.slots.get(id).getStack();
            return stack.getItem() == Items.TOTEM_OF_UNDYING;
        } catch (Exception e) { return false; }
    }

    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
