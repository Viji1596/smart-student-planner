// An Excel version of the university timetable: title bar, info row, "Slot" row, times with line breaks,
// merged cells, vertical merged break columns, multi-line cells, and the course list underneath.
const ExcelJS = require("exceljs");
async function build(path) {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet("III CSE D");
  const cols = 13; // A = day, B..M = slots
  ws.addRow(["TIME TABLE"]); ws.mergeCells("A1:M1");
  ws.addRow(["Dept:CSE", "Semester: V", "", "Class: B.Tech CSE", "", "", "Section: D", "", "AB III - C402", "", "Class Advisors: Dr. Anoop Vijay\nDr. N Lalithamani", "", ""]);
  ws.addRow(["", "Slot 1", "Slot 2", "Slot 3", "", "Slot 4", "Slot 5", "Slot 6", "Slot 7", "Slot 8", "Slot 9", "Slot 10", "Slot 12"]);
  ws.addRow(["Time/Day", "8.00 am -\n8.50 am", "8.50 am -\n09.40 am", "09.40 am -\n10.30 am", "10.30 am -\n10.45 am", "10.45 am -\n11.35 am", "11.35 am -\n12.25 pm", "12.25 pm - 1.15\npm", "01.15 pm -\n2.05 pm", "2.05 pm - 2.55 pm", "2.55 pm - 3.45 pm", "3.45 pm - 4.35pm", "5.25 pm -\n6.15 pm"]);
  const r = (a) => { const row = ws.addRow(a); return row.number; };
  const mon = r(["Monday", "", "23CSE303", "PE1", "Interval Break", "23CSE304", "23CSE302/Tutorial/Achala", "", "Lunch Break", "PE2", "23CSE303 - Lab", "", ""]);
  const tue = r(["Tuesday", "23ENV500", "PE2/23CSE465", "23CSE301", "", "OR\nB.Tech - CSE - V\nSem - D\n(APT)\nMs. Anitha M", "OR\nB.Tech - CSE - V\nSem - D\n(S/ER)\nMs. Priyanka K", "", "", "23CSE302", "23CSE304 - Lab", "", ""]);
  const wed = r(["Wednesday", "", "", "23CSE304", "", "23CSE303", "23CSE2020", "23CSE301", "", "PE1\n23CSE440\nAdvanced Algorithms and\nAnalysis\nAchala", "", "", ""]);
  const thu = r(["Thursday", "", "PE1\nOccupied by IV\nYr Elective", "23CSE302\nOccupied by\nIV Yr Elective", "", "PE2", "23CSE303", "Mentoring\nDr. N Lalithamani\nDr. Anoop Vijay", "", "23CSE304", "23CSE301", "", ""]);
  const fri = r(["Friday", "23CSE303/Tutorial", "", "PE1", "", "PE2 (All electives slot 4 except) Full Stack Frame Works and Mobile Application Development)\n23CSE461 - Full Stack Frameworks(slot 4,5,6)", "", "", "", "PE1\n23CSE440\nAdvanced Algorithms and\nAnalysis\nAchala", "23CSE300 - Lab\nOccupied by IV Year", "", ""]);
  // merges: wide cells and the vertical break columns
  ws.mergeCells(`E${mon}:E${fri}`);          // Interval Break (vertical)
  ws.mergeCells(`I${mon}:I${fri}`);          // Lunch Break (vertical)
  ws.getCell(`I${mon}`).value = "Lunch Break";
  ws.mergeCells(`G${mon}:H${mon}`);          // Monday slots 5-6
  ws.mergeCells(`K${mon}:L${mon}`);          // lab slots 9-10
  ws.mergeCells(`K${tue}:L${tue}`);
  ws.mergeCells(`B${fri}:C${fri}`);
  ws.mergeCells(`F${fri}:H${fri}`);
  ws.mergeCells(`K${fri}:L${fri}`);
  // course list
  ws.addRow([]); ws.addRow(["Theory"]);
  ws.addRow(["Course Code", "Course Name", "", "", "", "Faculty", "", "", "", "Venue"]);
  ws.addRow(["23CSE301", "Machine Learning", "", "", "", "Dr. Divya singh", "", "", "", "ABIII - TF - C402"]);
  ws.addRow(["23CSE302", "Computer Networks", "", "", "", "Mr. Arjun P K", "", "", "", "ABIII - TF - C402"]);
  ws.addRow(["23CSE303", "Theory of Computation", "", "", "", "Dr. M. Senthilkumar", "", "", "", "ABIII - TF - C402"]);
  ws.addRow(["23CSE304", "Embedded Systems", "", "", "", "Dr S Thangavelu", "", "", "", "ABIII - TF - C402"]);
  await wb.xlsx.writeFile(path);
}
module.exports = { build };
if (require.main === module) build(process.argv[2]).then(() => console.log("written"));
