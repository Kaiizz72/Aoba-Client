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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket; // Import mới để giả lập ấn F
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos; // Import mới
import net.minecraft.util.math.Direction; // Import mới

public class AutoTotem extends Module implements ReceivePacketListener, TickListener {

    private final MinecraftClient mc = MinecraftClient.getInstance();

    private final BooleanSetting autoEsc = BooleanSetting.builder()
            .id("autototem_autoesc")
            .displayName("Auto ESC")
            .description("Tự động đóng túi đồ sau khi refill")
            .defaultValue(true)
            .build();

    // Trạng thái
    private boolean isRefilling = false;
    private long lastTime = 0;
    
    private enum Step {
        NONE,
        WAIT_SWAP_OFFHAND,
        WAIT_OPEN_INV,
        WAIT_REFILL,
        WAIT_CLOSE_INV
    }
    private Step currentStep = Step.NONE;

    // Delay config (ms) - Bạn có thể chỉnh lại cho hợp mạng
    private final long DELAY_SWAP = 50;    // Cuộn và ấn F nhanh hơn chút
    private final long DELAY_OPEN = 150;   // Chờ server nhận diện đã swap tay
    private final long DELAY_CLICK = 150;  // Mở túi xong chờ load
    private final long DELAY_CLOSE = 150;  // Refill xong chờ đóng

    public AutoTotem() {
        super("AutoTotem");
        setCategory(Category.of("Combat"));
        setDescription("Totem Pop -> Cuộn Slot 8 -> Ấn F -> Mở túi -> Refill Slot 8 -> Đóng túi");
        addSetting(autoEsc);
    }

    @Override
    public void onToggle() {}

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
            if (packet.getStatus() == 35) { // 35 = Totem Pop
                if (packet.getEntity(mc.world) == mc.player) {
                    isRefilling = true;
                    currentStep = Step.WAIT_SWAP_OFFHAND;
                    lastTime = System.currentTimeMillis();
                }
            }
        }
    }

    // 2. XỬ LÝ LOGIC
    @Override
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || !isRefilling) return;

        long now = System.currentTimeMillis();

        switch (currentStep) {
            case WAIT_SWAP_OFFHAND:
                if (now - lastTime >= DELAY_SWAP) {
                    // Logic mới: Cuộn slot 8 + Ấn F
                    performSlot8SwapF();
                    
                    currentStep = Step.WAIT_OPEN_INV;
                    lastTime = now;
                }
                break;

            case WAIT_OPEN_INV:
                if (now - lastTime >= DELAY_OPEN) {
                    // Kiểm tra xem slot 8 đã trống chưa (do đã ấn F đẩy đi rồi)
                    if (mc.player.getInventory().getStack(8).isEmpty()) {
                        mc.setScreen(new InventoryScreen(mc.player));
                        currentStep = Step.WAIT_REFILL;
                    } else {
                        // Nếu slot 8 vẫn còn đồ nghĩa là ấn F xịt hoặc mạng lag
                        // Thử lại hoặc reset. Ở đây ta reset để tránh lỗi.
                        reset(); 
                    }
                    lastTime = now;
                }
                break;

            case WAIT_REFILL:
                if (mc.currentScreen instanceof InventoryScreen) {
                    if (now - lastTime >= DELAY_CLICK) {
                        boolean success = refillSlot8(); // Lấy đồ trong kho đắp vào slot 8
                        
                        if (success && autoEsc.getValue()) {
                            currentStep = Step.WAIT_CLOSE_INV;
                        } else {
                            reset();
                        }
                        lastTime = now;
                    }
                } else {
                    if (now - lastTime > 2000) reset();
                }
                break;

            case WAIT_CLOSE_INV:
                if (now - lastTime >= DELAY_CLOSE) {
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

    // --- CÁC HÀM XỬ LÝ (LOGIC MỚI) ---

    // Thay thế logic cũ bằng logic: Chọn slot 8 -> Gửi packet phím F
    private void performSlot8SwapF() {
        // 1. Cuộn chuột tới slot 8 (Index trong code là 8, tức là ô thứ 9 trên hotbar)
        mc.player.getInventory().selectedSlot = 8;
        
        // 2. Gửi packet giả lập hành động ấn phím "Swap Item With Offhand" (Phím F mặc định)
        // Hành động này sẽ đẩy item đang cầm (ở slot 8) sang tay trái
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, 
                BlockPos.ORIGIN, 
                Direction.DOWN
            ));
        }
    }

    private boolean refillSlot8() {
        PlayerInventory inv = mc.player.getInventory();
        int sourceSlot = -1;
        
        // Tìm totem trong kho chính (9 -> 35)
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                sourceSlot = i;
                break;
            }
        }

        if (sourceSlot != -1) {
            // Dùng SWAP: Click vào totem trong kho, ấn nút số 9 (index 8)
            // Totem sẽ bay xuống slot 8 (đang trống do nãy đã ấn F đẩy đi rồi)
            int syncId = mc.player.currentScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, sourceSlot, 8, SlotActionType.SWAP, mc.player);
            return true;
        }
        
        return false;
    }
}
