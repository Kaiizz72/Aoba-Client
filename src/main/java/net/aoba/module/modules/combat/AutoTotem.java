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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Random random = new Random();

    // --- CÀI ĐẶT ---
    private final IntegerSetting totemDelay = IntegerSetting.builder().id("totem_v59").displayName("Totem Delay (ms)").defaultValue(150).build();
    private final BooleanSetting stopMove = BooleanSetting.builder().id("stop_move").displayName("Stop Move (Safety)").defaultValue(true).build();
    
    // Crystal Settings
    private final BooleanSetting crystalEnable = BooleanSetting.builder().id("crystal_enable").displayName("Auto Crystal (R-Click)").defaultValue(true).build();
    private final IntegerSetting actionDelay = IntegerSetting.builder().id("action_delay").displayName("Action Delay (Tick)").defaultValue(3).build();
    private final BooleanSetting placeObsidian = BooleanSetting.builder().id("place_obs").displayName("Auto Place Obs").defaultValue(true).build();

    // --- SLOT CONFIG ---
    private final int S_OBSIDIAN = 1; // Slot 2 (Index 1)
    private final int S_CRYSTAL = 2;  // Slot 3 (Index 2)

    // Variables Totem
    private boolean isWorking = false; 
    private long nextTotemAction = 0;
    private enum TotemStep { SELECT, OPEN, SWAP, REFILL, CLOSE }
    private TotemStep tStep = TotemStep.SELECT;

    // Variables Crystal
    private int tickCounter = 0; 
    private boolean actionSentThisTick = false; 

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("V59: Base V54 + Crystal Slot 2/3 + Anti-Kick");
        addSetting(totemDelay); addSetting(stopMove);
        addSetting(crystalEnable); addSetting(actionDelay); addSetting(placeObsidian);
    }

    @Override public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        isWorking = false; 
        tickCounter = 0;
    }

    @Override public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null) return;
        if (event.GetPacket() instanceof EntityStatusS2CPacket p && p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
            if (!isWorking) { isWorking = true; tStep = TotemStep.SELECT; nextTotemAction = System.currentTimeMillis() + 50; }
        }
    }

    @Override public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        
        actionSentThisTick = false;
        if (tickCounter > 0) tickCounter--;

        // 1. TOTEM (ƯU TIÊN SỐ 1)
        if (isWorking) {
            if (stopMove.getValue() && tStep != TotemStep.SELECT && tStep != TotemStep.CLOSE) stopPlayerMovement();
            if (System.currentTimeMillis() >= nextTotemAction) handleTotem();
            return; 
        }

        // 2. CRYSTAL (DỰA TRÊN CODE V54: CHECK CHUỘT PHẢI)
        long win = mc.getWindow().getHandle();
        boolean isRightPressed = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (crystalEnable.getValue() && isRightPressed) {
            if (tickCounter > 0) return;
            
            PlayerEntity target = findNearestTarget(6.0); 
            if (target != null) {
                handleAutoCrystal(target);
            }
        }
    }

    // --- LOGIC CRYSTAL/OBSIDIAN ---
    private void handleAutoCrystal(PlayerEntity target) {
        if (actionSentThisTick) return; 

        // 1. Đập Crystal
        EndCrystalEntity crystal = findNearestCrystal(target, 4.5);
        if (crystal != null) {
            if (mc.player.getAttackCooldownProgress(0.5f) >= 1.0f) {
                attackEntity(crystal);
                setDelay(2); 
                return;
            }
        }

        // 2. Đặt Crystal (Slot 3)
        BlockPos placePos = findCrystalSpot(target);
        if (placePos != null) {
            if (switchToSlot(S_CRYSTAL)) {
                setDelay(1); return; // Chờ 1 tick switch slot
            }
            rotateAndPlace(placePos);
            setDelay(actionDelay.getValue()); 
            return;
        }

        // 3. Đặt Obsidian (Slot 2)
        if (placeObsidian.getValue()) {
            BlockPos obsPos = findObsidianSpot(target);
            if (obsPos != null) {
                if (switchToSlot(S_OBSIDIAN)) {
                    setDelay(1); return;
                }
                rotateAndPlace(obsPos);
                setDelay(actionDelay.getValue() + 1); 
                return;
            }
        }
    }

    // --- UTILS (Đã Fix lỗi Compile) ---
    
    // Fix lỗi: Không gọi trực tiếp .selectedSlot
    private int getCurrentSlot() {
        if (mc.player == null) return 0;
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            return f.getInt(inv);
        } catch (Exception e) {
            try { Field f = PlayerInventory.class.getDeclaredField("currentItem"); f.setAccessible(true); return f.getInt(inv); } 
            catch (Exception e2) { try { Field f = PlayerInventory.class.getDeclaredField("field_7545"); f.setAccessible(true); return f.getInt(inv); } catch (Exception ignored) { return 0; } }
        }
    }

    private boolean switchToSlot(int slotIndex) {
        if (getCurrentSlot() != slotIndex) {
            forceSetSlot(slotIndex);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slotIndex));
            return true; 
        }
        return false;
    }

    private void setDelay(int ticks) {
        this.tickCounter = ticks;
        this.actionSentThisTick = true;
    }

    // Rotation Fake (Legit Aim)
    private void rotateAndPlace(BlockPos pos) {
        Vec3d eyes = mc.player.getEyePos();
        Vec3d vec = new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        
        double dx = vec.x - eyes.x, dy = vec.y - eyes.y, dz = vec.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));

        float oldYaw = mc.player.getYaw();
        float oldPitch = mc.player.getPitch();

        // Gửi gói tin xoay (Server thấy xoay, màn hình mình không xoay)
        mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        
        BlockHitResult hit = new BlockHitResult(vec, Direction.UP, pos, false);
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
        mc.player.swingHand(Hand.MAIN_HAND);
        
        // Trả góc về
        mc.player.setYaw(oldYaw); mc.player.setPitch(oldPitch);
        
        actionSentThisTick = true;
    }

    private void attackEntity(Entity entity) {
        mc.interactionManager.attackEntity(mc.player, entity);
        mc.player.swingHand(Hand.MAIN_HAND);
        actionSentThisTick = true;
    }

    private BlockPos findCrystalSpot(PlayerEntity target) {
        BlockPos pPos = target.getBlockPos();
        BlockPos[] offsets = { pPos.down(), pPos.north().down(), pPos.south().down(), pPos.east().down(), pPos.west().down() };
        for (BlockPos pos : offsets) if (canPlaceCrystal(pos)) return pos;
        return null;
    }

    private BlockPos findObsidianSpot(PlayerEntity target) {
        BlockPos pPos = target.getBlockPos();
        BlockPos[] offsets = { pPos.north().down(), pPos.south().down(), pPos.east().down(), pPos.west().down() };
        for (BlockPos pos : offsets) {
            // Fix lỗi: Thay getMaterial bằng check trực tiếp
            if (mc.world.getBlockState(pos).isAir() || mc.world.getBlockState(pos).isReplaceable()) {
                if (!mc.world.getBlockState(pos.down()).isAir()) return pos;
            }
        }
        return null;
    }

    private boolean canPlaceCrystal(BlockPos pos) {
        if (mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN) || mc.world.getBlockState(pos).isOf(Blocks.BEDROCK)) {
            return mc.world.getBlockState(pos.up()).isAir();
        }
        return false;
    }

    private PlayerEntity findNearestTarget(double range) {
        PlayerEntity closest = null;
        double minDist = range * range;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && p.getHealth() > 0 && !p.isCreative()) {
                double dist = mc.player.squaredDistanceTo(p);
                if (dist < minDist) { minDist = dist; closest = p; }
            }
        }
        return closest;
    }
    
    private EndCrystalEntity findNearestCrystal(PlayerEntity target, double range) {
        List<EndCrystalEntity> list = mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(target.getBlockPos()).expand(range), e -> true);
        if (list.isEmpty()) return null;
        return list.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(target))).orElse(null);
    }

    // --- LOGIC TOTEM (GIỮ NGUYÊN TỪ V54 CỦA BẠN) ---
    private void stopPlayerMovement() {
        mc.options.forwardKey.setPressed(false); mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false); mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        if (mc.player.isSprinting()) {
            mc.player.setSprinting(false);
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
        }
    }

    private void handleTotem() {
        long delay = totemDelay.getValue() + random.nextInt(50);
        switch (tStep) {
            case SELECT -> {
                forceSetSlot(8); mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                tStep = TotemStep.OPEN; nextTotemAction = System.currentTimeMillis() + 50;
            }
            case OPEN -> {
                if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                if (stopMove.getValue()) stopPlayerMovement();
                tStep = TotemStep.SWAP; nextTotemAction = System.currentTimeMillis() + delay + 50; 
            }
            case SWAP -> {
                if (stopMove.getValue()) stopPlayerMovement();
                int slot = findAnyTotemSlot();
                if (slot != -1) { 
                    aimAtSlot(slot); 
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 40, SlotActionType.SWAP, mc.player); 
                    tStep = TotemStep.REFILL; 
                } else reset();
                nextTotemAction = System.currentTimeMillis() + delay;
            }
            case REFILL -> {
                if (stopMove.getValue()) stopPlayerMovement();
                int slot = findTotemInStorage();
                if (slot != -1 && !isSlotTotem(44)) { 
                    aimAtSlot(slot); 
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 8, SlotActionType.SWAP, mc.player); 
                }
                tStep = TotemStep.CLOSE; nextTotemAction = System.currentTimeMillis() + delay;
            }
            case CLOSE -> { mc.setScreen(null); reset(); }
        }
    }

    private void forceSetSlot(int slotIndex) {
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true); f.setInt(inv, slotIndex);
            inv.markDirty();
        } catch (Exception e) {
            try { Field f = PlayerInventory.class.getDeclaredField("currentItem"); f.setAccessible(true); f.setInt(inv, slotIndex); }
            catch (Exception e2) { try { Field f = PlayerInventory.class.getDeclaredField("field_7545"); f.setAccessible(true); f.setInt(inv, slotIndex); } catch (Exception ignored) {} }
        }
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen screen)) return;
        Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
        double scale = mc.getWindow().getScaleFactor();
        int x = (screen.width - 176) / 2 + slot.x + 8;
        int y = (screen.height - 166) / 2 + slot.y + 8;
        GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), x * scale, y * scale);
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
        try { return mc.player.currentScreenHandler.slots.get(id).getStack().isOf(Items.TOTEM_OF_UNDYING); } catch (Exception e) { return false; }
    }
    @Override public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }
    @Override public void onTick(TickEvent.Post event) {}
    @Override public void onToggle() {}
            }
