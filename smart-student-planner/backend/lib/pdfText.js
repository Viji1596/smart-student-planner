// Reads the text of a PDF together with where each piece sits on the page.
// Digital PDFs (exported from Excel, Google Sheets, Word...) have real text inside, so this is exact:
// no picture reading, no mistakes, no cost.

const MAX_PAGES = 5;
const MAX_BOXES_PER_PAGE = 4000;

let pdfjsPromise;
function loadPdfjs() {
  if (!pdfjsPromise) pdfjsPromise = import("pdfjs-dist/legacy/build/pdf.mjs");
  return pdfjsPromise;
}

/**
 * @returns pages: [{ width, height, boxes: [{ text, x0, y0, x1, y1 }] }]  (y grows downwards)
 * Sideways text (like the "Interval Break" label that runs up a column) is skipped.
 */
async function extractPdfBoxes(buffer, { maxPages = MAX_PAGES } = {}) {
  const pdfjs = await loadPdfjs();
  const doc = await pdfjs.getDocument({
    data: new Uint8Array(buffer),
    isEvalSupported: false,
    useSystemFonts: false,
    disableFontFace: true,
    verbosity: 0,
  }).promise;

  const pages = [];
  try {
    for (let n = 1; n <= Math.min(doc.numPages, maxPages); n++) {
      const page = await doc.getPage(n);
      const [, , width, height] = page.view;
      const content = await page.getTextContent();
      const boxes = [];
      for (const item of content.items) {
        const text = (item.str || "").trim();
        if (!text) continue;
        const [a, b, , , e, f] = item.transform;
        if (Math.abs(b) > Math.abs(a)) continue; // sideways text
        const h = item.height || Math.abs(item.transform[3]) || 8;
        const w = item.width || text.length * h * 0.5;
        const y1 = height - f;
        boxes.push({ text, x0: e, y0: y1 - h, x1: e + w, y1 });
        if (boxes.length >= MAX_BOXES_PER_PAGE) break;
      }
      pages.push({ width, height, boxes });
      page.cleanup();
    }
  } finally {
    await doc.destroy();
  }
  return pages;
}

module.exports = { extractPdfBoxes };
