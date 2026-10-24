// Reads a timetable that is laid out as a grid (Excel / CSV) without any AI.
//
// Two common layouts are understood:
//   A) days down the side:   Mon | 9:00-9:50 | 9:50-10:40 | ...   (one row per day)
//   B) days across the top:  time | Mon | Tue | ...                 (one row per period)
// Time headers may be ranges ("9:00-9:50", "9.00 AM - 9.50 AM", "9-10") or just start times
// ("9:00", "10:00", ...). Cells that repeat across consecutive periods become one longer class.

const DAY_PATTERNS = [
  [1, /^(mon|monday|mo)\.?$/i],
  [2, /^(tue|tues|tuesday|tu)\.?$/i],
  [3, /^(wed|weds|wednesday|we)\.?$/i],
  [4, /^(thu|thur|thurs|thursday|th)\.?$/i],
  [5, /^(fri|friday|fr)\.?$/i],
  [6, /^(sat|saturday|sa)\.?$/i],
  [7, /^(sun|sunday|su)\.?$/i],
];

const BREAK_WORDS =
  /^(lunch|lunch break|break|short break|recess|interval|tea|tea break|free|free period|nil|n\/a|na|-+|—+|–+|x|\.+|\*+)$/i;

const pad = (n) => String(n).padStart(2, "0");
const fmt = (h, m) => `${pad(h)}:${pad(m)}`;

function dayOf(text) {
  const t = String(text || "").trim().replace(/:$/, "");
  if (!t) return null;
  for (const [n, re] of DAY_PATTERNS) if (re.test(t)) return n;
  return null;
}

function applyMeridiem(h, ap) {
  const a = ap.toLowerCase().replace(/\./g, "");
  return a === "am" ? h % 12 : (h % 12) + 12;
}
const flip = (ap) => (ap.toLowerCase().startsWith("a") ? "pm" : "am");

// Hours 1-6 without am/pm are afternoon (1:30 means 13:30); 7-11 are morning; 12 is noon.
function guessHour(h) {
  return h >= 1 && h <= 6 ? h + 12 : h;
}

const RANGE_RE =
  /(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?\s*(?:-|–|—|to|till|until)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?/i;

/** "9:00-9:50", "1.30 PM - 2.20 PM", "9-10" -> { start: "09:00", end: "09:50" } or null. */
function parseRange(text) {
  const first = parseRangeOnce(text);
  if (first) return first;
  // Text recognition often drops the leading "1" of a start hour: "0:50-11:40" is "10:50-11:40" and
  // "1:40-12:30" is "11:40-12:30". Only tried when the range could not be read as it is.
  const m = /^(\D*)(\d)([:.]\d{2}\s*(?:a\.?m\.?|p\.?m\.?)?\s*(?:-|–|—|to)\s*\d{1,2}[:.]\d{2}.*)$/i.exec(String(text || "").trim());
  if (m) return parseRangeOnce(`${m[1]}1${m[2]}${m[3]}`);
  return null;
}

function parseRangeOnce(text) {
  const m = RANGE_RE.exec(String(text || ""));
  if (!m) return null;
  let h1 = Number(m[1]);
  const m1 = m[2] ? Number(m[2]) : 0;
  let h2 = Number(m[4]);
  const m2 = m[5] ? Number(m[5]) : 0;
  const ap1 = m[3] || null;
  const ap2 = m[6] || null;
  if (h1 > 23 || h2 > 23 || m1 > 59 || m2 > 59) return null;

  let s;
  let e;
  if (ap1 && ap2) {
    s = applyMeridiem(h1, ap1);
    e = applyMeridiem(h2, ap2);
  } else if (ap2) {
    e = applyMeridiem(h2, ap2);
    s = applyMeridiem(h1, ap2);
    if (s * 60 + m1 >= e * 60 + m2) s = applyMeridiem(h1, flip(ap2)); // "11:30 - 12:30 PM"
  } else if (ap1) {
    s = applyMeridiem(h1, ap1);
    e = applyMeridiem(h2, ap1);
    if (e * 60 + m2 <= s * 60 + m1) e = applyMeridiem(h2, flip(ap1));
  } else {
    s = h1 > 12 ? h1 : guessHour(h1);
    e = h2 > 12 ? h2 : guessHour(h2);
    if (h1 === 12) s = 12;
    if (h2 === 12) e = 12;
  }
  if (e * 60 + m2 <= s * 60 + m1) return null;
  if (s < 5 || e > 23 || (e === 23 && m2 > 59)) return null; // not a plausible class time
  return { start: fmt(s, m1), end: fmt(e, m2) };
}

const SINGLE_RE = /^(\d{1,2})[:.](\d{2})\s*(a\.?m\.?|p\.?m\.?)?$/i;

/** "9:00", "1.30 PM", "09:00" -> minutes since midnight, or null. */
function parseSingleTime(text) {
  const m = SINGLE_RE.exec(String(text || "").trim());
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  let hh = h;
  if (m[3]) hh = applyMeridiem(h, m[3]);
  else if (h <= 12) hh = h === 12 ? 12 : guessHour(h);
  if (hh < 5) return null;
  return hh * 60 + min;
}

const toMin = (hm) => Number(hm.slice(0, 2)) * 60 + Number(hm.slice(3));
const fromMin = (n) => fmt(Math.floor(n / 60), n % 60);

function cleanTitle(text) {
  const parts = String(text || "")
    .split(/\r?\n/)
    .map((p) => p.replace(/\s+/g, " ").trim())
    .filter(Boolean);
  return parts.join(" · ").slice(0, 100);
}

const BREAK_PHRASE = /\b(lunch|interval|recess|break)\b/i;

function isBreak(text) {
  const t = String(text || "").replace(/\s+/g, " ").trim();
  if (t === "" || BREAK_WORDS.test(t)) return true;
  // "Interval Break", "Lunch Break", "Tea break time": short cells that are about a break
  return t.split(" ").length <= 3 && BREAK_PHRASE.test(t);
}

function slotFromCell(text) {
  return parseRange(text);
}

/** Slots for a header line (a row or a column): ranges where possible, else derived from start times. */
function buildSlots(cells) {
  // cells: array of {idx, text}
  const slots = new Map();
  let ranges = 0;
  for (const { idx, text } of cells) {
    const r = slotFromCell(text);
    if (r) {
      slots.set(idx, r);
      ranges++;
    }
  }
  if (ranges >= 2) return slots;

  // start times only: "9:00", "10:00", ... each slot lasts until the next one starts
  const starts = [];
  for (const { idx, text } of cells) {
    const t = parseSingleTime(String(text || "").trim());
    if (t !== null) starts.push({ idx, t });
  }
  if (starts.length >= 3) {
    const ascending = starts.every((s, i) => i === 0 || s.t > starts[i - 1].t);
    if (ascending) {
      const out = new Map();
      starts.forEach((s, i) => {
        const next = starts[i + 1];
        const end = next ? next.t : s.t + (s.t - starts[i - 1].t);
        if (end > s.t && end <= 23 * 60 + 59) out.set(s.idx, { start: fromMin(s.t), end: fromMin(end) });
      });
      if (out.size >= 3) return out;
    }
  }
  return new Map();
}

function cell(rows, r, c) {
  const row = rows[r];
  if (!row) return "";
  return row[c] == null ? "" : String(row[c]).trim();
}

function width(rows) {
  return rows.reduce((w, r) => Math.max(w, r.length), 0);
}

function mergeRuns(items) {
  // items: [{ key, title, start, end, pos }] in position order; joins neighbours with the same title
  const out = [];
  for (const it of items) {
    const last = out[out.length - 1];
    if (last && last.title.toLowerCase() === it.title.toLowerCase() && it.pos === last.pos + 1) {
      last.end = it.end;
      last.pos = it.pos;
    } else {
      out.push({ ...it });
    }
  }
  return out;
}

/** Layout A: one row per day, periods across the top. */
function parseDaysAsRows(rows) {
  const w = width(rows);
  let best = null;
  for (let c = 0; c < w; c++) {
    const dayRows = [];
    for (let r = 0; r < rows.length; r++) {
      const d = dayOf(cell(rows, r, c));
      if (d) dayRows.push({ r, d });
    }
    if (new Set(dayRows.map((x) => x.d)).size >= 2 && (!best || dayRows.length > best.dayRows.length)) {
      best = { c, dayRows };
    }
  }
  if (!best) return [];

  const firstDayRow = Math.min(...best.dayRows.map((x) => x.r));
  let header = null;
  for (let r = 0; r < firstDayRow; r++) {
    const cells = [];
    for (let c = 0; c < w; c++) if (c !== best.c) cells.push({ idx: c, text: cell(rows, r, c) });
    const slots = buildSlots(cells);
    if (slots.size >= 2 && (!header || slots.size >= header.size)) header = slots;
  }
  if (!header) return [];

  const entries = [];
  for (const { r, d } of best.dayRows) {
    const items = [];
    for (const [c, slot] of [...header.entries()].sort((a, b) => a[0] - b[0])) {
      const text = cell(rows, r, c);
      if (isBreak(text) || dayOf(text) || parseRange(text)) continue;
      items.push({ title: cleanTitle(text), start: slot.start, end: slot.end, pos: c });
    }
    for (const run of mergeRuns(items)) {
      if (run.title) entries.push({ dayOfWeek: d, title: run.title, startTime: run.start, endTime: run.end });
    }
  }
  return entries;
}

/** Layout B: one column per day, periods down the side. */
function parseDaysAsColumns(rows) {
  const w = width(rows);
  let best = null;
  for (let r = 0; r < rows.length; r++) {
    const dayCols = [];
    for (let c = 0; c < w; c++) {
      const d = dayOf(cell(rows, r, c));
      if (d) dayCols.push({ c, d });
    }
    if (new Set(dayCols.map((x) => x.d)).size >= 2 && (!best || dayCols.length > best.dayCols.length)) {
      best = { r, dayCols };
    }
  }
  if (!best) return [];

  const dayColSet = new Set(best.dayCols.map((x) => x.c));
  let header = null;
  for (let c = 0; c < w; c++) {
    if (dayColSet.has(c)) continue;
    const cells = [];
    for (let r = best.r + 1; r < rows.length; r++) cells.push({ idx: r, text: cell(rows, r, c) });
    const slots = buildSlots(cells);
    if (slots.size >= 2 && (!header || slots.size > header.size)) header = slots;
  }
  if (!header) return [];

  const entries = [];
  for (const { c, d } of best.dayCols) {
    const items = [];
    for (const [r, slot] of [...header.entries()].sort((a, b) => a[0] - b[0])) {
      const text = cell(rows, r, c);
      if (isBreak(text) || parseRange(text)) continue;
      items.push({ title: cleanTitle(text), start: slot.start, end: slot.end, pos: r });
    }
    for (const run of mergeRuns(items)) {
      if (run.title) entries.push({ dayOfWeek: d, title: run.title, startTime: run.start, endTime: run.end });
    }
  }
  return entries;
}

function dedupe(entries) {
  const seen = new Set();
  const out = [];
  for (const e of entries) {
    const key = `${e.dayOfWeek}|${e.startTime}|${e.endTime}|${e.title.toLowerCase()}`;
    if (!seen.has(key)) {
      seen.add(key);
      out.push(e);
    }
  }
  return out.sort((a, b) => a.dayOfWeek - b.dayOfWeek || a.startTime.localeCompare(b.startTime));
}

/**
 * Tries both layouts and keeps the one that finds more classes.
 * Returns { entries, layout, confident }. "confident" means it looks like a real timetable.
 */
function parseGrid(rows) {
  const clean = rows.map((r) => r.map((c) => (c == null ? "" : String(c))));
  const a = dedupe(parseDaysAsRows(clean));
  const b = dedupe(parseDaysAsColumns(clean));
  const useA = a.length >= b.length;
  const entries = useA ? a : b;
  const days = new Set(entries.map((e) => e.dayOfWeek));
  return {
    entries,
    layout: entries.length === 0 ? null : useA ? "days-as-rows" : "days-as-columns",
    confident: entries.length >= 3 && days.size >= 2,
  };
}

module.exports = { parseGrid, parseRange, parseSingleTime, dayOf, cleanTitle, dedupe };
