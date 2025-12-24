package net.aoba.module.modules.combat;

import net.aoba.Aoba;
import net.aoba.event.events.ReceivePacketEvent;
import net.aoba.event.events.TickEvent;
import net.aoba.event.listeners.ReceivePacketListener;
import net.aoba.event.listeners.TickListener;
import net.aoba.module.Category;
import net.aoba.module.Module;
import net.aoba.settings.types.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom; // Thư viện tạo ngẫu nhiên

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự động đóng túi sau khi làm xong")
            .defaultValue(true)
            .build();

    private boolean isRefilling = false;
    private long lastTime = 0;
    
    private enum Step {
        NONE,
        STEP_1_SELECT_SLOT_8,   // Cuộn Slot 8
        STEP_2_OPEN_INV,        // Mở E
        STEP_3_AIM_AND_PICKUP,  // Vẩy chuột vào Totem + Cầm lên
        STEP_4_AIM_AND_OFFHAND, // Vẩy chuột vào Offhand + Thả xuống
        STEP_5_REFILL_HOTBAR,   // Bù hàng
        STEP_6_CLOSE_INV        // Đóng E
    }
    private Step currentStep = Step.NONE;

    // Delay: 120ms - 150ms là tốc độ chuẩn của con người khi tryhard
    private final long DELAY_COMMON = 130; 

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Full Legit: Cuộn -> Mở E -> Aim chuột (có lệch) -> Refill");
        addSetting(autoEsc);
    }

    @Override public void onToggle() {}

    @Override
    public void onEnable() {
        Aoba.getInstance().eventManager.AddListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.AddListener(TickListener.class, this);
        reset();
    }

    @Override
    public void onDisable() {
        Aoba.getInstance().eventManager.RemoveListener(ReceivePacketListener.class, this);
        Aoba.getInstance().eventManager.RemoveListener(TickListener.class, this);
        reset();
    }

    private void reset() {
        currentStep = Step.NONE;
        isRefilling = false;
    }

    // --- LOGIC DI CHUYỂN CHUỘT (HUMANIZED) ---
    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;
        
        InventoryScreen screen = (InventoryScreen) mc.currentScreen;
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        try {
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            
            // Tính toán vị trí cơ bản
            int guiWidth = 176;
            int guiHeight = 166;
            int winWidth = mc.getWindow().getScaledWidth();
            int winHeight = mc.getWindow().getScaledHeight();
            int guiLeft = (winWidth - guiWidth) / 2;
            int guiTop = (winHeight - guiHeight) / 2;
            
            // --- HUMANIZER: TẠO ĐỘ LỆCH NGẪU NHIÊN ---
            // Thay vì aim vào giữa (cộng 8), ta cộng thêm một khoảng random từ -4 đến +4 pixel
            // Giúp chuột không bị "cứng" như robot
            int jitterX = ThreadLocalRandom.current().nextInt(-4, 5);
            int jitterY = ThreadLocalRandom.current().nextInt(-4, 5);

            int targetX = guiLeft + slot.x + 8 + jitterX;
            int targetY = guiTop + slot.y + 8 + jitterY;
            
            // Set vị trí chuột
            double scaleFactor = mc.getWindow().getScaleFactor();
            GLFW.glfwSetCursorPos(
                mc.getWindow().getHandle(), 
                targetX * scaleFactor, 
                targetY * scaleFactor
            );
            
        } catch (Exception e) {}
    }

    // --- LOGIC CHÍNH ---
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isRefilling) { 
                        isRefilling = true;
                        currentStep = Step.STEP_1_SELECT_SLOT_8;
                        lastTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();

        switch (currentStep) {
            case STEP_1_SELECT_SLOT_8:
                // Bước 1: Cuộn chuột sang Slot 8
                if (now - lastTime >= 50) {
                    setSlotVisual(8);
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                    }
                    currentStep = Step.STEP_2_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_2_OPEN_INV:
                // Bước 2: Mở túi (E)
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                
                if (now - lastTime >= DELAY_COMMON) {
                    currentStep = Step.STEP_3_AIM_AND_PICKUP;
                    lastTime = now;
                }
                break;

            case STEP_3_AIM_AND_PICKUP:
                // Bước 3: Aim vào Totem + Click
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        
                        // Aim vào Slot 44 (Hotbar 8)
                        aimAtSlot(44);
                        
                        // Click chuẩn
                        mc.interactionManager.clickSlot(syncId, 44, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_4_AIM_AND_OFFHAND;
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_AIM_AND_OFFHAND:
                // Bước 4: Aim vào Offhand + Click
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        int syncId = mc.player.currentScreenHandler.syncId;
                        
                        // Aim vào Slot 45 (Offhand)
                        aimAtSlot(45);
                        
                        // Click chuẩn
                        mc.interactionManager.clickSlot(syncId, 45, 0, SlotActionType.PICKUP, mc.player);
                        
                        currentStep = Step.STEP_5_REFILL_HOTBAR;
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_REFILL_HOTBAR:
                // Bước 5: Bù hàng
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_COMMON) {
                        // Nếu thích ngầu: Aim chuột vào totem trong kho trước khi swap
                        int totemSlot = findTotemSlot();
                        if (totemSlot != -1) aimAtSlot(totemSlot);

                        boolean success = refillSlot8(); 
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_6_CLOSE_INV;
                        } else {
                            reset(); 
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_6_CLOSE_INV:
                // Bước 6: Đóng túi
                if (now - lastTime >= DELAY_COMMON) {
                    mc.setScreen(null);
                    reset();
                }
                break;
                
            default:
                break;
        }
    }

    @Override
    public void onTick(TickEvent.Post event) {}

    // --- HELPER ---

    private int findTotemSlot() {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }

    private void setSlotVisual(int slotIndex) {
        PlayerInventory inv = mc.player.getInventory();
        boolean success = false;
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true);
            f.setInt(inv, slotIndex);
            success = true;
        } catch (Exception ignored) {}

        if (!success) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true);
                f.setInt(inv, slotIndex);
            } catch (Exception ignored) {}
        }
    }

    private boolean refillSlot8() {
        int sourceSlot = findTotemSlot();
        if (sourceSlot != -1) {
            int syncId = mc.player.currentScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        return false;
    }
}
