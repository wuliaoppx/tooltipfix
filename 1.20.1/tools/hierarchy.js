// 从具名（official）类目录里抽出继承关系，供 build-maps.js 沿着父类/接口回溯成员映射。
//
// 背景：Fabric 的 intermediary 会把「实现了接口方法」的成员挂在**接口**那一行，
// 例如 SpriteContents.name() 在 intermediary 里是 `METHOD gqi$a ()Lakr; c method_45816`
// （gqi$a = Stitcher$Entry），而 client.txt 把它算在 gqf(SpriteContents) 名下。
// 只按 (obfClass, name, desc) 精确 join 就会漏掉这类成员，运行期直接 NoSuchMethodError。
//
// 用法: node hierarchy.js <namedClassesDir> <out.json>
const fs = require('fs');
const path = require('path');

const [, , classesDir, outPath] = process.argv;
if (!classesDir || !outPath) {
  console.error('usage: node hierarchy.js <namedClassesDir> <out.json>');
  process.exit(1);
}

function readU2(buf, off) { return buf.readUInt16BE(off); }
function readU4(buf, off) { return buf.readUInt32BE(off); }

/** 解析 class 文件，返回 { thisClass, superClass, interfaces }（内部名）。 */
function parseClass(buf) {
  let off = 8; // magic + minor + major
  const cpCount = readU2(buf, off); off += 2;
  const utf8 = [];
  const classRef = []; // 索引 -> 常量池下标（指向 Utf8）
  for (let i = 1; i < cpCount; i++) {
    const tag = buf[off]; off += 1;
    switch (tag) {
      case 1: { // Utf8
        const len = readU2(buf, off); off += 2;
        utf8[i] = buf.toString('utf8', off, off + len); off += len;
        break;
      }
      case 7: case 8: case 16: case 19: case 20: // Class/String/MethodType/Module/Package
        classRef[i] = readU2(buf, off); off += 2;
        break;
      case 15: off += 3; break; // MethodHandle
      case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: off += 4; break;
      case 5: case 6: off += 8; i++; break; // Long/Double 占两个槽
      default: throw new Error('unknown constant pool tag ' + tag);
    }
  }
  const nameOf = (idx) => (idx === 0 ? null : utf8[classRef[idx]]);
  off += 2; // access_flags
  const thisClass = nameOf(readU2(buf, off)); off += 2;
  const superClass = nameOf(readU2(buf, off)); off += 2;
  const ifaceCount = readU2(buf, off); off += 2;
  const interfaces = [];
  for (let i = 0; i < ifaceCount; i++) { interfaces.push(nameOf(readU2(buf, off))); off += 2; }
  return { thisClass, superClass, interfaces };
}

const out = {};
let files = 0;
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { walk(p); continue; }
    if (!e.name.endsWith('.class')) continue;
    const parsed = parseClass(fs.readFileSync(p));
    files++;
    // 只保留 net/minecraft 的类，映射表里也只有这些。
    if (!parsed.thisClass || !parsed.thisClass.startsWith('net/minecraft/')) continue;
    const parents = [];
    if (parsed.superClass && parsed.superClass !== 'java/lang/Object') parents.push(parsed.superClass);
    for (const itf of parsed.interfaces) parents.push(itf);
    out[parsed.thisClass] = parents;
  }
}
walk(classesDir);
fs.writeFileSync(outPath, JSON.stringify(out), 'utf8');
console.log(`${files} class files -> ${Object.keys(out).length} minecraft classes -> ${outPath}`);
