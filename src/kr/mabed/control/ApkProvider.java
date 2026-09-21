package kr.mabed.control;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** 내려받은 새 버전 APK 를 안드로이드 설치 화면에 건네주는 창구 (AndroidX FileProvider 를 쓰지 않으려고 직접 만듦).
 *  밖에 공개하지 않고(exported=false), 설치 화면을 열 때만 읽기 권한을 잠깐 준다. */
public class ApkProvider extends ContentProvider {

    static final String AUTH = "kr.mabed.control.apk";
    static final String MIME = "application/vnd.android.package-archive";

    static Uri uri() { return Uri.parse("content://" + AUTH + "/update.apk"); }

    static File file(Context c) { return new File(new File(c.getCacheDir(), "apk"), "update.apk"); }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri u) { return MIME; }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (mode == null || !mode.startsWith("r")) throw new SecurityException("읽기만 됩니다");
        return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 설치 화면이 파일 이름·크기를 물어볼 때 */
    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String order) {
        MatrixCursor c = new MatrixCursor(new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        c.addRow(new Object[]{ "mabed-update.apk", file(getContext()).length() });
        return c;
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
