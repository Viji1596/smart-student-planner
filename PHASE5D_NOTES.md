# Phase 5d: your university timetable PDF

## Why the screenshot couldn't be read
The screenshot you sent is 718 pixels wide and the table's text is about 5 pixels tall. No text recogniser
can read that: even enlarged 4 times, the recogniser returned noise. A screenshot of a whole PDF page, zoomed
out, is simply too small.

The fix is to import **the PDF itself** ("III CSE D.PDF"). A PDF exported from a spreadsheet has the real text
inside it, with exact positions, so it can be read perfectly: no picture reading, no mistakes, no AI, no cost.

## What changed
- **PDFs are now read from their own text first.** The server pulls out every piece of text and where it
  sits on the page. Only a scanned PDF (a picture of a page) falls back to reading it as a picture on the phone.
- **A new table reader** built for timetables like yours. It anchors columns to the header row of times, and
  rows to the day names, then drops everything else into the cell it falls in:
  - times split over two lines ("8.50 am -" / "09.40 am") are joined;
  - a cell that wraps over several lines (like "OR B.Tech - CSE - V Sem - D (APT) Ms. Anitha M") is one cell;
  - a cell spanning several slots (labs, "PE2" across slots 4-6) covers all of them;
  - "Interval Break" and "Lunch Break" columns, and sideways text, are never classes;
  - the title bar, the info row ("Dept:CSE ..."), the "Slot" row and the course list under the table are ignored;
  - **course codes become names** using the course list under the table: "23CSE302/Tutorial/Achala" becomes
    "Computer Networks (23CSE302) · Tutorial/Achala".
- Photos and scans use the same reader (the phone sends the recognised words with their positions), so there
  is one reader for every kind of file. Photos also use the same improvements.
- Clearer messages: for a screenshot that's too small it now says to import the PDF itself.

## Important: what I could and couldn't test
I do not have your PDF, only the screenshot. I rebuilt it as a PDF (same structure: two-line times, merged cells,
vertical break columns, multi-line cells, course list) and tested on that: it reads all 30 classes correctly.
Your real PDF may differ in details (fonts, how the spreadsheet exported it). If it doesn't read right, send me
the PDF (or tell me what the app shows) and I'll adjust. The review screen is there for the rest.

## Tests
- Backend: 95 tests (21 new): the university-style PDF (times, merged cells, labs, multi-line cells, break
  columns, course names), a scanned PDF, a PDF with text but no timetable, real OCR output through the new
  endpoint, and the reader's behaviour on wrapped cells, text below the table, and start-times-only headers.
- App logic: 122 tests (the picture reconstruction moved to the server, so its tests moved with it).
- Not tested by me: Google's text recogniser and the Android screens. And not your real PDF (see above).

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 5d"`, `git push`. Render redeploys (new libraries and endpoints).
   Check `/health`.
3. Android Studio: File -> Sync Project with Gradle Files, then Run.
4. Profile -> Timetable -> Import -> choose **the PDF itself** (not the screenshot).
   Try `samples/university-style-timetable.pdf` first to see it work.

## What to expect from your real timetable
Titles are cell text (course codes become names when the list under the table is readable). Labs and electives
come out as one class across their slots. Some cells ("PE1 Occupied by IV Yr Elective") are not real classes;
untick or edit them in the review screen. Then use Replace to put the classes in your timetable.
