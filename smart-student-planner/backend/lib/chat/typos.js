// Phones and thumbs make typos: "tommorrow", "wensday", "evning". The words that matter for dates, times and
// priorities are corrected before anything is understood, so a deadline is never silently lost.
// Only words that are very close to one of these few words are touched, so task titles are left alone.
const WORDS = [
  "tomorrow", "tonight", "today", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
  "morning", "afternoon", "evening", "priority", "deadline", "minutes", "hours", "weekend", "yesterday",
];
const KNOWN = new Set(WORDS);

// things that are too different for edit distance
const ALIASES = {
  tmrw: "tomorrow", tmr: "tomorrow", tmw: "tomorrow", tmrrw: "tomorrow", "2morrow": "tomorrow", "2moro": "tomorrow", tomo: "tomorrow",
  tonite: "tonight", "2night": "tonight", "2day": "today",
  wensday: "wednesday", wednsday: "wednesday", wendsday: "wednesday", wed: "wed",
  thrusday: "thursday", thursay: "thursday", thurday: "thursday",
  satday: "saturday", sundy: "sunday", mondy: "monday", tuesady: "tuesday", tueday: "tuesday",
  focs: "focus", foucs: "focus", focuss: "focus", fokus: "focus", focusing: "focusing", priorty: "priority", prioirty: "priority", priotity: "priority",
  evning: "evening", eveing: "evening", mornin: "morning", nxt: "next", nex: "next",
};

function distance(a, b) {
  const row = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    let prev = row[0];
    row[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const temp = row[j];
      row[j] = Math.min(row[j] + 1, row[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = temp;
    }
  }
  return row[b.length];
}

function correct(word) {
  const lower = word.toLowerCase();
  if (KNOWN.has(lower)) return null;
  if (ALIASES[lower] && ALIASES[lower] !== lower) return ALIASES[lower];
  if (lower.length < 5 || /\d/.test(lower)) return null;
  const limit = lower.length >= 8 ? 2 : 1;
  const close = WORDS.map((w) => ({ w, d: distance(lower, w) })).filter((x) => x.d <= limit && x.w[0] === lower[0]);
  if (!close.length) return null;
  close.sort((a, b) => a.d - b.d);
  return close.length > 1 && close[0].d === close[1].d ? null : close[0].w;
}

/** Fixes typos in the words that carry dates, times and priorities. Everything else is left exactly as typed. */
function fixTypos(text) {
  return String(text).replace(/[A-Za-z0-9]+/g, (word) => {
    const fixed = correct(word);
    if (!fixed) return word;
    return /^[A-Z]/.test(word) ? fixed[0].toUpperCase() + fixed.slice(1) : fixed;
  });
}

// "whats", "im", "dont", "wat", "pls": how people really type on a phone
const SLANG = {
  whats: "what's", hows: "how's", thats: "that's", whos: "who's", wheres: "where's", lets: "let's",
  im: "i'm", ive: "i've", dont: "don't", cant: "can't", wont: "won't", didnt: "didn't", isnt: "isn't", doesnt: "doesn't",
  wasnt: "wasn't", couldnt: "couldn't", shouldnt: "shouldn't", wouldnt: "wouldn't", havent: "haven't", hasnt: "hasn't", arent: "aren't",
  pls: "please", plz: "please", plss: "please", thx: "thanks", thnx: "thanks", tnx: "thanks", wat: "what", wut: "what",
  u: "you", ur: "your", gonna: "going to", gotta: "got to", dunno: "don't know", hw: "homework", ya: "you",
};

/** Expands texting spellings and restores missing apostrophes, so one set of patterns understands them all. */
function expandSlang(text) {
  return String(text).replace(/[A-Za-z]+/g, (word) => {
    const replacement = SLANG[word.toLowerCase()];
    if (!replacement) return word;
    return /^[A-Z]/.test(word) ? replacement[0].toUpperCase() + replacement.slice(1) : replacement;
  });
}

/** Keyboards type curly quotes and non-breaking spaces; the patterns expect plain ones. */
function plainText(text) {
  return String(text)
    .replace(/[\u2018\u2019\u02bc\u0060\u00b4]/g, "'")
    .replace(/[\u201c\u201d]/g, '"')
    .replace(/[\u00a0\u2009\u202f]/g, " ");
}

module.exports = { fixTypos, plainText, correct, expandSlang };
