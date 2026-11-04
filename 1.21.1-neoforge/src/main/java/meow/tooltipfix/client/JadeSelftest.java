package meow.tooltipfix.client;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Debug only: pins the player's pitch downwards so the block under the crosshair keeps a Jade
 * tooltip on screen, which makes screenshots comparable. Enabled with
 * {@code -Dtooltipfix.selftest.jade=true}; never active in normal play.
 */
public final class JadeSelftest {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-selftest");
    private static int calls;

    private JadeSelftest() {
    }

    public static void tick(Minecraft mc) {
        if (!Boolean.getBoolean("tooltipfix.selftest.jade")) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        mc.player.setXRot(80.0F);
        if (calls < 3) {
            calls++;
            LOG.info("jade selftest: pitch pinned to {}", mc.player.getXRot());
        }
    }
}
