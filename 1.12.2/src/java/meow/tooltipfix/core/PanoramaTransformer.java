package meow.tooltipfix.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 把 {@code GuiMainMenu} 里那个「渲染全景图」的私有方法整个换成 {@link meow.tooltipfix.client.Panorama#render}。
 *
 * <p><b>定位方式：</b>不认类名（coremod 阶段拿到的是混淆字节码）。三个条件同时成立才算命中：
 * <ol>
 *   <li>父类是 {@code GuiScreen}（srg 名或混淆名 {@code blk}）；</li>
 *   <li>常量池里有 {@code textures/gui/title/background/panorama_0.png} 这个字符串（主菜单独有）；</li>
 *   <li>存在一个 <b>private</b> 的 {@code (IIF)V} 方法——即 {@code func_73971_c(int,int,float)}；
 *       同名的 {@code func_73863_a(IIF)V} 是 public 的，靠访问标志区分。</li>
 * </ol>
 *
 * <p>被换掉的方法体只做两件事：把 partialTicks 传进去，然后返回。渲染逻辑全在 Java 侧，
 * 这样不用在字节码里拼 GL 调用，也不用给 {@code GuiMainMenu} 加字段。
 */
public final class PanoramaTransformer implements IClassTransformer {

    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String GUI_OBF = "bir";
    private static final String GUI_SCREEN = "net/minecraft/client/gui/GuiScreen";
    private static final String GUI_SCREEN_OBF = "blk";
    /** 主菜单独有的常量池字符串（用于确认这是主菜单而不是别的界面）。 */
    private static final String MARKER = "textures/gui/title/background/panorama_0.png";
    private static final String PANORAMA = "meow/tooltipfix/client/Panorama";
    private static final String RENDER_DESC = "(F)V";

    private boolean reported;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        if (!containsMarker(basicClass)) {
            return basicClass;
        }
        ClassReader reader;
        try {
            reader = new ClassReader(basicClass);
        } catch (Throwable t) {
            return basicClass;
        }
        String superName = reader.getSuperName();
        if (!GUI_SCREEN.equals(superName) && !GUI_SCREEN_OBF.equals(superName)) {
            return basicClass;
        }
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            PanoramaVisitor visitor = new PanoramaVisitor(writer);
            reader.accept(visitor, 0);
            if (!visitor.replaced) {
                return basicClass;
            }
            if (!reported) {
                reported = true;
                System.err.println("[tooltipfix] patched panorama rendering in " + name
                        + " (replaced a private (IIF)V method with Panorama.render)");
            }
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[tooltipfix] failed to transform panorama in " + name
                    + ", the background stays vanilla");
            t.printStackTrace();
            return basicClass;
        }
    }

    /** 常量池里的 UTF8 字符串在 class 字节里是原样出现的，直接扫字节最省事。 */
    private static boolean containsMarker(byte[] bytes) {
        byte[] marker = MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - marker.length; i++) {
            for (int j = 0; j < marker.length; j++) {
                if (bytes[i + j] != marker[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static final class PanoramaVisitor extends ClassVisitor {

        private boolean replaced;

        PanoramaVisitor(ClassVisitor parent) {
            super(Opcodes.ASM5, parent);
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String desc,
                                         String signature, String[] exceptions) {
            boolean isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (!isPrivate || isStatic || !"(IIF)V".equals(desc)) {
                return super.visitMethod(access, methodName, desc, signature, exceptions);
            }
            replaced = true;
            MethodVisitor mv = super.visitMethod(access, methodName, desc, signature, exceptions);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.FLOAD, 3);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PANORAMA, "render", RENDER_DESC, false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 4);
            mv.visitEnd();
            return null;
        }
    }
}
