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
import net.minecraft.entity.player.PlayerEntity;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // ================= SETTINGS =================

    // 1. TOTEM
    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("Bật Auto Totem").defaultValue(true).build();
    private final IntegerSetting totemSlot = IntegerSetting.builder().id("totem_slot").displayName("Totem Slot (1-9)").defaultValue(1).min(1).max(9).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Totem Delay (ms)").defaultValue(100).build();
    private final BooleanSetting autoEsc = BooleanSetting.builder().id("totem_esc").displayName("Auto Close Inv").defaultValue(true).build();

    // 2. CRYSTAL (One Tap Combo)
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("Bật Auto Crystal").defaultValue(true).build();
    private final BooleanSetting bindCrystalMode = BooleanSetting.builder().id("bind_cry").displayName("[Gán phím Crystal]").defaultValue(false).build();
    private final IntegerSetting crystalKey = IntegerSetting.builder().id("cry_key").displayName("Mã phím Crystal").defaultValue(67).build();
    private final IntegerSetting crystalSlot = IntegerSetting.builder().id("cry_slot").displayName("Crystal Slot (1-9)").defaultValue(3).min(1).max(9).build();
    private final IntegerSetting obsidianSlot = IntegerSetting.builder().id("obi_slot").displayName("Obsidian Slot (1-9)").defaultValue(2).min(1).max(9).build();
    private final BooleanSetting placeObsidian = BooleanSetting.builder().id("crystal_obi").displayName("Tự đặt Obsidian").defaultValue(true).build();
    private final FloatSetting crystalRange = FloatSetting.builder().id("crystal_range").displayName("Tầm Crystal").defaultValue(5.0f).build();
    private final IntegerSetting crystalDelay = IntegerSetting.builder().id("crystal_delay").displayName("Delay Combo Crystal").defaultValue(1).build();

    // 3. ANCHOR (One Tap Combo)
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("Bật Auto Anchor").defaultValue(true).build();
    private final BooleanSetting bindAnchorMode = BooleanSetting.builder().id("bind_anc").displayName("[Gán phím Anchor]").defaultValue(false).build();
    private final IntegerSetting anchorKey = IntegerSetting.builder().id("anc_key").displayName("Mã phím Anchor").defaultValue(88).build();
    private final IntegerSetting anchorSlot = IntegerSetting.builder().id("anc_slot").displayName("Anchor Slot (1-9)").defaultValue(8).min(1).max(9).build();
    private final IntegerSetting glowstoneSlot = IntegerSetting.builder().id("glo_slot").displayName("Glowstone Slot (1-9)").defaultValue(9).min(1).max(9).build();
    private final IntegerSetting anchorDelay = IntegerSetting.builder().id("anchor_delay").displayName("Delay Nổ Anchor").defaultValue(2).build();

    // 4. PEARL (One Tap)
    private final BooleanSetting pearlEnable = BooleanSetting.builder().id("pearl_enable").displayName("Bật Auto Pearl").defaultValue(true).build();
    private final BooleanSetting bindPearlMode = BooleanSetting.builder().id("bind_pearl").displayName("[Gán phím Pearl]").defaultValue(false).build();
    private final IntegerSetting pearlKey = IntegerSetting.builder().id("pearl_key").displayName("Mã phím Pearl").defaultValue(86).build();
    private final IntegerSetting pearlSlot = IntegerSetting.builder().id("pearl_slot").displayName("Pearl Slot (1-9)").defaultValue(4).min(1).max(9).build();
    private final FloatSetting pearlRange = FloatSetting.builder().id("pearl_range").displayName("Tầm ném Pearl").defaultValue(15.0f).build();
    private final IntegerSetting pearlCooldown = IntegerSetting.builder().id("pearl_cooldown").displayName("Cooldown Pearl").defaultValue(40).build();

    // ================= VARIABLES =================

    private boolean isRefilling = false;
    private long lastTotemTime = 0;
    private int targetSwapSlot = -1, targetRefillSlot = -1;
    private enum TotemStep { NONE, SELECT_SLOT, OPEN_INV, FIND_SWAP, DO_SWAP, FIND_REFILL, DO_REFILL, CLOSE }
    private TotemStep currentTotemStep = TotemStep.NONE;

    private int pearlTimer = 0;
    private boolean lastPearlKeyState = false;
    private int originalSlot = -1;
    private Field selectedSlotField = null;

    // Anchor Variables
    private boolean lastAnchorKeyState = false;
    private boolean isAnchorActive = false;
    private int anchorStage = 0;
    private int anchorTickCounter = 0;
    private BlockPos placedAnchorPos = null;

    // Crystal Variables (New)
    private boolean lastCrystalKeyState = false;
    private boolean isCrystalActive = false;
    private int crystalStage = 0;
    private int crystalTickCounter = 0;
    private BlockPos targetObiPos = null;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("PvP Suite: One Tap Totem - Crystal - Anchor - Pearl");

        addSetting(totemEnable); addSetting(totemSlot); addSetting(totemDelay); addSetting(autoEsc);
        
        addSetting(crystalEnable); addSetting(bindCrystalMode); addSetting(crystalKey); 
        addSetting(crystalSlot); addSetting(obsidianSlot); addSetting(placeObsidian); 
        addSetting(crystalRange); addSetting(crystalDelay);

        addSetting(anchorEnable); addSetting(bindAnchorMode); addSetting(anchorKey); 
        addSetting(anchorSlot); addSetting(glowstoneSlot); addSetting(anchorDelay);

        addSetting(pearlEnable); addSetting(bindPearlMode); addSetting(pearlKey); 
        addSetting(pearlSlot); addSetting(pearlRange); addSetting(pearlCooldown);
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
        bindCrystalMode.setValue(false); bindAnchorMode.setValue(false); bindPearlMode.setValue(false);
    }

    private void resetAll() {
        currentTotemStep = TotemStep.NONE; isRefilling = false;
        pearlTimer = 0; lastPearlKeyState = false; 
        
        lastAnchorKeyState = false; isAnchorActive = false; anchorStage = 0; placedAnchorPos = null;
        lastCrystalKeyState = false; isCrystalActive = false; crystalStage = 0; targetObiPos = null;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (!totemEnable.getValue() || mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35 && packet.getEntity(mc.world) == mc.player) {
                if (!isRefilling) { isRefilling = true; currentTotemStep = TotemStep.SELECT_SLOT; lastTotemTime = System.currentTimeMillis(); }
            }
        }
    }

    private boolean isInputDown(long window, int code) {
        if (code < 0) return false;
        if (code < 10) return GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
        else return GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (handleKeyBinding()) return;

        if (pearlTimer > 0) pearlTimer--;

        if (isRefilling && totemEnable.getValue()) { handleTotemRefill(); return; }

        long window = mc.getWindow().getHandle();

        // 1. ONE TAP CRYSTAL
        if (crystalEnable.getValue()) {
            boolean isKeyDown = isInputDown(window, crystalKey.getValue());
            if (isKeyDown && !lastCrystalKeyState && !isCrystalActive) {
                startCrystalSequence();
            }
            lastCrystalKeyState = isKeyDown;

            if (isCrystalActive) {
                processCrystalSequence();
                return; // Ưu tiên xử lý combo, chặn các việc khác
            }
        }

        // 2. ONE TAP ANCHOR
        if (anchorEnable.getValue()) {
            boolean isKeyDown = isInputDown(window, anchorKey.getValue());
            if (isKeyDown && !lastAnchorKeyState && !isAnchorActive) {
                startAnchorSequence();
            }
            lastAnchorKeyState = isKeyDown;

            if (isAnchorActive) {
                processAnchorSequence();
                return;
            }
        }

        // 3. ONE TAP PEARL
        if (pearlEnable.getValue()) {
            boolean isKeyDown = isInputDown(window, pearlKey.getValue());
            if (isKeyDown && !lastPearlKeyState) {
                handleAutoPearl();
            }
            lastPearlKeyState = isKeyDown;
        }
    }

    // ================= CRYSTAL COMBO LOGIC =================
    private void startCrystalSequence() {
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            sendInfo("Hãy nhìn vào Block!"); return;
        }
        
        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = hit.getBlockPos();
        
        // Kiểm tra block đang nhìn
        boolean isObi = mc.world.getBlockState(pos).getBlock() == Blocks.OBSIDIAN;
        boolean isBedrock = mc.world.getBlockState(pos).getBlock() == Blocks.BEDROCK;

        // Nếu đã là Obsidian/Bedrock -> Bỏ qua bước đặt Obi, chuyển thẳng sang đặt Crystal
        if (isObi || isBedrock) {
            targetObiPos = pos;
            crystalStage = 2; // Jump to place crystal
        } else {
            // Nếu chưa là Obi -> Chuẩn bị đặt Obi
            if (!placeObsidian.getValue()) { sendInfo("Cần nhìn vào Obsidian/Bedrock!"); return; }
            targetObiPos = pos.offset(hit.getSide()); // Đặt nổi lên mặt
            crystalStage = 0; // Start from placing obi
        }

        originalSlot = getHotbarSlot();
        isCrystalActive = true;
        crystalTickCounter = 0;
    }

    private void processCrystalSequence() {
        crystalTickCounter++;
        switch (crystalStage) {
            case 0: // Bước 1: Đặt Obsidian
                setHotbarSlot(getSlotIndex(obsidianSlot));
                if (mc.player.getMainHandStack().getItem() != Items.OBSIDIAN) { sendInfo("Hết Obsidian!"); isCrystalActive = false; setHotbarSlot(originalSlot); return; }
                
                BlockHitResult placeHit = new BlockHitResult(new Vec3d(targetObiPos.getX(), targetObiPos.getY(), targetObiPos.getZ()), Direction.UP, targetObiPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, placeHit);
                mc.player.swingHand(Hand.MAIN_HAND);
                
                crystalStage = 1; crystalTickCounter = 0;
                break;

            case 1: // Bước 2: Delay chờ Obi (cho server nhận)
                if (crystalTickCounter >= crystalDelay.getValue()) crystalStage = 2;
                break;

            case 2: // Bước 3: Đặt Crystal + Nổ
                setHotbarSlot(getSlotIndex(crystalSlot));
                if (mc.player.getMainHandStack().getItem() != Items.END_CRYSTAL) { sendInfo("Hết Crystal!"); isCrystalActive = false; setHotbarSlot(originalSlot); return; }
                
                lookAtBlock(targetObiPos); // Aim vào block Obi

                BlockHitResult crystalHit = new BlockHitResult(new Vec3d(targetObiPos.getX()+0.5, targetObiPos.getY()+1, targetObiPos.getZ()+0.5), Direction.UP, targetObiPos, false);
                
                // --- ACTION 1: ĐẶT CRYSTAL ---
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, crystalHit);
                mc.player.swingHand(Hand.MAIN_HAND);

                // --- ACTION 2: ĐẬP NỔ LUÔN (ATTACK) ---
                // Tìm entity crystal vừa đặt (hoặc dự đoán vị trí) để đập
                Entity crystal = findCrystalAt(targetObiPos);
                if (crystal != null) {
                    mc.interactionManager.attackEntity(mc.player, crystal);
                    mc.player.swingHand(Hand.MAIN_HAND);
                } else {
                    // Nếu crystal chưa hiện ra client kịp, ta vẫn swing để animation mượt, 
                    // hoặc có thể thêm logic chờ 1 tick nữa. Ở đây mình swing thôi.
                }

                setHotbarSlot(originalSlot);
                isCrystalActive = false;
                break;
        }
    }
    
    private Entity findCrystalAt(BlockPos pos) {
        // Tìm crystal ngay trên block pos
        return mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(pos.up()), e -> true).stream().findFirst().orElse(null);
    }

    // ================= ANCHOR COMBO LOGIC =================
    private void startAnchorSequence() {
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) { sendInfo("Hãy nhìn vào Block!"); return; }
        originalSlot = getHotbarSlot(); isAnchorActive = true; anchorStage = 0; anchorTickCounter = 0;
    }

    private void processAnchorSequence() {
        anchorTickCounter++;
        switch (anchorStage) {
            case 0:
                BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
                setHotbarSlot(getSlotIndex(anchorSlot)); 
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit); mc.player.swingHand(Hand.MAIN_HAND);
                placedAnchorPos = hit.getBlockPos().offset(hit.getSide()); anchorStage = 1; anchorTickCounter = 0;
                break;
            case 1:
                if (anchorTickCounter >= anchorDelay.getValue()) anchorStage = 2; break;
            case 2:
                if (placedAnchorPos != null) {
                    lookAtBlock(placedAnchorPos); setHotbarSlot(getSlotIndex(glowstoneSlot)); 
                    BlockHitResult aimHit = new BlockHitResult(new Vec3d(placedAnchorPos.getX()+0.5, placedAnchorPos.getY()+1, placedAnchorPos.getZ()+0.5), Direction.UP, placedAnchorPos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); mc.player.swingHand(Hand.MAIN_HAND); // Sạc
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, aimHit); mc.player.swingHand(Hand.MAIN_HAND); // Nổ
                }
                setHotbarSlot(originalSlot); isAnchorActive = false; break;
        }
    }

    // ================= OTHER LOGIC =================
    private boolean handleKeyBinding() {
        if (!bindCrystalMode.getValue() && !bindAnchorMode.getValue() && !bindPearlMode.getValue()) return false;
        long window = mc.getWindow().getHandle();
        int pressedCode = -1; String name = "Key";
        for (int i = 0; i <= 7; i++) { if (GLFW.glfwGetMouseButton(window, i) == GLFW.GLFW_PRESS) { pressedCode = i; name = (i == 0 ? "Chuột Trái" : (i == 1 ? "Chuột Phải" : "Mouse " + i)); break; } }
        if (pressedCode == -1) { for (int i = 32; i <= 348; i++) { if (GLFW.glfwGetKey(window, i) == GLFW.GLFW_PRESS) { pressedCode = i; name = "Phím " + i; break; } } }
        if (pressedCode != -1) {
            if (bindCrystalMode.getValue()) { crystalKey.setValue(pressedCode); bindCrystalMode.setValue(false); sendInfo("Gán Crystal: " + name); } 
            else if (bindAnchorMode.getValue()) { anchorKey.setValue(pressedCode); bindAnchorMode.setValue(false); sendInfo("Gán Anchor: " + name); } 
            else if (bindPearlMode.getValue()) { pearlKey.setValue(pressedCode); bindPearlMode.setValue(false); sendInfo("Gán Pearl: " + name); }
            return true;
        } return false;
    }

    private void lookAtBlock(BlockPos pos) { Vec3d vec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5); mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, vec); }
    private void handleAutoPearl() { if (mc.currentScreen != null || pearlTimer > 0) return; PlayerEntity target = StreamSupport.stream(mc.world.getPlayers().spliterator(), false).filter(p -> p != mc.player && !p.isDead() && p.getHealth() > 0).min(Comparator.comparingDouble(p -> mc.player.distanceTo(p))).orElse(null); if (target == null) { sendInfo("Không có mục tiêu!"); return; } if (mc.player.distanceTo(target) < pearlRange.getValue() || mc.player.distanceTo(target) > 60) { sendInfo("Mục tiêu quá xa!"); return; } int pearlIdx = getSlotIndex(pearlSlot); if (mc.player.getInventory().getStack(pearlIdx).getItem() != Items.ENDER_PEARL) { sendInfo("Thiếu Pearl!"); return; } int oldSlot = getHotbarSlot(); mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getPos().add(0, target.getHeight() * 0.5, 0)); setHotbarSlot(pearlIdx); mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); setHotbarSlot(oldSlot); pearlTimer = pearlCooldown.getValue(); }
    
    // --- TOTEM REFILL & HELPERS ---
    private void handleTotemRefill() { long now = System.currentTimeMillis(); long delay = Math.max(0, totemDelay.getValue()); int totemIdx = getSlotIndex(totemSlot); switch (currentTotemStep) { case SELECT_SLOT: if (now - lastTotemTime >= 50) { setHotbarSlot(totemIdx); currentTotemStep = TotemStep.OPEN_INV; lastTotemTime = now; } break; case OPEN_INV: if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player)); if (now - lastTotemTime >= delay) { currentTotemStep = TotemStep.FIND_SWAP; lastTotemTime = now; } break; case FIND_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { targetSwapSlot = findTotemSlot(true); if (targetSwapSlot != -1) { aimSlot(targetSwapSlot); currentTotemStep = TotemStep.DO_SWAP; } else resetAll(); lastTotemTime = now; } } break; case DO_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player); currentTotemStep = TotemStep.FIND_REFILL; lastTotemTime = now; } } break; case FIND_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay){ setHotbarSlot(totemIdx); currentTotemStep = TotemStep.OPEN_INV; lastTotemTime = now; } break; case OPEN_INV: if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player)); if (now - lastTotemTime >= delay) { currentTotemStep = TotemStep.FIND_SWAP; lastTotemTime = now; } break; case FIND_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { targetSwapSlot = findTotemSlot(true); if (targetSwapSlot != -1) { aimSlot(targetSwapSlot); currentTotemStep = TotemStep.DO_SWAP; } else resetAll(); lastTotemTime = now; } } break; case DO_SWAP: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player); currentTotemStep = TotemStep.FIND_REFILL; lastTotemTime = now; } } break; case FIND_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (isSlotTotem(36)) { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } else { targetRefillSlot = findTotemSlot(false); if (targetRefillSlot != -1) { aimSlot(targetRefillSlot); currentTotemStep = TotemStep.DO_REFILL; } else { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); } } lastTotemTime = now; } } break; case DO_REFILL: if (mc.currentScreen instanceof InventoryScreen) { if (now - lastTotemTime >= delay) { if (targetRefillSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetRefillSlot, totemIdx, SlotActionType.SWAP, mc.player); if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); lastTotemTime = now; } } break; case CLOSE: if (now - lastTotemTime >= delay) { mc.setScreen(null); resetAll(); } break; } }
    private int getHotbarSlot() { try { if (selectedSlotField == null) { try { selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot"); } catch (NoSuchFieldException e) { selectedSlotField = PlayerInventory.class.getDeclaredField("currentItem"); } selectedSlotField.setAccessible(true); } return selectedSlotField.getInt(mc.player.getInventory()); } catch (Exception e) { return 0; } }
    private void setHotbarSlot(int slot) { if (getHotbarSlot() == slot) return; try { if (selectedSlotField == null) { try { selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot"); } catch (NoSuchFieldException e) { selectedSlotField = PlayerInventory.class.getDeclaredField("currentItem"); } selectedSlotField.setAccessible(true); } selectedSlotField.setInt(mc.player.getInventory(), slot); mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot)); } catch (Exception e) { e.printStackTrace(); } }
    private void sendInfo(String msg) { if (mc.player != null) mc.player.sendMessage(Text.of("§b[AutoTotem] §f" + msg), false); }
    private int getSlotIndex(IntegerSetting setting) { int val = setting.getValue(); if (val < 1) val = 1; if (val > 9) val = 9; return val - 1; }
    private int findTotemSlot(boolean includeHotbar) { if (includeHotbar) for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i; for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i; return -1; }
    private boolean isSlotTotem(int id) { try { return mc.player.currentScreenHandler.slots.get(id).getStack().getItem() == Items.TOTEM_OF_UNDYING; } catch (Exception e) { return false; } }
    private void aimSlot(int slotId) { try { InventoryScreen s = (InventoryScreen) mc.currentScreen; Slot slot = mc.player.currentScreenHandler.slots.get(slotId); int guiLeft = (s.width - 176)/2, guiTop = (s.height - 166)/2; int x = guiLeft + slot.x + 8, y = guiTop + slot.y + 8; int jX = ThreadLocalRandom.current().nextInt(-3, 4), jY = ThreadLocalRandom.current().nextInt(-3, 4); double sc = mc.getWindow().getScaleFactor(); GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), (x+jX)*sc, (y+jY)*sc); } catch (Exception ignored) {} }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
}
