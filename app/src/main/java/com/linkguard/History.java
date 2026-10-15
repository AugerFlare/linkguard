package com.linkguard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On-device log of intercepted links. Never leaves the phone (no INTERNET permission). */
final class History {

    private static final long DAY = 86_400_000L;

    /** Returns the new entry's id. */
    static long add(Context c, String url, String result) {
        try (SQLiteDatabase db = open(c)) {
            db.execSQL("INSERT INTO h(t, url, result) VALUES (?, ?, ?)",
                    new Object[]{System.currentTimeMillis(), url, result});
            long id;
            try (Cursor q = db.rawQuery("SELECT last_insert_rowid()", null)) { q.moveToFirst(); id = q.getLong(0); }
            prune(c, db);
            return id;
        }
    }

    /** Appends text to an entry's result, e.g. what the user chose on a warning. */
    static void note(Context c, long id, String text) {
        try (SQLiteDatabase db = open(c)) {
            db.execSQL("UPDATE h SET result = result || ' ' || ? WHERE rowid = ?", new Object[]{text, id});
        }
    }

    static void delete(Context c, long id) {
        try (SQLiteDatabase db = open(c)) { db.execSQL("DELETE FROM h WHERE rowid = ?", new Object[]{id}); }
    }

    static void clear(Context c) {
        try (SQLiteDatabase db = open(c)) { db.execSQL("DELETE FROM h"); }
    }

    /** Newest first. Each row has "id", "t" (millis), "url" and "result". */
    static List<Map<String, Object>> list(Context c) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (SQLiteDatabase db = open(c)) {
            prune(c, db);
            try (Cursor q = db.rawQuery("SELECT rowid, t, url, result FROM h ORDER BY t DESC", null)) {
                while (q.moveToNext()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", q.getLong(0));
                    row.put("t", q.getLong(1));
                    row.put("url", q.getString(2));
                    row.put("result", q.getString(3));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** Days to keep entries; 0 = unlimited. */
    static int keepDays(Context c) {
        return MainActivity.prefs(c).getInt("keepDays", 30);
    }

    static void setKeepDays(Context c, int days) {
        MainActivity.prefs(c).edit().putInt("keepDays", days).apply();
    }

    private static void prune(Context c, SQLiteDatabase db) {
        int days = keepDays(c);
        if (days > 0) db.execSQL("DELETE FROM h WHERE t < ?", new Object[]{System.currentTimeMillis() - days * DAY});
    }

    private static SQLiteDatabase open(Context c) {
        SQLiteDatabase db = c.openOrCreateDatabase("history.db", Context.MODE_PRIVATE, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS h(t INTEGER NOT NULL, url TEXT NOT NULL, result TEXT NOT NULL)");
        return db;
    }
}
