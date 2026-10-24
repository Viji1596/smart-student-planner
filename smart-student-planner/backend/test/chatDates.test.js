const test = require("node:test");
const assert = require("node:assert/strict");
const { parseDate, parseTime, parseDuration, addDays, weekdayOf, label, hhmm } = require("../lib/chat/dates");

const TODAY = "2026-10-21"; // a Wednesday

test("weekday arithmetic", () => {
  assert.equal(weekdayOf(TODAY), 3);
  assert.equal(addDays(TODAY, 1), "2026-10-22");
  assert.equal(addDays("2026-12-31", 1), "2027-01-01");
  assert.equal(label("2026-10-23"), "Fri 23 Oct");
});

test("relative dates", () => {
  const d = (t) => parseDate(t, TODAY)?.date;
  assert.equal(d("due today"), TODAY);
  assert.equal(d("by tonight"), TODAY);
  assert.equal(d("due tomorrow"), "2026-10-22");
  assert.equal(d("due tmrw"), "2026-10-22");
  assert.equal(d("the day after tomorrow"), "2026-10-23");
  assert.equal(d("in 3 days"), "2026-10-24");
  assert.equal(d("in two weeks"), "2026-11-04");
  assert.equal(d("in a week"), "2026-10-28");
  assert.equal(d("next week"), "2026-10-26"); // next Monday
  assert.equal(d("this weekend"), "2026-10-24");
  assert.equal(d("by end of the week"), "2026-10-23");
});

test("weekdays: the next one, today if it is today, and 'next'", () => {
  const d = (t) => parseDate(t, TODAY)?.date;
  assert.equal(d("on friday"), "2026-10-23");
  assert.equal(d("due fri"), "2026-10-23");
  assert.equal(d("monday"), "2026-10-26");
  assert.equal(d("wednesday"), TODAY);          // today is Wednesday
  assert.equal(d("next wednesday"), "2026-10-28"); // "next" means after today
  assert.equal(d("this thursday"), "2026-10-22");
});

test("calendar dates, day first", () => {
  const d = (t) => parseDate(t, TODAY)?.date;
  assert.equal(d("on 25 oct"), "2026-10-25");
  assert.equal(d("due oct 30th"), "2026-10-30");
  assert.equal(d("1st of november"), "2026-11-01");
  assert.equal(d("25/10"), "2026-10-25");
  assert.equal(d("5-11-2026"), "2026-11-05");
  assert.equal(d("3 jan"), "2027-01-03", "a date that has passed this year means next year");
  assert.equal(d("on the 28th"), "2026-10-28");
  assert.equal(d("on the 10th"), "2026-11-10");
});

test("no date, and impossible dates", () => {
  assert.equal(parseDate("do my homework", TODAY), null);
  assert.equal(parseDate("31/02", TODAY), null);
  assert.equal(parseDate("30 feb", TODAY), null);
});

test("the matched words are returned so they can be removed", () => {
  assert.equal(parseDate("finish it by next friday please", TODAY).text, "next friday");
  assert.equal(parseDate("submit on 25 oct", TODAY).text, "25 oct");
});

test("times of day", () => {
  assert.deepEqual(parseTime("at 6pm"), { minutes: 1080, text: "6pm", flexible: false });
  assert.equal(parseTime("18:30").minutes, 1110);
  assert.equal(parseTime("6:30 pm").minutes, 1110);
  assert.equal(parseTime("at 9 am").minutes, 540);
  assert.equal(parseTime("12am").minutes, 0);
  assert.equal(parseTime("12pm").minutes, 720);
  assert.equal(parseTime("at 4").minutes, 960, "a bare 4 means 16:00");
  assert.equal(parseTime("at 10").minutes, 600);
  assert.equal(parseTime("at 13").minutes, 780);
  assert.equal(parseTime("do it nowhere"), null);
});

test("parts of the day are flexible", () => {
  assert.deepEqual(parseTime("in the evening"), { minutes: 1080, text: "evening", flexible: true });
  assert.equal(parseTime("this morning").minutes, 540);
  assert.equal(parseTime("afternoon").minutes, 840);
  assert.equal(parseTime("at 7:15").flexible, false);
});

test("a duration is not mistaken for a time", () => {
  assert.equal(parseTime("for 30 min"), null);
  assert.equal(parseTime("at 2 hours"), null);
});

test("durations", () => {
  const m = (t) => parseDuration(t)?.minutes ?? null;
  assert.equal(m("for 30 min"), 30);
  assert.equal(m("45 minutes"), 45);
  assert.equal(m("takes 2 hours"), 120);
  assert.equal(m("1 hour"), 60);
  assert.equal(m("an hour"), 60);
  assert.equal(m("half an hour"), 30);
  assert.equal(m("1.5 hrs"), 90);
  assert.equal(m("1h30"), 90);
  assert.equal(m("2 hours 15 minutes"), 135);
  assert.equal(m("2 hours and 15 mins"), 135);
  assert.equal(m("90m"), 90);
  assert.equal(m("no duration here"), null);
  assert.equal(m("5000 minutes"), null);
});

test("formatting", () => {
  assert.equal(hhmm(1110), "18:30");
  assert.equal(hhmm(65), "01:05");
  assert.equal(hhmm(0), "00:00");
});

test("a bare hour means the next such time still ahead today", () => {
  const at = (text, now) => parseTime(text, now)?.minutes;
  assert.equal(at("to 6", 15 * 60), 18 * 60, "at 15:00, 6 is 18:00");
  assert.equal(at("to 6", 5 * 60), 6 * 60, "at 05:00, 6 is 06:00");
  assert.equal(at("at 9", 10 * 60), 21 * 60, "9 already passed this morning: 21:00");
  assert.equal(at("for 7:30", 15 * 60), 19 * 60 + 30, "reschedule reading for 7:30");
  assert.equal(at("6 o'clock", 12 * 60), 18 * 60);
  assert.equal(at("at 12", 9 * 60), 12 * 60, "12 is noon");
  assert.equal(at("to 11", 9 * 60), 11 * 60);
});

test("explicit times are never guessed", () => {
  const at = (text, now) => parseTime(text, now)?.minutes;
  assert.equal(at("to 6am", 15 * 60), 6 * 60);
  assert.equal(at("to 6pm", 5 * 60), 18 * 60);
  assert.equal(at("to 18:30", 5 * 60), 18 * 60 + 30);
  assert.equal(at("to 07:30", 15 * 60), 7 * 60 + 30, "a leading zero is a 24-hour time");
  assert.equal(at("at 14", 5 * 60), 14 * 60);
});

test("numbers that are not times are left alone", () => {
  assert.equal(parseTime("to 5 tasks", 600), null);
  assert.equal(parseTime("for 30 minutes", 600), null);
  assert.equal(parseTime("in 3 days", 600), null);
  assert.equal(parseTime("on the 25th", 600), null);
});

test("tonight and the parts of the day", () => {
  assert.deepEqual(parseTime("tonight", 600), { minutes: 1200, text: "tonight", flexible: true });
  assert.equal(parseTime("this evening").minutes, 1080);
});

