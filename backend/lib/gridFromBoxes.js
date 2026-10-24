// Turns text with positions (from a PDF's text layer, or from a text recogniser reading a photo) back into
// the rows and columns of a timetable, so the grid reader can understand it.
//
// The idea: a timetable has a header row of times and a column of day names. Those two are reliable
// anchors. Columns are placed by the header cells (cut halfway between neighbours); rows are placed by
// the day names (cut halfway between them). Everything else is dropped into the cell it falls in, joined
// top to bottom, so a cell that wraps over several lines stays one cell. A cell centred across a column
// boundary (a merged cell) is repeated in each column it covers, like a merged Excel cell.
const { dayOf, parseRange, parseSingleTime, parseGrid } = require("./timetableGrid");

const TIME_PATTERN = /\d{1,2}[:.]\d{2}/;
const TIME_RANGE_IN_TEXT =
  /\d{1,2}[:.]\d{2}\s*(?:a\.?m\.?|p\.?m\.?)?\s*[-–—]\s*\d{1,2}[:.]\d{2}(?:\s*(?:a\.?m\.?|p\.?m\.?))?/gi;
const NOT_TIME_CHARS = /[^0-9:.\-–—apmAPM\s]/g;
const EDGE_NOISE = "|!¦[]{}_~\\/";
const LONE_PUNCTUATION = ".,:;'`\"";
const COURSE_CODE = /^\d{2}[A-Z]{2,5}\d{3}[A-Z]?$/;
const COURSE_CODE_START = /^(\d{2}[A-Z]{2,5}\d{3}[A-Z]?)(.*)$/;

const cx = (b) => (b.x0 + b.x1) / 2;
const cy = (b) => (b.y0 + b.y1) / 2;
const width = (b) => b.x1 - b.x0;
const height = (b) => b.y1 - b.y0;

function median(values) {
  if (!values.length) return 0;
  const s = [...values].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function trimNoise(text) {
  let a = 0;
  let b = text.length;
  while (a < b && EDGE_NOISE.includes(text[a])) a++;
  while (b > a && EDGE_NOISE.includes(text[b - 1])) b--;
  return text.slice(a, b).trim();
}

/** Removes border junk, splits two header cells that were read as one word, drops stray fragments. */
function clean(raw, { ocr }) {
  const out = [];
  for (const b of raw) {
    if (![b.x0, b.y0, b.x1, b.y1].every(Number.isFinite) || b.x1 <= b.x0 || b.y1 <= b.y0) continue;
    const t = trimNoise(String(b.text || ""));
    if (!t) continue;

    // "10:50-11:40/11:40-12:30" (or with the border read as letters): two header cells read as one word
    const timeOnly = t.replace(NOT_TIME_CHARS, " ");
    const ranges = [...timeOnly.matchAll(TIME_RANGE_IN_TEXT)];
    if (ranges.length >= 2) {
      const total = t.length;
      for (const m of ranges) {
        const w = b.x1 - b.x0;
        out.push({
          text: m[0].trim(),
          x0: b.x0 + (w * m.index) / total,
          x1: Math.max(b.x0 + (w * (m.index + m[0].length)) / total, b.x0 + 1),
          y0: b.y0,
          y1: b.y1,
        });
      }
      continue;
    }
    if (ocr && t.length === 1 && LONE_PUNCTUATION.includes(t)) continue;
    out.push({ ...b, text: t });
  }
  return out;
}

/** Photos are rarely level. Finds the small rotation that makes the rows line up best and applies it. */
function straighten(boxes, h) {
  const score = (deg) => {
    const rad = (deg * Math.PI) / 180;
    const bins = new Map();
    for (const b of boxes) {
      const y = -cx(b) * Math.sin(rad) + cy(b) * Math.cos(rad);
      const k = Math.floor(y / (h / 2));
      bins.set(k, (bins.get(k) || 0) + 1);
    }
    let s = 0;
    for (const v of bins.values()) s += v * v;
    return s;
  };
  const base = score(0);
  let best = 0;
  let bestScore = base;
  for (let deg = -4; deg <= 4; deg += 0.25) {
    const s = score(deg);
    if (s > bestScore) {
      bestScore = s;
      best = deg;
    }
  }
  if (best === 0 || bestScore < base * 1.05) return boxes;
  const rad = (best * Math.PI) / 180;
  return boxes.map((b) => {
    const x = cx(b) * Math.cos(rad) + cy(b) * Math.sin(rad);
    const y = -cx(b) * Math.sin(rad) + cy(b) * Math.cos(rad);
    const w = width(b) / 2;
    const hh = height(b) / 2;
    return { text: b.text, x0: x - w, x1: x + w, y0: y - hh, y1: y + hh };
  });
}

const FULL_RANGE = /^\d{1,2}[:.]\d{2}\s*(?:a\.?m\.?|p\.?m\.?)?\s*[-–—]\s*\d{1,2}[:.]\d{2}\s*(?:a\.?m\.?|p\.?m\.?)?$/i;
const SINGLE_TIME = /^\d{1,2}[:.]\d{2}\s*(?:a\.?m\.?|p\.?m\.?)?$/i;
const isDash = (t) => t.length === 1 && "-–—".includes(t);
/** A time range, a start time or a day name is always a whole cell: never joined to its neighbour. */
const standalone = (t) => FULL_RANGE.test(t.trim()) || SINGLE_TIME.test(t.trim()) || Boolean(dayOf(t));

/**
 * Joins words that sit close together on a line into one piece ("CS" + "Lab" -> "CS Lab", or
 * "9:00" "-" "10:00" -> one range), so a merged cell's text isn't split between two columns.
 */
function joinWords(boxes) {
  const lines = [];
  for (const b of [...boxes].sort((p, q) => cy(p) - cy(q))) {
    const line = lines.find((l) => Math.abs(l.y - cy(b)) <= 0.6 * Math.max(l.h, height(b)));
    if (line) {
      line.boxes.push(b);
      line.y = line.boxes.reduce((sum, x) => sum + cy(x), 0) / line.boxes.length;
      line.h = median(line.boxes.map(height));
    } else lines.push({ y: cy(b), h: height(b), boxes: [b] });
  }
  const out = [];
  for (const line of lines) {
    let current = null;
    for (const b of line.boxes.sort((p, q) => p.x0 - q.x0)) {
      const gap = current ? b.x0 - current.x1 : Infinity;
      const dash = isDash(b.text);
      const afterDash = current && current.text.length > 0 && "-–—".includes(current.text[current.text.length - 1]);
      const timeThenDash = dash && current && SINGLE_TIME.test(current.text.trim());
      const dashThenTime = afterDash && SINGLE_TIME.test(b.text.trim());
      const plain = current && !standalone(b.text) && !standalone(current.text) && !dash && gap <= 0.8 * line.h;
      const range = (timeThenDash || dashThenTime) && gap <= 1.6 * line.h;
      if (current && (plain || range)) {
        current.text += ` ${b.text}`;
        current.x1 = Math.max(current.x1, b.x1);
        current.y0 = Math.min(current.y0, b.y0);
        current.y1 = Math.max(current.y1, b.y1);
      } else {
        if (current) out.push(current);
        current = { ...b };
      }
    }
    if (current) out.push(current);
  }
  return out;
}

const overlap = (a0, a1, b0, b1) => Math.min(a1, b1) - Math.max(a0, b0);

/** Boxes whose horizontal extents overlap belong to the same column; returns groups of boxes. */
function groupByColumn(boxes) {
  const groups = [];
  for (const b of [...boxes].sort((p, q) => p.x0 - q.x0)) {
    const g = groups.find((c) => overlap(b.x0, b.x1, c.x0, c.x1) >= 0.3 * Math.min(width(b), c.x1 - c.x0));
    if (g) {
      g.boxes.push(b);
      g.x0 = Math.min(g.x0, b.x0);
      g.x1 = Math.max(g.x1, b.x1);
    } else {
      groups.push({ x0: b.x0, x1: b.x1, boxes: [b] });
    }
  }
  return groups;
}

/** Text of a group of boxes read top to bottom, left to right. */
function joinBoxes(boxes, h) {
  const ordered = [...boxes].sort((a, b) => {
    const la = Math.round(cy(a) / (h * 0.5));
    const lb = Math.round(cy(b) / (h * 0.5));
    return la !== lb ? cy(a) - cy(b) : a.x0 - b.x0;
  });
  // words on one line first, then lines
  const lines = [];
  for (const b of ordered) {
    const line = lines.find((l) => Math.abs(l.y - cy(b)) <= 0.6 * h);
    if (line) line.boxes.push(b);
    else lines.push({ y: cy(b), boxes: [b] });
  }
  return lines
    .sort((a, b) => a.y - b.y)
    .map((l) => l.boxes.sort((a, b) => a.x0 - b.x0).map((b) => b.text).join(" "))
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

const fmt = (min) => `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;

/** When the header only lists start times ("9:00 10:00 11:00..."), turn them into ranges. */
function startsToRanges(columns) {
  const starts = columns.map((c) => ({ c, t: parseSingleTime(c.text) })).filter((x) => x.t !== null);
  if (starts.length < 3 || !starts.every((s, i) => i === 0 || s.t > starts[i - 1].t)) return false;
  starts.forEach((s, i) => {
    const end = i + 1 < starts.length ? starts[i + 1].t : s.t + (s.t - starts[i - 1].t);
    s.c.text = `${fmt(s.t)} - ${fmt(end)}`;
  });
  return true;
}

/**
 * Reads a table whose days run down the side and whose times run across the top.
 * Returns { rows, bottom } or null when it doesn't find that structure.
 */
function anchoredGrid(boxes, h) {
  // ---- the day names: a column of boxes
  const dayBoxes = boxes.filter((b) => dayOf(b.text));
  if (dayBoxes.length < 2) return null;

  const medianWidth = median(dayBoxes.map(width));
  const dayGroups = [];
  for (const b of [...dayBoxes].sort((p, q) => cx(p) - cx(q))) {
    const g = dayGroups.find((c) => Math.abs(cx(b) - c.mean) <= 0.6 * medianWidth + h);
    if (g) {
      g.boxes.push(b);
      g.mean = g.boxes.reduce((s, x) => s + cx(x), 0) / g.boxes.length;
    } else dayGroups.push({ mean: cx(b), boxes: [b] });
  }
  const dayColumn = dayGroups
    .map((g) => ({ ...g, distinct: new Set(g.boxes.map((b) => dayOf(b.text))).size }))
    .sort((a, b) => b.distinct - a.distinct || b.boxes.length - a.boxes.length)[0];
  if (dayColumn.distinct < 2) return null;
  const labels = [...dayColumn.boxes].sort((a, b) => cy(a) - cy(b));
  const dayX0 = Math.min(...labels.map((b) => b.x0));
  const dayX1 = Math.max(...labels.map((b) => b.x1));
  const firstLabelTop = labels[0].y0;

  // ---- the header row: the rows of time-like text above the first day name
  const candidates = boxes
    .filter((b) => TIME_PATTERN.test(b.text) && cy(b) < labels[0].y0 && !dayOf(b.text))
    .sort((a, b) => a.y0 - b.y0);
  const clusters = [];
  for (const b of candidates) {
    const last = clusters[clusters.length - 1];
    if (last && b.y0 <= last.y1 + h) {
      last.boxes.push(b);
      last.y1 = Math.max(last.y1, b.y1);
    } else clusters.push({ boxes: [b], y0: b.y0, y1: b.y1 });
  }
  let headerCluster = null;
  for (const c of clusters) {
    c.cells = groupByColumn(c.boxes).length;
    if (c.cells >= 3 && (!headerCluster || c.cells >= headerCluster.cells)) headerCluster = c; // nearest to the days wins ties
  }
  if (!headerCluster) return null;

  // everything in that band (also a lone "pm" on its own line, or "LUNCH") is part of the header
  const bandTop = headerCluster.y0 - 0.6 * h;
  const bandBottom = headerCluster.y1 + 0.6 * h;
  const bandBoxes = boxes.filter((b) => cy(b) >= bandTop && cy(b) <= bandBottom && !labels.includes(b));
  let columns = groupByColumn(bandBoxes).map((g) => ({
    x0: g.x0,
    x1: g.x1,
    boxes: g.boxes,
    text: joinBoxes(g.boxes, h),
  }));

  // the header cell above the day names ("Time/Day", "DAY") belongs to the day column
  const dayHeader = columns.find((c) => overlap(c.x0, c.x1, dayX0 - h, dayX1 + h) > 0);
  let timeCols = columns.filter((c) => c !== dayHeader).sort((a, b) => a.x0 - b.x0);
  if (timeCols.filter((c) => parseRange(c.text)).length < 2) startsToRanges(timeCols);
  if (timeCols.filter((c) => parseRange(c.text)).length < 2) return null;
  // columns to the left of the day names are not part of the table
  timeCols = timeCols.filter((c) => (c.x0 + c.x1) / 2 > dayX1);
  if (timeCols.length < 2) return null;

  // ---- column spans: cut halfway between neighbouring header cells
  const centres = timeCols.map((c) => (c.x0 + c.x1) / 2);
  const dayCentre = (dayX0 + dayX1) / 2;
  const cuts = [(dayCentre + centres[0]) / 2];
  for (let i = 0; i < centres.length - 1; i++) cuts.push((centres[i] + centres[i + 1]) / 2);
  cuts.push(centres[centres.length - 1] + (centres[centres.length - 1] - cuts[cuts.length - 1]));
  const spans = timeCols.map((_, i) => ({ start: cuts[i], end: cuts[i + 1] }));

  // ---- row bands: cut halfway between neighbouring day names
  const ys = labels.map(cy);
  const gaps = ys.slice(1).map((y, i) => y - ys[i]);
  const spacing = median(gaps) || 4 * h;
  const tops = ys.map((y, i) => (i === 0 ? Math.max(y - spacing / 2, bandBottom) : (ys[i - 1] + y) / 2));
  const bottoms = ys.map((y, i) => (i === ys.length - 1 ? y + spacing / 2 : (y + ys[i + 1]) / 2));

  // ---- put every other box in its cell
  const cells = labels.map(() => timeCols.map(() => []));
  for (const b of boxes) {
    if (labels.includes(b) || bandBoxes.includes(b)) continue;
    const y = cy(b);
    const row = tops.findIndex((t, i) => y >= t && y < bottoms[i]);
    if (row < 0 || cx(b) < cuts[0]) continue;
    const w = Math.max(width(b), 1);
    let covered = spans.map((s, i) => i).filter((i) => overlap(b.x0, b.x1, spans[i].start, spans[i].end) >= 0.2 * w);
    if (covered.length === 0) {
      let best = 0;
      centres.forEach((c, i) => {
        if (Math.abs(c - cx(b)) < Math.abs(centres[best] - cx(b))) best = i;
      });
      covered = [best];
    }
    for (const i of covered) cells[row][i].push(b);
  }

  const rows = [[dayHeader ? dayHeader.text : "", ...timeCols.map((c) => c.text)]];
  labels.forEach((label, r) => {
    rows.push([label.text, ...cells[r].map((boxesInCell) => joinBoxes(boxesInCell, h))]);
  });
  return { rows, bottom: bottoms[bottoms.length - 1] };
}

const transpose = (boxes) => boxes.map((b) => ({ text: b.text, x0: b.y0, y0: b.x0, x1: b.y1, y1: b.x1 }));

// ------------------------------------------------------------------ course names

/** The course list that usually sits under a timetable: "23CSE301  Machine Learning  Dr. ..." */
function courseNames(boxes, below, h) {
  const names = new Map();
  const lower = boxes.filter((b) => cy(b) > below).sort((a, b) => cy(a) - cy(b));
  const lines = [];
  for (const b of lower) {
    const line = lines.find((l) => Math.abs(l.y - cy(b)) <= 0.6 * h);
    if (line) line.boxes.push(b);
    else lines.push({ y: cy(b), boxes: [b] });
  }
  for (const line of lines) {
    const cellsInLine = [];
    for (const b of line.boxes.sort((a, c) => a.x0 - c.x0)) {
      const last = cellsInLine[cellsInLine.length - 1];
      if (last && b.x0 - last.x1 <= 1.0 * h) {
        last.text += ` ${b.text}`;
        last.x1 = Math.max(last.x1, b.x1);
      } else cellsInLine.push({ text: b.text, x0: b.x0, x1: b.x1 });
    }
    const [code, name] = cellsInLine;
    if (code && name && COURSE_CODE.test(code.text.trim()) && !COURSE_CODE.test(name.text.trim()) && name.text.length >= 3) {
      names.set(code.text.trim(), name.text.trim().slice(0, 60));
    }
  }
  return names;
}

/** The same course list, read from the rows of a spreadsheet: a cell with a code, then the next cell is the name. */
function courseNamesFromRows(rows) {
  const names = new Map();
  for (const row of rows) {
    const cells = row.map((c) => String(c || "").trim());
    const i = cells.findIndex((c) => COURSE_CODE.test(c));
    if (i < 0) continue;
    const name = cells.slice(i + 1).find((c) => c);
    if (name && !COURSE_CODE.test(name) && name.length >= 3 && !/^\d+$/.test(name)) names.set(cells[i], name.slice(0, 60));
  }
  return names;
}

/** "23CSE302/Tutorial/Achala" -> "Computer Networks (23CSE302) · Tutorial/Achala" */
function applyCourseNames(entries, names) {
  if (!names.size) return entries;
  return entries.map((e) => {
    const m = COURSE_CODE_START.exec(e.title);
    if (!m || !names.has(m[1])) return e;
    const rest = m[2].replace(/^[\s/\-–·]+/, "").trim();
    const title = (rest ? `${names.get(m[1])} (${m[1]}) · ${rest}` : `${names.get(m[1])} (${m[1]})`).slice(0, 100);
    return { ...e, title };
  });
}

// ---------------------------------------------------------------------- entry point

/**
 * @param pages [{ boxes: [{ text, x0, y0, x1, y1 }] }]  one entry per page or picture
 * @param options.ocr true for text read from a photo (straightens tilt, drops stray fragments)
 * @returns { entries, confident, layout } for the best page
 */
function readPages(pages, { ocr = false } = {}) {
  let best = { entries: [], confident: false, layout: null };
  for (const page of pages) {
    let boxes = clean(page.boxes || [], { ocr });
    if (boxes.length < 8) continue;
    const h = Math.max(median(boxes.map(height)), 4);
    if (ocr) boxes = straighten(boxes, h);
    boxes = joinWords(boxes);

    const attempts = [
      { layout: "days-as-rows", table: anchoredGrid(boxes, h), flipped: false },
      { layout: "days-as-columns", table: anchoredGrid(transpose(boxes), h), flipped: true },
    ];
    for (const attempt of attempts) {
      if (!attempt.table) continue;
      const parsed = parseGrid(attempt.table.rows);
      if (parsed.entries.length <= best.entries.length) continue;
      // the course list lies below the table (only meaningful in the upright layout)
      const names = attempt.flipped ? new Map() : courseNames(boxes, attempt.table.bottom, h);
      best = {
        entries: applyCourseNames(parsed.entries, names),
        confident: parsed.confident,
        layout: attempt.layout,
      };
    }
  }
  return best;
}

/** A plain-text version of the pages (one line per row of text), for the AI reader. */
function pagesToText(pages, limit = 20000) {
  const lines = [];
  for (const page of pages) {
    const boxes = joinWords(clean(page.boxes || [], { ocr: true }));
    const rows = [];
    for (const b of [...boxes].sort((p, q) => cy(p) - cy(q))) {
      const row = rows.find((r) => Math.abs(r.y - cy(b)) <= 0.6 * height(b));
      if (row) row.boxes.push(b);
      else rows.push({ y: cy(b), boxes: [b] });
    }
    for (const r of rows) lines.push(`R${lines.length + 1}: ${r.boxes.sort((p, q) => p.x0 - q.x0).map((b) => b.text).join(" | ")}`);
  }
  return lines.join("\n").slice(0, limit);
}

module.exports = { readPages, pagesToText, courseNamesFromRows, clean, straighten, anchoredGrid, courseNames, applyCourseNames };
