// 生成两种 tiny v2 映射，供 tiny-remapper 把「按官方名编译好的 mod jar」重映射到两个运行期命名空间：
//   named -> srg          （Forge 1.20.1：类名保持官方名，成员变成 m_xxxxx_/f_xxxxx_）
//   named -> intermediary （Fabric 1.20.1：类名变 class_xxx，成员变 method_xxx/field_xxx）
//
// 数据来源：
//   client-1.20.1.txt     官方 ProGuard：混淆 -> 官方名（成员行用官方类型写描述符）
//   joined.tsrg           Forge tsrg2：混淆 -> SRG（类名列是 net/minecraft/src/C_xxx，运行时不用，只要成员名）
//   intermediary.tiny     Fabric tiny v1：official(混淆) -> intermediary
//
// 三个来源的共同键都是「混淆类 + 混淆成员名 + 混淆描述符」，所以按它 join。
const fs = require('fs');

const [, , clientTxt, tsrgPath, interTinyPath, outDir, hierPath] = process.argv;
if (!clientTxt || !tsrgPath || !interTinyPath || !outDir) {
  console.error('usage: node build-maps.js <client.txt> <joined.tsrg> <intermediary.tiny> <outDir> [hierarchy.json]');
  process.exit(1);
}

const namedToObf = new Map();
const obfToNamed = new Map();

// ---------------------------------------------------------------- client.txt
const memberNamed = new Map(); // `${obfClass}\0${kind}\0${obfName}\0${obfDesc}` -> namedName
{
  const lines = fs.readFileSync(clientTxt, 'utf8').split(/\r?\n/);
  const classes = [];
  let cur = null;
  for (const raw of lines) {
    if (!raw || raw[0] === '#') continue;
    const cm = raw.match(/^(\S+) -> (\S+):$/);
    if (cm) {
      namedToObf.set(cm[1], cm[2]);
      obfToNamed.set(cm[2], cm[1]);
      cur = { named: cm[1], obf: cm[2], members: [] };
      classes.push(cur);
      continue;
    }
    if (!cur) continue;
    const t = raw.trim();
    if (!t) continue;
    const mm = t.match(/^(?:\d+:\d+:)?(\S+)\s+(\S+?)\((.*)\)\s+->\s+(\S+)$/);
    if (mm) { cur.members.push({ kind: 'M', type: mm[1], name: mm[2], args: mm[3], obf: mm[4] }); continue; }
    const fm = t.match(/^(\S+)\s+(\S+)\s+->\s+(\S+)$/);
    if (fm) cur.members.push({ kind: 'F', type: fm[1], name: fm[2], obf: fm[3] });
  }

  const PRIM = { void: 'V', boolean: 'Z', byte: 'B', char: 'C', short: 'S', int: 'I', long: 'J', float: 'F', double: 'D' };
  const toObfType = (t) => {
    if (PRIM[t]) return PRIM[t];
    const obf = namedToObf.get(t);
    return 'L' + (obf !== undefined ? obf : t.replace(/\./g, '/')) + ';';
  };
  const typeToDesc = (type) => {
    let arr = '';
    let t = type;
    while (t.endsWith('[]')) { arr += '['; t = t.slice(0, -2); }
    return arr + toObfType(t);
  };

  for (const c of classes) {
    for (const m of c.members) {
      const desc = m.kind === 'M'
        ? '(' + (m.args ? m.args.split(',').map(typeToDesc).join('') : '') + ')' + typeToDesc(m.type)
        : typeToDesc(m.type);
      memberNamed.set(`${c.obf}\0${m.kind}\0${m.obf}\0${desc}`, m.name);
    }
  }
}

// ---------------------------------------------------------------- joined.tsrg (tsrg2, obf -> srg)
const memberSrg = new Map(); // 同上的键 -> srg 名
{
  const lines = fs.readFileSync(tsrgPath, 'utf8').split(/\r?\n/);
  let curClass = null;
  for (const raw of lines) {
    if (!raw || raw[0] === '#') continue;
    if (!raw.startsWith('\t')) {
      const t = raw.split(/\s+/);
      curClass = t[0];
      continue;
    }
    // 一个 tab = 成员，两个及以上 = 参数/局部变量，跳过
    if (raw.startsWith('\t\t')) continue;
    const t = raw.trim().split(/\s+/);
    if (!curClass || t.length < 2) continue;
    if (t[1].startsWith('(')) {
      // <obfName> <obfDesc> <srgName> <id>
      memberSrg.set(`${curClass}\0M\0${t[0]}\0${t[1]}`, t[2]);
    } else {
      // <obfName> <srgName> <id>，字段没有描述符，按名字索引
      memberSrg.set(`${curClass}\0F\0${t[0]}\0*`, t[1]);
    }
  }
}

// ---------------------------------------------------------------- intermediary (tiny v1, official -> intermediary)
const memberInter = new Map();
const obfToInter = new Map();
{
  // tiny v1（Fabric intermediary）：
  //   CLASS <obfClass> <intClass>
  //   FIELD <obfClass> <obfDesc> <obfName> <intName>
  //   METHOD <obfClass> <obfDesc> <obfName> <intName>
  const lines = fs.readFileSync(interTinyPath, 'utf8').split(/\r?\n/);
  for (const raw of lines) {
    if (!raw || raw.startsWith('#')) continue;
    const t = raw.split('\t');
    if (t[0] === 'CLASS' && t.length >= 3) {
      obfToInter.set(t[1], t[2]);
    } else if (t[0] === 'FIELD' && t.length >= 5) {
      memberInter.set(`${t[1]}\0F\0${t[3]}\0${t[2]}`, t[4]);
    } else if (t[0] === 'METHOD' && t.length >= 5) {
      memberInter.set(`${t[1]}\0M\0${t[3]}\0${t[2]}`, t[4]);
    }
  }
}

// ---------------------------------------------------------------- 继承关系（可选，第 5 个参数）
// Fabric 的 intermediary 把「接口方法的实现」挂在接口类那一行，只按 (obfClass, name, desc) 精确 join
// 会漏掉，运行期 NoSuchMethodError。有层次表时沿父类/接口回溯再查一次。
let hierarchy = {};
if (hierPath && fs.existsSync(hierPath)) {
  hierarchy = JSON.parse(fs.readFileSync(hierPath, 'utf8'));
}
const ancestorsCache = new Map();
function ancestorsOf(namedInternal) {
  if (ancestorsCache.has(namedInternal)) return ancestorsCache.get(namedInternal);
  const seen = new Set();
  const queue = [namedInternal];
  while (queue.length) {
    const cur = queue.shift();
    for (const p of (hierarchy[cur] || [])) {
      if (seen.has(p)) continue;
      seen.add(p);
      queue.push(p);
    }
  }
  const arr = [...seen];
  ancestorsCache.set(namedInternal, arr);
  return arr;
}

// ---------------------------------------------------------------- 输出 tiny v2
const PRIM = { void: 'V', boolean: 'Z', byte: 'B', char: 'C', short: 'S', int: 'I', long: 'J', float: 'F', double: 'D' };
function descObfToNamed(desc) {
  // 输入是混淆命名空间的描述符，输出要用官方名（因为要被重映射的 jar 是按官方名编译的）。
  // 混淆内部名没有包路径，嵌套类形如 evp$a，所以按 '$' 切分、从长到短试前缀。
  return desc.replace(/L([^;]+);/g, (whole, inner) => {
    const segs = inner.split('$');
    for (let i = segs.length; i >= 1; i--) {
      const candidate = segs.slice(0, i).join('$');
      const named = obfToNamed.get(candidate);
      if (named !== undefined) {
        const tail = i < segs.length ? '$' + segs.slice(i).join('$') : '';
        return 'L' + (named + tail).replace(/\./g, '/') + ';';
      }
    }
    return whole; // 没被混淆的库类，原样保留
  });
}

// 按「声明类」把成员分组，供继承展开用
const byClass = new Map();
for (const [key, namedName] of memberNamed) {
  const idx = key.indexOf('\0');
  const declClass = key.slice(0, idx);
  const [, kind, obfName, obfDesc] = key.split('\0');
  if (!byClass.has(declClass)) byClass.set(declClass, []);
  byClass.get(declClass).push({ kind, obfName, obfDesc, namedName });
}

function emit(path, ns, lookup, classLookup) {
  const out = [`tiny\t2\t0\tnamed\t${ns}`];
  let mapped = 0;
  for (const obfClass of obfToNamed.keys()) {
    const named = obfToNamed.get(obfClass);
    const namedInternal = named.replace(/\./g, '/');
    const targetClass = classLookup ? classLookup(obfClass) : null;
    out.push(`c\t${namedInternal}\t${targetClass || namedInternal}`);

    // 继承完备：字节码会用「子类当 owner」调继承来的方法（如 child.getResource），而 intermediary /
    // tsrg 把该成员记在声明它的类（常常是接口）名下 —— 只输出自己声明的成员会漏 → 运行期
    // NoSuchMethodError。所以每个类都要输出「自己 + 全部祖先」的成员（同一条 (tag, desc, name)
    // 只保留最先出现的那个，自己声明的优先，避免覆盖方法与祖先实现冲突）。
    const owners = [obfClass];
    for (const anc of ancestorsOf(namedInternal)) {
      const ancObf = namedToObf.get(anc.replace(/\//g, '.'));
      if (ancObf !== undefined) owners.push(ancObf);
    }
    const clsMembers = [];
    const emitted = new Set();
    for (const owner of owners) {
      const list = byClass.get(owner);
      if (!list) continue;
      for (const m of list) {
        const identity = `${m.kind}\0${m.obfDesc}\0${m.namedName}`;
        if (emitted.has(identity)) continue;
        const target = lookup(owner, m.kind, m.obfName, m.obfDesc);
        if (!target || target === m.namedName) continue;
        emitted.add(identity);
        clsMembers.push(`\t${m.kind === 'M' ? 'm' : 'f'}\t${descObfToNamed(m.obfDesc)}\t${m.namedName}\t${target}`);
        mapped++;
      }
    }
    out.push(...clsMembers);
  }
  fs.writeFileSync(path, out.join('\n') + '\n', 'utf8');
  console.log(`${path}: ${obfToNamed.size} classes, ${mapped} renamed members (ns=${ns})`);
}

fs.mkdirSync(outDir, { recursive: true });
emit(`${outDir}/named-srg.tiny`, 'srg',
  (cls, kind, name, desc) => memberSrg.get(`${cls}\0${kind}\0${name}\0${kind === 'M' ? desc : '*'}`));
emit(`${outDir}/named-intermediary.tiny`, 'intermediary',
  (cls, kind, name, desc) => memberInter.get(`${cls}\0${kind}\0${name}\0${desc}`),
  (cls) => obfToInter.get(cls));
