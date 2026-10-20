const ExcelJS = require("exceljs");

const MAX_ROWS = 300;
const MAX_COLS = 60;

/** Works out what a file really is from its first bytes (not from its name). */
function detectKind(buf, filename = "") {
  const name = String(filename).toLowerCase();
  if (buf.length < 4) return "unknown";
  const hex = buf.subarray(0, 8).toString("hex");
  if (hex.startsWith("504b0304")) {
    // a zip file: a modern Excel workbook has [Content_Types].xml and an xl/ folder
    const head = buf.toString("latin1", 0, Math.min(buf.length, 4096));
    return name.endsWith(".docx") || name.endsWith(".pptx") ? "unknown" : head.includes("xl/") || name.endsWith(".xlsx") ? "xlsx" : "unknown";
  }
  if (buf.toString("latin1", 0, 5) === "%PDF-") return "pdf";
  if (hex.startsWith("89504e470d0a1a0a")) return "png";
  if (hex.startsWith("ffd8ff")) return "jpeg";
  if (buf.toString("latin1", 0, 4) === "RIFF" && buf.toString("latin1", 8, 12) === "WEBP") return "webp";
  if (hex.startsWith("d0cf11e0a1b11ae1")) return "xls"; // old binary Excel
  // plain text: only treat as CSV if the name says so and it has no binary bytes
  if ((name.endsWith(".csv") || name.endsWith(".tsv") || name.endsWith(".txt")) && !buf.includes(0)) return "csv";
  return "unknown";
}

const MEDIA_TYPES = { pdf: "application/pdf", png: "image/png", jpeg: "image/jpeg", webp: "image/webp" };
const mediaTypeFor = (kind) => MEDIA_TYPES[kind] || null;

function pad(n) {
  return String(n).padStart(2, "0");
}

function cellToText(value) {
  if (value == null) return "";
  if (value instanceof Date) {
    // Excel stores a time of day as a date in 1899/1900
    const y = value.getUTCFullYear();
    if (y <= 1900) return `${pad(value.getUTCHours())}:${pad(value.getUTCMinutes())}`;
    return "";
  }
  if (typeof value === "object") {
    if (Array.isArray(value.richText)) return value.richText.map((t) => t.text).join("");
    if ("result" in value) return cellToText(value.result);
    if ("text" in value) return String(value.text ?? "");
    return "";
  }
  return String(value);
}

/** Every sheet of a workbook as a grid of text. Merged cells repeat the merged value in each covered cell. */
async function readWorkbook(buf) {
  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.load(buf);
  const sheets = [];
  workbook.eachSheet((ws) => {
    const rowCount = Math.min(ws.rowCount, MAX_ROWS);
    const colCount = Math.min(ws.columnCount, MAX_COLS);
    const rows = [];
    for (let r = 1; r <= rowCount; r++) {
      const row = ws.getRow(r);
      const cells = [];
      for (let c = 1; c <= colCount; c++) {
        const cell = row.getCell(c);
        const source = cell.isMerged && cell.master ? cell.master : cell;
        cells.push(cellToText(source.value).trim());
      }
      rows.push(cells);
    }
    sheets.push({ name: ws.name, rows });
  });
  return sheets;
}

/** A small CSV/TSV reader: quotes, escaped quotes, and ; , or tab as the separator. */
function readCsv(text) {
  const clean = text.replace(/^\uFEFF/, "");
  const firstLine = clean.split(/\r?\n/, 1)[0] || "";
  const counts = [",", ";", "\t"].map((d) => [d, firstLine.split(d).length - 1]);
  const delimiter = counts.sort((a, b) => b[1] - a[1])[0][0];

  const rows = [];
  let row = [];
  let field = "";
  let quoted = false;
  for (let i = 0; i < clean.length && rows.length < MAX_ROWS; i++) {
    const ch = clean[i];
    if (quoted) {
      if (ch === '"' && clean[i + 1] === '"') {
        field += '"';
        i++;
      } else if (ch === '"') quoted = false;
      else field += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === delimiter) {
      row.push(field.trim());
      field = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && clean[i + 1] === "\n") i++;
      row.push(field.trim());
      rows.push(row.slice(0, MAX_COLS));
      row = [];
      field = "";
    } else field += ch;
  }
  if (field !== "" || row.length) {
    row.push(field.trim());
    rows.push(row.slice(0, MAX_COLS));
  }
  return [{ name: "csv", rows }];
}

/** A compact text version of a grid, for the AI reader. */
function gridToText(rows, limit = 20000) {
  const lines = rows
    .map((r, i) => `R${i + 1}: ${r.map((c) => c.replace(/\s*\n\s*/g, " / ")).join(" | ")}`)
    .filter((l) => l.replace(/R\d+:|\|/g, "").trim());
  return lines.join("\n").slice(0, limit);
}

module.exports = { detectKind, mediaTypeFor, readWorkbook, readCsv, gridToText, cellToText };
