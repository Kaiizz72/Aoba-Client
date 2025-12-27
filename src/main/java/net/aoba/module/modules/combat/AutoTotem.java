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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
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
import java.util.concurrent.ThreadLocalRandom;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Settings từ code của bạn
    private final IntegerSetting delayMs = IntegerSetting.builder().id("autototem_delay").displayName("Tốc độ (ms)").defaultValue(100).build();
    private final BooleanSetting autoEsc = BooleanSetting.builder().id("autototem_autoesc").displayName("Auto ESC").defaultValue(true).build();
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("Auto Anchor (Chuột Trái)").defaultValue(true).build();

    // Slot Mapping cho Anchor
    private final int S_ANCHOR = 6;  // Phím 7
    private final int S_GLOW = 7;    // Phím 8

    // States
    private boolean isWorking = false; // Đang bơm Totem
    private long lastTime = 0;
    private int targetSwapSlot = -1;
    private int targetRefillSlot = -1;
    
    private int anchorStage = 0;
    private BlockPos targetPos = null;
    private boolean lastLeft = false;

    private enum Step {
        NONE, STEP_1_SELECT_SLOT, STEP_2_OPEN_INV, STEP_3_FIND_AND_AIM_SWAP, 
        STEP_4_ACTION_SWAP, STEP_5_FIND_AND_AIM_REFILL, STEP_6_ACTION_REFILL, STEP_7_CLOSE
    }
    private Step currentStep = Step.NONE;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V48: Merge Legit Totem & Anchor (Fixed Visual Slot)");
        addSetting(delayMs); addSetting(autoEsc); addSetting(anchorEnable);
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        currentStep = Step.NONE; isWorking = false;
        targetSwapSlot = -1; targetRefillSlot = -1;
        anchorStage = 0; targetPos = null;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket p && p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
            if (!isWorking) {
                isWorking = true;
                currentStep = Step.STEP_1_SELECT_SLOT;
                lastTime = System.currentTimeMillis();
            }
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // --- PHẦN 1: LOGIC AUTO TOTEM (Lấy từ code của bạn) ---
        if (isWorking) {
            handleAutoTotemStep();
            return; // Ưu tiên tuyệt đối, không cho nổ Anchor khi đang bơm đồ
        }

        // --- PHẦN 2: LOGIC AUTO ANCHOR (Chuột trái) ---
        long win = mc.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (anchorEnable.getValue() && left && !lastLeft && anchorStage == 0) anchorStage = 1;
        if (anchorStage > 0) doAnchorSequence();
        lastLeft = left;
    }

    private void handleAutoTotemStep() {
        long now = System.currentTimeMillis();
        long delay = delayMs.getValue();

        switch (currentStep) {
            case STEP_1_SELECT_SLOT:
                if (now - lastTime >= 50) {
                    forceSetSlot(8); // Chuyển sang ô số 9 (Index 8)
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                    currentStep = Step.STEP_2_OPEN_INV;
                    lastTime = now;
                }
                break;
            case STEP_2_OPEN_INV:
                if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                if (now - lastTime >= delay) { currentStep = Step.STEP_3_FIND_AND_AIM_SWAP; lastTime = now; }
                break;
            case STEP_3_FIND_AND_AIM_SWAP:
                if (mc.currentScreen instanceof InventoryScreen) {
                    targetSwapSlot = findAnyTotemSlot();
                    if (targetSwapSlot != -1) {
                        aimAtSlot(targetSwapSlot);
                        currentStep = Step.STEP_4_ACTION_SWAP;
                    } else reset();
                    lastTime = now;
                }
                break;
            case STEP_4_ACTION_SWAP:
                if (now - lastTime >= delay) {
                    if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player);
                    currentStep = Step.STEP_5_FIND_AND_AIM_REFILL;
                    lastTime = now;
                }
                break;
            case STEP_5_FIND_AND_AIM_REFILL:
                if (now - lastTime >= delay) {
                    if (isSlotTotem(44)) { // Ô số 9 đã có totem
                        if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE; else reset();
                    } else {
                        targetRefillSlot = findTotemInStorage();
                        if (targetRefillSlot != -1) { aimAtSlot(targetRefillSlot); currentStep = Step.STEP_6_ACTION_REFILL; }
                        else { if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE; else reset(); }
                    }
                    lastTime = now;
                }
                break;
            case STEP_6_ACTION_REFILL:
                if (now - lastTime >= delay) {
                    if (targetRefillSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetRefillSlot, 8, SlotActionType.SWAP, mc.player);
                    if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE; else reset();
                    lastTime = now;
                }
                break;
            case STEP_7_CLOSE:
                if (now - lastTime >= delay) { mc.setScreen(null); reset(); }
                break;
        }
    }

    private void doAnchorSequence() {
        switch (anchorStage) {
            case 1:
                forceSetSlot(S_ANCHOR); // Dùng hàm forceSetSlot xịn của bạn
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(S_ANCHOR));
                if (mc.crosshairTarget instanceof BlockHitResult hit) {
                    BlockPos p = hit.getBlockPos();
                    lookAt(p);
                    if (mc.world.getBlockState(p).isOf(Blocks.RESPAWN_ANCHOR)) { targetPos = p; anchorStage = 2; }
                    else {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        targetPos = p.offset(hit.getSide());
                        anchorStage = 2;
                    }
                } else anchorStage = 0; break;
            case 2:
                forceSetSlot(S_GLOW); // Nhảy sang slot Glowstone
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(S_GLOW));
                if (targetPos != null) {
                    lookAt(targetPos);
                    BlockHitResult bhr = new BlockHitResult(new Vec3d(targetPos.getX()+0.5, targetPos.getY()+0.5, targetPos.getZ()+0.5), Direction.UP, targetPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr); // Nạp
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr); // Nổ
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                anchorStage = 0; targetPos = null; break;
        }
    }

    // --- HÀM CHUYỂN SLOT THẦN THÁNH CỦA BẠN ---
    private void forceSetSlot(int slotIndex) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true); f.setInt(inv, slotIndex);
        } catch (Exception e1) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true); f.setInt(inv, slotIndex);
            } catch (Exception e2) {
                try {
                     Field f = PlayerInventory.class.getDeclaredField("field_7545");
                     f.setAccessible(true); f.setInt(inv, slotIndex);
                } catch (Exception ignored) {}
            }
        }
        inv.markDirty(); // Ép đồng bộ
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;
        try {
            InventoryScreen screen = (InventoryScreen) mc.currentScreen;
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            int guiLeft = (screen.width - 176) / 2;
            int guiTop = (screen.height - 166) / 2;
            int targetX = guiLeft + slot.x + 8;
            int targetY = guiTop + slot.y + 8;
            double scale = mc.getWindow().getScaleFactor();
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), targetX * scale, targetY * scale);
        } catch (Exception ignored) {}
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

    private void lookAt(BlockPos pos) {
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
