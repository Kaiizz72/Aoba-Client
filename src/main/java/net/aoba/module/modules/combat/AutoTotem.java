package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.aoba.settings.types.FloatSetting;
import net.aoba.settings.types.IntegerSetting;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
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
import java.util.concurrent.ThreadLocalRandom;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // ================= SETTINGS (ĐÃ BỎ GÁN PHÍM) =================

    // 1. TOTEM
    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("Bật Auto Totem").defaultValue(true).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Totem Delay (ms)").defaultValue(100).build();
    private final BooleanSetting autoEsc = BooleanSetting.builder().id("totem_esc").displayName("Auto Close Inv").defaultValue(true).build();

    // 2. CRYSTAL (Kích hoạt bằng CHUỘT PHẢI)
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("Bật Auto Crystal").defaultValue(true).build();
    private final IntegerSetting crystalDelay = IntegerSetting.builder().id("crystal_delay").displayName("Delay Combo (Tick)").defaultValue(1).build();

    // 3. ANCHOR (Kích hoạt bằng CHUỘT TRÁI)
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("Bật Auto Anchor").defaultValue(true).build();
    private final IntegerSetting anchorDelay = IntegerSetting.builder().id("anchor_delay").displayName("Delay Nổ Anchor").defaultValue(2).build();

    // 4. PEARL (Kích hoạt bằng PHÍM C)
    private final BooleanSetting pearlEnable = BooleanSetting.builder().id("pearl_enable").displayName("Bật Auto Pearl").defaultValue(true).build();
    // Cooldown để tránh ném liên tục 2 quả
    private final IntegerSetting pearlCooldown = IntegerSetting.builder().id("pearl_cooldown").displayName("Cooldown Pearl").defaultValue(10).build();

    // ================= FIXED SLOTS =================
    // Code Index = Game Slot - 1
    
    private final int SLOT_OBSIDIAN = 0;  // Slot 1
    private final int SLOT_CRYSTAL = 1;   // Slot 2
    private final int SLOT_PEARL = 4;     // Slot 5
    private final int SLOT_ANCHOR = 5;    // Slot 6
    private final int SLOT_GLOWSTONE = 6; // Slot 7
    private final int SLOT_TOTEM = 7;     // Slot 8

    // ================= VARIABLES =================

    private boolean isRefilling = false;
    private long lastTotemTime = 0;
    private int targetSwapSlot = -1, targetRefillSlot = -1;
    private enum TotemStep { NONE, SELECT_SLOT, OPEN_INV, FIND_SWAP, DO_SWAP, FIND_REFILL, DO_REFILL, CLOSE }
    private TotemStep currentTotemStep = TotemStep.NONE;

    // Control Inputs
    private boolean lastLeftMouse = false;
    private boolean lastRightMouse = false;
    private boolean lastKeyC = false;

    // Anchor Logic Variables
    private boolean isAnchorActive = false;
    private int anchorStage = 0;
    private int anchorTickCounter = 0;
    private BlockPos placedAnchorPos = null;

    // Crystal Logic Variables
    private boolean isCrystalActive = false;
    private int crystalStage = 0;
    private int crystalTickCounter = 0;
    private BlockPos targetObiPos = null;
    
    // Pearl Variables
    private int pearlTimer = 0;
    private int originalSlot = -1;
    private Field selectedSlotField = null;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("PvP Suite: Left=Anchor, Right=Cry, C=Pearl");

        addSetting(totemEnable); addSetting(totemDelay); addSetting(autoEsc);
        addSetting(crystalEnable); addSetting(crystalDelay);
        addSetting(anchorEnable); addSetting(anchorDelay);
        addSetting(pearlEnable); addSetting(pearlCooldown);
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
        currentTotemStep = TotemStep.NONE; isRefilling = false;
        pearlTimer = 0; 
        lastLeftMouse = false; isAnchorActive = false; anchorStage = 0; placedAnchorPos = null;
        lastRightMouse = false; isCrystalActive = false; crystalStage = 0; targetObiPos = null;
        lastKeyC = false;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (!totemEnable.getValue() || mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35 && packet.getEntity(mc.world) == mc.player) {
                if (!isRefilling) { isRefilling = true; currentTotemStep = TotemStep.SELECT_SLOT; lastTotemTime = System.currentTimeMillis(); }
            }
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (pearlTimer > 0) pearlTimer--;

        if (isRefilling && totemEnable.getValue()) { handleTotemRefill(); return; }

        long window = mc.getWindow().getHandle();
        
        // 1. ANCHOR -> CHUỘT TRÁI (LEFT CLICK)
        if (anchorEnable.getValue()) {
            boolean isLeft = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (isLeft && !lastLeftMouse && !isAnchorActive) {
                startAnchorSequence();
            }
            lastLeftMouse = isLeft;
            if (isAnchorActive) { processAnchorSequence(); return; } // Ưu tiên xử lý xong
        }

        // 2. CRYSTAL -> CHUỘT PHẢI (RIGHT CLICK)
        if (crystalEnable.getValue()) {
            boolean isRight = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (isRight && !lastRightMouse && !isCrystalActive) {
                // Chỉ kích hoạt nếu đang nhìn vào Block
                if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                    startCrystalSequence();
                }
            }
            lastRightMouse = isRight;
            if (isCrystalActive) { processCrystalSequence(); return; }
        }

        // 3. PEARL -> PHÍM C
        if (pearlEnable.getValue()) {
            boolean isC = GLFW.glfwGetKey(window, GLFW.KEY_C) == GLFW.GLFW_PRESS;
            if (isC && !lastKeyC && pearlTimer == 0) {
                handleFastPearl();
            }
            lastKeyC = isC;
        }
    }

    // ================= ANCHOR LOGIC (LEFT CLICK) =================
    private void startAnchorSequence() {
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
        originalSlot = getHotbarSlot(); isAnchorActive = true; anchorStage = 0; anchorTickCounter = 0;
    }

    private void processAnchorSequence() {
        anchorTickCounter++;
        switch (anchorStage) {
            case 0: // Đặt Anchor (Slot 6)
                BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                setHotbarSlot(SLOT_ANCHOR);
                if (mc.player.getMainHandStack().getItem() != Items.RESPAWN_ANCHOR) { 
                    sendInfo("Hết Anchor (Slot 6)!"); isAnchorActive = false; setHotbarSlot(originalSlot); return; 
                }
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); 
                mc.player.swingHand(Hand.MAIN_HAND);
                placedAnchorPos = hit.getBlockPos().offset(hit.getSide()); 
                anchorStage = 1; anchorTickCounter = 0;
                break;
            case 1: // Delay
                if (anchorTickCounter >= anchorDelay.getValue()) anchorStage = 2; 
                break;
            case 2: // Sạc + Nổ (Slot 7)
                if (placedAnchorPos != null) {
                    setHotbarSlot(SLOT_GLOWSTONE);
                    if (mc.player.getMainHandStack().getItem() != Items.GLOWSTONE) { 
                        sendInfo("Hết Glowstone (Slot 7)!"); isAnchorActive = false; setHotbarSlot(originalSlot); return; 
                    }
                    lookAtBlock(placedAnchorPos); 
                    BlockHitResult aimHit = new BlockHitResult(new Vec3d(placedAnchorPos.getX()+0.5, placedAnchorPos.getY()+1, placedAnchorPos.getZ()+0.5), Direction.UP, placedAnchorPos, false);
                    // Click 1: Sạc
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); 
                    mc.player.swingHand(Hand.MAIN_HAND);
                    // Click 2: Nổ
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); 
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                setHotbarSlot(originalSlot); isAnchorActive = false; 
                break;
        }
    }

    // ================= CRYSTAL LOGIC (RIGHT CLICK) =================
    private void startCrystalSequence() {
        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = hit.getBlockPos();
        
        // Chỉ hoạt động trên Obsidian hoặc Bedrock
        if (mc.world.getBlockState(pos).getBlock() != Blocks.OBSIDIAN && mc.world.getBlockState(pos).getBlock() != Blocks.BEDROCK) {
            return; 
        }

        targetObiPos = pos;
        originalSlot = getHotbarSlot(); 
        isCrystalActive = true; 
        crystalStage = 0; 
        crystalTickCounter = 0;
    }

    private void processCrystalSequence() {
        crystalTickCounter++;
        switch (crystalStage) {
            case 0: // Bước 1: Đặt Crystal (Slot 2)
                setHotbarSlot(SLOT_CRYSTAL);
                if (mc.player.getMainHandStack().getItem() != Items.END_CRYSTAL) { 
                    sendInfo("Hết Crystal (Slot 2)!"); isCrystalActive = false; setHotbarSlot(originalSlot); return; 
                }
                
                // Aim vào Obsidian
                lookAtBlock(targetObiPos);
                
                BlockHitResult placeHit = new BlockHitResult(new Vec3d(targetObiPos.getX()+0.5, targetObiPos.getY()+1, targetObiPos.getZ()+0.5), Direction.UP, targetObiPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                
                crystalStage = 1; crystalTickCounter = 0;
                break;

            case 1: // Bước 2: Delay
                if (crystalTickCounter >= crystalDelay.getValue()) crystalStage = 2;
                break;

            case 2: // Bước 3: Nổ
                Entity crystal = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(targetObiPos.up()), e -> true).stream().findFirst().orElse(null);
                if (crystal != null) { 
                    mc.interactionManager.attackEntity(mc.player, crystal); 
                    mc.player.swingHand(Hand.MAIN_HAND); 
                }
                setHotbarSlot(originalSlot);
                isCrystalActive = false;
                break;
        }
    }

    // ================= PEARL LOGIC (KEY C) =================
    private void handleFastPearl() {
        int oldSlot = getHotbarSlot();
        int pearlIdx = SLOT_PEARL; // Slot 5
        
        // Kiểm tra slot 5 có phải pearl không
        if (mc.player.getInventory().getStack(pearlIdx).getItem() != Items.ENDER_PEARL) {
            sendInfo("Slot 5 không có Ender Pearl!");
            return;
        }

        // Đổi slot -> Ném -> Về slot cũ
        setHotbarSlot(pearlIdx);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        setHotbarSlot(oldSlot);
        
        pearlTimer = pearlCooldown.getValue();
    }

    // ================= HELPER FUNCTIONS =================
    private void lookAtBlock(BlockPos pos) { Vec3d vec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, vec); }
    private void sendInfo(String msg) { if (mc.player != null) mc.player.sendMessage(Text.of("§b[AutoTotem] §f" + msg), false); }
    
    // ================= TOTEM REFILL (SLOT 8) =================
    private void handleTotemRefill() {
        long now = System.currentTimeMillis(); long delay = Math.max(0, totemDelay.getValue());
        int totemIdx = SLOT_TOTEM; // Slot 8
        switch (currentTotemStep) {
            case SELECT_SLOT: if (now - lastTotemTime >= 50) { setHotbarSlot(totemIdx); currentTotemStep = TotemStep.OPEN_INV; lastTotemTime = now; } break;
            case OPEN_INV: if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player)); if (now - lastTotemTime >= delay) { currentTotemStep = TotemStep.FIND_SWAP; lastTotemTime = now; } break;
            case FIND_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { targetSwapSlot = findTotemSlot(true); if (targetSwapSlot != -1) { aimSlot(targetSwapSlot); currentTotemStep = TotemStep.DO_SWAP; } else resetAll(); lastTotemTime = now; } } break;
            case DO_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player); currentTotemStep = TotemStep.FIND_REFILL; lastTotemTime = now; } } break;
            case FIND_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (isSlotTotem(36 + totemIdx)) { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } else { targetRefillSlot = findTotemSlot(false); if (targetRefillSlot != -1) { aimSlot(targetRefillSlot); currentTotemStep = TotemStep.DO_REFILL; } else { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } } lastTotemTime = now; } } break;
            case DO_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetRefillSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetRefillSlot, totemIdx, SlotActionType.SWAP, mc.player); if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); lastTotemTime = now; } } break;
            case CLOSE: if (now - lastTotemTime >= delay) { mc.setScreen(null); resetAll(); } break;
        }
    }

    private int getHotbarSlot() { try { if (selectedSlotField == null) { try { selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot"); } catch (NoSuchFieldException e) { selectedSlotField = PlayerInventory.class.getDeclaredField("currentItem"); } selectedSlotField.setAccessible(true); } return selectedSlotField.getInt(mc.player.getInventory()); } catch (Exception e) { return 0; } }
    private void setHotbarSlot(int slot) { if (getHotbarSlot() == slot) return; try { if (selectedSlotField == null) { try { selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot"); } catch (NoSuchFieldException e) { selectedSlotField = PlayerInventory.class.getDeclaredField("currentItem"); } selectedSlotField.setAccessible(true); } selectedSlotField.setInt(mc.player.getInventory(), slot); mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot)); } catch (Exception e) { e.printStackTrace(); } }
    private int findTotemSlot(boolean includeHotbar) { if (includeHotbar) for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i; for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i; return -1; }
    private boolean isSlotTotem(int id) { try { return mc.player.currentScreenHandler.slots.get(id).getStack().getItem() == Items.TOTEM_OF_UNDYING; } catch (Exception e) { return false; } }
    private void aimSlot(int slotId) { try { InventoryScreen s = (InventoryScreen) mc.currentScreen; Slot slot = mc.player.currentScreenHandler.slots.get(slotId); int guiLeft = (s.width - 176)/2, guiTop = (s.height - 166)/2; int x = guiLeft + slot.x + 8, y = guiTop + slot.y + 8; int jX = ThreadLocalRandom.current().nextInt(-3, 4), jY = ThreadLocalRandom.current().nextInt(-3, 4); double sc = mc.getWindow().getScaleFactor(); GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), (x+jX)*sc, (y+jY)*sc); } catch (Exception ignored) {} }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
                                                                       }
