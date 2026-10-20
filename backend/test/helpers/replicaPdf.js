// Builds a PDF that looks like a real university timetable (as exported from a spreadsheet):
// a title bar, an info row, a "Slot" row, a times row whose cells wrap onto two lines, day names down the
// side, merged cells, vertical "Interval Break" / "Lunch Break" columns, multi-line cells, and a course
// list underneath. Used to test reading real PDFs.
const PDFDocument = require("pdfkit");

function build({ legend = true, pages = 1 } = {}) {
  return new Promise((resolve) => {
    const doc = new PDFDocument({ size: [900, 800], margin: 0 });
    const chunks = [];
    doc.on("data", (c) => chunks.push(c));
    doc.on("end", () => resolve(Buffer.concat(chunks)));

    const left = 40;
    const dayW = 60;
    // columns: [label, width]
    const cols = [
      ["8.00 am -\n8.50 am", 62], ["8.50 am -\n09.40 am", 62], ["09.40 am -\n10.30 am", 62],
      ["10.30 am -\n10.45 am", 34], // interval break
      ["10.45 am -\n11.35 am", 62], ["11.35 am -\n12.25 pm", 62], ["12.25 pm - 1.15\npm", 66],
      ["01.15 pm -\n2.05 pm", 40], // lunch break
      ["2.05 pm - 2.55 pm", 90], ["2.55 pm - 3.45 pm", 90], ["3.45 pm - 4.35pm", 90], ["5.25 pm -\n6.15 pm", 62],
    ];
    const xs = [left + dayW];
    for (const [, w] of cols) xs.push(xs[xs.length - 1] + w);
    const slotY = 140, timeY = 160, timeH = 34, rowTop = timeY + timeH;
    const rowH = [46, 70, 46, 56, 70];
    const rowY = [rowTop];
    for (const h of rowH) rowY.push(rowY[rowY.length - 1] + h);

    const center = (text, x0, x1, y0, y1, size = 7, opts = {}) => {
      doc.fontSize(size).fillColor("black");
      const lines = text.split("\n");
      const lh = size * 1.2;
      let y = (y0 + y1) / 2 - (lines.length * lh) / 2;
      for (const line of lines) {
        doc.text(line, x0, y, { width: x1 - x0, align: "center", lineBreak: false, ...opts });
        y += lh;
      }
    };
    const box = (x0, y0, x1, y1, fill) => {
      if (fill) doc.rect(x0, y0, x1 - x0, y1 - y0).fillAndStroke(fill, "black");
      else doc.rect(x0, y0, x1 - x0, y1 - y0).stroke("black");
    };

    // title bar and info row
    box(left, 60, xs[xs.length - 1], 85, "#70ad47");
    center("TIME TABLE", left, xs[xs.length - 1], 60, 85, 10, { bold: true });
    box(left, 85, xs[xs.length - 1], 120, "#ffd966");
    doc.fontSize(7).fillColor("black");
    doc.text("Dept:CSE", left + 4, 97, { lineBreak: false });
    doc.text("Semester: V", left + 90, 97, { lineBreak: false });
    doc.text("Class: B.Tech CSE", left + 200, 97, { lineBreak: false });
    doc.text("Section: D", left + 330, 97, { lineBreak: false });
    doc.text("AB III - C402", left + 430, 97, { lineBreak: false });
    doc.text("Class Advisors: Dr. Anoop Vijay", left + 520, 92, { lineBreak: false });
    doc.text("Dr. N Lalithamani", left + 520, 102, { lineBreak: false });

    // slot row and times row
    box(left, slotY, left + dayW, timeY + timeH, "#ffd966");
    center("Time/Day", left, left + dayW, timeY, timeY + timeH, 7);
    const slotNames = ["Slot 1", "Slot 2", "Slot 3", "", "Slot 4", "Slot 5", "Slot 6", "Slot 7", "Slot 8", "Slot 9", "Slot 10", "Slot 12"];
    cols.forEach(([label], i) => {
      box(xs[i], slotY, xs[i + 1], timeY, "#e7e6e6");
      center(slotNames[i], xs[i], xs[i + 1], slotY, timeY, 6);
      box(xs[i], timeY, xs[i + 1], timeY + timeH, "#e7e6e6");
      center(label, xs[i], xs[i + 1], timeY, timeY + timeH, 6);
    });

    // day names
    const days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"];
    days.forEach((d, r) => {
      box(left, rowY[r], left + dayW, rowY[r + 1], "#ffd966");
      center(d, left, left + dayW, rowY[r], rowY[r + 1], 7);
    });
    // empty body cells
    days.forEach((_, r) => cols.forEach((__, c) => box(xs[c], rowY[r], xs[c + 1], rowY[r + 1])));

    // cell helper: spans columns a..b of a day row
    const cell = (r, a, b, text, fill) => {
      box(xs[a], rowY[r], xs[b + 1], rowY[r + 1], fill);
      center(text, xs[a], xs[b + 1], rowY[r], rowY[r + 1], 6);
    };

    // Monday
    cell(0, 1, 1, "23CSE303");
    cell(0, 2, 2, "PE1", "#a6bfc4");
    cell(0, 4, 4, "23CSE304");
    cell(0, 5, 6, "23CSE302/Tutorial/Achala");
    cell(0, 8, 8, "PE2", "#f4c6f7");
    cell(0, 9, 10, "23CSE303 - Lab", "#00ffff");
    // Tuesday
    cell(1, 0, 0, "23ENV500");
    cell(1, 1, 1, "PE2/23CSE465", "#f4c6f7");
    cell(1, 2, 2, "23CSE301");
    cell(1, 4, 4, "OR\nB.Tech - CSE - V\nSem - D\n(APT)\nMs. Anitha M", "#fce4d6");
    cell(1, 5, 5, "OR\nB.Tech - CSE - V\nSem - D\n(S/ER)\nMs. Priyanka K", "#fce4d6");
    cell(1, 8, 8, "23CSE302");
    cell(1, 9, 10, "23CSE304 - Lab", "#00ffff");
    // Wednesday
    cell(2, 2, 2, "23CSE304");
    cell(2, 4, 4, "23CSE303");
    cell(2, 5, 5, "23CSE2020");
    cell(2, 6, 6, "23CSE301");
    cell(2, 8, 8, "PE1\n23CSE440\nAdvanced Algorithms and\nAnalysis\nAchala");
    // Thursday
    cell(3, 1, 1, "PE1\nOccupied by IV\nYr Elective", "#a6bfc4");
    cell(3, 2, 2, "23CSE302\nOccupied by\nIV Yr Elective");
    cell(3, 4, 4, "PE2", "#f4c6f7");
    cell(3, 5, 5, "23CSE303");
    cell(3, 6, 6, "Mentoring\nDr. N Lalithamani\nDr. Anoop Vijay");
    cell(3, 8, 8, "23CSE304");
    cell(3, 9, 9, "23CSE301");
    // Friday
    cell(4, 0, 1, "23CSE303/Tutorial");
    cell(4, 2, 2, "PE1", "#a6bfc4");
    cell(4, 4, 6, "PE2 (All electives slot 4 except) Full Stack Frame\nWorks and Mobile Application Development)\n23CSE461 - Full Stack Frameworks(slot 4,5,6)", "#f4c6f7");
    cell(4, 8, 8, "PE1\n23CSE440\nAdvanced Algorithms and\nAnalysis\nAchala");
    cell(4, 9, 10, "23CSE300 - Lab\nOccupied by IV Year", "#00ffff");

    // vertical break columns (sideways text, as in the real file)
    const vertical = (c, text) => {
      const x = (xs[c] + xs[c + 1]) / 2;
      box(xs[c], rowY[0], xs[c + 1], rowY[5], "#00b0f0");
      doc.save();
      doc.translate(x + 3, (rowY[0] + rowY[5]) / 2 + 20).rotate(-90);
      doc.fontSize(6).fillColor("black").text(text, 0, 0, { lineBreak: false });
      doc.restore();
    };
    vertical(3, "Interval Break");
    vertical(7, "Lunch Break");

    if (legend) {
      // the course list underneath
      let y = rowY[5] + 30;
      const header = (t) => {
        box(left, y, xs[xs.length - 1], y + 12, "#ffd966");
        center(t, left, xs[xs.length - 1], y, y + 12, 7);
        y += 12;
      };
      const row = (code, name, faculty, venue) => {
        doc.fontSize(6).fillColor("black");
        doc.text(code, left + 4, y + 3, { lineBreak: false });
        doc.text(name, left + 80, y + 3, { lineBreak: false });
        doc.text(faculty, left + 330, y + 3, { lineBreak: false });
        doc.text(venue, left + 470, y + 3, { lineBreak: false });
        y += 18;
      };
      header("Theory");
      doc.fontSize(6);
      row("Course Code", "Course Name", "Faculty", "Venue");
      row("23CSE301", "Machine Learning", "Dr. Divya singh", "ABIII - TF - C402");
      row("23CSE302", "Computer Networks", "Mr. Arjun P K", "ABIII - TF - C402");
      row("23CSE303", "Theory of Computation", "Dr. M. Senthilkumar", "ABIII - TF - C402");
      row("23CSE304", "Embedded Systems", "Dr S Thangavelu", "ABIII - TF - C402");
      row("23ENV500", "Environmental Science", "Dr. Nikhil Raj P", "Online");
      row("23CSE440", "Advanced Algorithms and Analysis", "Dr. Vidhya Balasubramanian", "ABIII - TF - Achala / C104");
    }

    if (pages > 1) {
      doc.addPage({ size: [900, 800], margin: 0 });
      doc.fontSize(8).text("23CSE371   Digital Image Processing   Ms. D Bharathi", 40, 60, { lineBreak: false });
    }
    doc.end();
  });
}

module.exports = { build };
