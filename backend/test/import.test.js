const test = require("node:test");
const assert = require("node:assert/strict");
const express = require("express");
const ExcelJS = require("exceljs");

process.env.JWT_SECRET = "test-secret-test-secret-123";

const { parseGrid, parseRange } = require("../lib/timetableGrid");
const { detectKind, readWorkbook, readCsv } = require("../lib/timetableFile");
const ai = require("../lib/aiExtract");
const { buildRouter } = require("../routes/timetableImportRoutes");
const { signAccessToken } = require("../lib/tokens");

// ------------------------------------------------------------------ fixtures
async function workbook(build) {
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet("Timetable");
  build(ws);
  return Buffer.from(await wb.xlsx.writeBuffer());
}

/** Days down the side, periods across, a vertical LUNCH cell and merged lab/double periods. */
const daysAsRows = () =>
  workbook((ws) => {
    ws.addRow(["TIME TABLE 2026-27 (III SEM)"]);
    ws.addRow(["DAY", "9:00-9:50", "9:50-10:40", "10:50-11:40", "11:40-12:30", "LUNCH", "1:30-2:20", "2:20-3:10"]);
    ws.addRow(["Monday", "Maths", "Physics", "CS Lab", "", "", "DBMS", ""]);
    ws.addRow(["Tuesday", "Physics", "Maths", "DBMS", "DBMS", "", "OS", "OS"]);
    ws.addRow(["Wednesday", "CS Lab\nRoom 204", "", "Maths", "Physics", "", "", ""]);
    ws.addRow(["Thursday", "OS", "DBMS", "Maths", "Free", "", "Sports", ""]);
    ws.addRow(["Friday", "Maths", "Maths", "Physics", "OS", "", "", ""]);
    ws.mergeCells("D3:E3"); // Monday lab spans two periods
    ws.mergeCells("G3:H3"); // Monday DBMS spans two periods
    ws.mergeCells("F3:F7"); // LUNCH
    ws.getCell("F3").value = "LUNCH";
    ws.mergeCells("A3:A3");
  });

const daysAsColumns = () =>
  workbook((ws) => {
    ws.addRow(["Time", "Mon", "Tue", "Wed"]);
    ws.addRow(["9:00 - 10:00", "Maths", "Physics", "Maths"]);
    ws.addRow(["10:00 - 11:00", "Maths", "Chemistry", "Chemistry"]);
    ws.addRow(["11:00 - 12:00", "", "Lab", "Lab"]);
    ws.addRow(["12:00 - 1:00", "Lunch", "Lunch", "Lunch"]);
  });

const startTimesOnly = () =>
  workbook((ws) => {
    ws.addRow(["", "9:00", "10:00", "11:00", "12:00"]);
    ws.addRow(["Mon", "Maths", "Physics", "Lab", "Lab"]);
    ws.addRow(["Tue", "OS", "OS", "Maths", ""]);
    ws.addRow(["Wed", "Physics", "", "Maths", "DBMS"]);
  });

const amPm = () =>
  workbook((ws) => {
    ws.addRow(["Day", "9:00 AM - 9:50 AM", "10:00 AM - 10:50 AM", "1:00 PM - 1:50 PM"]);
    ws.addRow(["Mon", "Maths", "Physics", "Lab"]);
    ws.addRow(["Tue", "OS", "DBMS", "Maths"]);
  });

const garbage = () =>
  workbook((ws) => {
    ws.addRow(["Name", "Marks", "Grade"]);
    ws.addRow(["Asha", 91, "A"]);
    ws.addRow(["Ravi", 78, "B"]);
  });

const key = (e) => `${e.dayOfWeek} ${e.startTime}-${e.endTime} ${e.title}`;
const entriesOf = async (buf) => {
  const sheets = await readWorkbook(buf);
  return parseGrid(sheets[0].rows);
};

// ---------------------------------------------------------------- time parsing
test("time ranges in the formats colleges use", () => {
  assert.deepEqual(parseRange("9:00-9:50"), { start: "09:00", end: "09:50" });
  assert.deepEqual(parseRange("9.00 AM - 9.50 AM"), { start: "09:00", end: "09:50" });
  assert.deepEqual(parseRange("1:30-2:20"), { start: "13:30", end: "14:20" });
  assert.deepEqual(parseRange("12:30-1:30"), { start: "12:30", end: "13:30" });
  assert.deepEqual(parseRange("11:30 - 12:30 PM"), { start: "11:30", end: "12:30" });
  assert.deepEqual(parseRange("9-10"), { start: "09:00", end: "10:00" });
  assert.equal(parseRange("Maths"), null);
  assert.equal(parseRange("10:00-9:00"), null);
});

// ------------------------------------------------------------------ grid reader
test("days down the side, with merged double periods and a LUNCH column", async () => {
  const { entries, layout, confident } = await entriesOf(await daysAsRows());
  assert.equal(layout, "days-as-rows");
  assert.ok(confident);
  const keys = entries.map(key);
  assert.ok(keys.includes("1 09:00-09:50 Maths"));
  assert.ok(keys.includes("1 09:50-10:40 Physics"));
  assert.ok(keys.includes("1 10:50-12:30 CS Lab"), "merged lab cells become one class"); // D3:E3
  assert.ok(keys.includes("1 13:30-15:10 DBMS")); // G3:H3
  assert.ok(keys.includes("2 10:50-12:30 DBMS"), "two periods in a row with the same subject join");
  assert.ok(keys.includes("2 13:30-15:10 OS"));
  assert.ok(keys.includes("3 09:00-09:50 CS Lab · Room 204"), "extra lines in a cell are kept in the title");
  assert.ok(!keys.some((k) => /lunch/i.test(k)));
  assert.ok(!keys.some((k) => /Free/i.test(k)), "free periods are left out");
  assert.ok(keys.includes("4 13:30-14:20 Sports"));
});

test("a class never spans the lunch column", async () => {
  const { entries } = await entriesOf(await daysAsRows());
  // Thursday: DBMS 9:50-10:40 and Maths 10:50-11:40 are different; nothing crosses 12:30-1:30
  assert.ok(entries.every((e) => !(e.startTime < "12:30" && e.endTime > "13:00")));
});

test("days across the top, periods down the side", async () => {
  const { entries, layout } = await entriesOf(await daysAsColumns());
  assert.equal(layout, "days-as-columns");
  const keys = entries.map(key);
  assert.ok(keys.includes("1 09:00-11:00 Maths"), "Maths in two rows joins into one");
  assert.ok(keys.includes("2 09:00-10:00 Physics"));
  assert.ok(keys.includes("2 10:00-11:00 Chemistry"));
  assert.ok(keys.includes("2 11:00-12:00 Lab"));
  assert.ok(!keys.some((k) => /Lunch/i.test(k)));
});

test("headers with start times only", async () => {
  const { entries } = await entriesOf(await startTimesOnly());
  const keys = entries.map(key);
  assert.ok(keys.includes("1 09:00-10:00 Maths"));
  assert.ok(keys.includes("1 11:00-13:00 Lab"), "the last slot lasts as long as the one before");
  assert.ok(keys.includes("2 09:00-11:00 OS"));
});

test("AM/PM headers", async () => {
  const { entries } = await entriesOf(await amPm());
  const keys = entries.map(key);
  assert.ok(keys.includes("1 13:00-13:50 Lab"));
  assert.ok(keys.includes("2 10:00-10:50 DBMS"));
});

test("Excel time cells (real times, not text) work as headers", async () => {
  const buf = await workbook((ws) => {
    ws.addRow(["", "", "", "", ""]);
    const header = ws.addRow(["Day"]);
    [9, 10, 11, 12].forEach((h, i) => {
      const c = header.getCell(i + 2);
      c.value = new Date(Date.UTC(1899, 11, 30, h, 0));
      c.numFmt = "h:mm";
    });
    ws.addRow(["Mon", "Maths", "Physics", "Lab", "Lab"]);
    ws.addRow(["Tue", "OS", "OS", "Maths", "DBMS"]);
  });
  const { entries } = await entriesOf(buf);
  const keys = entries.map(key);
  assert.ok(keys.includes("1 09:00-10:00 Maths"), keys.join(" | "));
  assert.ok(keys.includes("2 09:00-11:00 OS"));
});

test("a sheet that isn't a timetable is not accepted as one", async () => {
  const { entries, confident } = await entriesOf(await garbage());
  assert.equal(entries.length, 0);
  assert.equal(confident, false);
});

test("CSV, with quotes and semicolons", () => {
  const csv = 'Day;9:00-10:00;10:00-11:00;11:00-12:00\nMon;"Maths; Sec A";Physics;Lab\nTue;OS;OS;"Maths"\nWed;Lab;Lab;Maths\n';
  const { entries, confident } = parseGrid(readCsv(csv)[0].rows);
  assert.ok(confident);
  assert.ok(entries.map(key).includes("1 09:00-10:00 Maths; Sec A"));
  assert.ok(entries.map(key).includes("2 09:00-11:00 OS"));
});

test("the best sheet of several is used", async () => {
  const wb = new ExcelJS.Workbook();
  const junk = wb.addWorksheet("Notes");
  junk.addRow(["remember", "to", "bring", "ID"]);
  const tt = wb.addWorksheet("Sem 3");
  tt.addRow(["Day", "9:00-10:00", "10:00-11:00", "11:00-12:00"]);
  tt.addRow(["Mon", "Maths", "Physics", "Lab"]);
  tt.addRow(["Tue", "OS", "DBMS", "Maths"]);
  const sheets = await readWorkbook(Buffer.from(await wb.xlsx.writeBuffer()));
  const results = sheets.map((s) => parseGrid(s.rows));
  assert.equal(results[0].entries.length, 0);
  assert.ok(results[1].confident);
});

// --------------------------------------------------------------- file detection
test("file types are recognised by content, not by name", async () => {
  assert.equal(detectKind(await daysAsRows(), "whatever.bin"), "xlsx");
  assert.equal(detectKind(Buffer.from("%PDF-1.7 rest"), "a.pdf"), "pdf");
  assert.equal(detectKind(Buffer.from("89504e470d0a1a0a00", "hex"), "a.png"), "png");
  assert.equal(detectKind(Buffer.from("ffd8ffe000104a46", "hex"), "a.jpg"), "jpeg");
  assert.equal(detectKind(Buffer.concat([Buffer.from("RIFF"), Buffer.from([0, 0, 0, 0]), Buffer.from("WEBPVP8 ")]), "a.webp"), "webp");
  assert.equal(detectKind(Buffer.from("d0cf11e0a1b11ae100", "hex"), "old.xls"), "xls");
  assert.equal(detectKind(Buffer.from("Day,9:00\nMon,Maths"), "tt.csv"), "csv");
  assert.equal(detectKind(Buffer.from("Day,9:00\nMon,Maths"), "tt.exe"), "unknown");
  assert.equal(detectKind(Buffer.from("MZ\u0000\u0000exe"), "tt.csv"), "unknown");
});

// ------------------------------------------------------------------ AI reader
test("the request for a PDF, a photo and spreadsheet text", () => {
  const pdf = ai.buildRequest({ kind: "pdf", base64: "QUJD" });
  assert.equal(pdf.messages[0].content[0].type, "document");
  assert.equal(pdf.messages[0].content[0].source.media_type, "application/pdf");
  const img = ai.buildRequest({ kind: "png", mediaType: "image/png", base64: "QUJD" });
  assert.equal(img.messages[0].content[0].type, "image");
  const text = ai.buildRequest({ kind: "xlsx", text: "R1: a | b" });
  assert.equal(text.messages[0].content[0].type, "text");
  assert.match(text.messages[0].content[0].text, /R1: a \| b/);
  assert.ok(pdf.system.includes("JSON"));
  assert.equal(pdf.model, ai.DEFAULT_MODEL);
});

test("the model's answer is cleaned up and checked", () => {
  const raw = ai.parseModelJson('Here you go:\n```json\n{"entries":[{"day":"Monday","title":"Maths","start":"9:00","end":"09:50 AM"},' +
    '{"day":"Funday","title":"X","start":"09:00","end":"10:00"},{"day":"Tue","title":"Bad","start":"10:00","end":"09:00"},' +
    '{"day":2,"title":"OS","start":"11:00","end":"12:00"}],"notes":["Handwriting was hard to read"]}\n```');
  const { entries, warnings } = ai.normalizeEntries(raw);
  assert.deepEqual(entries.map(key), ["1 09:00-09:50 Maths", "2 11:00-12:00 OS"]);
  assert.ok(warnings.some((w) => /2 items could not be read/.test(w)));
  assert.ok(warnings.includes("Handwriting was hard to read"));
});

test("an unreadable answer is an error, not a crash", () => {
  assert.throws(() => ai.parseModelJson("sorry, I can't"), /couldn't be understood/);
  assert.throws(() => ai.parseModelJson("{not json}"), /couldn't be understood/);
});

function fakeFetch(handler) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    return handler(calls[calls.length - 1]);
  };
  fn.calls = calls;
  return fn;
}
const okReply = (obj) => ({ ok: true, status: 200, json: async () => ({ content: [{ type: "text", text: "```json\n" + JSON.stringify(obj) + "\n```" }] }) });

test("calling the API: headers, body and the parsed result", async () => {
  const f = fakeFetch(() => okReply({ entries: [{ day: "Wednesday", title: "Maths", start: "09:00", end: "09:50" }] }));
  const out = await ai.extractWithAi({ apiKey: "sk-test", kind: "pdf", buffer: Buffer.from("%PDF-x"), fetchImpl: f });
  assert.deepEqual(out.entries.map(key), ["3 09:00-09:50 Maths"]);
  const call = f.calls[0];
  assert.equal(call.url, "https://api.anthropic.com/v1/messages");
  assert.equal(call.init.headers["x-api-key"], "sk-test");
  assert.equal(call.init.headers["anthropic-version"], "2023-06-01");
  assert.equal(call.body.messages[0].content[0].source.data, Buffer.from("%PDF-x").toString("base64"));
});

test("API failures become clear messages", async () => {
  const run = (status, json) =>
    ai.extractWithAi({
      apiKey: "k", kind: "png", mediaType: "image/png", buffer: Buffer.from("x"),
      fetchImpl: fakeFetch(() => ({ ok: false, status, json: async () => json || {} })),
    });
  await assert.rejects(run(401), /API key was rejected/);
  await assert.rejects(run(429), /busy/);
  await assert.rejects(run(500), /failed/);
  await assert.rejects(run(400, { error: { message: "model: not found" } }), /model name/);
  await assert.rejects(
    ai.extractWithAi({ apiKey: "k", kind: "png", mediaType: "image/png", buffer: Buffer.from("x"), fetchImpl: async () => { throw new Error("network"); } }),
    /Couldn't reach/
  );
  await assert.rejects(
    ai.extractWithAi({ apiKey: "k", kind: "png", mediaType: "image/png", buffer: Buffer.from("x"), fetchImpl: async () => { const e = new Error("t"); e.name = "AbortError"; throw e; } }),
    /too long/
  );
});

// ------------------------------------------------------------------------ route
let server, base;
let aiCalls;
const fakeAi = async (args) => {
  aiCalls.push(args);
  return { entries: [{ dayOfWeek: 1, title: "From AI", startTime: "09:00", endTime: "10:00" }], warnings: [] };
};

function startServer(deps) {
  const app = express();
  app.use("/api/timetable/import", express.json({ limit: "14mb" }), buildRouter(deps));
  return new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
}

const post = (user, filename, buf, token = true) =>
  fetch(`${base}/api/timetable/import`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${signAccessToken(user)}` } : {}),
    },
    body: JSON.stringify({ filename, data: buf.toString("base64") }),
  });

test.describe("upload route with the AI reader set up", () => {
  test.before(async () => {
    aiCalls = [];
    server = await startServer({ apiKey: "sk-test", extractWithAi: fakeAi });
    base = `http://127.0.0.1:${server.address().port}`;
  });
  test.after(() => server.close());

  test("requires a login", async () => {
    assert.equal((await post("u", "a.xlsx", await daysAsRows(), false)).status, 401);
  });

  test("an Excel grid is read without the AI", async () => {
    aiCalls.length = 0;
    const res = await post("u1", "tt.xlsx", await daysAsRows());
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.method, "grid");
    assert.ok(body.entries.length > 10);
    assert.equal(aiCalls.length, 0);
  });

  test("a sheet in an unknown layout falls back to the AI, which sees the cell text", async () => {
    aiCalls.length = 0;
    const res = await post("u2", "marks.xlsx", await garbage());
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.method, "ai");
    assert.equal(aiCalls.length, 1);
    assert.match(aiCalls[0].text, /Asha/);
  });

  test("a PDF goes to the AI", async () => {
    aiCalls.length = 0;
    const res = await post("u3", "tt.pdf", Buffer.from("%PDF-1.4 fake"));
    assert.equal(res.status, 200);
    assert.equal(aiCalls[0].kind, "pdf");
    assert.equal(aiCalls[0].mediaType, "application/pdf");
  });

  test("a photo goes to the AI", async () => {
    aiCalls.length = 0;
    const png = Buffer.concat([Buffer.from("89504e470d0a1a0a", "hex"), Buffer.alloc(50)]);
    const res = await post("u4", "photo.png", png);
    assert.equal(res.status, 200);
    assert.equal(aiCalls[0].mediaType, "image/png");
  });

  test("old .xls and unknown files are refused with advice", async () => {
    let res = await post("u5", "old.xls", Buffer.from("d0cf11e0a1b11ae100000000", "hex"));
    assert.equal(res.status, 400);
    assert.match((await res.json()).message, /\.xlsx/);
    res = await post("u5", "virus.exe", Buffer.from("MZ......"));
    assert.equal(res.status, 400);
  });

  test("empty and oversized files", async () => {
    let res = await fetch(`${base}/api/timetable/import`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken("u6")}` },
      body: JSON.stringify({ filename: "a.pdf", data: "" }),
    });
    assert.equal(res.status, 400);
    res = await post("u6", "big.pdf", Buffer.concat([Buffer.from("%PDF-"), Buffer.alloc(8 * 1024 * 1024)]));
    assert.equal(res.status, 413);
  });

  test("a corrupt Excel file is a friendly error", async () => {
    const res = await post("u7", "broken.xlsx", Buffer.concat([Buffer.from("504b0304", "hex"), Buffer.from("xl/ not really a zip")]));
    assert.equal(res.status, 400);
    assert.match((await res.json()).message, /couldn't be opened/);
  });

  test("the AI finding nothing is reported, not saved as an empty timetable", async () => {
    const s = await startServer({ apiKey: "k", extractWithAi: async () => ({ entries: [], warnings: ["Image too blurry"] }) });
    const url = `http://127.0.0.1:${s.address().port}/api/timetable/import`;
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken("u8")}` },
      body: JSON.stringify({ filename: "p.pdf", data: Buffer.from("%PDF-1").toString("base64") }),
    });
    assert.equal(res.status, 422);
    assert.match((await res.json()).message, /blurry/);
    s.close();
  });
});

test.describe("upload route without an API key", () => {
  test.before(async () => {
    aiCalls = [];
    server = await startServer({ apiKey: "", extractWithAi: fakeAi });
    base = `http://127.0.0.1:${server.address().port}`;
  });
  test.after(() => server.close());

  test("Excel grids still work", async () => {
    assert.equal((await post("n1", "tt.xlsx", await daysAsColumns())).status, 200);
  });

  test("a PDF with no readable text tells the app to read it as a picture", async () => {
    const res = await post("n2", "tt.pdf", Buffer.from("%PDF-1.4 fake"));
    assert.equal(res.status, 422);
    const body = await res.json();
    assert.equal(body.code, "scanned");
    assert.equal(aiCalls.length, 0);
  });

  test("a photo explains that the server's AI reader isn't set up", async () => {
    const png = Buffer.concat([Buffer.from("89504e470d0a1a0a", "hex"), Buffer.alloc(50)]);
    const res = await post("n4", "photo.png", png);
    assert.equal(res.status, 503);
    assert.equal((await res.json()).code, "no_ai");
  });

  test("an unrecognised sheet gives advice instead of an AI call", async () => {
    const res = await post("n3", "marks.xlsx", await garbage());
    assert.equal(res.status, 422);
    assert.match((await res.json()).message, /layout/);
  });
});

// ---------------------------------------------------------- text recognised on the phone
const fs = require("node:fs");
const path = require("node:path");
const fixture = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, "fixtures", `ocr-rows-${name}.json`), "utf8"));

test("a start hour that lost its leading 1 is repaired", () => {
  assert.deepEqual(parseRange("0:50-11:40"), { start: "10:50", end: "11:40" });
  assert.deepEqual(parseRange("1:40-12:30"), { start: "11:40", end: "12:30" });
  assert.deepEqual(parseRange("9:00-9:50"), { start: "09:00", end: "09:50" }); // untouched when it is fine
  assert.equal(parseRange("Maths"), null);
});

test("rows rebuilt from a recognised photo of a college timetable (merged periods included)", () => {
  const { entries, layout, confident } = parseGrid(fixture("A_clean"));
  assert.equal(layout, "days-as-rows");
  assert.ok(confident);
  const keys = entries.map(key);
  assert.ok(keys.includes("1 10:50-12:30 CS Lab"), "text centred across two periods covers both");
  assert.ok(keys.includes("1 13:30-15:10 DBMS"));
  assert.ok(keys.includes("2 10:50-12:30 DBMS"));
  assert.ok(keys.includes("5 09:00-10:40 Maths"));
  assert.ok(!keys.some((k) => /Free|LUNCH/i.test(k)));
  assert.equal(entries.length, 18);
});

test("rows rebuilt from a recognised timetable with days across the top", () => {
  const { entries, layout } = parseGrid(fixture("B_clean"));
  assert.equal(layout, "days-as-columns");
  const keys = entries.map(key);
  assert.ok(keys.includes("1 09:00-11:00 Maths"));
  assert.ok(keys.includes("2 11:00-12:00 Lab"));
  assert.ok(!keys.some((k) => /Lunch/i.test(k)));
  assert.equal(entries.length, 17);
});

test("rows rebuilt from small text", () => {
  const { entries } = parseGrid(fixture("C_small"));
  assert.equal(entries.length, 18);
  assert.ok(entries.map(key).includes("2 10:50-12:30 DBMS"));
});

test.describe("text from the phone, with the AI reader set up", () => {
  let s, url;
  const calls = [];
  test.before(async () => {
    s = await startServer({
      apiKey: "sk-test",
      extractWithAi: async (args) => {
        calls.push(args);
        return { entries: [{ dayOfWeek: 2, title: "From AI", startTime: "09:00", endTime: "10:00" }], warnings: [] };
      },
    });
    url = `http://127.0.0.1:${s.address().port}/api/timetable/import`;
  });
  test.after(() => s.close());
  const send = (user, payload, token = true) =>
    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${signAccessToken(user)}` } : {}) },
      body: JSON.stringify(payload),
    });

  test("a clean grid is read without the AI and is marked as OCR", async () => {
    calls.length = 0;
    const res = await send("g1", { grids: [fixture("A_clean")] });
    assert.equal(res.status, 200);
    const out = await res.json();
    assert.equal(out.method, "ocr");
    assert.equal(out.entries.length, 18);
    assert.equal(calls.length, 0);
  });

  test("the best page of several is used", async () => {
    const res = await send("g2", { grids: [[["page", "one"]], fixture("B_clean"), [["x"]]] });
    assert.equal((await res.json()).entries.length, 17);
  });

  test("text that isn't a timetable goes to the AI", async () => {
    calls.length = 0;
    const res = await send("g3", { grids: [[["Name", "Marks"], ["Asha", "91"]]] });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).method, "ai");
    assert.match(calls[0].text, /Asha/);
  });

  test("invalid payloads and missing login", async () => {
    assert.equal((await send("g4", { grids: [] })).status, 400);
    assert.equal((await send("g4", { grids: [[["a".repeat(300)]]] })).status, 400);
    assert.equal((await send("g4", { grids: [fixture("A_clean")] }, false)).status, 401);
  });

  test("status says the AI reader is available", async () => {
    const res = await fetch(`${url}/status`, { headers: { Authorization: `Bearer ${signAccessToken("g5")}` } });
    assert.deepEqual(await res.json(), { ai: true });
    assert.equal((await fetch(`${url}/status`)).status, 401);
  });
});

test.describe("text from the phone, without the AI reader", () => {
  let s, url;
  test.before(async () => {
    s = await startServer({ apiKey: "", extractWithAi: fakeAi });
    url = `http://127.0.0.1:${s.address().port}/api/timetable/import`;
  });
  test.after(() => s.close());
  const send = (payload) =>
    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken("h1")}` },
      body: JSON.stringify(payload),
    });

  test("a clean grid still works", async () => {
    assert.equal((await send({ grids: [fixture("C_small")] })).status, 200);
  });

  test("text that isn't a timetable gets advice, not an AI call", async () => {
    const res = await send({ grids: [[["Name", "Marks"], ["Asha", "91"]]] });
    assert.equal(res.status, 422);
    assert.match((await res.json()).message, /sharper/);
  });

  test("status says the AI reader is not available", async () => {
    const res = await fetch(`${url}/status`, { headers: { Authorization: `Bearer ${signAccessToken("h2")}` } });
    assert.deepEqual(await res.json(), { ai: false });
  });
});

// ============================================================ PDFs with real text, and recognised text boxes
const { build: buildReplicaPdf } = require("./helpers/replicaPdf");
const { extractPdfBoxes } = require("../lib/pdfText");
const boxesLib = require("../lib/gridFromBoxes");

function loadTsv(name) {
  return fs
    .readFileSync(path.join(__dirname, "fixtures", "ocr", `${name}.tsv`), "utf8")
    .split("\n")
    .slice(1)
    .map((l) => l.split("\t"))
    .filter((f) => f.length >= 12 && f[11].trim() && parseFloat(f[10]) >= 0)
    .map((f) => ({ text: f[11], x0: +f[6], y0: +f[7], x1: +f[6] + +f[8], y1: +f[7] + +f[9] }));
}

test("a university timetable PDF: text is read exactly, merged cells and multi-line cells included", async () => {
  const pages = await extractPdfBoxes(await buildReplicaPdf());
  const { entries, layout, confident } = boxesLib.readPages(pages, { ocr: false });
  assert.equal(layout, "days-as-rows");
  assert.ok(confident);
  assert.equal(entries.length, 30);
  const keys = entries.map(key);

  // times split over two lines in the header ("8.50 am -" / "09.40 am") become 08:50-09:40
  assert.ok(keys.includes("1 08:50-09:40 Theory of Computation (23CSE303)"), keys.join("\n"));
  // a cell centred across two slots covers both (11:35-12:25 and 12:25-1:15)
  assert.ok(keys.includes("1 11:35-13:15 Computer Networks (23CSE302) · Tutorial/Achala"));
  // a lab spanning slots 9 and 10
  assert.ok(keys.includes("1 14:55-16:35 Theory of Computation (23CSE303) · Lab"));
  // a cell over the first two slots
  assert.ok(keys.includes("5 08:00-09:40 Theory of Computation (23CSE303) · Tutorial"));
  // a three-slot cell with several lines of text is one class
  assert.ok(entries.some((e) => e.dayOfWeek === 5 && e.startTime === "10:45" && e.endTime === "13:15" && /^PE2/.test(e.title)));
  // multi-line cells are joined into one title
  assert.ok(entries.some((e) => e.dayOfWeek === 2 && e.startTime === "10:45" && /Ms\. Anitha M/.test(e.title)));
});

test("the break columns are never classes", async () => {
  const pages = await extractPdfBoxes(await buildReplicaPdf());
  const { entries } = boxesLib.readPages(pages, { ocr: false });
  assert.ok(!entries.some((e) => /break/i.test(e.title)));
  // nothing lands in the interval (10:30-10:45) or lunch (13:15-14:05) columns
  assert.ok(!entries.some((e) => e.startTime === "10:30" || e.startTime === "13:15" || e.endTime === "14:05"));
});

test("course codes are replaced by names from the list underneath", async () => {
  const withList = boxesLib.readPages(await extractPdfBoxes(await buildReplicaPdf({ legend: true })), { ocr: false });
  const without = boxesLib.readPages(await extractPdfBoxes(await buildReplicaPdf({ legend: false })), { ocr: false });
  assert.ok(withList.entries.some((e) => e.title === "Machine Learning (23CSE301)"));
  assert.ok(without.entries.some((e) => e.title === "23CSE301"), "without the list the code stays as it is");
  assert.equal(withList.entries.length, without.entries.length);
});

test("only the first pages of a PDF are read, and extra pages don't confuse it", async () => {
  const { entries } = boxesLib.readPages(await extractPdfBoxes(await buildReplicaPdf({ pages: 2 })), { ocr: false });
  assert.equal(entries.length, 30);
});

test("sideways text and empty text are skipped when reading a PDF", async () => {
  const pages = await extractPdfBoxes(await buildReplicaPdf());
  assert.ok(!pages[0].boxes.some((b) => /Interval|Lunch Break/.test(b.text)));
  assert.ok(pages[0].boxes.every((b) => b.text.trim() === b.text && b.x1 > b.x0));
});

test("garbage that looks like a PDF does not crash the reader", async () => {
  await assert.rejects(extractPdfBoxes(Buffer.from("%PDF-1.4 this is not really a pdf")));
});

test.describe("PDF upload route", () => {
  let s, url;
  const send = (user, buf, name = "III CSE D.PDF") =>
    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken(user)}` },
      body: JSON.stringify({ filename: name, data: buf.toString("base64") }),
    });

  test.describe("without the AI reader", () => {
    test.before(async () => {
      s = await startServer({ apiKey: "", extractWithAi: fakeAi });
      url = `http://127.0.0.1:${s.address().port}/api/timetable/import`;
    });
    test.after(() => s.close());

    test("a real-text PDF is read with no AI at all", async () => {
      aiCalls.length = 0;
      const res = await send("p1", await buildReplicaPdf());
      assert.equal(res.status, 200);
      const body = await res.json();
      assert.equal(body.method, "pdf");
      assert.equal(body.entries.length, 30);
      assert.equal(aiCalls.length, 0);
    });

    test("a PDF made only of a picture is passed back to the app to read as a picture", async () => {
      const PDFDocument = require("pdfkit");
      const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");
      const scan = await new Promise((resolve) => {
        const doc = new PDFDocument();
        const chunks = [];
        doc.on("data", (c) => chunks.push(c));
        doc.on("end", () => resolve(Buffer.concat(chunks)));
        doc.image(png, 50, 50, { width: 300 });
        doc.end();
      });
      const res = await send("p2", scan);
      assert.equal(res.status, 422);
      assert.equal((await res.json()).code, "scanned");
    });

    test("a PDF with text but no timetable in it says so", async () => {
      const PDFDocument = require("pdfkit");
      const doc = await new Promise((resolve) => {
        const d = new PDFDocument();
        const chunks = [];
        d.on("data", (c) => chunks.push(c));
        d.on("end", () => resolve(Buffer.concat(chunks)));
        for (let i = 0; i < 40; i++) d.fontSize(10).text(`Some ordinary sentence number ${i} about nothing`, 50, 40 + i * 14, { lineBreak: false });
        d.end();
      });
      const res = await send("p3", doc);
      assert.equal(res.status, 422);
      assert.equal((await res.json()).code, "layout");
    });
  });

  test.describe("with the AI reader", () => {
    test.before(async () => {
      s = await startServer({ apiKey: "k", extractWithAi: fakeAi });
      url = `http://127.0.0.1:${s.address().port}/api/timetable/import`;
    });
    test.after(() => s.close());

    test("a real-text PDF still never needs the AI", async () => {
      aiCalls.length = 0;
      const res = await send("q1", await buildReplicaPdf());
      assert.equal((await res.json()).method, "pdf");
      assert.equal(aiCalls.length, 0);
    });
  });
});

test.describe("recognised text boxes from the phone", () => {
  let s, url;
  const send = (user, payload, token = true) =>
    fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${signAccessToken(user)}` } : {}) },
      body: JSON.stringify(payload),
    });
  test.before(async () => {
    s = await startServer({ apiKey: "", extractWithAi: fakeAi });
    url = `http://127.0.0.1:${s.address().port}/api/timetable/import`;
  });
  test.after(() => s.close());

  test("real recogniser output for a college timetable photo", async () => {
    const res = await send("b1", { pages: [{ boxes: loadTsv("A_clean") }] });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.method, "ocr");
    const keys = body.entries.map(key);
    assert.equal(body.entries.length, 18);
    assert.ok(keys.includes("1 10:50-12:30 CS Lab"), "words of a merged cell stay together and cover both periods");
    assert.ok(keys.includes("1 13:30-15:10 DBMS"));
  });

  test("days across the top, and small text", async () => {
    assert.equal((await (await send("b2", { pages: [{ boxes: loadTsv("B_clean") }] })).json()).entries.length, 17);
    assert.equal((await (await send("b3", { pages: [{ boxes: loadTsv("C_small") }] })).json()).entries.length, 18);
  });

  test("a tilted, blurry photo still gives most of the classes", async () => {
    const res = await send("b4", { pages: [{ boxes: loadTsv("A_photo") }] });
    assert.equal(res.status, 200);
    assert.ok((await res.json()).entries.length >= 15);
  });

  test("a picture with only a few words is not a timetable", async () => {
    const boxes = [{ text: "Hello", x0: 0, y0: 0, x1: 50, y1: 12 }, { text: "World", x0: 60, y0: 0, x1: 110, y1: 12 }];
    const res = await send("b5", { pages: [{ boxes }] });
    assert.equal(res.status, 422);
    assert.equal((await res.json()).code, "layout");
  });

  test("invalid payloads and missing login", async () => {
    assert.equal((await send("b6", { pages: [] })).status, 400);
    assert.equal((await send("b6", { pages: [{ boxes: [{ text: "x", x0: "a", y0: 0, x1: 1, y1: 1 }] }] })).status, 400);
    assert.equal((await send("b6", { pages: [{ boxes: loadTsv("A_clean") }] }, false)).status, 401);
  });
});

test.describe("the reader's own behaviour", () => {
  const box = (text, x0, y0, w = 40, h = 10) => ({ text, x0, y0, x1: x0 + w, y1: y0 + h });

  test("a day-name column and a time header are enough: everything else is placed by them", () => {
    const boxes = [
      box("Day", 10, 100, 30),
      box("9:00-10:00", 100, 100, 70), box("10:00-11:00", 200, 100, 70), box("11:00-12:00", 300, 100, 70),
      box("Monday", 10, 140, 40), box("Maths", 110, 140), box("Physics", 210, 140), box("Lab", 310, 140),
      box("Tuesday", 10, 190, 40), box("OS", 110, 190), box("Maths", 210, 190), box("DBMS", 310, 190),
    ];
    const { entries } = boxesLib.readPages([{ boxes }]);
    assert.deepEqual(entries.map(key), [
      "1 09:00-10:00 Maths", "1 10:00-11:00 Physics", "1 11:00-12:00 Lab",
      "2 09:00-10:00 OS", "2 10:00-11:00 Maths", "2 11:00-12:00 DBMS",
    ]);
  });

  test("a cell whose text wraps over several lines is one cell, even if the lines drift", () => {
    const boxes = [
      box("Day", 10, 100, 30),
      box("9:00-10:00", 100, 100, 70), box("10:00-11:00", 200, 100, 70), box("11:00-12:00", 300, 100, 70),
      box("Monday", 10, 150, 40), box("Maths", 110, 130), box("Dr. A", 110, 145), box("Room 4", 110, 160),
      box("Physics", 210, 150),
      box("Tuesday", 10, 220, 40), box("OS", 110, 220), box("DBMS", 210, 220),
    ];
    const { entries } = boxesLib.readPages([{ boxes }]);
    assert.ok(entries.map(key).includes("1 09:00-10:00 Maths Dr. A Room 4"), entries.map(key).join("\n"));
  });

  test("text below the last day is not mistaken for a cell", () => {
    const boxes = [
      box("Day", 10, 100, 30),
      box("9:00-10:00", 100, 100, 70), box("10:00-11:00", 200, 100, 70), box("11:00-12:00", 300, 100, 70),
      box("Monday", 10, 140, 40), box("Maths", 110, 140), box("Physics", 210, 140), box("Lab", 310, 140),
      box("Tuesday", 10, 190, 40), box("OS", 110, 190), box("Maths", 210, 190), box("DBMS", 310, 190),
      box("Notes: bring ID card", 110, 400, 150), box("Page 1 of 3", 110, 430, 80),
    ];
    const { entries } = boxesLib.readPages([{ boxes }]);
    assert.equal(entries.length, 6);
  });

  test("no days or no times means no timetable", () => {
    const noDays = [box("9:00-10:00", 100, 100, 70), box("10:00-11:00", 200, 100, 70), box("11:00-12:00", 300, 100, 70), box("Maths", 110, 140), box("OS", 110, 190), box("Lab", 210, 190), box("Art", 310, 190), box("Gym", 310, 220)];
    assert.equal(boxesLib.readPages([{ boxes: noDays }]).entries.length, 0);
    assert.equal(boxesLib.readPages([{ boxes: [] }]).entries.length, 0);
  });

  test("start times only in the header are turned into periods", () => {
    const boxes = [
      box("9:00", 100, 100, 30), box("10:00", 200, 100, 30), box("11:00", 300, 100, 30), box("12:00", 400, 100, 30),
      box("Mon", 10, 140, 30), box("Maths", 100, 140), box("Physics", 200, 140), box("Lab", 300, 140), box("Lab", 400, 140),
      box("Tue", 10, 190, 30), box("OS", 100, 190), box("OS", 200, 190), box("Maths", 300, 190), box("DBMS", 400, 190),
    ];
    const keys = boxesLib.readPages([{ boxes }]).entries.map(key);
    assert.ok(keys.includes("1 09:00-10:00 Maths"));
    assert.ok(keys.includes("1 11:00-13:00 Lab"));
    assert.ok(keys.includes("2 09:00-11:00 OS"));
  });
});

test.describe("an Excel version of the university timetable", () => {
  const { build: buildUnivXlsx } = require("./helpers/univXlsx");
  let buf;
  test.before(async () => {
    const file = path.join(require("node:os").tmpdir(), `univ-${process.pid}.xlsx`);
    await buildUnivXlsx(file);
    buf = fs.readFileSync(file);
    fs.unlinkSync(file);
  });

  test("times split over two lines, merged cells, break columns and a course list", async () => {
    const s = await startServer({ apiKey: "", extractWithAi: fakeAi });
    const res = await fetch(`http://127.0.0.1:${s.address().port}/api/timetable/import`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${signAccessToken("x1")}` },
      body: JSON.stringify({ filename: "III CSE D.xlsx", data: buf.toString("base64") }),
    });
    s.close();
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.method, "grid");
    const keys = body.entries.map(key);
    assert.equal(body.entries.length, 30);
    assert.ok(keys.includes("1 08:50-09:40 Theory of Computation (23CSE303)"));
    assert.ok(keys.includes("1 11:35-13:15 Computer Networks (23CSE302) · Tutorial/Achala"), "a merged cell covers both slots");
    assert.ok(!body.entries.some((e) => /break/i.test(e.title)), "the vertical break columns are not classes");
  });
});

