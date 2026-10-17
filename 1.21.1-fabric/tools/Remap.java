import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * 极简 tiny v2 重映射器。
 *
 * 为什么不用 tiny-remapper：它会把「类名在源/目标命名空间里相同」的映射整类跳过，于是
 * Forge 1.20.1 这种「类名不变、只改成员名（SRG）」的映射一条都不会生效，而且是静默的。
 *
 * 本 jar 自己的类不在映射表里，所以「覆写 vanilla 方法」的声明、以及以自己为 owner 调用继承来的
 * vanilla 方法，都必须沿父类/接口链回退查找，否则声明不会被改名，运行期就是：
 *   java.lang.AbstractMethodError: Missing implementation of resolved method
 *   'abstract void method_4625(net.minecraft.class_3300)' of abstract class net.minecraft.class_1044.
 * 为此先预扫输入 jar，收集每个类的 superName/interfaces，直接查找失败时沿这条链继续找。
 *
 * 用法: java -cp <asm jars>;. Remap <in.jar> <out.jar> <mapping.tiny> <from> <to>
 */
public final class Remap {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: Remap <in.jar> <out.jar> <mapping.tiny> <from> <to>");
            System.exit(1);
        }
        String inPath = args[0];
        String outPath = args[1];
        Mapping mapping = Mapping.load(args[2], args[3], args[4]);
        System.out.printf("mapping: %d classes, %d methods, %d fields (%s -> %s)%n",
                mapping.classes.size(), mapping.methods.size(), mapping.fields.size(), args[3], args[4]);

        // 预扫：类名 -> {父类, 接口...}。父类可能是本 jar 之外的 vanilla 类（它们才在映射表里）。
        Map<String, String[]> parents = new HashMap<>();
        try (JarFile jar = new JarFile(inPath, false)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (var in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                ClassReader reader = new ClassReader(data);
                String[] itfs = reader.getInterfaces();
                String[] sup = new String[itfs.length + 1];
                sup[0] = reader.getSuperName();
                System.arraycopy(itfs, 0, sup, 1, itfs.length);
                parents.put(reader.getClassName(), sup);
            }
        }
        System.out.println("hierarchy: " + parents.size() + " classes");

        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return mapping.classes.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String mapped = lookupMethod(owner, name, descriptor, new HashSet<>());
                return mapped != null ? mapped : name;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String mapped = lookupField(owner, name, descriptor, new HashSet<>());
                return mapped != null ? mapped : name;
            }

            /** 先在 owner 自己的映射里找，找不到就沿父类/接口链回退（覆写 vanilla 方法时靠这一步）。 */
            private String lookupMethod(String owner, String name, String descriptor, Set<String> seen) {
                if (owner == null || !seen.add(owner)) {
                    return null;
                }
                String mapped = mapping.methods.get(owner + "." + name + descriptor);
                if (mapped == null) {
                    mapped = mapping.methods.get(owner + "." + name + mapMethodDesc(descriptor));
                }
                if (mapped != null) {
                    return mapped;
                }
                String[] sup = parents.get(owner);
                if (sup != null) {
                    for (String parent : sup) {
                        String up = lookupMethod(parent, name, descriptor, seen);
                        if (up != null) {
                            return up;
                        }
                    }
                }
                return null;
            }

            private String lookupField(String owner, String name, String descriptor, Set<String> seen) {
                if (owner == null || !seen.add(owner)) {
                    return null;
                }
                String mapped = mapping.fields.get(owner + "." + name + descriptor);
                if (mapped == null) {
                    mapped = mapping.fields.get(owner + "." + name + mapDesc(descriptor));
                }
                if (mapped == null) {
                    mapped = mapping.fieldsByName.get(owner + "." + name);
                }
                if (mapped != null) {
                    return mapped;
                }
                String[] sup = parents.get(owner);
                if (sup != null) {
                    for (String parent : sup) {
                        String up = lookupField(parent, name, descriptor, seen);
                        if (up != null) {
                            return up;
                        }
                    }
                }
                return null;
            }
        };

        int classes = 0;
        int others = 0;
        try (JarFile jar = new JarFile(inPath, false);
             OutputStream fileOut = new FileOutputStream(outPath);
             JarOutputStream out = new JarOutputStream(fileOut)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data;
                try (var in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassWriter writer = new ClassWriter(0);
                    reader.accept(new ClassRemapper(writer, remapper), 0);
                    data = writer.toByteArray();
                    classes++;
                } else {
                    others++;
                }
                JarEntry copy = new JarEntry(entry.getName());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("remapped " + classes + " classes (" + others + " other entries) -> " + outPath);
    }

    /** tiny v2 子集解析：只关心 c / m / f 三种行。 */
    static final class Mapping {
        final Map<String, String> classes = new HashMap<>();
        final Map<String, String> methods = new HashMap<>();
        final Map<String, String> fields = new HashMap<>();
        final Map<String, String> fieldsByName = new HashMap<>();

        static Mapping load(String path, String from, String to) throws IOException {
            Mapping mapping = new Mapping();
            List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new IOException("empty mapping file: " + path);
            }
            String[] header = lines.get(0).split("\t", -1);
            if (!"tiny".equals(header[0]) || !"2".equals(header[1])) {
                throw new IOException("not a tiny v2 mapping: " + path);
            }
            // 表头: tiny, 2, 0, <ns0>, <ns1>...  命名空间序号 = 表头下标 - 3。
            // 类行 c <ns0> <ns1>：命名空间从第 1 列开始；成员行 m|f <desc> <ns0> <ns1>：从第 2 列开始。
            int fromCol = indexOf(header, from, 3) - 3;
            int toCol = indexOf(header, to, 3) - 3;
            int maxCol = Math.max(fromCol, toCol);

            String currentClass = null;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isEmpty()) {
                    continue;
                }
                boolean indented = line.charAt(0) == '\t';
                String[] parts = line.trim().split("\t", -1);
                if (!indented) {
                    // c <ns0> <ns1>
                    if ("c".equals(parts[0]) && parts.length > 1 + maxCol) {
                        String source = parts[1 + fromCol];
                        String target = parts[1 + toCol];
                        currentClass = source;
                        if (!source.equals(target)) {
                            mapping.classes.put(source, target);
                        }
                    }
                    continue;
                }
                // m|f <desc> <ns0> <ns1>
                if (currentClass == null || parts.length <= 2 + maxCol) {
                    continue;
                }
                String descriptor = parts[1];
                String sourceName = parts[2 + fromCol];
                String targetName = parts[2 + toCol];
                if (sourceName.equals(targetName)) {
                    continue;
                }
                if ("m".equals(parts[0])) {
                    mapping.methods.put(currentClass + "." + sourceName + descriptor, targetName);
                } else if ("f".equals(parts[0])) {
                    mapping.fields.put(currentClass + "." + sourceName + descriptor, targetName);
                    mapping.fieldsByName.put(currentClass + "." + sourceName, targetName);
                }
            }
            return mapping;
        }

        private static int indexOf(String[] header, String name, int start) throws IOException {
            for (int i = start; i < header.length; i++) {
                if (name.equals(header[i])) {
                    return i;
                }
            }
            throw new IOException("namespace not found in header: " + name);
        }
    }

    private Remap() {
    }
}
