package meow.tooltipfix.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 给 {@code FontRenderer} 的两个字形方法套一层「前置判断」：
 *
 * <pre>
 * private float a(char c, boolean italic) {          // func_181559_a
 *     if (HdFont.available()) return HdFont.draw(this.i, this.j, c, italic);
 *     return meow$drawVanilla(c, italic);            // 原方法原封不动搬到这里
 * }
 * public int a(char c) {                             // func_78263_a
 *     if (HdFont.available()) return HdFont.advance(c);
 *     return meow$widthVanilla(c);
 * }
 * </pre>
 *
 * <p><b>为什么要「改名 + 包装」而不是往原方法头部插代码：</b>原方法自带 StackMapTable，
 * 在头部插入分支后原表会错位，运行期直接 {@code VerifyError: Expecting a stackmap frame}。
 * 改名搬运能让原字节码（含它自己的 frame）一个字节都不动，新包装方法很小、frame 手写完全可控。</p>
 *
 * <p><b>为什么还要留原方法：</b>材质包没装（或没带 {@code font/default.json}）时必须还能用
 * 1.12.2 原生的 8px 管线，不用把原版逻辑抄一遍。</p>
 *
 * <p><b>定位方式：</b>coremod 阶段是混淆字节码，所以不认类名，靠特征：
 * 常量池里有 {@code "0123456789abcdefklmnor"}（§ 颜色码表，FontRenderer 独有），
 * 且存在 {@code a(char,boolean):float}、{@code a(char):int} 与两个 float 字段
 * {@code i}/{@code j}（= posX/posY，由 {@code bip} 的字段声明顺序确定）。</p>
 */
public final class FontRendererTransformer implements IClassTransformer {

    private static final String MARKER = "0123456789abcdefklmnor";
    private static final String HD = "meow/tooltipfix/client/HdFont";
    private static final String TITLES = "meow/tooltipfix/client/TitleFilter";
    private static final String DRAW_DESC = "(CZ)F";
    private static final String WIDTH_DESC = "(C)I";
    private static final String STRING_DESC = "(Ljava/lang/String;)I";
    private static final String STRING_INT_DESC = "(Ljava/lang/String;III)I";
    private static final String POS_X = "i";
    private static final String POS_Y = "j";

    private boolean reported;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        if ("false".equalsIgnoreCase(System.getProperty("tooltipfix.hdfont"))) {
            return basicClass;
        }
        if (!contains(basicClass, MARKER)) {
            return basicClass;
        }
        try {
            ClassReader reader = new ClassReader(basicClass);
            String owner = reader.getClassName();
            Probe probe = new Probe();
            reader.accept(probe, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            if (!probe.ok) {
                return basicClass;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            reader.accept(new Patch(owner, writer), 0);
            byte[] out = writer.toByteArray();
            if (!reported) {
                reported = true;
                System.err.println("[tooltipfix] patched HD font into " + name
                        + " [" + owner + ".a" + DRAW_DESC + " -> HdFont.draw, "
                        + owner + ".a" + WIDTH_DESC + " -> HdFont.advance, " + owner + ".a" + STRING_DESC
                        + " -> HdFont.stringWidth"
                        + (probe.hasStringInt ? ", " + owner + ".a" + STRING_INT_DESC + " -> TitleFilter" : "")
                        + "]");
            }
            return out;
        } catch (Throwable t) {
            System.err.println("[tooltipfix] failed to patch HD font in " + name
                    + ", the vanilla 8px font stays in charge");
            t.printStackTrace();
            return basicClass;
        }
    }

    /** 常量池里的 UTF8 字符串在 class 字节里是原样出现的，直接扫字节最省事。 */
    private static boolean contains(byte[] bytes, String needle) {
        byte[] m = needle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i <= bytes.length - m.length; i++) {
            for (int j = 0; j < m.length; j++) {
                if (bytes[i + j] != m[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 先扫一遍，确认目标方法与 posX/posY 字段都在，再决定要不要改字节码。 */
    private static final class Probe extends ClassVisitor {

        private boolean hasDraw;
        private boolean hasWidth;
        private boolean hasString;
        private boolean hasStringInt;
        private boolean hasPosX;
        private boolean hasPosY;
        private boolean ok;

        Probe() {
            super(Opcodes.ASM5);
        }

        @Override
        public FieldVisitor visitField(int access, String fieldName, String desc,
                                       String signature, Object value) {
            if ("F".equals(desc)) {
                if (POS_X.equals(fieldName)) {
                    hasPosX = true;
                } else if (POS_Y.equals(fieldName)) {
                    hasPosY = true;
                }
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String desc,
                                         String signature, String[] exceptions) {
            if ("a".equals(methodName)) {
                if (DRAW_DESC.equals(desc)) {
                    hasDraw = true;
                } else if (WIDTH_DESC.equals(desc)) {
                    hasWidth = true;
                } else if (STRING_DESC.equals(desc)) {
                    hasString = true;
                } else if (STRING_INT_DESC.equals(desc)) {
                    hasStringInt = true;
                }
            }
            return null;
        }

        @Override
        public void visitEnd() {
            ok = hasDraw && hasWidth && hasString && hasPosX && hasPosY;
        }
    }

    private static final class Patch extends ClassVisitor {

        private static final String DRAW_VANILLA = "meow$drawVanilla";
        private static final String WIDTH_VANILLA = "meow$widthVanilla";
        private static final String STRING_VANILLA = "meow$stringWidthVanilla";
        private static final String STRING_INT_VANILLA = "meow$drawStringIntVanilla";

        private final String owner;
        private boolean hasDraw;
        private boolean hasWidth;
        private boolean hasString;
        private boolean hasStringInt;

        Patch(String owner, ClassVisitor parent) {
            super(Opcodes.ASM5, parent);
            this.owner = owner;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String desc,
                                         String signature, String[] exceptions) {
            if ("a".equals(methodName) && DRAW_DESC.equals(desc)) {
                hasDraw = true;
                return super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                        DRAW_VANILLA, desc, signature, exceptions);
            }
            if ("a".equals(methodName) && WIDTH_DESC.equals(desc)) {
                hasWidth = true;
                return super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                        WIDTH_VANILLA, desc, signature, exceptions);
            }
            if ("a".equals(methodName) && STRING_DESC.equals(desc)) {
                hasString = true;
                return super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                        STRING_VANILLA, desc, signature, exceptions);
            }
            if ("a".equals(methodName) && STRING_INT_DESC.equals(desc)) {
                hasStringInt = true;
                return super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                        STRING_INT_VANILLA, desc, signature, exceptions);
            }
            return super.visitMethod(access, methodName, desc, signature, exceptions);
        }

        @Override
        public void visitEnd() {
            if (hasDraw) {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "a", DRAW_DESC, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "has", "(C)Z", false);
                Label vanilla = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, vanilla);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, POS_X, "F");
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, owner, POS_Y, "F");
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "draw", "(FFCZ)F", false);
                mv.visitInsn(Opcodes.FRETURN);
                mv.visitLabel(vanilla);
                mv.visitFrame(Opcodes.F_NEW, 3,
                        new Object[]{owner, Opcodes.INTEGER, Opcodes.INTEGER}, 0, new Object[0]);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, DRAW_VANILLA, DRAW_DESC, false);
                mv.visitInsn(Opcodes.FRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            if (hasWidth) {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "a", WIDTH_DESC, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "has", "(C)Z", false);
                Label vanilla = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, vanilla);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "advance", "(C)I", false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(vanilla);
                mv.visitFrame(Opcodes.F_NEW, 2,
                        new Object[]{owner, Opcodes.INTEGER}, 0, new Object[0]);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ILOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, WIDTH_VANILLA, WIDTH_DESC, false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            if (hasString) {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "a", STRING_DESC, null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "available", "()Z", false);
                Label vanilla = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, vanilla);
                // 整串都有 HD 字形才用 HD 宽度：混着原版字形时退回原版测量，保证两边一致
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "covers", "(Ljava/lang/String;)Z", false);
                mv.visitJumpInsn(Opcodes.IFEQ, vanilla);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HD, "stringWidth", STRING_DESC, false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(vanilla);
                mv.visitFrame(Opcodes.F_NEW, 2,
                        new Object[]{owner, "java/lang/String"}, 0, new Object[0]);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, STRING_VANILLA, STRING_DESC, false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            if (hasStringInt) {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "a", STRING_INT_DESC, null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, TITLES, "hidden", "(Ljava/lang/String;II)Z", false);
                Label draw = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, draw);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(draw);
                mv.visitFrame(Opcodes.F_NEW, 5,
                        new Object[]{owner, "java/lang/String", Opcodes.INTEGER, Opcodes.INTEGER,
                                Opcodes.INTEGER}, 0, new Object[0]);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitVarInsn(Opcodes.ILOAD, 4);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, STRING_INT_VANILLA, STRING_INT_DESC, false);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
            super.visitEnd();
        }
    }
}
