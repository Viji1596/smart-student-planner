// Understanding dates, times and durations written in plain English ("friday", "in 3 days", "6pm", "1h30").
// Calendar dates are plain "YYYY-MM-DD" strings, so time zones never get in the way: the app tells us
// what "today" is for the user.

const WEEKDAYS = {
  mon: 1, monday: 1, tue: 2, tues: 2, tuesday: 2, wed: 3, weds: 3, wednesday: 3,
  thu: 4, thur: 4, thurs: 4, thursday: 4, fri: 5, friday: 5, sat: 6, saturday: 6, sun: 7, sunday: 7,
};
const MONTHS = {
  jan: 1, january: 1, feb: 2, february: 2, mar: 3, march: 3, apr: 4, april: 4, may: 5, jun: 6, june: 6,
  jul: 7, july: 7, aug: 8, august: 8, sep: 9, sept: 9, september: 9, oct: 10, october: 10,
  nov: 11, november: 11, dec: 12, december: 12,
};
const NUMBER_WORDS = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };

const pad = (n) => String(n).padStart(2, "0");
const toUtc = (s) => {
  const [y, m, d] = s.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d));
};
const fromUtc = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;

function addDays(date, n) {
  const d = toUtc(date);
  d.setUTCDate(d.getUTCDate() + n);
  return fromUtc(d);
}

/** 1 = Monday ... 7 = Sunday */
function weekdayOf(date) {
  const w = toUtc(date).getUTCDay();
  return w === 0 ? 7 : w;
}

function isRealDate(y, m, d) {
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

/** "Fri 23 Oct" */
function label(date) {
  const d = toUtc(date);
  const names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return `${names[d.getUTCDay()]} ${d.getUTCDate()} ${months[d.getUTCMonth()]}`;
}

const wordNumber = (w) => (/^\d+$/.test(w) ? Number(w) : NUMBER_WORDS[w]);

/**
 * Finds a date in [text]. Returns { date, text } (the matched words, so callers can remove them) or null.
 * A weekday means its next occurrence (today, if today is that weekday); "next friday" is the one after today.
 */
function parseDate(text, today) {
  const l = String(text).toLowerCase();
  const todayWd = weekdayOf(today);
  let m;

  // 2026-10-25: the assistant's own buttons use this form
  if ((m = /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(l))) {
    const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
    if (isRealDate(y, mo, d)) return { date: `${y}-${pad(mo)}-${pad(d)}`, text: m[0] };
  }

  if ((m = /\bday after tomorrow\b/.exec(l))) return { date: addDays(today, 2), text: m[0] };
  if ((m = /\b(tomorrow|tmrw|tmr)\b/.exec(l))) return { date: addDays(today, 1), text: m[0] };
  if ((m = /\b(today|tonight)\b/.exec(l))) return { date: today, text: m[0] };

  if ((m = /\bin (\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten) (day|days|week|weeks)\b/.exec(l))) {
    const n = wordNumber(m[1]);
    return { date: addDays(today, m[2].startsWith("week") ? n * 7 : n), text: m[0] };
  }
  if ((m = /\bnext week\b/.exec(l))) return { date: addDays(today, 8 - todayWd), text: m[0] }; // next Monday
  if ((m = /\b(this )?weekend\b/.exec(l))) return { date: addDays(today, (6 - todayWd + 7) % 7), text: m[0] };
  if ((m = /\bend of (the )?week\b/.exec(l))) return { date: addDays(today, (5 - todayWd + 7) % 7), text: m[0] };

  // "25 oct", "oct 25th"
  if ((m = /\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\b(?:\s+(\d{4}))?/.exec(l))) {
    return inYear(Number(m[1]), MONTHS[m[2]], m[3], today, m[0]);
  }
  if ((m = /\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4}))?/.exec(l))) {
    return inYear(Number(m[2]), MONTHS[m[1]], m[3], today, m[0]);
  }
  // "25/10", "25-10-2026": day first
  if ((m = /\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b/.exec(l))) {
    let year = m[3] ? Number(m[3]) : null;
    if (year !== null && year < 100) year += 2000;
    return inYear(Number(m[1]), Number(m[2]), year, today, m[0]);
  }
  // "on the 25th", "by 25th", "due the 3rd" (a bare "2nd year" is not a date: it needs the little word before)
  if ((m = /\b(?:on|by|before|until|till|due|for)\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\b|\bthe\s+(\d{1,2})(?:st|nd|rd|th)\b/.exec(l))) {
    const day = Number(m[1] || m[2]);
    const [y, mo, d] = today.split("-").map(Number);
    let month = mo;
    let year = y;
    if (day < d) {
      month += 1;
      if (month > 12) {
        month = 1;
        year += 1;
      }
    }
    return isRealDate(year, month, day) ? { date: `${year}-${pad(month)}-${pad(day)}`, text: m[0] } : null;
  }

  if ((m = /\b(next |this )?(monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)\b/.exec(l))) {
    const wd = WEEKDAYS[m[2]];
    let ahead = (wd - todayWd + 7) % 7;
    if (m[1] === "next " && ahead === 0) ahead = 7;
    return { date: addDays(today, ahead), text: m[0] };
  }
  return null;
}

/**
 * What follows the word "due" / "deadline": a date as usual, or just "30th" / "28" (the day of the month).
 * Returns { date, text } or null.
 */
function parseDueTail(tail, today) {
  const t = String(tail).trim();
  const direct = parseDate(t, today);
  if (direct) return direct;
  const ordinal = /^(\d{1,2})(?:st|nd|rd|th)\b/.exec(t) || /^(\d{1,2})\b(?!\s*[:/.-]?\s*\d)/.exec(t);
  if (ordinal) {
    const viaDay = parseDate(`on the ${ordinal[1]}th`, today);
    if (viaDay) return { date: viaDay.date, text: ordinal[0] };
  }
  return null;
}

function inYear(day, month, year, today, text) {
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const [ty] = today.split("-").map(Number);
  let y = year ? Number(year) : ty;
  if (!isRealDate(y, month, day)) return null;
  let date = `${y}-${pad(month)}-${pad(day)}`;
  if (!year && date < today) {
    y += 1; // a date without a year that has already passed means next year
    if (!isRealDate(y, month, day)) return null;
    date = `${y}-${pad(month)}-${pad(day)}`;
  }
  return { date, text };
}

/** Parts of the day, as a starting time. */
const PARTS_OF_DAY = { morning: 9 * 60, noon: 12 * 60, afternoon: 14 * 60, evening: 18 * 60, night: 20 * 60, tonight: 20 * 60 };

/**
 * "6" or "7:30" with no am/pm. With [nowMin] it means the next such time that is still ahead today
 * (at 15:00, "6" is 18:00; at 05:00 it is 06:00). Without it, a student's guess: 1-6 is pm, 7-11 is am.
 */
function resolveAmbiguous(hour, minutes, nowMin) {
  if (hour === 12) return 12 * 60 + minutes; // noon
  if (nowMin === undefined || nowMin === null) return (hour >= 1 && hour <= 6 ? hour + 12 : hour) * 60 + minutes;
  const am = hour * 60 + minutes;
  const pm = (hour + 12) * 60 + minutes;
  return am >= nowMin + 5 ? am : pm;
}

/**
 * Finds a time of day in [text]. Returns { minutes, text, flexible } or null.
 * "flexible" means a part of the day ("evening"), i.e. "somewhere around then".
 * [nowMin] (minutes since midnight) settles bare hours like "6": see resolveAmbiguous.
 */
function parseTime(text, nowMin) {
  const l = String(text).toLowerCase();
  let m;
  if ((m = /\b(\d{1,2}):(\d{2})\s*(am|pm)?\b/.exec(l))) {
    const mins = Number(m[2]);
    let h = Number(m[1]);
    if (mins > 59 || h > 23) return null;
    if (m[3]) return { minutes: ((h % 12) + (m[3] === "pm" ? 12 : 0)) * 60 + mins, text: m[0], flexible: false };
    // 18:30, 00:15 and 07:30 (a leading zero) are 24-hour times; 7:30 is "7:30 something"
    if (h >= 13 || h === 0 || /^0\d/.test(m[1])) return { minutes: h * 60 + mins, text: m[0], flexible: false };
    return { minutes: resolveAmbiguous(h, mins, nowMin), text: m[0], flexible: false };
  }
  if ((m = /\b(\d{1,2})\s*(am|pm)\b/.exec(l))) {
    const h = Number(m[1]);
    if (h < 1 || h > 12) return null;
    return { minutes: ((h % 12) + (m[2] === "pm" ? 12 : 0)) * 60, text: m[0], flexible: false };
  }
  if ((m = /\b(\d{1,2})\s*o'?\s*clock\b/.exec(l))) {
    const h = Number(m[1]);
    if (h < 1 || h > 24) return null;
    return { minutes: h > 12 ? (h % 24) * 60 : resolveAmbiguous(h, 0, nowMin), text: m[0], flexible: false };
  }
  if ((m = /\b(?:at|to|for|around|by|till|until)\s+(\d{1,2})\b(?!\s*(?:minutes?|mins?|hours?|hrs?|h\b|%|st\b|nd\b|rd\b|th\b|tasks?|sessions?|times?|x\b))/.exec(l))) {
    const h = Number(m[1]);
    if (h < 1 || h > 23) return null;
    return { minutes: h > 12 ? h * 60 : resolveAmbiguous(h, 0, nowMin), text: m[0], flexible: false };
  }
  if ((m = /\b(morning|noon|afternoon|evening|night|tonight)\b/.exec(l))) {
    return { minutes: PARTS_OF_DAY[m[1]], text: m[0], flexible: true };
  }
  return null;
}

/** Finds a length of time in [text]: "30 min", "1 hour", "1.5 hrs", "1h30", "half an hour". Returns { minutes, text } or null. */
function parseDuration(text) {
  const l = String(text).toLowerCase();
  let m;
  if ((m = /\b(\d+)\s*h\s*(\d{1,2})\b(?!\s*(?:am|pm))/.exec(l))) {
    return { minutes: Number(m[1]) * 60 + Number(m[2]), text: m[0] };
  }
  if ((m = /\bhalf (?:an |a )?hour\b/.exec(l))) return { minutes: 30, text: m[0] };
  if ((m = /\b(?:an|one) hour\b/.exec(l))) return { minutes: 60, text: m[0] };
  if ((m = /\b(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|h)\b(?:\s*(?:and\s*)?(\d+)\s*(?:minutes?|mins?|m)\b)?/.exec(l))) {
    const minutes = Math.round(Number(m[1]) * 60) + (m[2] ? Number(m[2]) : 0);
    return minutes > 0 && minutes <= 1440 ? { minutes, text: m[0] } : null;
  }
  if ((m = /\b(\d+)\s*(?:minutes?|mins?|m)\b/.exec(l))) {
    const minutes = Number(m[1]);
    return minutes > 0 && minutes <= 1440 ? { minutes, text: m[0] } : null;
  }
  return null;
}

const hhmm = (minutes) => `${pad(Math.floor(minutes / 60) % 24)}:${pad(minutes % 60)}`;

module.exports = { parseDate, parseDueTail, parseTime, parseDuration, addDays, weekdayOf, label, hhmm, WEEKDAYS };
