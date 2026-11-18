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
check('one inverter', [{slot:1}], [{slot:1,gridPowerW:761}], 761, false, 'sens non validé');
check('two inverters, one reading', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761},{slot:2}], 761, false, 'INV1 uniquement');
check('two inverters, one zero', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:0},{slot:2,gridPowerW:761}], 761, false, 'INV2 uniquement');
check('two readings must not sum', [{slot:1},{slot:2}],
  [{slot:1,gridPowerW:761},{slot:2,gridPowerW:755}], 761, false, 'INV1 uniquement');
check('no measurement', [{slot:1},{slot:2}],
  [{slot:1},{slot:2}], null, false, 'indisponible');
check('single verified direction', [{slot:1}],
  [{slot:1,gridPowerW:100,gridDirectionVerified:true}], 100, true, 'Sens confirmé');
check('incomplete two inverter site', [{slot:1},{slot:2}],
  [{slot:2,gridPowerW:761,gridDirectionVerified:true}], 761, false, 'INV2 uniquement');
assert(html.includes('id="mGridInfo"'), 'Modern source label must be visible');
assert(html.includes("txt('mGridInfo',gridInfo)"), 'Modern source label must be updated');
// Check the complete inline JS for syntax errors without running its DOM code.
for (const match of html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)) {
  new vm.Script(match[1]);
}
console.log('Energy dashboard grid regressions: PASS (7 cases)');
