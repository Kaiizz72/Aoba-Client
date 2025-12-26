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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // ================= [SETTINGS] =================
    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("1. Bật Auto Totem").defaultValue(true).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Totem Delay (ms)").defaultValue(50).build();
    private final BooleanSetting autoEsc = BooleanSetting.builder().id("totem_esc").displayName("Tự đóng túi đồ").defaultValue(true).build();

    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("2. Auto Anchor (Left Click)").defaultValue(true).build();
    private final IntegerSetting anchorDelay = IntegerSetting.builder().id("anchor_delay").displayName("Delay Anchor (Tick)").defaultValue(1).build();

    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("3. Auto Crystal (Right Click)").defaultValue(true).build();
    private final IntegerSetting crystalSpeed = IntegerSetting.builder().id("crystal_speed").displayName("Delay Crystal (Tick)").defaultValue(3).build();

    private final BooleanSetting pearlEnable = BooleanSetting.builder().id("pearl_enable").displayName("4. Bật Auto Pearl (Key C)").defaultValue(true).build();

    // ================= [CONFIG HOTBAR] =================
    private final int SLOT_OBSIDIAN = 1;   // Slot 2
    private final int SLOT_CRYSTAL = 2;    // Slot 3
    private final int SLOT_PEARL = 3;      // Slot 4
    private final int SLOT_ANCHOR = 6;     // Slot 7
    private final int SLOT_GLOWSTONE = 7;  // Slot 8
    private final int SLOT_TOTEM = 8;      // Slot 9

    // ================= VARIABLES =================
    private boolean isRefilling = false;
    private boolean forceTotemSlot = false; 
    private long lastTotemTime = 0;
    private int targetSwapSlot = -1, targetRefillSlot = -1;
    private enum TotemStep { NONE, OPEN_INV, FIND_SWAP, DO_SWAP, FIND_REFILL, DO_REFILL, CLOSE }
    private TotemStep currentTotemStep = TotemStep.NONE;

    private boolean lastKeyC = false;
    private int anchorStage = 0;
    private int waitTimer = 0;
    private BlockPos currentAnchorPos = null;
    
    private boolean isCrystalActive = false;
    private int crystalStage = 0;
    private int crystalWaitTimer = 0;
    private BlockPos targetObiPos = null;
    
    private int pearlTimer = 0;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("PvP V29: Hard-Switch & Input Fix");
        addSetting(totemEnable); addSetting(totemDelay); addSetting(autoEsc);
        addSetting(anchorEnable); addSetting(anchorDelay);
        addSetting(crystalEnable); addSetting(crystalSpeed);
        addSetting(pearlEnable); 
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        resetAll();
    }

    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        resetAll();
    }

    private void resetAll() {
        currentTotemStep = TotemStep.NONE; isRefilling = false; forceTotemSlot = false;
        pearlTimer = 0; anchorStage = 0; currentAnchorPos = null; waitTimer = 0;
        isCrystalActive = false; crystalStage = 0; targetObiPos = null; crystalWaitTimer = 0;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (!totemEnable.getValue() || mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35 && packet.getEntity(mc.world) == mc.player) {
                forceTotemSlot = true; 
            }
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.player.isDead()) { resetAll(); return; }
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return;

        // --- MOUSE DETECTION ---
        long window = mc.getWindow().getHandle();
        boolean isLeftHeld = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean isRightHeld = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        // 1. ƯU TIÊN TOTEM
        if (forceTotemSlot) {
            setHotbarSlot(SLOT_TOTEM);
            forceTotemSlot = false; isRefilling = true; currentTotemStep = TotemStep.OPEN_INV; lastTotemTime = System.currentTimeMillis();
            return; 
        }
        if (isRefilling) { handleTotemRefill(); return; }

        // 2. AUTO ANCHOR (LEFT CLICK)
        if (anchorEnable.getValue() && isLeftHeld) {
            processAnchorLoop();
        } else if (!isLeftHeld && anchorStage != 0) {
            anchorStage = 0; waitTimer = 0; currentAnchorPos = null;
        }

        // 3. AUTO CRYSTAL (RIGHT CLICK)
        if (crystalEnable.getValue() && isRightHeld) {
            processCrystalLoop();
        } else if (!isRightHeld && isCrystalActive) {
            isCrystalActive = false; crystalStage = 0; crystalWaitTimer = 0; targetObiPos = null;
        }

        // 4. PEARL (KEY C)
        if (pearlEnable.getValue()) {
            boolean isC = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
            if (isC && !lastKeyC && pearlTimer == 0) handleFastPearl();
            lastKeyC = isC;
        }
        if (pearlTimer > 0) pearlTimer--;
    }

    // ================= [LOGIC ANCHOR V29] =================
    private void processAnchorLoop() {
        // [FIX] Ép slot 7 liên tục nếu đang ở giai đoạn 0
        if (anchorStage == 0) setHotbarSlot(SLOT_ANCHOR);

        if (waitTimer > 0) { waitTimer--; return; }

        switch (anchorStage) {
            case 0:
                if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
                BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                if (mc.player.getMainHandStack().getItem() != Items.RESPAWN_ANCHOR) return;

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); 
                mc.player.swingHand(Hand.MAIN_HAND);
                currentAnchorPos = hit.getBlockPos().offset(hit.getSide());
                anchorStage = 1;
                break;

            case 1:
                if (currentAnchorPos != null) {
                    forceLookAtBlockTop(currentAnchorPos);
                    setHotbarSlot(SLOT_GLOWSTONE); // Chuyển sang Slot 8
                    anchorStage = 2;
                } else anchorStage = 0;
                break;

            case 2:
                if (currentAnchorPos != null) {
                    forceLookAtBlockTop(currentAnchorPos);
                    if (mc.player.getMainHandStack().getItem() != Items.GLOWSTONE) {
                        setHotbarSlot(SLOT_GLOWSTONE);
                        waitTimer = 1; return;
                    }
                    BlockHitResult aimHit = new BlockHitResult(new Vec3d(currentAnchorPos.getX()+0.5, currentAnchorPos.getY()+1.0, currentAnchorPos.getZ()+0.5), Direction.UP, currentAnchorPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); // Nạp
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); // Nổ
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                waitTimer = anchorDelay.getValue();
                anchorStage = 0;
                break;
        }
    }

    // ================= [LOGIC CRYSTAL V29] =================
    private void processCrystalLoop() {
        isCrystalActive = true;
        if (crystalStage == 0) setHotbarSlot(SLOT_OBSIDIAN); // Ép slot 2

        if (crystalWaitTimer > 0) { crystalWaitTimer--; return; }

        switch (crystalStage) {
            case 0:
                if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
                BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                BlockPos targetBlock = hit.getBlockPos();
                boolean isObi = mc.world.getBlockState(targetBlock).getBlock() == Blocks.OBSIDIAN || mc.world.getBlockState(targetBlock).getBlock() == Blocks.BEDROCK;

                if (isObi) {
                    targetObiPos = targetBlock; crystalStage = 1;
                } else {
                    if (mc.player.getMainHandStack().getItem() != Items.OBSIDIAN) return;
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    targetObiPos = targetBlock.offset(hit.getSide());
                    crystalWaitTimer = crystalSpeed.getValue(); crystalStage = 1;
                }
                break;

            case 1:
                if (targetObiPos != null) {
                    setHotbarSlot(SLOT_CRYSTAL); // Chuyển sang slot 3
                    forceLookAtBlockTop(targetObiPos);
                    if (mc.player.getMainHandStack().getItem() != Items.END_CRYSTAL) { crystalWaitTimer = 1; return; }
                    BlockHitResult placeHit = new BlockHitResult(new Vec3d(targetObiPos.getX()+0.5, targetObiPos.getY()+1.0, targetObiPos.getZ()+0.5), Direction.UP, targetObiPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    crystalWaitTimer = crystalSpeed.getValue(); crystalStage = 2;
                } else crystalStage = 0;
                break;

            case 2:
                if (targetObiPos != null) {
                    forceLookAtBlockTop(targetObiPos); // Ghim aim vào Obi
                    List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(targetObiPos.up()), e -> true);
                    if (!crystals.isEmpty()) {
                        mc.interactionManager.attackEntity(mc.player, crystals.get(0));
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                crystalWaitTimer = crystalSpeed.getValue();
                crystalStage = 0;
                break;
        }
    }

    // ================= [HARD-SWITCH METHOD] =================
    private void setHotbarSlot(int slot) {
        if (mc.player == null) return;
        // [FIX] Ép trực tiếp vào biến inventory của Minecraft
        if (mc.player.getInventory().selectedSlot != slot) {
            mc.player.getInventory().selectedSlot = slot;
            // Gửi packet để server cập nhật theo
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    // ================= [UTILS] =================
    private void handleTotemRefill() {
        if (currentTotemStep == TotemStep.OPEN_INV) setHotbarSlot(SLOT_TOTEM);
        long now = System.currentTimeMillis(); long delay = Math.max(0, totemDelay.getValue());
        switch (currentTotemStep) {
            case OPEN_INV: if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player)); if (now - lastTotemTime >= delay) { currentTotemStep = TotemStep.FIND_SWAP; lastTotemTime = now; } break;
            case FIND_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { targetSwapSlot = findTotemSlot(true); if (targetSwapSlot != -1) { aimSlot(targetSwapSlot); currentTotemStep = TotemStep.DO_SWAP; } else resetAll(); lastTotemTime = now; } } break;
            case DO_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player); currentTotemStep = TotemStep.FIND_REFILL; lastTotemTime = now; } } break;
            case FIND_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (isSlotTotem(36 + SLOT_TOTEM)) { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } else { targetRefillSlot = findTotemSlot(false); if (targetRefillSlot != -1) { aimSlot(targetRefillSlot); currentTotemStep = TotemStep.DO_REFILL; } else { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } } lastTotemTime = now; } } break;
            case DO_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetRefillSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetRefillSlot, SLOT_TOTEM, SlotActionType.SWAP, mc.player); if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); lastTotemTime = now; } } break;
            case CLOSE: if (now - lastTotemTime >= delay) { mc.setScreen(null); resetAll(); } break;
        }
    }

    private void handleFastPearl() {
        if (mc.player.getInventory().getStack(SLOT_PEARL).getItem() != Items.ENDER_PEARL) return;
        setHotbarSlot(SLOT_PEARL);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);
        pearlTimer = 10;
    }

    private void forceLookAtBlockTop(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        double dx = target.x - eyes.x; double dy = target.y - eyes.y; double dz = target.z - eyes.z; double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0); float pitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    private int findTotemSlot(boolean hotbar) { if (hotbar) for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i; for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i; return -1; }
    private boolean isSlotTotem(int id) { try { return mc.player.currentScreenHandler.slots.get(id).getStack().getItem() == Items.TOTEM_OF_UNDYING; } catch (Exception e) { return false; } }
    private void aimSlot(int slotId) { try { InventoryScreen s = (InventoryScreen) mc.currentScreen; Slot slot = mc.player.currentScreenHandler.slots.get(slotId); int x = (s.width - 176)/2 + slot.x + 8, y = (s.height - 166)/2 + slot.y + 8; double sc = mc.getWindow().getScaleFactor(); GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x*sc, y*sc); } catch (Exception ignored) {} }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
