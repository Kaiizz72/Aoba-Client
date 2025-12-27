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
import net.minecraft.text.Text; // ĐÃ THÊM IMPORT
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

    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("1. Bật Auto Totem").defaultValue(true).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Totem Delay (ms)").defaultValue(50).build();
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("2. Auto Anchor").defaultValue(true).build();
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("3. Auto Crystal").defaultValue(true).build();
    private final IntegerSetting crystalSpeed = IntegerSetting.builder().id("crystal_speed").displayName("Tốc độ đập").defaultValue(2).build();

    private final int S_OBI = 1;     
    private final int S_CRY = 2;     
    private final int S_ANCHOR = 6;  
    private final int S_GLOW = 7;    
    private final int S_TOTEM = 8;   

    private boolean forceTotemSlot = false;
    private int crystalStage = 0;
    private int crystalWait = 0;
    private BlockPos targetObiPos = null;
    
    private static Field slotField = null;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V35: Fixed Compile Errors & Slot Bypass");
        addSetting(totemEnable); addSetting(totemDelay);
        addSetting(anchorEnable); addSetting(crystalEnable); addSetting(crystalSpeed);
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
    }

    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (event.GetPacket() instanceof EntityStatusS2CPacket p && p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
            forceTotemSlot = true;
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (forceTotemSlot) {
            hardSwitch(S_TOTEM);
            forceTotemSlot = false;
            return;
        }

        long win = mc.getWindow().getHandle();
        boolean rightHeld = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean leftHeld = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (crystalEnable.getValue() && rightHeld) doCrystal();
        else if (crystalStage != 0) { crystalStage = 0; targetObiPos = null; }

        if (anchorEnable.getValue() && leftHeld) doAnchor();
    }

    private void doCrystal() {
        if (crystalStage == 0) hardSwitch(S_OBI);
        if (crystalWait > 0) { crystalWait--; return; }

        switch (crystalStage) {
            case 0:
                if (mc.crosshairTarget instanceof BlockHitResult hit) {
                    BlockPos p = hit.getBlockPos();
                    if (mc.world.getBlockState(p).isOf(Blocks.OBSIDIAN)) {
                        targetObiPos = p; crystalStage = 1;
                    } else if (mc.player.getInventory().getStack(S_OBI).isOf(Items.OBSIDIAN)) {
                        hardSwitch(S_OBI);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        targetObiPos = p.offset(hit.getSide());
                        crystalWait = 1; crystalStage = 1;
                    }
                } break;
            case 1:
                if (targetObiPos != null) {
                    hardSwitch(S_CRY);
                    lookAt(targetObiPos);
                    if (mc.player.getInventory().getStack(S_CRY).isOf(Items.END_CRYSTAL)) {
                        BlockHitResult bhr = new BlockHitResult(new Vec3d(targetObiPos.getX()+0.5, targetObiPos.getY()+1, targetObiPos.getZ()+0.5), Direction.UP, targetObiPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        crystalWait = crystalSpeed.getValue(); crystalStage = 2;
                    }
                } else crystalStage = 0; break;
            case 2:
                if (targetObiPos != null) {
                    lookAt(targetObiPos);
                    List<EndCrystalEntity> ents = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(targetObiPos.up()), e -> true);
                    if (!ents.isEmpty()) {
                        mc.interactionManager.attackEntity(mc.player, ents.get(0));
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                crystalWait = crystalSpeed.getValue(); crystalStage = 0; break;
        }
    }

    private void doAnchor() {
        if (mc.crosshairTarget instanceof BlockHitResult hit) {
            if (mc.world.getBlockState(hit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) {
                hardSwitch(S_GLOW);
            } else {
                hardSwitch(S_ANCHOR);
            }
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void hardSwitch(int slot) {
        if (mc.player == null) return;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        try {
            if (slotField == null) {
                String[] fieldNames = {"selectedSlot", "currentItem", "field_7533"};
                for (String name : fieldNames) {
                    try {
                        slotField = PlayerInventory.class.getDeclaredField(name);
                        slotField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            if (slotField != null) slotField.setInt(mc.player.getInventory(), slot);
        } catch (Exception ignored) {}
    }

    private void lookAt(BlockPos pos) {
        Vec3d t = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        Vec3d e = mc.player.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        
        // SỬA LỖI: THÊM THAM SỐ THỨ 4 (horizontalCollision)
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
            yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision
        ));
    }

    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
