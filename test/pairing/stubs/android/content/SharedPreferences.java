package android.content;

/** 테스트용 가짜 인터페이스 */
public interface SharedPreferences {
    String getString(String k, String d);
    boolean getBoolean(String k, boolean d);
    int getInt(String k, int d);
    long getLong(String k, long d);
    boolean contains(String k);
    Editor edit();

    interface Editor {
        Editor putString(String k, String v);
        Editor putBoolean(String k, boolean v);
        Editor putInt(String k, int v);
        Editor putLong(String k, long v);
        Editor remove(String k);
        void apply();
        boolean commit();
    }
}
