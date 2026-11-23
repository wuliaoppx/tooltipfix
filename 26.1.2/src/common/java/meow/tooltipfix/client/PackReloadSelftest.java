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
    private static final int[] TIMES = {12, 16, 29, 33, 46, 50, 63};
    private static final String PACK = "file/CozyUI+_v1.11.zip";

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("tooltipfix.selftest.reload")) return;
        if (start == 0) { start = System.currentTimeMillis(); LOG.info("TEST START"); }
        long seconds = (System.currentTimeMillis() - start) / 1000;
        if (step >= TIMES.length || loading || seconds < TIMES[step]) return;
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
            Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), result -> LOG.info("TEST SCREENSHOT {}", result.getString()));
            if (current == 6) LOG.info("TEST COMPLETE");
        }
    }
}
