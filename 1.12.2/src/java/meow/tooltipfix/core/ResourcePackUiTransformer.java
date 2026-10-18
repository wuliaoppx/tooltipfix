package meow.tooltipfix.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 在资源包（材质包）界面的 {@code drawScreen(void)} 末尾插一次 {@code Hooks.resourcePackScreen}，
 * 用来画字号滑块。
 *
 * <p><b>怎么认这个界面：</b>coremod 阶段拿到的是混淆字节码（FML 的反混淆器排在后面），所以不认类名。
 * 认「父类 = {@code GuiScreen}（srg 名 {@code net/minecraft/client/gui/GuiScreen} 或 1.12.2 的混淆名
 * {@code blk}）」+「类里有字符串常量 {@code resourcePack.folderInfo}」——这条翻译键只在这个界面用，
 * 唯一命中 {@code GuiScreenResourcePacks}（混淆名 {@code bnw}）。</p>
 *
 * <p><b>为什么只敢插在 {@code (IIF)V}：</b>那是 {@code drawScreen(int mouseX, int mouseY, float
 * partialTicks)}。1.12.2 里它是从头到尾的直线代码——没有跳转（原版根本没写 if，连 GuiScreen 的
 * 那几个 field 都直接读）、没有 try/catch，所以<b>没有 StackMapTable</b>，在唯一的 RETURN 前插三条
 * 取参指令 + 一次静态调用不需要重算栈映射帧，{@code COMPUTE_MAXS} 就够（{@code COMPUTE_FRAMES}
 * 会触发 {@code getCommonSuperClass} 去加载 MC 的类，coremod 阶段很危险）。</p>
 *
 * <p>前置检查：同一方法里必须能数出「跳转 0、try/catch 0、RETURN 恰好 1」，否则一概不碰——别的
 * 版本/别的界面结构一旦不同，宁可没有滑块，也不能把界面弄崩。</p>
 */
public final class ResourcePackUiTransformer implements IClassTransformer {

    /** srg（运行期）类名。 */
    private static final String GUI_SCREEN = "net/minecraft/client/gui/GuiScreen";
    /** 纯混淆类名（1.12.2）。 */
    private static final String GUI_SCREEN_OBF = "blk";
    /** 只在这个界面出现的翻译键，用来确认「这就是资源包界面」。 */
    private static final String MARKER = "resourcePack.folderInfo";
    /** drawScreen 的描述符。 */
    private static final String DRAW_DESC = "(IIF)V";
    /** Hooks 内部名。 */
    private static final String HOOKS = "meow/tooltipfix/client/Hooks";
    private static final String HOOK_DESC = "(Lnet/minecraft/client/gui/GuiScreen;II)V";

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
        if (!GUI_SCREEN.equals(superName) && !GUI_SCREEN_OBF.equals(superName)) {
            return basicClass;
        }
        try {
            Scan scan = new Scan();
            reader.accept(scan, ClassReader.SKIP_DEBUG);
            if (scan.drawMethod == null) {
                return basicClass;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            Patch patch = new Patch(writer, scan.drawMethod);
            reader.accept(patch, 0);
            if (!patch.patched) {
                return basicClass;
            }
            report("patched font scale slider into " + name + " " + scan.drawMethod);
            byte[] patched = writer.toByteArray();
            dump(name, patched);
            return patched;
        } catch (Throwable t) {
            System.err.println("[tooltipfix] failed to transform " + name + ", font scale slider unavailable");
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

    /** 第一遍：找出「能安全插入」的 drawScreen（{@code 方法名+描述符}）。 */
    private static final class Scan extends ClassVisitor {

        String drawMethod;

        Scan() {
            super(Opcodes.ASM5);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic || !DRAW_DESC.equals(desc)) {
                return null;
            }
            return new MethodVisitor(Opcodes.ASM5) {
                private boolean marker;
                private int jumps;
                private int handlers;
                private int returns;

                @Override
                public void visitLdcInsn(Object value) {
                    if (MARKER.equals(value)) {
                        marker = true;
                    }
                }

                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    jumps++;
                }

                @Override
                public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                                 org.objectweb.asm.Label... labels) {
                    jumps++;
                }

                @Override
                public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                                  org.objectweb.asm.Label[] labels) {
                    jumps++;
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                               org.objectweb.asm.Label handler, String type) {
                    handlers++;
                }

                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN || opcode == Opcodes.IRETURN
                            || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN
                            || opcode == Opcodes.DRETURN || opcode == Opcodes.ARETURN) {
                        returns++;
                    }
                }

                @Override
                public void visitEnd() {
                    if (marker && jumps == 0 && handlers == 0 && returns == 1) {
                        drawMethod = name + desc;
                    }
                }
            };
        }
    }

    /** 第二遍：在目标方法的 RETURN 之前插入 hook 调用。 */
    private static final class Patch extends ClassVisitor {

        private final String target;
        boolean patched;

        Patch(ClassVisitor parent, String target) {
            super(Opcodes.ASM5, parent);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (!target.equals(name + desc) || mv == null) {
                return mv;
            }
            return new MethodVisitor(Opcodes.ASM5, mv) {
                @Override
                public void visitInsn(int opcode) {
                    if (opcode == Opcodes.RETURN && !patched) {
                        patched = true;
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ILOAD, 1);
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "resourcePackScreen",
                                HOOK_DESC, false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
    }
}
