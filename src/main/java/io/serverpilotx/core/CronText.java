package io.serverpilotx.core;

/** Turns a cron schedule into a short human-readable sentence. */
public final class CronText {
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final String[] MONTHS = {"", "January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};

    private CronText() {}

    public static boolean valid(String schedule) {
        String s = schedule.strip();
        if (s.matches("@(reboot|yearly|annually|monthly|weekly|daily|midnight|hourly)")) return true;
        String[] f = s.split("\\s+");
        if (f.length != 5) return false;
        for (String x : f) if (!x.matches("[0-9*/,\\-A-Za-z]+")) return false;
        return true;
    }

    public static String describe(String schedule) {
        String s = schedule.strip();
        switch (s) {
            case "@reboot": return "At every boot";
            case "@yearly": case "@annually": return "Once a year (Jan 1, 00:00)";
            case "@monthly": return "Once a month (day 1, 00:00)";
            case "@weekly": return "Once a week (Sunday 00:00)";
            case "@daily": case "@midnight": return "Every day at 00:00";
            case "@hourly": return "Every hour";
            default: break;
        }
        String[] f = s.split("\\s+");
        if (f.length != 5) return "Invalid schedule";
        String min = f[0], hour = f[1], dom = f[2], mon = f[3], dow = f[4];

        String time;
        if (min.startsWith("*/") && hour.equals("*")) time = "Every " + min.substring(2) + " minutes";
        else if (min.equals("*") && hour.equals("*")) time = "Every minute";
        else if (isNum(min) && hour.equals("*")) time = "Every hour at :" + pad(min);
        else if (isNum(min) && hour.startsWith("*/")) time = "Every " + hour.substring(2) + " hours at :" + pad(min);
        else if (isNum(min) && isNum(hour)) time = "at " + pad(hour) + ":" + pad(min);
        else if (isNum(min) && hour.matches("[0-9,]+")) time = "at :" + pad(min) + " past hours " + hour;
        else time = "at minute " + min + ", hour " + hour;

        String day;
        boolean anyDom = dom.equals("*"), anyDow = dow.equals("*"), anyMon = mon.equals("*");
        if (anyDom && anyDow) day = time.startsWith("Every") ? "" : "Every day ";
        else if (anyDom) day = "Every " + days(dow) + " ";
        else if (anyDow) day = "On day " + dom + " of " + (anyMon ? "every month" : months(mon)) + " ";
        else day = "On day " + dom + " and on " + days(dow) + " ";
        if (!anyMon && anyDom) day += "in " + months(mon) + " ";

        String out = (day + time).strip();
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    private static boolean isNum(String x) { return x.matches("\\d+"); }

    private static String pad(String x) { return x.length() == 1 ? "0" + x : x; }

    private static String days(String dow) {
        if (dow.equals("1-5")) return "weekday";
        if (dow.equals("0,6") || dow.equals("6,0")) return "Saturday and Sunday";
        StringBuilder b = new StringBuilder();
        for (String p : dow.split(",")) {
            if (b.length() > 0) b.append(", ");
            if (isNum(p) && Integer.parseInt(p) <= 7) b.append(DAYS[Integer.parseInt(p)]);
            else b.append(p);
        }
        return b.toString();
    }

    private static String months(String mon) {
        StringBuilder b = new StringBuilder();
        for (String p : mon.split(",")) {
            if (b.length() > 0) b.append(", ");
            if (isNum(p) && Integer.parseInt(p) >= 1 && Integer.parseInt(p) <= 12) b.append(MONTHS[Integer.parseInt(p)]);
            else b.append(p);
        }
        return b.toString();
    }
}
