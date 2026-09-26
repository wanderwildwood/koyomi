package android.text;

/**
 * The one TextUtils call the vendored recurrence parser makes, for the JVM tests. The
 * android.jar stub would answer false for everything, and a rule would then be written with
 * "UNTIL=null" in it.
 */
public class TextUtils {
    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }
}
