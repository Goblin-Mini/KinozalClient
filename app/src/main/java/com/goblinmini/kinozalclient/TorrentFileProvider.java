package com.goblinmini.kinozalclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class TorrentFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null || (!"torrent".equals(uri.getLastPathSegment())
                && !"update.apk".equals(uri.getLastPathSegment()))) {
            throw new FileNotFoundException("Torrent not found");
        }
        File file = new File(getContext().getCacheDir(), "update.apk".equals(uri.getLastPathSegment())
                ? "KinozalClient-update.apk" : "kinozal-transfer.torrent");
        if (!file.isFile()) throw new FileNotFoundException("Torrent file not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "update.apk".equals(uri.getLastPathSegment())
                ? "application/vnd.android.package-archive" : "application/x-bittorrent";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
