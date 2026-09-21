package android.content;

/** 테스트용 가짜 — 실제 코드가 쓰는 두 메서드만 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract Context getApplicationContext();
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
