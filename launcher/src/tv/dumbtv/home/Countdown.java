package tv.dumbtv.home;

import java.util.Calendar;

/** Options › Christmas countdown: the line under the date from November 1 to Christmas Day. */
final class Countdown {
    private Countdown() {}

    /** The line for {@code now}, or null outside the season. */
    static String line(Calendar now) {
        int month = now.get(Calendar.MONTH), day = now.get(Calendar.DAY_OF_MONTH);
        if (month == Calendar.DECEMBER && day == 25) return "Merry Christmas!";
        if (month == Calendar.DECEMBER && day == 24) return "Christmas Eve — tomorrow is Christmas!";
        if (month != Calendar.NOVEMBER && !(month == Calendar.DECEMBER && day < 25)) return null;
        Calendar christmas = (Calendar) now.clone();
        christmas.set(Calendar.MONTH, Calendar.DECEMBER);
        christmas.set(Calendar.DAY_OF_MONTH, 25);
        int days = christmas.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR);
        return days + " days until Christmas";
    }
}
