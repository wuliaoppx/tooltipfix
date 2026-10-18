package meow.tooltipfix.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 把 {@code GuiScreen.drawHoveringText(List,int,int)} 的整个方法体换成 {@code Hooks.tooltip}。
 *
 * <p><b>为什么必须从调用点换，而不能继续嗅探渐变：</b>1.12.2 的 tooltip 底部和描边是 9 次
 * {@code drawGradientRect}。实测（{@code -Dtooltipfix.debug=true} 的日志）这 9 次调用一次都没进
 * {@code Hooks.gradient}，而同一帧里 {@code drawDefaultBackground} 的那次渐变进了——说明
 * {@code drawHoveringText} 里画渐变用的是未映射的 {@code invokevirtual GuiScreen.a(IIIIII)V}，
 * 运行期解析到的不是我们补丁过的 {@code Gui.func_73733_a}。既然不知道它最终落在哪，就直接把
 * 「画 tooltip」这一整段接管，顺便还能拿到文本行与鼠标坐标（1.21.2 也是这么画的）。
 *
 * <p><b>怎么定位类和方法：</b>同样不认类名（coremod 阶段拿到的是混淆字节码）。这里认「父类叫
 * {@code Gui}（或它的混淆名）」+「存在非静态的 {@code (Ljava/util/List;II)V} 方法」，
 * 在 1.12.2 里这个组合只有 {@code GuiScreen} 一个。
 *
 * <p><b>必须同时接管两个重载：</b>{@code drawHoveringText(List,int,int)} 和 Forge 加的
 * {@code drawHoveringText(List,int,int,FontRenderer)}。Forge 把 {@code renderToolTip} 改成了调四参
 * 版本（为了支持物品自定义字体），所以只换三参时，真实的物品 tooltip 一次都不会进 hook——实机表现
 * 就是「偶尔（只有少数走三参的调用点）才变成材质包样式」。
 */
public final class GuiScreenTransformer implements IClassTransformer {

    /** srg（运行期）类名。 */
    private static final String GUI = "net/minecraft/client/gui/Gui";
    /** 纯混淆类名（1.12.2）。 */
    private static final String GUI_OBF = "bir";
    /** Hooks 内部名。 */
    private static final String HOOKS = "meow/tooltipfix/client/Hooks";
    /** Hooks.tooltip 的描述符（参数里带 MC 类型，用 srg 名写，FML 的反混淆不会把它当混淆名改掉）。 */
    private static final String HOOK_DESC = "(Lnet/minecraft/client/gui/GuiScreen;Ljava/util/List;II)V";
    /**
     * Forge 给 {@code GuiScreen} 加的四参重载
     * {@code drawHoveringText(List,int,int,FontRenderer)}。第四个参数是混淆过的
     * {@code FontRenderer}，描述符里不能写死名字，所以用 {@link #tooltipArity} 结构匹配。
     */
    private static final String HOOK_DESC_FONT =
            "(Lnet/minecraft/client/gui/GuiScreen;Ljava/util/List;IILnet/minecraft/client/gui/FontRenderer;)V";

    private boolean reported;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        ClassReader reader;
        try {
            reader = new ClassReader(basicClass);
        } catch (Throwable t) {
            return basicClass;
        }
        String superName = reader.getSuperName();
        if (!GUI.equals(superName) && !GUI_OBF.equals(superName)) {
            return basicClass;
        }
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            TooltipVisitor visitor = new TooltipVisitor(writer, reader.getClassName());
            reader.accept(visitor, 0);
            if (visitor.replaced.isEmpty()) {
                return basicClass;
            }
            report("patched tooltip drawing in " + name + " " + visitor.replaced);
            byte[] patched = writer.toByteArray();
            dump(name, patched);
            return patched;
        } catch (Throwable t) {
            System.err.println("[tooltipfix] failed to transform " + name + ", tooltips stay vanilla");
            t.printStackTrace();
            return basicClass;
        }
    }

    /** {@code -Dtooltipfix.dump=true} 时把改造后的字节码落盘，便于 javap 对照。 */
    private static void dump(String name, byte[] bytes) {
        if (!Boolean.getBoolean("tooltipfix.dump")) {
            return;
        }
        try {
            java.io.File dir = new java.io.File("logs/tooltipfix-dump");
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, name + ".class");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(out)) {
                stream.write(bytes);
            }
            System.err.println("[tooltipfix] dumped transformed " + name + " -> " + out.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[tooltipfix] dump failed for " + name + ": " + t);
        }
    }

    private void report(String message) {
        if (reported) {
            return;
        }
        reported = true;
        System.err.println("[tooltipfix] " + message);
    }

    private static final class TooltipVisitor extends ClassVisitor {

        private final String owner;
        private final java.util.List<String> replaced = new java.util.ArrayList<String>();

        TooltipVisitor(ClassVisitor parent, String owner) {
            super(Opcodes.ASM5, parent);
            this.owner = owner;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String desc,
                                         String signature, String[] exceptions) {
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            int arity = isStatic ? 0 : tooltipArity(desc);
            if (arity != 3 && arity != 4) {
                return super.visitMethod(access, methodName, desc, signature, exceptions);
            }
            replaced.add(methodName + desc);
            MethodVisitor mv = super.visitMethod(access, methodName, desc, signature, exceptions);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            if (arity == 4) {
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "tooltip", HOOK_DESC_FONT, false);
                mv.visitMaxs(5, 5);
            } else {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "tooltip", HOOK_DESC, false);
                mv.visitMaxs(4, 4);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitEnd();
            return null;
        }
    }

    /**
     * 数一下「tooltip 形状」的方法有几个参数：{@code (List,int,int)} 返回 3，
     * {@code (List,int,int,FontRenderer)} 返回 4，其它形状返回 0。
     */
    static int tooltipArity(String desc) {
        if (desc == null || !desc.startsWith("(Ljava/util/List;II") || !desc.endsWith(")V")) {
            return 0;
        }
        String params = desc.substring(1, desc.length() - 2);
        int count = 0;
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == '[') {
                i++;
                continue;
            }
            if (c == 'L') {
                int end = params.indexOf(';', i);
                if (end < 0) {
                    return 0;
                }
                i = end + 1;
            } else {
                i++;
            }
            count++;
        }
        return count;
    }
}
