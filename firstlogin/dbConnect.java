package com.example.firstlogin;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

public class dbConnect extends SQLiteOpenHelper {

    // ===== DATABASE SCHEMA =====
    private static final String dbName = "openlogs.db";
    private static final int dbVersion = 1;

    private static final String dbTable = "users";

    private static final String ID = "id";
    private static final String fullname = "fullname";
    private static final String emailaddress = "emailaddress";
    private static final String password = "password";

    // SQLite helper for local user table.
    public dbConnect(@Nullable Context context) {
        super(context, dbName, null, dbVersion);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String query = "CREATE TABLE " + dbTable + " (" +
                ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                fullname + " TEXT, " +
                emailaddress + " TEXT, " +
                password + " TEXT" +
                ")";
        db.execSQL(query);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + dbTable);
        onCreate(db);
    }

    // Insert one user row in local database.
    public void addUser(Users users) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(fullname, users.getFullname());
        values.put(emailaddress, users.getEmailaddress());
        values.put(password, users.getPassword());

        db.insert(dbTable, null, values);
        db.close();
    }

    // Verify email + password exists in local table.
    public boolean checkUser(String email, String pass) {
        SQLiteDatabase db = this.getReadableDatabase();
        String query = "SELECT * FROM " + dbTable + " WHERE " + emailaddress + "=? AND " + password + "=?";
        Cursor cursor = db.rawQuery(query, new String[]{email, pass});

        boolean exists = (cursor != null && cursor.getCount() > 0);

        if (cursor != null) cursor.close();
        db.close();
        return exists;
    }

    // Read full name for a given email (fallback: "User").
    public String getUserName(String email) {
        SQLiteDatabase db = this.getReadableDatabase();
        String name = "User";
        Cursor cursor = db.query(dbTable, new String[]{fullname}, emailaddress + "=?",
                new String[]{email}, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            name = cursor.getString(0);
            cursor.close();
        }
        db.close();
        return name;
    }
}
