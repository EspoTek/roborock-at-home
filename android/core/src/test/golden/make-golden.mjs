// Writes the expected answers the floor-plan tests in :core compare the Kotlin port against, by
// running the web page's own functions, lifted out of index.html, on the maps in resources/maps.
// Run it from the android directory whenever the page's map code changes, then run :core:test:
//   node core/src/test/golden/make-golden.mjs ../src/roborock_at_home/static/index.html
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { gzipSync } from 'node:zlib';

const page = process.argv[2];
if (!page) throw new Error('Give the path of index.html, the page whose answers are wanted.');
const html = readFileSync(page, 'utf8');
const mapsDir = new URL('../resources/maps/', import.meta.url), out = new URL('../resources/golden/', import.meta.url);

// The source from `start` up to (not including) `end`, or to the end of the line when `end` is '\n'.
function slice(start, end) {
  const a = html.indexOf(start);
  if (a < 0 || html.indexOf(start, a + 1) >= 0) throw new Error(`"${start}" is not in the page exactly once`);
  const b = html.indexOf(end, a);
  if (b < 0) throw new Error(`Nothing ends "${start}" with "${end}"`);
  return html.slice(a, b);
}
const src = [
  slice('const need = (o, k) =>', '\n'),
  slice('const ROOM_PALETTE = {', 'const cssRGB = '),
  slice('const pack = c =>', '\n'),
  slice('function paint(id) {', 'function overlay(r) {'),
  slice('const RAYS = ', '// A tap toggles the room'),
].join('\n');

// Stand-ins for the page state the lifted functions read: one robot, 'r', shown with the identity
// view transform, so screen and map coordinates are the same and a reach is in map pixels.
const HT = {dataset: {theme: 'light'}}, maps = new Map(), M = {id: 'r', hov: null, w: 0, h: 0, bytes: null, s: 1, tx: 0, ty: 0};
let robot = null, sel = new Set();
const P = new Function('HT', 'maps', 'R', 'selOf', 'M', src + '\nreturn {ROOM_PALETTE, oklab, slotD, adjacency, assignSlots, slotsOf, paint, hit};')(
  HT, maps, () => robot, () => sel, M);

function show(map, rooms) {
  robot = {id: 'r', rooms: rooms.map(id => ({id})), map: {}};
  maps.set('r', {rev: `${map.name}|${rooms}`, w: map.w, h: map.h, bytes: map.bytes, img: {data: new Uint8ClampedArray(map.w * map.h * 4)}, ctx: {putImageData() {}}, key: ''});
  Object.assign(M, {w: map.w, h: map.h, bytes: map.bytes});
}
const argb = c => ((255 << 24) | (c[0] << 16) | (c[1] << 8) | c[2]) | 0;
const rle = a => { const r = []; for (const v of a) { const l = r[r.length - 1]; if (l && l[0] === v) l[1]++; else r.push([v, 1]); } return r; };
const write = (name, obj) => writeFileSync(new URL(name, out), '{\n' + Object.entries(obj).map(([k, v]) => ` ${JSON.stringify(k)}: ${JSON.stringify(v)}`).join(',\n') + '\n}\n');
mkdirSync(new URL('paint/', out), {recursive: true});

const tones = t => { const T = P.ROOM_PALETTE[t]; return {wall: argb(T.wall), floor: argb(T.floor), seen: argb(T.seen), slots: T.slots.map(s => [argb(s.normal), argb(s.selected), argb(s.edge)])}; };
write('palette.json', {
  light: tones('light'), dark: tones('dark'),
  oklab: Object.fromEntries(['light', 'dark'].map(t => [t, P.ROOM_PALETTE[t].slots.map(s => P.oklab(s.normal))])),
  distances: P.slotD.map(row => [...row]),
});
// The page's own edge cases: rooms with no map take the slots in room order; 32 rooms in a row.
const chain = new Map(Array.from({length: 31}, (_, i) => [`${i},${i + 1}`, 20]));
write('edge-cases.json', {
  noMap: {rooms: [18, 16, 17], slots: [...P.assignSlots([18, 16, 17], new Map(), P.slotD)]},
  chain: {rooms: [...Array(32).keys()], contacts: [...chain].map(([k, n]) => [...k.split(',').map(Number), n]), slots: [...P.assignSlots([...Array(32).keys()], chain, P.slotD)]},
});
console.log('palette.json, edge-cases.json');

const meta = JSON.parse(readFileSync(new URL('meta.json', mapsDir), 'utf8'));
for (const [name, {w, h, rooms: listed}] of Object.entries(meta)) {
  const t0 = performance.now(), bytes = new Uint8Array(readFileSync(new URL(`${name}.map`, mapsDir)));
  if (bytes.length !== w * h) throw new Error(`${name}.map has ${bytes.length} bytes, expected ${w * h}`);
  const map = {name, w, h, bytes};
  // The robot lists its rooms in its own order, not by id; the partial list leaves one room out, as
  // when a segment of the map is not a room the robot offers.
  const full = [...listed].reverse(), dropped = full[1], partial = full.filter(id => id !== dropped);
  const adj = P.adjacency(bytes, w, h);
  const contacts = [...adj].map(([k, n]) => [...k.split(',').map(Number), n]).sort((p, q) => p[0] - q[0] || p[1] - q[1]);
  const [na, nb] = contacts.filter(([a, b]) => full.includes(a) && full.includes(b)).reduce((p, q) => q[2] > p[2] ? q : p);
  const slots = {};
  for (const [k, rooms] of [['full', full], ['partial', partial]]) { show(map, rooms); slots[k] = {rooms, slots: [...P.slotsOf(robot)]}; }

  // Points on a grid over the plan and 20 pixels beyond it, off the pixel centres.
  const grid = {x0: -20.25, y0: -20.25, step: 2.5, nx: Math.ceil((w + 40) / 2.5), ny: Math.ceil((h + 40) / 2.5)};
  const hits = [];
  for (const [list, reach] of [['full', 6], ['full', 12], ['full', 2.4], ['partial', 12]]) {
    show(map, slots[list].rooms);
    const got = [];
    for (let j = 0; j < grid.ny; j++) for (let i = 0; i < grid.nx; i++) got.push(P.hit(grid.x0 + i * grid.step, grid.y0 + j * grid.step, reach));
    hits.push({rooms: list, reach, rle: rle(got)});
  }

  const paints = [];
  for (const [list, selected] of [['full', []], ['full', [full[0]]], ['full', [na, nb]], ['partial', [dropped, partial[0]]]]) for (const theme of ['light', 'dark']) {
    show(map, slots[list].rooms);
    HT.dataset.theme = theme; sel = new Set(selected);
    P.paint('r');
    // ImageData holds RGBA bytes; the file holds one big-endian ARGB int per pixel.
    const rgba = maps.get('r').img.data, px = Buffer.alloc(w * h * 4);
    for (let i = 0; i < w * h; i++) px.writeInt32BE((rgba[4 * i + 3] << 24) | (rgba[4 * i] << 16) | (rgba[4 * i + 1] << 8) | rgba[4 * i + 2], 4 * i);
    const file = `paint/${name}-${list}-${selected.join('+') || 'none'}-${theme}.argb.gz`;
    writeFileSync(new URL(file, out), gzipSync(px, {level: 9}));
    paints.push({rooms: list, selected, theme, file});
  }
  write(`${name}.json`, {w, h, contacts, slots, grid, hits, paints});
  console.log(`${name}: ${contacts.length} contacts, ${hits.length} x ${grid.nx * grid.ny} hits, ${paints.length} paintings (${(performance.now() - t0).toFixed(0)} ms)`);
}
