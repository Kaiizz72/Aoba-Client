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
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự động đóng túi đồ sau khi refill")
            .defaultValue(true)
            .build();

    // Trạng thái
    private boolean isRefilling = false;
    private long lastTime = 0;
    
    // Các bước thực hiện
    private enum Step {
        NONE,
        WAIT_SWAP_OFFHAND,
        WAIT_OPEN_INV,
        WAIT_REFILL,
        WAIT_CLOSE_INV
    }
    private Step currentStep = Step.NONE;

    // Delay config (ms) - Chỉnh số này nếu mạng lag
    private final long DELAY_SWAP = 100;   // Delay trước khi swap offhand
    private final long DELAY_OPEN = 200;   // Delay trước khi mở túi
    private final long DELAY_CLICK = 150;  // Delay chờ túi mở hẳn rồi mới click
    private final long DELAY_CLOSE = 150;  // Delay sau khi click rồi mới đóng

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem Pop -> Swap Offhand -> Open Inv -> Refill -> Close Inv");
        addSetting(autoEsc);
    }

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

    // 1. BẮT GÓI TIN TOTEM POP
    @Override
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null || mc.world == null) return;
        
        if (event.GetPacket() instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35) { // 35 = Totem Pop animation
                if (packet.getEntity(mc.world) == mc.player) {
                    // Kích hoạt quy trình
                    isRefilling = true;
                    currentStep = Step.WAIT_SWAP_OFFHAND;
                    lastTime = System.currentTimeMillis();
                }
            }
        }
    }

    // 2. XỬ LÝ LOGIC THEO TỪNG TICK
    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();

        switch (currentStep) {
            case WAIT_SWAP_OFFHAND:
                if (now - lastTime >= DELAY_SWAP) {
                    // Thực hiện Swap slot 8 (đang có totem) vào Offhand
                    swapSlot8ToOffhand();
                    
                    // Chuyển sang bước chờ mở túi
                    currentStep = Step.WAIT_OPEN_INV;
                    lastTime = now;
                }
                break;

            case WAIT_OPEN_INV:
                if (now - lastTime >= DELAY_OPEN) {
                    // Nếu slot 8 đã trống (do swap đi rồi), thì mở túi để refill
                    // Nếu slot 8 vẫn còn đồ thì thôi, coi như lỗi hoặc đã đầy
                    if (mc.player.getInventory().getStack(8).isEmpty()) {
                        mc.setScreen(new InventoryScreen(mc.player));
                        currentStep = Step.WAIT_REFILL;
                    } else {
                        reset(); // Slot 8 không trống, hủy kèo
                    }
                    lastTime = now;
                }
                break;

            case WAIT_REFILL:
                // Chỉ refill khi màn hình ĐÃ LÀ InventoryScreen
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_CLICK) {
                        boolean success = refillSlot8();
                        
                        if (success && autoEsc.getValue()) {
                            currentStep = Step.WAIT_CLOSE_INV;
                        } else {
                            reset(); // Refill xong mà ko auto close hoặc lỗi
                        }
                        lastTime = now;
                    }
                } else {
                    // Nếu vì lý do gì đó mà túi bị đóng mất tiêu
                    if (now - lastTime > 2000) reset(); // Timeout
                }
                break;

            case WAIT_CLOSE_INV:
                if (now - lastTime >= DELAY_CLOSE) {
                    mc.setScreen(null);
                    reset(); // Hoàn thành
                }
                break;
                
            default:
                break;
        }
    }

    @Override
    public void onTick(TickEvent.Post event) {}

    // --- CÁC HÀM XỬ LÝ CLICK ---

    private void swapSlot8ToOffhand() {
        // Slot Offhand ID trong Inventory Container là 45
        // Button 8 đại diện cho Hotbar Slot 9 (Index 8)
        // Lệnh này: Swap item tại Slot 45 với Hotbar Slot 8
        int syncId = mc.player.currentScreenHandler.syncId;
        mc.interactionManager.clickSlot(syncId, 45, 8, SlotActionType.SWAP, mc.player);
    }

    private boolean refillSlot8() {
        PlayerInventory inv = mc.player.getInventory();
        
        // Tìm totem ở kho chính (9 -> 35)
        int sourceSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                sourceSlot = i;
                break;
            }
        }

        if (sourceSlot != -1) {
            // Lệnh này: Swap item tại sourceSlot với Hotbar Slot 8
            // Item từ kho sẽ bay xuống slot 8
            int syncId = mc.player.currentScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        
        return false; // Không tìm thấy totem dự trữ
    }
}
