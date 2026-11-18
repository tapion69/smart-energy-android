// Regression: a multi-inverter installation must not hide a valid grid reading.
// Tests the actual embedded dashboard selector, without requiring Android.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(
  path.join(__dirname, '../../android/app/src/main/assets/energy_dashboard_v3.html'), 'utf8');
const start = html.indexOf('function resolveGridReading(items,live){');
assert.notEqual(start, -1, 'Dashboard grid selector must exist');
const end = html.indexOf('\n}', start);
assert(end > start, 'Grid selector closing brace must exist');
const selector = html.slice(start, end + 2);
const sandbox = {};
vm.runInNewContext(
  "function n(x){return typeof x==='number'&&Number.isFinite(x)?x:null;}\n" +
  selector + '\nthis.resolveGridReading=resolveGridReading;', sandbox);
const read = sandbox.resolveGridReading;

function check(name, items, live, power, verified, label) {
  const result = read(items, live);
  assert.equal(result.power, power, name + ': power');
  assert.equal(result.verified, verified, name + ': direction');
  assert(result.info.includes(label), name + ': label');
}
check('one inverter', [{slot:1}], [{slot:1,gridPowerW:761}], 761, true, 'Import réseau');
check('one inverter, zero measured', [{slot:1}], [{slot:1,gridPowerW:0}], 0, true, '1 source');
check('MPPT-only second inverter without grid power', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761},{slot:2}], 761, true, '1 source');
check('two inverters, one measured zero', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:0},{slot:2,gridPowerW:761}], 761, true, '2 sources');
check('two inverters sum native grid power', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761},{slot:2,gridPowerW:755}], 1516, true, '2 sources');
check('three inverter sum with MPPT-only third', [{slot:1},{slot:2},{slot:3}],
  [{slot:1,gridPowerW:600},{slot:2,gridPowerW:250},{slot:3}], 850, true, '2 sources');
check('all sources zero', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:0},{slot:2,gridPowerW:0}], 0, true, '2 sources');
check('no measurement', [{slot:1},{slot:2}],
  [{slot:1},{slot:2}], null, false, 'indisponible');
check('non-finite reading ignored', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761},{slot:2,gridPowerW:NaN}], 761, true, '1 source');
check('negative measurement does not imply export', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761},{slot:2,gridPowerW:-100}], 761, true, 'négative exclue');
check('only negative measurement', [{slot:1}],
  [{slot:1,gridPowerW:-100}], null, false, 'incohérente');
check('one fresh inverter in a two-inverter installation', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761}], 761, true, '1 source');
assert(html.includes('id="mGridInfo"'), 'Modern source label must be visible');
assert(html.includes("txt('mGridInfo',gridInfo)"), 'Modern source label must be updated');
// Check the complete inline JS for syntax errors without running its DOM code.
for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  new vm.Script(match[1]);
}
console.log('Energy dashboard grid regressions: PASS (12 cases)');
