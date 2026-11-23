package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;

/** Local test harness. Active ONLY with -Dtooltipfix.selftest.reload=true. */
public final class PackReloadSelftest {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-pack-test");
    private static long start;
    private static int step;
    private static boolean loading;
    private static boolean exiting;
    private static long miningStart;
    private static boolean miningCaptured;
    private static boolean miningReleased;
    private static boolean miningPrepared;
    private static final int[] TIMES = {12, 16, 29, 33, 46, 50, 63};
    private static final String PACK = "file/CozyUI+_v1.11.zip";

    // Screenshot reads pixels immediately. At Gui.render HEAD the HUD is not painted yet.
    // RenderSystem replays this queue at flipFrame, after HUD/Jade/toasts have all finished.
    private static void captureAfterFrame(Minecraft mc, String kind) {
        com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(new com.mojang.blaze3d.pipeline.RenderCall() {
            @Override public void execute() {
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), result -> LOG.info("{} {}", kind, result.getString()));
            }
        });
    }

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("tooltipfix.selftest.reload") || exiting) return;
        if (Boolean.getBoolean("tooltipfix.selftest.jade")) {
            if (mc.level == null) return;
            if (Boolean.getBoolean("tooltipfix.selftest.scene") && mc.screen != null) mc.setScreen(null);
        }
        if (start == 0) { start = System.currentTimeMillis(); LOG.info("TEST START"); }
        long seconds = (System.currentTimeMillis() - start) / 1000;
        if (step >= TIMES.length) {
            if (seconds >= 72 && Boolean.getBoolean("tooltipfix.selftest.exit")) { exiting = true; LOG.info("TEST EXIT"); mc.stop(); return; }
            if (mc.player != null && mc.screen == null && seconds >= 66 && !miningReleased) {
                mc.options.pauseOnLostFocus = false;
                if (miningStart == 0) { miningStart = System.currentTimeMillis(); mc.mouseHandler.grabMouse(); }
                mc.options.keyAttack.setDown(true);
                long duration = System.currentTimeMillis() - miningStart;
                if (duration >= 300 && !miningCaptured) {
                    miningCaptured = true;
                    LOG.info("TEST MINING CAPTURE duration={}ms", duration);
                    captureAfterFrame(mc, "TEST MINING SCREENSHOT");
                }
                if (duration >= 450) { mc.options.keyAttack.setDown(false); miningReleased = true; LOG.info("TEST MINING RELEASED"); }
            }
            return;
        }
        if (loading || seconds < TIMES[step]) return;
        int current = step++;
        if (current == 1 || current == 3 || current == 5) {
            boolean enable = current != 3;
            var repository = mc.getResourcePackRepository();
            repository.reload();
            var selected = new ArrayList<>(repository.getSelectedIds());
            selected.remove(PACK);
            if (enable) selected.add(PACK);
            repository.setSelected(selected);
            loading = true;
            LOG.info("TEST RELOAD step={} selected={}", current, selected);
            mc.reloadResourcePacks().whenComplete((result, error) -> {
                loading = false;
                LOG.info("TEST RELOAD COMPLETE step={} error={}", current, error == null ? "none" : error.toString());
            });
        } else {
            LOG.info("TEST CAPTURE step={} seconds={} packs={}", current, seconds, mc.getResourcePackRepository().getSelectedIds());
            captureAfterFrame(mc, "TEST SCREENSHOT");
            if (current == 6) LOG.info("TEST COMPLETE");
        }
    }
}
