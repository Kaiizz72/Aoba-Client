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
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;

public class AutoCombat extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // ================= SETTINGS =================

    // --- 1. TOTEM ---
    private final BooleanSetting totemEnable = BooleanSetting.builder().id("totem_enable").displayName("Bật Auto Totem").defaultValue(true).build();
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_delay").displayName("Totem Delay (ms)").defaultValue(100).build();
    private final BooleanSetting autoEsc = BooleanSetting.builder().id("totem_esc").displayName("Auto Close Inv").defaultValue(true).build();

    // --- 2. CRYSTAL (Giữ Chuột Phải) ---
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("Bật Auto Crystal (Phải)").defaultValue(true).build();
    private final BooleanSetting placeObsidian = BooleanSetting.builder().id("crystal_obi").displayName("Tự đặt Obsidian").defaultValue(true).build();
    private final FloatSetting crystalRange = FloatSetting.builder().id("crystal_range").displayName("Tầm Crystal").defaultValue(5.0f).min(1.0f).max(6.0f).build();
    private final IntegerSetting crystalPlaceDelay = IntegerSetting.builder().id("crystal_p_delay").displayName("Delay Đặt Crys (tick)").defaultValue(1).build();
    private final IntegerSetting crystalBreakDelay = IntegerSetting.builder().id("crystal_b_delay").displayName("Delay Đập Crys (tick)").defaultValue(1).build();

    // --- 3. ANCHOR (Giữ Chuột Trái) ---
    private final BooleanSetting anchorEnable = BooleanSetting.builder().id("anchor_enable").displayName("Bật Auto Anchor (Trái)").defaultValue(true).build();
    private final IntegerSetting anchorDelay = IntegerSetting.builder().id("anchor_delay").displayName("Delay Anchor (tick)").defaultValue(2).build();

    // --- 4. PEARL (Tự động) ---
    private final BooleanSetting pearlEnable = BooleanSetting.builder().id("pearl_enable").displayName("Bật Auto Pearl").defaultValue(true).build();
    private final FloatSetting pearlRange = FloatSetting.builder().id("pearl_range").displayName("Tầm ném Pearl").defaultValue(15.0f).min(5.0f).max(50.0f).build();
    private final IntegerSetting pearlCooldown = IntegerSetting.builder().id("pearl_cooldown").displayName("Cooldown Pearl (tick)").defaultValue(40).build();

    // ================= VARIABLES =================

    // Totem Vars
    private boolean isRefilling = false;
    private long lastTotemTime = 0;
    private int targetSwapSlot = -1, targetRefillSlot = -1;
    private enum TotemStep { NONE, SELECT_SLOT, OPEN_INV, FIND_SWAP, DO_SWAP, FIND_REFILL, DO_REFILL, CLOSE }
    private TotemStep currentTotemStep = TotemStep.NONE;

    // Timers
    private int crystalPlaceTimer = 0, crystalBreakTimer = 0;
    private int anchorTimer = 0;
    private int pearlTimer = 0;

    // HARDCODED SLOTS (Index = Slot - 1)
    private final int SLOT_TOTEM = 0;    // Slot 1
    private final int SLOT_OBSIDIAN = 1; // Slot 2
    private final int SLOT_CRYSTAL = 2;  // Slot 3
    private final int SLOT_PEARL = 3;    // Slot 4
    private final int SLOT_ANCHOR = 7;   // Slot 8
    private final int SLOT_GLOWSTONE = 8;// Slot 9

    public AutoCombat() {
        super("AutoCombat");
        setCategory(Category.of("Combat"));
        setDescription("Totem(1), Obi(2), Cry(3), Pearl(4), Anchor(8,9)");

        addSetting(totemEnable); addSetting(totemDelay); addSetting(autoEsc);
        addSetting(crystalEnable); addSetting(placeObsidian); addSetting(crystalRange); addSetting(crystalPlaceDelay); addSetting(crystalBreakDelay);
        addSetting(anchorEnable); addSetting(anchorDelay);
        addSetting(pearlEnable); addSetting(pearlRange); addSetting(pearlCooldown);
    }

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        resetAll();
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        resetAll();
    }

    private void resetAll() {
        currentTotemStep = TotemStep.NONE;
        isRefilling = false;
        crystalPlaceTimer = 0; crystalBreakTimer = 0;
        anchorTimer = 0; pearlTimer = 0;
    }

    // --- PACKET LISTENER (TOTEM POP) ---
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!totemEnable.getValue() || mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35 && packet.getEntity(mc.world) == mc.player) {
                if (!isRefilling) {
                    isRefilling = true;
                    currentTotemStep = TotemStep.SELECT_SLOT;
                    lastTotemTime = System.currentTimeMillis();
                }
            }
        }
    }

    // --- MAIN TICK LOGIC ---
    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Decrement Timers
        if (crystalPlaceTimer > 0) crystalPlaceTimer--;
        if (crystalBreakTimer > 0) crystalBreakTimer--;
        if (anchorTimer > 0) anchorTimer--;
        if (pearlTimer > 0) pearlTimer--;

        // ===========================================
        // PRIORITY 1: AUTO TOTEM REFILL (SỐNG CÒN)
        // ===========================================
        if (isRefilling && totemEnable.getValue()) {
            handleTotemRefill();
            return; // Dừng mọi hành động khác khi đang refill
        }

        // ===========================================
        // PRIORITY 2: AUTO CRYSTAL (GIỮ CHUỘT PHẢI)
        // ===========================================
        if (crystalEnable.getValue() && mc.options.useKey.isPressed()) {
            handleAutoCrystal();
            return; // Không làm việc khác khi đang spam crystal
        }

        // ===========================================
        // PRIORITY 3: AUTO ANCHOR (GIỮ CHUỘT TRÁI)
        // ===========================================
        if (anchorEnable.getValue() && mc.options.attackKey.isPressed()) {
            handleAutoAnchor();
            return;
        }

        // ===========================================
        // PRIORITY 4: AUTO PEARL (AUTO THẢ)
        // ===========================================
        if (pearlEnable.getValue() && !mc.options.attackKey.isPressed() && !mc.options.useKey.isPressed()) {
            handleAutoPearl();
        }
    }

    // ---------------------------------------------------------
    // LOGIC: AUTO CRYSTAL (Place Obi -> Place Cry -> Break)
    // ---------------------------------------------------------
    private void handleAutoCrystal() {
        // 1. Break (Nổ)
        if (crystalBreakTimer <= 0) {
            Entity target = findCrystal();
            if (target != null) {
                mc.interactionManager.attackEntity(mc.player, target);
                mc.player.swingHand(Hand.MAIN_HAND);
                crystalBreakTimer = crystalBreakDelay.getValue();
                return;
            }
        }

        // 2. Place (Đặt)
        if (crystalPlaceTimer <= 0) {
            HitResult hit = mc.crosshairTarget;
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) return;
            BlockHitResult bHit = (BlockHitResult) hit;
            BlockPos pos = bHit.getBlockPos();

            boolean isObi = mc.world.getBlockState(pos).getBlock() == Blocks.OBSIDIAN;
            boolean isBedrock = mc.world.getBlockState(pos).getBlock() == Blocks.BEDROCK;

            if (isObi || isBedrock) {
                // Đặt Crystal
                if (!hasCrystalAt(pos)) {
                    switchToSlot(SLOT_CRYSTAL);
                    if (mc.player.getMainHandStack().getItem() == Items.END_CRYSTAL) {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bHit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                    crystalPlaceTimer = crystalPlaceDelay.getValue();
                }
            } else if (placeObsidian.getValue()) {
                // Đặt Obsidian
                if (mc.world.getBlockState(pos).getMaterial().isReplaceable()) {
                    switchToSlot(SLOT_OBSIDIAN);
                    if (mc.player.getMainHandStack().getItem() == Items.OBSIDIAN) {
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bHit);
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                    crystalPlaceTimer = crystalPlaceDelay.getValue();
                }
            }
        }
    }

    // ---------------------------------------------------------
    // LOGIC: AUTO ANCHOR
    // ---------------------------------------------------------
    private void handleAutoAnchor() {
        if (anchorTimer > 0) return;
        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;
        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;

        PlayerInventory inv = mc.player.getInventory();
        if (inv.getStack(SLOT_ANCHOR).getItem() != Items.RESPAWN_ANCHOR || 
            inv.getStack(SLOT_GLOWSTONE).getItem() != Items.GLOWSTONE) return;

        switchToSlot(SLOT_ANCHOR);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        
        switchToSlot(SLOT_GLOWSTONE);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);

        switchToSlot(SLOT_ANCHOR); // Reset về Anchor
        anchorTimer = anchorDelay.getValue();
    }

    // ---------------------------------------------------------
    // LOGIC: AUTO PEARL
    // ---------------------------------------------------------
    private void handleAutoPearl() {
        if (mc.currentScreen != null || pearlTimer > 0) return;

        PlayerEntity target = StreamSupport.stream(mc.world.getPlayers().spliterator(), false)
                .filter(p -> p != mc.player && !p.isDead() && p.getHealth() > 0)
                .min(Comparator.comparingDouble(p -> mc.player.distanceTo(p))).orElse(null);

        if (target == null) return;
        float dist = mc.player.distanceTo(target);
        if (dist < pearlRange.getValue() || dist > 60) return;

        if (mc.player.getInventory().getStack(SLOT_PEARL).getItem() != Items.ENDER_PEARL) return;

        int oldSlot = mc.player.getInventory().selectedSlot;
        mc.player.lookAt(EntityAnchorArgumentType.EYES, target.getPos().add(0, target.getHeight() * 0.5, 0));
        switchToSlot(SLOT_PEARL);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        switchToSlot(oldSlot);
        pearlTimer = pearlCooldown.getValue();
    }

    // ---------------------------------------------------------
    // LOGIC: AUTO TOTEM (State Machine)
    // ---------------------------------------------------------
    private void handleTotemRefill() {
        long now = System.currentTimeMillis();
        long delay = Math.max(0, totemDelay.getValue());

        switch (currentTotemStep) {
            case SELECT_SLOT:
                if (now - lastTotemTime >= 50) {
                    forceSetSlot(SLOT_TOTEM);
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(SLOT_TOTEM));
                    currentTotemStep = TotemStep.OPEN_INV;
                    lastTotemTime = now;
                }
                break;
            case OPEN_INV:
                if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                if (now - lastTotemTime >= delay) { currentTotemStep = TotemStep.FIND_SWAP; lastTotemTime = now; }
                break;
            case FIND_SWAP:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTotemTime >= delay) {
                        targetSwapSlot = findTotemSlot(true);
                        if (targetSwapSlot != -1) { aimSlot(targetSwapSlot); currentTotemStep = TotemStep.DO_SWAP; }
                        else resetAll();
                        lastTotemTime = now;
                    }
                }
                break;
            case DO_SWAP:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTotemTime >= delay) {
                        if (targetSwapSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player);
                        currentTotemStep = TotemStep.FIND_REFILL; lastTotemTime = now;
                    }
                }
                break;
            case FIND_REFILL:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTotemTime >= delay) {
                        if (isSlotTotem(36)) { // 36 is Slot 1 container id
                             if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll();
                        } else {
                            targetRefillSlot = findTotemSlot(false);
                            if (targetRefillSlot != -1) { aimSlot(targetRefillSlot); currentTotemStep = TotemStep.DO_REFILL; }
                            else { if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll(); }
                        }
                        lastTotemTime = now;
                    }
                }
                break;
            case DO_REFILL:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTotemTime >= delay) {
                        if (targetRefillSlot != -1) mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, targetRefillSlot, SLOT_TOTEM, SlotActionType.SWAP, mc.player);
                        if (autoEsc.getValue()) currentTotemStep = TotemStep.CLOSE; else resetAll();
                        lastTotemTime = now;
                    }
                }
                break;
            case CLOSE:
                if (now - lastTotemTime >= delay) { mc.setScreen(null); resetAll(); }
                break;
            default: break;
        }
    }

    // --- HELPER FUNCTIONS ---

    private void switchToSlot(int slot) {
        if (mc.player.getInventory().selectedSlot == slot) return;
        mc.player.getInventory().selectedSlot = slot;
        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void forceSetSlot(int slotIndex) {
        try { Field f = PlayerInventory.class.getDeclaredField("selectedSlot"); f.setAccessible(true); f.setInt(mc.player.getInventory(), slotIndex); }
        catch (Exception e) { try { Field f = PlayerInventory.class.getDeclaredField("currentItem"); f.setAccessible(true); f.setInt(mc.player.getInventory(), slotIndex); } catch (Exception ignored) {} }
    }

    private Entity findCrystal() {
        float r = crystalRange.getValue();
        List<EndCrystalEntity> list = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(mc.player.getPos().add(-r,-r,-r), mc.player.getPos().add(r,r,r)), e -> e.isAlive() && mc.player.distanceTo(e) <= r);
        return list.stream().min(Comparator.comparingDouble(e -> mc.player.distanceTo(e))).orElse(null);
    }

    private boolean hasCrystalAt(BlockPos pos) {
        return !mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(pos.up()), e -> true).isEmpty();
    }

    private int findTotemSlot(boolean includeHotbar) {
        if (includeHotbar) for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i;
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }
    
    private boolean isSlotTotem(int id) { try { return mc.player.currentScreenHandler.slots.get(id).getStack().getItem() == Items.TOTEM_OF_UNDYING; } catch (Exception e) { return false; } }

    private void aimSlot(int slotId) {
        try {
            InventoryScreen s = (InventoryScreen) mc.currentScreen;
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            int guiLeft = (s.width - 176)/2, guiTop = (s.height - 166)/2;
            int x = guiLeft + slot.x + 8, y = guiTop + slot.y + 8;
            int jX = ThreadLocalRandom.current().nextInt(-3, 4), jY = ThreadLocalRandom.current().nextInt(-3, 4);
            double sc = mc.getWindow().getScaleFactor();
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), (x+jX)*sc, (y+jY)*sc);
        } catch (Exception ignored) {}
    }
}
