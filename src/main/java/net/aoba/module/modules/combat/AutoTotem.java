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
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
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
    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("1. Bật Auto Totem").defaultValue(true).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Totem Delay (ms)").defaultValue(50).build();
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("2. Auto Anchor (Left)").defaultValue(true).build();
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("3. Auto Crystal (Right)").defaultValue(true).build();
    private final IntegerSetting crystalSpeed = IntegerSetting.builder().id("crystal_speed").displayName("Delay Crystal").defaultValue(2).build();

    // Slots (0-index: 0 là slot 1, 1 là slot 2...)
    private final int S_OBI = 1;     // Slot 2
    private final int S_CRY = 2;     // Slot 3
    private final int S_ANCHOR = 6;  // Slot 7
    private final int S_GLOW = 7;    // Slot 8
    private final int S_TOTEM = 8;   // Slot 9

    private boolean isRefilling = false;
    private boolean forceTotemSlot = false;
    private int crystalStage = 0;
    private int crystalWait = 0;
    private BlockPos targetObiPos = null;
    private static Field selectedSlotField = null;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V32: Ultimate Slot Sync & Crystal Aim");
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

        // 1. ƯU TIÊN TOTEM
        if (forceTotemSlot) {
            syncSlot(S_TOTEM);
            forceTotemSlot = false;
            return;
        }

        long win = mc.getWindow().getHandle();
        boolean right = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean left = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        // 2. AUTO CRYSTAL (RIGHT CLICK)
        if (crystalEnable.getValue() && right) {
            doCrystalLogic();
        } else {
            if (crystalStage != 0) { crystalStage = 0; targetObiPos = null; }
        }

        // 3. AUTO ANCHOR (LEFT CLICK)
        if (anchorEnable.getValue() && left) {
            doAnchorLogic();
        }
    }

    private void doCrystalLogic() {
        // [QUAN TRỌNG] BƯỚC 1: ÉP SLOT 2 (OBSIDIAN)
        if (crystalStage == 0) {
            syncSlot(S_OBI);
        }

        if (crystalWait > 0) { crystalWait--; return; }

        switch (crystalStage) {
            case 0: // Đặt Obsidian
                if (mc.crosshairTarget instanceof BlockHitResult hit) {
                    BlockPos pos = hit.getBlockPos();
                    if (mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                        targetObiPos = pos;
                        crystalStage = 1;
                    } else {
                        if (mc.player.getStackInHand(Hand.MAIN_HAND).isOf(Items.OBSIDIAN)) {
                            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                            mc.player.swingHand(Hand.MAIN_HAND);
                            targetObiPos = pos.offset(hit.getSide());
                            crystalWait = crystalSpeed.getValue();
                            crystalStage = 1;
                        }
                    }
                }
                break;

            case 1: // Nhảy Slot 3 & Đặt Crystal
                if (targetObiPos != null) {
                    syncSlot(S_CRY); // Ép slot 3
                    lookAtBlock(targetObiPos); // Nhìn Obsidian

                    if (mc.player.getStackInHand(Hand.MAIN_HAND).isOf(Items.END_CRYSTAL)) {
                        BlockHitResult cryHit = new BlockHitResult(new Vec3d(targetObiPos.getX()+0.5, targetObiPos.getY()+1, targetObiPos.getZ()+0.5), Direction.UP, targetObiPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, cryHit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        crystalWait = crystalSpeed.getValue();
                        crystalStage = 2;
                    }
                } else crystalStage = 0;
                break;

            case 2: // Đập nổ (Vẫn nhìn Obsidian)
                if (targetObiPos != null) {
                    lookAtBlock(targetObiPos); // KHÔNG NHÌN CRYSTAL, NHÌN OBI
                    List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(targetObiPos.up()), e -> true);
                    if (!crystals.isEmpty()) {
                        mc.interactionManager.attackEntity(mc.player, crystals.get(0));
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                crystalWait = crystalSpeed.getValue();
                crystalStage = 0;
                break;
        }
    }

    private void doAnchorLogic() {
        // Tương tự, ép slot 7 rồi 8
        if (mc.crosshairTarget instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            if (mc.player.getStackInHand(Hand.MAIN_HAND).isOf(Items.RESPAWN_ANCHOR)) {
                syncSlot(S_ANCHOR);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                syncSlot(S_GLOW);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }
    }

    // ================= [HÀM ÉP SLOT CỰC MẠNH] =================
    private void syncSlot(int slot) {
        if (mc.player == null) return;
        try {
            if (selectedSlotField == null) {
                // Dò tìm field private bằng mọi giá
                String[] fieldNames = {"selectedSlot", "currentItem", "field_7533"};
                for (String name : fieldNames) {
                    try {
                        selectedSlotField = PlayerInventory.class.getDeclaredField(name);
                        selectedSlotField.setAccessible(true);
                        break;
                    } catch (Exception ignored) {}
                }
            }

            if (selectedSlotField != null) {
                // 1. Ghi đè bộ nhớ Client
                selectedSlotField.setInt(mc.player.getInventory(), slot);
                // 2. Gửi gói tin ép Server cập nhật
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
        } catch (Exception e) {
            // Fallback cuối cùng nếu reflection lỗi (Dùng packet đơn thuần)
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private void lookAtBlock(BlockPos pos) {
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        Vec3d eyes = mc.player.getEyePos();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        mc.player.setYaw(yaw);
        mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    // Các hàm phụ trợ Totem giữ nguyên...
    private int findTotemSlot(boolean hotbar) { 
        for (int i = hotbar ? 36 : 9; i <= (hotbar ? 44 : 35); i++) {
            if (mc.player.currentScreenHandler.getSlot(i).getStack().isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
