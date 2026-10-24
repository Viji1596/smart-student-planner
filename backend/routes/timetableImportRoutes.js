const express = require("express");
const rateLimit = require("express-rate-limit");
const { z } = require("zod");
const requireAuth = require("../middleware/authMiddleware");
const { parseGrid } = require("../lib/timetableGrid");
const { detectKind, mediaTypeFor, readWorkbook, readCsv, gridToText } = require("../lib/timetableFile");
const { extractWithAi, ImportError } = require("../lib/aiExtract");
const { extractPdfBoxes } = require("../lib/pdfText");
const { readPages, pagesToText, courseNamesFromRows, applyCourseNames } = require("../lib/gridFromBoxes");

const MAX_FILE_BYTES = 8 * 1024 * 1024;

const body = z.object({
  filename: z.string().max(200).default(""),
  data: z.string().min(8).max(12_000_000), // base64
});

// Words (with their positions) the phone recognised in a photo or a scanned PDF: one entry per page.
const pagesBody = z.object({
  pages: z
    .array(
      z.object({
        boxes: z
          .array(
            z.object({
              text: z.string().max(200),
              x0: z.number().finite(),
              y0: z.number().finite(),
              x1: z.number().finite(),
              y1: z.number().finite(),
            })
          )
          .max(4000),
      })
    )
    .min(1)
    .max(5),
});

// Text the phone already recognised in a photo or PDF, put back into rows and columns: one grid per page.
const gridsBody = z.object({
  grids: z.array(z.array(z.array(z.string().max(200)).max(60)).max(300)).min(1).max(5),
});

/**
 * POST / : read a timetable from an Excel/CSV file (no AI needed for common layouts), or from a
 * PDF / photo (needs the AI reader). Returns a preview; nothing is saved here.
 * Dependencies can be replaced in tests.
 */
function buildRouter(deps = {}) {
  const ai = deps.extractWithAi || extractWithAi;
  const config = () => ({
    apiKey: deps.apiKey !== undefined ? deps.apiKey : process.env.ANTHROPIC_API_KEY,
    model: deps.model || process.env.ANTHROPIC_MODEL,
  });

  const router = express.Router();
  router.use(requireAuth);
  router.use(
    rateLimit({
      windowMs: 60 * 60 * 1000,
      limit: 20,
      standardHeaders: true,
      keyGenerator: (req) => req.user.id,
    })
  );

  // Lets the app know whether the AI reader is available (it decides how to read a photo or PDF).
  router.get("/status", (req, res) => res.json({ ai: Boolean(config().apiKey) }));

  router.post("/", async (req, res) => {
    // ---- words and positions the phone recognised in a photo or a scanned PDF
    if (req.body && Array.isArray(req.body.pages)) {
      const parsedPages = pagesBody.safeParse(req.body);
      if (!parsedPages.success) return res.status(400).json({ message: "That text couldn't be used." });
      const { apiKey, model } = config();
      try {
        const best = readPages(parsedPages.data.pages, { ocr: true });
        if (best.confident) return res.json({ entries: best.entries, warnings: [], method: "ocr" });
        if (apiKey) {
          const text = pagesToText(parsedPages.data.pages);
          if (!text) throw new ImportError("No text could be read from the picture.", 422, "layout");
          const out = await ai({ apiKey, model, kind: "ocr", text });
          if (out.entries.length === 0) throw new ImportError("No classes could be found in the picture.", 422, "layout");
          return res.json({ ...out, method: "ai" });
        }
        throw new ImportError(
          "I couldn't make a timetable out of the text in the picture. A screenshot of a whole page is too small to read: if you have the PDF, import the PDF itself. Otherwise try a sharper, straight-on photo of just the table, or an Excel file.",
          422,
          "layout"
        );
      } catch (error) {
        if (error instanceof ImportError) return res.status(error.status).json({ message: error.message, code: error.code });
        console.error("Timetable import (pages) failed:", error);
        return res.status(500).json({ message: "Something went wrong while reading the picture." });
      }
    }

    // ---- text the phone recognised in a photo or PDF (older app versions)
    if (req.body && Array.isArray(req.body.grids)) {
      const grids = gridsBody.safeParse(req.body);
      if (!grids.success) return res.status(400).json({ message: "That text couldn't be used." });
      const { apiKey, model } = config();
      try {
        const results = grids.data.grids.map((rows) => ({ rows, result: parseGrid(rows) }));
        const best = results.sort((a, b) => b.result.entries.length - a.result.entries.length)[0];
        if (best.result.confident) {
          return res.json({ entries: best.result.entries, warnings: [], method: "ocr" });
        }
        // The text didn't form a grid we recognise: let the AI try to make sense of it, if it is set up.
        if (apiKey) {
          const text = gridToText(best.rows);
          if (!text) throw new ImportError("No text could be read from the picture.", 422);
          const out = await ai({ apiKey, model, kind: "ocr", text });
          if (out.entries.length === 0) throw new ImportError("No classes could be found in the picture.", 422);
          return res.json({ ...out, method: "ai" });
        }
        throw new ImportError(
          "I couldn't make a timetable out of the text in the picture. Try a sharper, straight-on photo of just the table, a PDF, or an Excel file.",
          422
        );
      } catch (error) {
        if (error instanceof ImportError) return res.status(error.status).json({ message: error.message });
        console.error("Timetable import (text) failed:", error);
        return res.status(500).json({ message: "Something went wrong while reading the picture." });
      }
    }

    const parsed = body.safeParse(req.body);
    if (!parsed.success) return res.status(400).json({ message: "Send the file as base64 in 'data'." });

    const buffer = Buffer.from(parsed.data.data, "base64");
    if (buffer.length === 0) return res.status(400).json({ message: "The file is empty." });
    if (buffer.length > MAX_FILE_BYTES) return res.status(413).json({ message: "The file is too large (8 MB at most)." });

    const kind = detectKind(buffer, parsed.data.filename);
    const { apiKey, model } = config();

    try {
      if (kind === "xls") {
        throw new ImportError(
          "Old Excel files (.xls) can't be read. Open it, choose Save As > Excel Workbook (.xlsx), or use a PDF or photo.",
          400
        );
      }
      if (kind === "unknown") {
        throw new ImportError("This file type isn't supported. Use an Excel (.xlsx) or CSV file, a PDF, or a photo (JPG, PNG).", 400);
      }

      // ---- spreadsheets: try the built-in grid reader first
      if (kind === "xlsx" || kind === "csv") {
        let sheets;
        try {
          sheets = kind === "xlsx" ? await readWorkbook(buffer) : readCsv(buffer.toString("utf8"));
        } catch {
          throw new ImportError("This file couldn't be opened. Is it a valid Excel or CSV file?", 400);
        }

        const results = sheets.map((s) => ({ sheet: s, result: parseGrid(s.rows) }));
        const best = results.sort((a, b) => b.result.entries.length - a.result.entries.length)[0];
        if (best && best.result.confident) {
          const names = courseNamesFromRows(best.sheet.rows);
          return res.json({ entries: applyCourseNames(best.result.entries, names), warnings: [], method: "grid" });
        }

        // not a layout we recognise: use the AI reader if it is set up
        if (apiKey) {
          const text = gridToText(best ? best.sheet.rows : []);
          if (!text) throw new ImportError("The sheet is empty.", 422);
          const out = await ai({ apiKey, model, kind, text });
          if (out.entries.length === 0) throw new ImportError("No classes could be found in this file.", 422);
          return res.json({ ...out, method: "ai" });
        }
        throw new ImportError(
          "I couldn't recognise the layout of this sheet. Put days and times in a simple grid (days down the side, periods across the top), or add classes by hand.",
          422
        );
      }

      // ---- PDFs: most have real text inside (exported from Excel, Sheets, Word), which is read exactly
      if (kind === "pdf") {
        let pages = [];
        try {
          pages = await extractPdfBoxes(buffer);
        } catch {
          pages = []; // not a readable PDF, or nothing in it
        }
        const words = pages.reduce((n, p) => n + p.boxes.length, 0);
        if (words >= 20) {
          const best = readPages(pages, { ocr: false });
          if (best.confident) return res.json({ entries: best.entries, warnings: [], method: "pdf" });
          if (!apiKey) {
            throw new ImportError(
              "This PDF has text, but I couldn't find a timetable grid in it (days down the side and times across the top). Try an Excel version of it, or add the classes by hand.",
              422,
              "layout"
            );
          }
        } else if (!apiKey) {
          throw new ImportError(
            "This PDF has no readable text (it is probably a scan or a picture). The app will read it as a picture instead.",
            422,
            "scanned"
          );
        }
        const out = await ai({ apiKey, model, kind, mediaType: mediaTypeFor(kind), buffer });
        if (out.entries.length === 0) {
          throw new ImportError(out.warnings[0] || "No classes could be found. Try a clearer file.", 422, "layout");
        }
        return res.json({ ...out, method: "ai" });
      }

      // ---- photos: only the AI reader can read these on the server (the phone reads them itself otherwise)
      if (!apiKey) {
        throw new ImportError(
          "Reading photos on the server needs the AI reader, which isn't set up (ANTHROPIC_API_KEY). The app reads photos on your phone instead.",
          503,
          "no_ai"
        );
      }
      const out = await ai({ apiKey, model, kind, mediaType: mediaTypeFor(kind), buffer });
      if (out.entries.length === 0) {
        throw new ImportError(out.warnings[0] || "No classes could be found. Try a clearer photo.", 422, "layout");
      }
      return res.json({ ...out, method: "ai" });
    } catch (error) {
      if (error instanceof ImportError) return res.status(error.status).json({ message: error.message, code: error.code });
      console.error("Timetable import failed:", error);
      return res.status(500).json({ message: "Something went wrong while reading the file." });
    }
  });

  return router;
}

module.exports = { buildRouter };
