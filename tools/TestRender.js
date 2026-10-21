// 用最小 DOM stub 跑一遍 renderScan，验证前端渲染不会吐出 undefined / NaN。
// 真浏览器在这台机器上起不来（daemon 连接失败），但渲染逻辑本身可以这样验。
const fs = require('fs');

const html = fs.readFileSync('src/main/resources/static/index.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/g).pop().replace(/^<script>|<\/script>$/g, '');

// 按大括号配平，抠出指定的函数定义
function extract(src, name) {
  const start = src.indexOf('function ' + name + '(');
  if (start < 0) throw new Error('找不到函数 ' + name);
  let i = src.indexOf('{', start), depth = 0;
  for (let j = i; j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}') { depth--; if (depth === 0) return src.slice(start, j + 1); }
  }
  throw new Error('括号不配平: ' + name);
}

const needed = ['esc', 'md', 'fmtNum', 'fmtPct', 'colName', 'renderScan'];
const code = needed.map(n => extract(script, n)).join('\n');

const store = {};
global.document = {
  getElementById: function (id) {
    if (!store[id]) store[id] = { innerHTML: '', textContent: '', style: {} };
    return store[id];
  }
};

eval(code);

const d = JSON.parse(fs.readFileSync('_sr.json', 'utf8'));
renderScan(d);

const out = store['healthBody'].innerHTML || '';
const meta = store['healthMeta'].innerHTML || '';

console.log('meta  :', meta.replace(/<[^>]+>/g, '').slice(0, 110));
console.log('body  :', out.length, '字符');

let bad = 0;
['undefined', 'NaN', 'null'].forEach(w => {
  const n = (out + meta).split(w).length - 1;
  if (n) { console.log('  !! 出现 ' + w + ' × ' + n); bad += n; }
});

const checks = [
  ['模式徽章', /hb-mode (a|b)/.test(meta)],
  ['发现条目', (out.match(/class="finding/g) || []).length === d.findings.length],
  ['强度着色', /finding (up|down)/.test(out)],
  ['取数 SQL', /<pre class="dict">/.test(out)],
  ['反向检验', out.indexOf('反向检验') >= 0],
  ['被拦折叠', d.blocked.length ? out.indexOf('被拦下') >= 0 : true],
  ['质量提醒', d.quality.length ? out.indexOf('数据质量') >= 0 : true],
  ['解读占位', (out.match(/data-note="/g) || []).length === d.findings.length],
  ['解读容器', out.indexOf('id="narrSum"') >= 0]
];
checks.forEach(([k, ok]) => { console.log((ok ? '  ✅ ' : '  ❌ ') + k); if (!ok) bad++; });

console.log(bad === 0 ? '\n渲染检查全部通过' : '\n有 ' + bad + ' 项问题');
