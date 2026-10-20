# Phase 5c: photos and PDFs now import (free, on your phone)

## What was wrong
Photos and PDFs could only be read by a paid AI service. Without an API key on the server the app refused
them. Also, big phone photos would have been rejected by the AI service even with a key (it accepts images
only up to a few MB).

## What changed
- **A free reader on the phone.** Photos and PDFs are now read with Google's on-device text recognition
  (ML Kit): no account, no cost, works offline. The words and their positions are put back into rows and
  columns, and the server's grid reader (the same one used for Excel) turns that into classes.
- **How a photo or PDF is read:**
  1. If the server has the AI reader set up, it goes first (it copes best with messy pictures). Photos are
     shrunk and straightened first so they fit.
  2. If it isn't set up, or can't read the file, the phone reads the text itself.
  3. You always get the review screen: tick/untick, fix titles and times, add to or replace your timetable.
- **PDFs:** the first 3 pages are turned into pictures and read the same way.
- **What the reconstruction handles:** merged cells whose text sits across two periods, lunch and free
  periods, titles above the table, border lines misread as characters, two header cells read as one word,
  a lost dash in "9:00 - 10:00", a start hour that lost its leading "1" ("0:50" -> "10:50"), and photos
  tilted by a few degrees.
- Excel and CSV import is unchanged.

## What to expect (be realistic)
- Works well on: screenshots, typed or printed timetables, PDFs, a straight-on photo in good light.
- Struggles with: handwriting, blurry or dark photos, strong angles or curved pages, glare, very small text.
- The review screen warns that it can misread times and names. Always check every line.
- A damaged header row (a misread time range) can drop a column; fix it in the review or add by hand.

## Try it with the samples
The `samples/` folder has a picture (two layouts), a PDF and an Excel file of the same timetable. Copy them
to your phone and import each. All should give the same 18 classes
(Monday: Maths 09:00-09:50, Physics 09:50-10:40, CS Lab 10:50-12:30, DBMS 13:30-15:10 ...).

## Tests
- Backend: 74 tests (12 new): text rebuilt from real OCR output of timetable images, the "grids" request,
  the status check, the repair of a start hour that lost its "1", with and without the AI reader.
- App logic: 136 tests (14 new for the reconstruction), using real OCR output from an OCR engine on four
  timetable images (clean, small text, days across the top, tilted and blurry) plus synthetic cases:
  tilt from -3 to +2.5 degrees, merged cells over a column boundary, fused header cells, border noise.
- NOT tested by me: Google's text recogniser itself (ML Kit) and the Android screens: I ran a different
  OCR engine for the fixtures, so results on your phone may differ a little.

## Update steps
1. Copy the zip over your project.
2. Backend: `git add .`, `git commit -m "Phase 5c"`, `git push` (Render redeploys: new endpoints).
   Check `/health`.
3. Android Studio: File -> Sync Project with Gradle Files (it downloads the text recogniser), then Run.
   The app gets a few MB bigger (the recogniser is bundled).
4. Profile -> Timetable -> Import -> choose a sample picture or PDF.

## If it fails
Note the message on screen and tell me which file (sample or your own). Useful: "No text could be found"
means the recogniser found nothing; "I couldn't make a timetable out of the text" means it found text but not
a grid I recognise (send me the photo if you're comfortable sharing it).
