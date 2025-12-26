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

    // ================= [FIXED SLOTS CONFIG] =================
    
    // 1. Crystal Combo
    private final int SLOT_OBSIDIAN = 1;   // Game Slot 2
    private final int SLOT_CRYSTAL = 2;    // Game Slot 3
    
    // 2. Pearl
    private final int SLOT_PEARL = 3;      // Game Slot 4
    
    // 3. Anchor Combo
    private final int SLOT_ANCHOR = 6;     // Game Slot 7
    private final int SLOT_GLOWSTONE = 7;  // Game Slot 8
    
    // 4. Totem
    private final int SLOT_TOTEM = 8;      // Game Slot 9 (Index 8)

    // ================= VARIABLES =================

    private boolean isRefilling = false;
    private boolean forceTotemSlot = false; 
    private long lastTotemTime = 0;
    private int targetSwapSlot = -1, targetRefillSlot = -1;
    private enum TotemStep { NONE, OPEN_INV, FIND_SWAP, DO_SWAP, FIND_REFILL, DO_REFILL, CLOSE }
    private TotemStep currentTotemStep = TotemStep.NONE;

    private boolean lastKeyC = false;

    // Anchor Variables
    private int anchorStage = 0;
    private int waitTimer = 0;
    private BlockPos currentAnchorPos = null;
    
    // Crystal Variables
    private boolean isCrystalActive = false;
    private int crystalStage = 0;
    private int crystalWaitTimer = 0;
    private BlockPos targetObiPos = null;
    
    private int pearlTimer = 0;
    private Field selectedSlotField = null;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("PvP V28: Crystal Aim Fix");

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
        
        // --- PRIORITY: TOTEM ---
        if (forceTotemSlot) {
            setHotbarSlot(SLOT_TOTEM);
            forceTotemSlot = false; isRefilling = true; currentTotemStep = TotemStep.OPEN_INV; lastTotemTime = System.currentTimeMillis();
            return; 
        }
        if (isRefilling && totemEnable.getValue()) { handleTotemRefill(); return; }

        if (pearlTimer > 0) pearlTimer--;
        long window = mc.getWindow().getHandle();
        
        // --- ANCHOR LOOP (LEFT CLICK) ---
        if (anchorEnable.getValue()) {
            boolean isLeftHeld = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (isLeftHeld) processAnchorLoop();
            else if (anchorStage != 0) { anchorStage = 0; waitTimer = 0; currentAnchorPos = null; }
        }

        // --- CRYSTAL LOOP (RIGHT CLICK) ---
        if (crystalEnable.getValue()) {
            boolean isRightHeld = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (isRightHeld) {
                processCrystalLoop();
            } else {
                if (isCrystalActive) { isCrystalActive = false; crystalStage = 0; crystalWaitTimer = 0; targetObiPos = null; }
            }
        }

        // --- PEARL (KEY C) ---
        if (pearlEnable.getValue()) {
            boolean isC = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
            if (isC && !lastKeyC && pearlTimer == 0) handleFastPearl();
            lastKeyC = isC;
        }
    }

    // ================= [CRYSTAL FIX V28 - AIM VÀO OBSIDIAN] =================
    private void processCrystalLoop() {
        isCrystalActive = true;
        
        if (crystalStage == 0) setHotbarSlot(SLOT_OBSIDIAN); // Ép về Slot 2 ngay

        if (crystalWaitTimer > 0) { crystalWaitTimer--; return; }

        switch (crystalStage) {
            case 0: // GIAI ĐOẠN 1: ĐẶT OBSIDIAN
                if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
                BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                BlockPos targetBlock = hit.getBlockPos();
                
                boolean isObiOrBedrock = mc.world.getBlockState(targetBlock).getBlock() == Blocks.OBSIDIAN || mc.world.getBlockState(targetBlock).getBlock() == Blocks.BEDROCK;

                if (isObiOrBedrock) {
                    targetObiPos = targetBlock; 
                    crystalStage = 1; 
                    crystalWaitTimer = 0;
                } else {
                    if (mc.player.getMainHandStack().getItem() != Items.OBSIDIAN) return;
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); 
                    mc.player.swingHand(Hand.MAIN_HAND);
                    targetObiPos = targetBlock.offset(hit.getSide()); 
                    crystalWaitTimer = crystalSpeed.getValue(); 
                    crystalStage = 1; 
                }
                break;

            case 1: // GIAI ĐOẠN 2: CHUYỂN SLOT 3 & ĐẶT CRYSTAL
                if (targetObiPos != null) {
                    setHotbarSlot(SLOT_CRYSTAL); 
                    
                    // Aim vào mặt trên của Obsidian (nơi đặt crystal)
                    forceLookAtBlockTop(targetObiPos);
                    
                    if (mc.player.getMainHandStack().getItem() != Items.END_CRYSTAL) { 
                        crystalWaitTimer = 1; 
                        return; 
                    }
                    
                    BlockHitResult placeHit = new BlockHitResult(new Vec3d(targetObiPos.getX()+0.5, targetObiPos.getY()+1.0, targetObiPos.getZ()+0.5), Direction.UP, targetObiPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit); 
                    mc.player.swingHand(Hand.MAIN_HAND);
                    
                    crystalWaitTimer = crystalSpeed.getValue(); 
                    crystalStage = 2;
                } else { crystalStage = 0; }
                break;

            case 2: // GIAI ĐOẠN 3: ĐẬP NỔ (FIX AIM VÀO OBSIDIAN)
                if (targetObiPos != null) {
                    // [FIX] Tiếp tục Aim vào Obsidian (Chân Crystal) thay vì Entity
                    forceLookAtBlockTop(targetObiPos);

                    // Tìm Crystal ở vị trí đó
                    List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(targetObiPos.up()), e -> true);
                    
                    if (!crystals.isEmpty()) {
                        // Tấn công con Crystal nhưng mắt vẫn nhìn vào block ở dưới
                        mc.interactionManager.attackEntity(mc.player, crystals.get(0));
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
                crystalWaitTimer = crystalSpeed.getValue(); 
                crystalStage = 0;
                break;
        }
    }

    // ================= [ANCHOR LOOP] =================
    private void processAnchorLoop() {
        if (waitTimer > 0) { waitTimer--; return; }
        switch (anchorStage) {
            case 0: 
                setHotbarSlot(SLOT_ANCHOR);
                if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
                BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                if (mc.player.getMainHandStack().getItem() != Items.RESPAWN_ANCHOR) return;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); mc.player.swingHand(Hand.MAIN_HAND);
                currentAnchorPos = hit.getBlockPos().offset(hit.getSide());
                waitTimer = 0; anchorStage = 1; break;
            case 1: 
                if (currentAnchorPos != null) { forceLookAtBlockTop(currentAnchorPos); setHotbarSlot(SLOT_GLOWSTONE); waitTimer = 0; anchorStage = 2; } else anchorStage = 0; break;
            case 2: 
                if (currentAnchorPos != null) {
                    forceLookAtBlockTop(currentAnchorPos);
                    if (mc.player.getMainHandStack().getItem() != Items.GLOWSTONE) { setHotbarSlot(SLOT_GLOWSTONE); waitTimer = 1; return; }
                    BlockHitResult aimHit = new BlockHitResult(new Vec3d(currentAnchorPos.getX()+0.5, currentAnchorPos.getY()+1.0, currentAnchorPos.getZ()+0.5), Direction.UP, currentAnchorPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); mc.player.swingHand(Hand.MAIN_HAND);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); mc.player.swingHand(Hand.MAIN_HAND);
                }
                waitTimer = anchorDelay.getValue(); anchorStage = 0; break;
        }
    }

    // ================= [HANDLERS] =================
    private void handleTotemRefill() {
        if (currentTotemStep == TotemStep.OPEN_INV) setHotbarSlot(SLOT_TOTEM);
        long now = System.currentTimeMillis(); long delay = Math.max(0, totemDelay.getValue()); int totemIdx = SLOT_TOTEM;
        switch (currentTotemStep) {
            case OPEN_INV: if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player)); if (now - lastTotemTime >= delay) { currentTotemStep = TotemStep.FIND_SWAP; lastTotemTime = now; } break;
            case FIND_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { targetSwapSlot = findTotemSlot(true); if (targetSwapSlot != -1) { aimSlot(targetSwapSlot); currentTotemStep = TotemStep.DO_SWAP; } else resetAll(); lastTotemTime = now; } } break;
            case DO_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player); currentTotemStep = TotemStep.FIND_REFILL; lastTotemTime = now; } } break;
            case FIND_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (isSlotTotem(36 + totemIdx)) { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } else { targetRefillSlot = findTotemSlot(false); if (targetRefillSlot != -1) { aimSlot(targetRefillSlot); currentTotemStep = TotemStep.DO_REFILL; } else { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } } lastTotemTime = now; } } break;
            case DO_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetRefillSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetRefillSlot, totemIdx, SlotActionType.SWAP, mc.player); if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); lastTotemTime = now; } } break;
            case CLOSE: if (now - lastTotemTime >= delay) { mc.setScreen(null); resetAll(); } break;
        }
    }

    private void handleFastPearl() {
        if (mc.player.getInventory().getStack(SLOT_PEARL).getItem() != Items.ENDER_PEARL) { sendInfo("Slot 4 không có Pearl!"); pearlTimer = 20; return; }
        setHotbarSlot(SLOT_PEARL);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); mc.player.swingHand(Hand.MAIN_HAND);
        pearlTimer = 10;
    }

    // ================= [UTILS] =================
    // Hàm này aim vào mặt trên của block (Y + 1.0)
    private void forceLookAtBlockTop(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        // Target là tâm của mặt trên block (chỗ đặt chân Crystal)
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
        
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }
    
    // Giữ hàm này cho các logic khác nếu cần, nhưng Crystal giờ dùng hàm trên
    private void forceLookAtEntity(Entity entity) {
        Vec3d eyes = mc.player.getEyePos(); Vec3d target = entity.getPos().add(0, entity.getHeight() / 2, 0);
        double dx = target.x - eyes.x; double dy = target.y - eyes.y; double dz = target.z - eyes.z; double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0); float pitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }
    private void sendInfo(String msg) { if (mc.player != null) mc.player.sendMessage(Text.of("§b[AutoTotem] §f" + msg), false); }
    private int getHotbarSlot() { try { if (selectedSlotField == null) { try { selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot"); } catch (NoSuchFieldException e) { selectedSlotField = PlayerInventory.class.getDeclaredField("currentItem"); } selectedSlotField.setAccessible(true); } return selectedSlotField.getInt(mc.player.getInventory()); } catch (Exception e) { return 0; } }
    private void setHotbarSlot(int slot) { 
        if (getHotbarSlot() == slot) return; 
        try { if (selectedSlotField == null) { try { selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot"); } catch (NoSuchFieldException e) { selectedSlotField = PlayerInventory.class.getDeclaredField("currentItem"); } selectedSlotField.setAccessible(true); } selectedSlotField.setInt(mc.player.getInventory(), slot); mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot)); } catch (Exception e) { e.printStackTrace(); } 
    }
    private int findTotemSlot(boolean includeHotbar) { if (includeHotbar) for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i; for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i; return -1; }
    private boolean isSlotTotem(int id) { try { return mc.player.currentScreenHandler.slots.get(id).getStack().getItem() == Items.TOTEM_OF_UNDYING; } catch (Exception e) { return false; } }
    private void aimSlot(int slotId) { try { InventoryScreen s = (InventoryScreen) mc.currentScreen; Slot slot = mc.player.currentScreenHandler.slots.get(slotId); int guiLeft = (s.width - 176)/2, guiTop = (s.height - 166)/2; int x = guiLeft + slot.x + 8, y = guiTop + slot.y + 8; int jX = ThreadLocalRandom.current().nextInt(-3, 4), jY = ThreadLocalRandom.current().nextInt(-3, 4); double sc = mc.getWindow().getScaleFactor(); GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), (x+jX)*sc, (y+jY)*sc); } catch (Exception ignored) {} }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
