// 把 Mojang 官方 client.txt（ProGuard 格式）转成 Tiny v2 映射，供 tiny-remapper 使用。
//   官方(client.txt 的右侧)= 混淆名 -> named(client.txt 的左侧)
//   tiny v2 的成员描述符必须用「源命名空间」的类型，也就是混淆类名，所以要先把 named 类型反查成混淆名。
const fs = require('fs');

const [, , inPath, outPath] = process.argv;
if (!inPath || !outPath) {
  console.error('usage: node to-tiny.js <client.txt> <out.tiny>');
  process.exit(1);
}

const lines = fs.readFileSync(inPath, 'utf8').split(/\r?\n/);

const classes = [];
const namedToObf = new Map();
let cur = null;

for (const raw of lines) {
  if (!raw || raw[0] === '#') continue;
  const cm = raw.match(/^(\S+) -> (\S+):$/);
  if (cm) {
    cur = { named: cm[1], obf: cm[2], members: [] };
    classes.push(cur);
    namedToObf.set(cm[1], cm[2]);
    continue;
  }
  if (!cur) continue;
  const t = raw.trim();
  if (!t) continue;

  // 方法： [start:end:]<ret> <name>(<args>) -> <obf>
  const mm = t.match(/^(?:\d+:\d+:)?(\S+)\s+(\S+?)\((.*)\)\s+->\s+(\S+)$/);
  if (mm) {
    cur.members.push({ kind: 'm', type: mm[1], name: mm[2], args: mm[3], obf: mm[4] });
    continue;
  }
  // 字段： <type> <name> -> <obf>
  const fm = t.match(/^(\S+)\s+(\S+)\s+->\s+(\S+)$/);
  if (fm) {
    cur.members.push({ kind: 'f', type: fm[1], name: fm[2], obf: fm[3] });
  }
}

const PRIM = {
  void: 'V', boolean: 'Z', byte: 'B', char: 'C', short: 'S',
  int: 'I', long: 'J', float: 'F', double: 'D',
};

function typeToDesc(type) {
  let arr = '';
  let t = type;
  while (t.endsWith('[]')) {
    arr += '[';
    t = t.slice(0, -2);
  }
  if (PRIM[t]) return arr + PRIM[t];
  const obf = namedToObf.get(t);
  // MC 之外的库（java.*、com.mojang.*）没有被混淆，名字原样保留。
  return arr + 'L' + (obf !== undefined ? obf : t.replace(/\./g, '/')) + ';';
}

const out = ['tiny\t2\t0\tofficial\tnamed'];
let memberCount = 0;

for (const c of classes) {
  out.push(`c\t${c.obf}\t${c.named.replace(/\./g, '/')}`);
  for (const m of c.members) {
    const desc = m.kind === 'm'
      ? '(' + (m.args ? m.args.split(',').map(typeToDesc).join('') : '') + ')' + typeToDesc(m.type)
      : typeToDesc(m.type);
    out.push(`\t${m.kind}\t${desc}\t${m.obf}\t${m.name}`);
    memberCount++;
  }
}

fs.writeFileSync(outPath, out.join('\n') + '\n', 'utf8');
console.log(`classes=${classes.length} members=${memberCount} -> ${outPath}`);
