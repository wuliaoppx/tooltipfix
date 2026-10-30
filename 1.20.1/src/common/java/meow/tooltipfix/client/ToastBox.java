package meow.tooltipfix.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.20.1 的 toast 背景修复。
 *
 * <p>1.20.1 的 {@code SystemToast} 把 {@code textures/gui/toasts.png} 当成「三段横切 + 中段每 64 像素
 * 从 u=32 重新起一张切片」来平铺。原版那张 256×256 图集本来就是按这个画法设计的（顶行 28 高、
 * 中行 16、底行 4），所以看不出问题；但 HD 材质包（例如 CozyUI 的 1024×1024）每个格子画的是一整个
 * 圆角框，平铺时每 64 像素重采一次框的左右边，就出现明显的竖直接缝，第二行还会把框底再画一遍。
 *
 * <p>这里的做法：把「一个格子」（160 单位宽 × 32 单位高）当成一整张九宫格图，按目标宽高一次画完。
 * 因为 1.20.1 的 {@code GuiGraphics.blit} 把贴图尺寸写死成 256×256 单位，格子坐标在原版和 4 倍
 * HD 图集里是一样的，不需要读实际像素尺寸就能正确采样。
 *
 * <p>模式（{@code config/tooltipfix-toast.txt}，保存即生效）：
 * <ul>
 *   <li>{@code auto}（默认）：图集实际宽度 &gt; 256 像素时用九宫格，否则完全交给原版；</li>
 *   <li>{@code fix}：强制九宫格；</li>
 *   <li>{@code vanilla}：强制原版画法（用来做 A/B 对比）；</li>
 *   <li>{@code off}：整个修复不介入。</li>
 * </ul>
 */
public final class ToastBox {
    private static final Logger LOG = LoggerFactory.getLogger("tooltipfix-toast");
    /** {@code Toast.TEXTURE}，原版写死按 256×256 单位采样。 */
    public static final ResourceLocation ATLAS = new ResourceLocation("textures/gui/toasts.png");
    private static final int UNIT = 256;
    private static final int SLOT_V = 64;
    private static final int SLOT_W = 160;
    private static final int SLOT_H = 32;

    private static String mode = null;
    private static int left = 16;
    private static int top = 8;
    private static int right = 16;
    private static int bottom = 8;
    private static long stamp = -1L;
    private static long checkedAt = 0L;
    private static Boolean hdAtlas = null;

    private ToastBox() {
    }

    /** 是否接管背景绘制。 */
    public static boolean active() {
        return "fix".equals(mode()) || ("auto".equals(mode()) && hdAtlas());
    }

    /** Logs the first draw so a headless run can prove the mixin is live. */
    private static boolean announced;

    /** 画整个背景（只在原版开始画第一行时调用一次）。 */
    public static void draw(GuiGraphics graphics, int width, int height) {
        if (!announced) {
            announced = true;
            LOG.info("toast box draw {}x{} mode={} hd={} borders={},{},{},{}",
                    width, height, mode(), hdAtlas(), left, top, right, bottom);
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        int l = Math.max(0, left);
        int r = Math.max(0, right);
        int t = Math.max(0, top);
        int b = Math.max(0, bottom);
        if (l + r > width) {
            l = width / 2;
            r = width - l;
        }
        if (t + b > height) {
            t = height / 2;
            b = height - t;
        }
        int innerW = width - l - r;
        int innerH = height - t - b;
        int midU = SLOT_W - r;
        int midV = SLOT_V + SLOT_H - b;

        // 四角，1:1
        blit(graphics, 0, 0, l, t, 0, SLOT_V, l, t);
        blit(graphics, width - r, 0, r, t, midU, SLOT_V, r, t);
        blit(graphics, 0, height - b, l, b, 0, midV, l, b);
        blit(graphics, width - r, height - b, r, b, midU, midV, r, b);
        // 上下边，只横向拉伸
        blit(graphics, l, 0, innerW, t, l, SLOT_V, SLOT_W - l - r, t);
        blit(graphics, l, height - b, innerW, b, l, midV, SLOT_W - l - r, b);
        // 左右边，只纵向拉伸
        blit(graphics, 0, t, l, innerH, 0, SLOT_V + t, l, SLOT_H - t - b);
        blit(graphics, width - r, t, r, innerH, midU, SLOT_V + t, r, SLOT_H - t - b);
        // 中间
        blit(graphics, l, t, innerW, innerH, l, SLOT_V + t, SLOT_W - l - r, SLOT_H - t - b);
    }

    private static void blit(GuiGraphics graphics, int x, int y, int w, int h,
                             int u, int v, int uw, int vh) {
        if (w <= 0 || h <= 0 || uw <= 0 || vh <= 0) {
            return;
        }
        graphics.blit(ATLAS, x, y, w, h, u, v, uw, vh, UNIT, UNIT);
    }

    /** 图集是不是 HD（宽 &gt; 256 像素）。读不到时按原版处理。 */
    private static boolean hdAtlas() {
        if (hdAtlas != null) {
            return hdAtlas;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            List<Resource> found = mc.getResourceManager().getResourceStack(ATLAS);
            if (found.isEmpty()) {
                return false;
            }
            try (InputStream in = found.get(found.size() - 1).open()) {
                NativeImage image = NativeImage.read(in);
                hdAtlas = image.getWidth() > UNIT;
                image.close();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return hdAtlas != null && hdAtlas;
    }

    /** 资源包重载后要重新判断图集尺寸。 */
    public static void reload() {
        hdAtlas = null;
        stamp = -1L;
    }

    private static String mode() {
        poll();
        return mode == null ? "auto" : mode;
    }

    private static void poll() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 250L) {
            return;
        }
        checkedAt = now;
        File file = new File(new File(Minecraft.getInstance().gameDirectory, "config"), "tooltipfix-toast.txt");
        if (!file.isFile()) {
            if (stamp != -2L) {
                stamp = -2L;
                mode = "auto";
                left = 16;
                top = 8;
                right = 16;
                bottom = 8;
            }
            return;
        }
        long modified = file.lastModified();
        if (modified == stamp) {
            return;
        }
        stamp = modified;
        mode = "auto";
        left = 16;
        top = 8;
        right = 16;
        bottom = 8;
        try {
            for (String raw : Files.readAllLines(file.toPath())) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                String key = (eq < 0 ? line : line.substring(0, eq)).trim().toLowerCase();
                String value = eq < 0 ? "" : line.substring(eq + 1).trim().toLowerCase();
                if (eq < 0) {
                    mode = key;
                } else if ("mode".equals(key)) {
                    mode = value;
                } else if ("borders".equals(key)) {
                    String[] parts = value.split("[, ]+");
                    if (parts.length == 4) {
                        left = number(parts[0], 16);
                        top = number(parts[1], 8);
                        right = number(parts[2], 16);
                        bottom = number(parts[3], 8);
                    }
                }
            }
        } catch (IOException ignored) {
            // 读不到就按默认值
        }
        if ("off".equals(mode) || "vanilla".equals(mode) || "fix".equals(mode) || "auto".equals(mode)) {
            return;
        }
        mode = "auto";
    }

    private static int number(String text, int fallback) {
        try {
            return Math.max(0, Math.min(64, Integer.parseInt(text)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
