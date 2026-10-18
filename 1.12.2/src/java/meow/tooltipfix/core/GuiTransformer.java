package meow.tooltipfix.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 把 {@code net.minecraft.client.gui.Gui} 的三个绘制原语换成 {@code Hooks} 的同名入口。
 *
 * <p><b>关键前提：这个 transformer 拿到的是「还没被 FML 反混淆」的字节码。</b>
 * 1.12.2 里 coremod 注册的 transformer 全部排在 {@code FMLDeobfuscatingRemapper}（它由
 * {@code FMLTweaker} 在最后注册）之前，所以方法名还是混淆名：
 * {@code drawTexturedModalRect} = {@code b(IIIIII)V}、{@code drawGradientRect} = {@code a(IIIIII)V}、
 * {@code drawRect} = {@code a(IIIII)V}，zLevel 字段叫 {@code e}。
 * 只按 srg 名匹配会**一个都匹配不上**（第一次进游戏的实测日志就证明了这点）。
 *
 * <p>因此这里**完全不认名字**，改成按「描述符 + 静态性 + 可见性」定位方法：
 * <ul>
 *   <li>{@code (IIIIII)V} 且非静态且 public ⇒ blit —— Gui 里只有这样两个方法，另一个是 protected</li>
 *   <li>{@code (IIIIII)V} 且非静态且 protected ⇒ gradient</li>
 *   <li>{@code (IIIII)V} 且 static ⇒ rect</li>
 * </ul>
 * zLevel 字段则取「类里唯一的非静态 float 字段」。这样无论字节码是混淆名还是 srg 名都能命中，
 * 而且不依赖 FML 的映射 API（那些 API 的映射方向在本版本里很容易搞反）。
 *
 * <p><b>为什么整段替换方法体</b>：这三个方法的原版实现都是直线代码（无分支、无 try/catch），
 * 换掉之后方法里一条跳转都没有，于是不需要 StackMapTable，{@code COMPUTE_MAXS} 就够，
 * 不必用 {@code COMPUTE_FRAMES}（那会在 coremod 阶段触发 {@code getCommonSuperClass} 去加载 MCC 类）。
 *
 * <p>{@code GETFIELD} 的 owner 用 {@code ClassReader.getClassName()}（字节码里真实的名字），
 * 稍后 FML 的反混淆会把 {@code bir.e} 自动改写成 {@code net/minecraft/client/gui/Gui.field_73735_i}。
 */
public final class GuiTransformer implements IClassTransformer {

    /** srg（运行期）类名。 */
    private static final String GUI = "net/minecraft/client/gui/Gui";
    /** 纯混淆类名（1.12.2），开发环境不会出现。 */
    private static final String GUI_OBF = "bir";
    /** Hooks 内部名。 */
    private static final String HOOKS = "meow/tooltipfix/client/Hooks";

    private static final String SIX_INTS = "(IIIIII)V";
    private static final String FIVE_INTS = "(IIIII)V";

    private static final int PUBLIC = Opcodes.ACC_PUBLIC;
    private static final int PROTECTED = Opcodes.ACC_PROTECTED;
    private static final int STATIC = Opcodes.ACC_STATIC;

    private boolean reported;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        boolean candidate = GUI.equals(transformedName) || GUI.equals(name) || GUI_OBF.equals(name);
        if (!candidate) {
            return basicClass;
        }
        try {
            ClassReader reader = new ClassReader(basicClass);
            String zField = findZField(reader);
            if (zField == null) {
                report("Gui has no non-static float field; coremod disabled for this class");
                return basicClass;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            GuiVisitor visitor = new GuiVisitor(writer, reader.getClassName(), zField);
            reader.accept(visitor, 0);
            if (visitor.replaced == 0) {
                report("none of Gui's drawing primitives matched; coremod disabled for this class");
                return basicClass;
            }
            report("patched Gui (zField=" + zField + ", methods=" + visitor.replaced + ", names="
                    + visitor.replacedNames + ")");
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[tooltipfix] failed to transform Gui, falling back to vanilla drawing");
            t.printStackTrace();
            return basicClass;
        }
    }

    /**
     * 找 zLevel：优先 srg 名 {@code field_73735_i}，否则退化成「唯一的非静态 float 字段」。
     * Gui 里只有这一个实例字段，所以这个退化规则在 1.12.2 是精确的。
     */
    private static String findZField(ClassReader reader) {
        final String[] byName = {null};
        final String[] byType = {null};
        final int[] floatFields = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public FieldVisitor visitField(int access, String fieldName, String desc,
                                           String signature, Object value) {
                if ("F".equals(desc) && (access & STATIC) == 0) {
                    floatFields[0]++;
                    byType[0] = fieldName;
                    if ("field_73735_i".equals(fieldName)) {
                        byName[0] = fieldName;
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (byName[0] != null) {
            return byName[0];
        }
        return floatFields[0] == 1 ? byType[0] : null;
    }

    private void report(String message) {
        if (reported) {
            return;
        }
        reported = true;
        System.err.println("[tooltipfix] " + message);
    }

    private static final class GuiVisitor extends ClassVisitor {

        private final String owner;
        private final String zField;
        private int replaced;
        private final StringBuilder replacedNames = new StringBuilder();

        GuiVisitor(ClassVisitor parent, String owner, String zField) {
            super(Opcodes.ASM5, parent);
            this.owner = owner;
            this.zField = zField;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String desc,
                                         String signature, String[] exceptions) {
            boolean isStatic = (access & STATIC) != 0;
            boolean isPublic = (access & PUBLIC) != 0;
            boolean isProtected = (access & PROTECTED) != 0;
            if (SIX_INTS.equals(desc) && !isStatic && isPublic) {
                return emitBlit(access, methodName, desc, signature, exceptions);
            }
            if (SIX_INTS.equals(desc) && !isStatic && isProtected) {
                return emitGradient(access, methodName, desc, signature, exceptions);
            }
            if (FIVE_INTS.equals(desc) && isStatic) {
                return emitRect(access, methodName, desc, signature, exceptions);
            }
            return super.visitMethod(access, methodName, desc, signature, exceptions);
        }

        /** {@code Hooks.blit(this.zLevel, x, y, u, v, width, height)} */
        private MethodVisitor emitBlit(int access, String methodName, String desc,
                                       String signature, String[] exceptions) {
            touch(methodName);
            MethodVisitor mv = super.visitMethod(access, methodName, desc, signature, exceptions);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, zField, "F");
            for (int i = 1; i <= 6; i++) {
                mv.visitVarInsn(Opcodes.ILOAD, i);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "blit", "(FIIIIII)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(8, 7);
            mv.visitEnd();
            return null;
        }

        /** {@code Hooks.gradient(this.zLevel, left, top, right, bottom, colorFrom, colorTo)} */
        private MethodVisitor emitGradient(int access, String methodName, String desc,
                                           String signature, String[] exceptions) {
            touch(methodName);
            MethodVisitor mv = super.visitMethod(access, methodName, desc, signature, exceptions);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, zField, "F");
            for (int i = 1; i <= 6; i++) {
                mv.visitVarInsn(Opcodes.ILOAD, i);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "gradient", "(FIIIIII)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(8, 7);
            mv.visitEnd();
            return null;
        }

        /** {@code Hooks.rect(left, top, right, bottom, color)}（原版这个方法的 z 硬编码为 0） */
        private MethodVisitor emitRect(int access, String methodName, String desc,
                                       String signature, String[] exceptions) {
            touch(methodName);
            MethodVisitor mv = super.visitMethod(access, methodName, desc, signature, exceptions);
            mv.visitCode();
            for (int i = 0; i <= 4; i++) {
                mv.visitVarInsn(Opcodes.ILOAD, i);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "rect", "(IIIII)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(5, 5);
            mv.visitEnd();
            return null;
        }

        private void touch(String methodName) {
            replaced++;
            if (replacedNames.length() > 0) {
                replacedNames.append(',');
            }
            replacedNames.append(methodName);
        }
    }
}
