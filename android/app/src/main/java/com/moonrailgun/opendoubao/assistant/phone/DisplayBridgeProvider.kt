package com.moonrailgun.opendoubao.assistant.phone

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Shell-only Binder delivery; ordinary apps cannot register a privileged backend. */
class DisplayBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 || Binder.getCallingUid() == 0) { "ADB only" }
        check(method == "attach" && shellBackend(context!!) == BACKEND_ADB) { "请先在设置中选择 ADB + scrcpy" }
        val binder = requireNotNull(extras?.getBinder("bridge"))
        // Provider.call executes with the shell caller's identity; attach must identify the actual app.
        val identity = Binder.clearCallingIdentity()
        try { ShellBridge.connect(binder, BACKEND_ADB) } finally { Binder.restoreCallingIdentity(identity) }
        return Bundle().apply { putBoolean("accepted", true) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
