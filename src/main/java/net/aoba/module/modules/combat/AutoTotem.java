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
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.List;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // Settings
    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("1. Ưu tiên Totem (Offhand)").defaultValue(true).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Tốc độ bơm (ms)").defaultValue(80).build();
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("2. Crystal (Bạn Tự Aim)").defaultValue(true).build();
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("3. Anchor (Máy Tự Aim)").defaultValue(true).build();

    // Hotbar Mapping
    private final int S_OBI = 1;     // Phím 2
    private final int S_CRY = 2;     // Phím 3
    private final int S_ANCHOR = 6;  // Phím 7
    private final int S_GLOW = 7;    // Phím 8

    // States
    private boolean isRefilling = false;
    private boolean totemPopped = false;
    private long lastTotemAction = 0;
    private enum TotemStep { NONE, OPEN, SWAP, CLOSE }
    private TotemStep totemStep = TotemStep.NONE;

    private int crystalStage = 0;
    private int anchorStage = 0;
    private int waitTimer = 0;
    private final int ACTION_DELAY = 7; // Tốc độ PvP ổn định
    private BlockPos targetPos = null;
    
    private boolean lastRight = false;
    private boolean lastLeft = false;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V42: Visual Hotbar Fix & Offhand Totem");
        addSetting(totemEnable); addSetting(totemDelay);
        addSetting(crystalEnable); addSetting(anchorEnable);
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        resetStates();
    }

    private void resetStates() {
        isRefilling = false; totemStep = TotemStep.NONE; totemPopped = false;
        crystalStage = 0; anchorStage = 0; waitTimer = 0; targetPos = null;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (event.GetPacket() instanceof EntityStatusS2CPacket p && p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
            totemPopped = true;
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // --- 1. ƯU TIÊN TOTEM (OFFHAND) ---
        if (totemEnable.getValue() && (totemPopped || mc.player.getOffHandStack().isEmpty())) {
            totemPopped = false;
            if (!isRefilling) {
                isRefilling = true; totemStep = TotemStep.OPEN;
                lastTotemAction = System.currentTimeMillis();
            }
        }
        if (isRefilling) { handleOffhandTotem(); return; } // Khóa combat

        // --- 2. HÀNH ĐỘNG COMBAT ---
        if (waitTimer > 0) { waitTimer--; return; }

        long win = mc.getWindow().getHandle();
        boolean right = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        // Crystal Sequence (Click Phải 1 lần - Không Aim)
        if (crystalEnable.getValue() && right && !lastRight && crystalStage == 0) crystalStage = 1;
        if (crystalStage > 0) doCrystalSequence();
        lastRight = right;

        // Anchor Sequence (Click Trái 1 lần - Có Aim)
        if (anchorEnable.getValue() && left && !lastLeft && anchorStage == 0) anchorStage = 1;
        if (anchorStage > 0) doAnchorSequence();
        lastLeft = left;
    }

    private void handleOffhandTotem() {
        long now = System.currentTimeMillis();
        switch (totemStep) {
            case OPEN:
                if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                if (now - lastTotemAction >= totemDelay.getValue()) { totemStep = TotemStep.SWAP; lastTotemAction = now; }
                break;
            case SWAP:
                int tIdx = -1;
                for (int i = 9; i <= 44; i++) {
                    if (mc.player.currentScreenHandler.getSlot(i).getStack().isOf(Items.TOTEM_OF_UNDYING)) { tIdx = i; break; }
                }
                if (tIdx != -1) {
                    // Swap vào slot 45 (Offhand)
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, tIdx, 40, SlotActionType.SWAP, mc.player);
                    totemStep = TotemStep.CLOSE;
                } else { isRefilling = false; mc.setScreen(null); }
                lastTotemAction = now;
                break;
            case CLOSE:
                if (now - lastTotemAction >= totemDelay.getValue()) {
                    mc.setScreen(null);
                    isRefilling = false; totemStep = TotemStep.NONE;
                }
                break;
        }
    }

    private void doCrystalSequence() {
        switch (crystalStage) {
            case 1:
                visualSwitch(S_OBI);
                if (mc.crosshairTarget instanceof BlockHitResult hit) {
                    BlockPos p = hit.getBlockPos();
                    if (mc.world.getBlockState(p).isOf(Blocks.OBSIDIAN)) { targetPos = p; crystalStage = 2; }
                    else {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        targetPos = p.offset(hit.getSide());
                        waitTimer = ACTION_DELAY; crystalStage = 2;
                    }
                } else crystalStage = 0; break;
            case 2:
                visualSwitch(S_CRY);
                if (targetPos != null) {
                    BlockHitResult bhr = new BlockHitResult(new Vec3d(targetPos.getX()+0.5, targetPos.getY()+1, targetPos.getZ()+0.5), Direction.UP, targetPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    waitTimer = ACTION_DELAY; crystalStage = 3;
                } else crystalStage = 0; break;
            case 3:
                List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(targetPos.up()), e -> true);
                if (!crystals.isEmpty()) {
                    mc.interactionManager.attackEntity(mc.player, crystals.get(0));
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                crystalStage = 0; targetPos = null; break;
        }
    }

    private void doAnchorSequence() {
        switch (anchorStage) {
            case 1:
                visualSwitch(S_ANCHOR);
                if (mc.crosshairTarget instanceof BlockHitResult hit) {
                    BlockPos p = hit.getBlockPos();
                    lookAt(p); // MÁY TỰ AIM ANCHOR
                    if (mc.world.getBlockState(p).isOf(Blocks.RESPAWN_ANCHOR)) { targetPos = p; anchorStage = 2; }
                    else {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        targetPos = p.offset(hit.getSide());
                        waitTimer = ACTION_DELAY; anchorStage = 2;
                    }
                } else anchorStage = 0; break;
            case 2:
                visualSwitch(S_GLOW);
                if (targetPos != null) {
                    lookAt(targetPos);
                    BlockHitResult bhr = new BlockHitResult(new Vec3d(targetPos.getX()+0.5, targetPos.getY()+0.5, targetPos.getZ()+0.5), Direction.UP, targetPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr); // Nạp
                    waitTimer = 2; // Nghỉ cực ngắn giữa nạp và nổ
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr); // Nổ
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                anchorStage = 0; targetPos = null; break;
        }
    }

    // --- HÀM CHUYỂN SLOT THỰC SỰ NHẢY TRÊN MÀN HÌNH ---
    private void visualSwitch(int slot) {
        if (mc.player == null) return;
        
        // 1. Gửi lệnh cho Server
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        
        // 2. Ép Client cập nhật ô đang chọn (Làm thanh highlight nhảy đi)
        mc.player.getInventory().selectedSlot = slot;
        
        // 3. Đồng bộ hóa tay (Hand swing sync)
        mc.player.getInventory().markDirty();
    }

    private void lookAt(BlockPos pos) {
        Vec3d t = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vec3d eye = mc.player.getEyePos();
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
    }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
