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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final IntegerSetting delayMs = IntegerSetting.builder()
            .id("autototem_delay")
            .displayName("Tốc độ (ms)")
            .description("Delay hành động")
            .defaultValue(100)
            .build();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự đóng túi")
            .defaultValue(true)
            .build();

    private boolean isWorking = false;
    private long lastTime = 0;
    
    // Biến lưu vị trí để aim chuột
    private int targetSwapSlot = -1;
    private int targetRefillSlot = -1;

    private enum Step {
        NONE,
        STEP_1_SELECT_SLOT,
        STEP_2_OPEN_INV,
        STEP_3_FIND_AND_AIM_SWAP,   // Tìm Totem để cứu mạng
        STEP_4_ACTION_SWAP,         // Thực hiện Swap Offhand
        STEP_5_FIND_AND_AIM_REFILL, // Tìm Totem dự trữ để Refill
        STEP_6_ACTION_REFILL,       // Thực hiện Refill vào Slot 8
        STEP_7_CLOSE
    }
    private Step currentStep = Step.NONE;

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Full Legit: Cứu mạng + Refill Slot 8 + Aim chuột");
        addSetting(delayMs);
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
        isWorking = false;
        targetSwapSlot = -1;
        targetRefillSlot = -1;
    }

    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { 
                if (packet.getEntity(mc.world) == mc.player) {
                    if (!isWorking) { 
                        isWorking = true;
                        currentStep = Step.STEP_1_SELECT_SLOT;
                        lastTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isWorking) return;

        long now = System.currentTimeMillis();
        long delay = Math.max(0, delayMs.getValue());

        switch (currentStep) {
            case STEP_1_SELECT_SLOT:
                if (now - lastTime >= 50) {
                    forceSetSlot(8); 
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(8));
                    }
                    currentStep = Step.STEP_2_OPEN_INV;
                    lastTime = now;
                }
                break;

            case STEP_2_OPEN_INV:
                if (!(mc.currentScreen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                }
                if (now - lastTime >= delay) {
                    currentStep = Step.STEP_3_FIND_AND_AIM_SWAP;
                    lastTime = now;
                }
                break;

            case STEP_3_FIND_AND_AIM_SWAP:
                // Tìm totem bất kỳ để ném vào Offhand
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        targetSwapSlot = findAnyTotemSlot(); // Tìm totem

                        if (targetSwapSlot != -1) {
                            aimAtSlot(targetSwapSlot); // Aim chuột vào đó
                            currentStep = Step.STEP_4_ACTION_SWAP;
                        } else {
                            reset(); // Hết totem thì chịu
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_4_ACTION_SWAP:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        if (targetSwapSlot != -1) {
                            int syncId = mc.player.currentScreenHandler.syncId;
                            // Button 40 = Swap to Offhand (Giống ấn F)
                            mc.interactionManager.clickSlot(syncId, targetSwapSlot, 40, SlotActionType.SWAP, mc.player);
                        }
                        // Xong phần cứu mạng, chuyển sang phần nạp đạn
                        currentStep = Step.STEP_5_FIND_AND_AIM_REFILL;
                        lastTime = now;
                    }
                }
                break;

            case STEP_5_FIND_AND_AIM_REFILL:
                // Tìm totem trong kho để bù vào Slot 8 (nếu Slot 8 đang trống hoặc không phải Totem)
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        // Kiểm tra xem Slot 8 (ID 44) có phải Totem chưa?
                        if (isSlotTotem(44)) {
                            // Nếu có rồi thì thôi, đóng túi luôn
                            if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE;
                            else reset();
                        } else {
                            // Nếu chưa có, đi tìm hàng trong kho (9-35)
                            targetRefillSlot = findTotemInStorage();
                            if (targetRefillSlot != -1) {
                                aimAtSlot(targetRefillSlot); // Aim vào hàng dự trữ
                                currentStep = Step.STEP_6_ACTION_REFILL;
                            } else {
                                // Hết hàng dự trữ
                                if (autoEsc.getValue()) currentStep = Step.STEP_7_CLOSE;
                                else reset();
                            }
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_6_ACTION_REFILL:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= delay) {
                        if (targetRefillSlot != -1) {
                            int syncId = mc.player.currentScreenHandler.syncId;
                            // Button 8 = Hotbar Slot 8 (Số 9 trên bàn phím)
                            // Hành động này sẽ Swap totem dự trữ vào Hotbar 8
                            mc.interactionManager.clickSlot(syncId, targetRefillSlot, 8, SlotActionType.SWAP, mc.player);
                        }
                        
                        if (autoEsc.getValue()) {
                            currentStep = Step.STEP_7_CLOSE;
                        } else {
                            reset(); 
                        }
                        lastTime = now;
                    }
                }
                break;

            case STEP_7_CLOSE:
                if (now - lastTime >= delay) {
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

    private void forceSetSlot(int slotIndex) {
        PlayerInventory inv = mc.player.getInventory();
        try {
            Field f = PlayerInventory.class.getDeclaredField("selectedSlot");
            f.setAccessible(true); f.setInt(inv, slotIndex);
        } catch (Exception e1) {
            try {
                Field f = PlayerInventory.class.getDeclaredField("currentItem");
                f.setAccessible(true); f.setInt(inv, slotIndex);
            } catch (Exception e2) {
                try {
                     Field f = PlayerInventory.class.getDeclaredField("field_7545");
                     f.setAccessible(true); f.setInt(inv, slotIndex);
                } catch (Exception ignored) {}
            }
        }
    }

    private void aimAtSlot(int slotId) {
        if (!(mc.currentScreen instanceof InventoryScreen)) return;
        try {
            InventoryScreen screen = (InventoryScreen) mc.currentScreen;
            Slot slot = mc.player.currentScreenHandler.slots.get(slotId);
            
            // Check an toàn: Chỉ aim nếu đúng là Totem
            if (slot.getStack().getItem() != Items.TOTEM_OF_UNDYING) return;

            int guiLeft = (screen.width - 176) / 2;
            int guiTop = (screen.height - 166) / 2;
            int targetX = guiLeft + slot.x + 8;
            int targetY = guiTop + slot.y + 8;

            int jitterX = ThreadLocalRandom.current().nextInt(-3, 4);
            int jitterY = ThreadLocalRandom.current().nextInt(-3, 4);
            double scale = mc.getWindow().getScaleFactor();
            
            GLFW.glfwSetCursorPos(mc.getWindow().getHandle(), (targetX + jitterX) * scale, (targetY + jitterY) * scale);
        } catch (Exception ignored) {}
    }

    // Tìm Totem bất kỳ (Ưu tiên hotbar để swap cho nhanh)
    private int findAnyTotemSlot() {
        for (int i = 36; i <= 44; i++) if (isSlotTotem(i)) return i;
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }

    // Chỉ tìm Totem trong kho (9-35) để Refill xuống Hotbar
    private int findTotemInStorage() {
        for (int i = 9; i <= 35; i++) if (isSlotTotem(i)) return i;
        return -1;
    }

    private boolean isSlotTotem(int id) {
        try {
            ItemStack stack = mc.player.currentScreenHandler.slots.get(id).getStack();
            return stack.getItem() == Items.TOTEM_OF_UNDYING;
        } catch (Exception e) {
            return false;
        }
    }
}
