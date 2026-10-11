package com.moonrailgun.opendoubao.assistant.phone

import android.content.pm.PackageManager
import android.content.pm.SignedPackage
import android.os.Bundle
import android.os.allowlist.AllowlistManager
import android.os.allowlist.AllowlistProviderService
import android.os.allowlist.AllowlistRequest
import android.os.allowlist.AllowlistResponse
import java.security.MessageDigest

/**
 * The image names this app the system allowlist provider (`config_allowlistProviderServicePackage`), the one way a user build
 * lets it run Computer Control sessions: super agents need a debuggable build and these DeviceConfig flags refuse adb.
 * Only the Computer Control lists get answers; the app scope and the system's per-app consent still limit what runs.
 * Every other list fails exactly as it did without a provider. Other systems never bind it: binding needs a system-only permission.
 */
class AllowlistProvider : AllowlistProviderService() {
    override fun onQueryAllowlist(request: AllowlistRequest): AllowlistResponse = when (request.allowlistId) {
        AllowlistManager.ALLOWLIST_ID_COMPUTER_CONTROL_AGENTS -> allowed(SignedPackage(packageName, certificateDigest()))
        AllowlistManager.ALLOWLIST_ID_COMPUTER_CONTROL -> allowed(SignedPackage("*", null))
        AllowlistManager.ALLOWLIST_ID_COMPUTER_CONTROL_DENIED_TARGETS -> allowed()
        else -> AllowlistResponse(AllowlistManager.RESPONSE_STATUS_ERROR_PROVIDER, Bundle())
    }

    // Status 0 is success; the system has no named constant for it.
    private fun allowed(vararg packages: SignedPackage) = AllowlistResponse(0, Bundle().apply {
        putParcelableArrayList(AllowlistManager.RESPONSE_KEY_ALLOWED_PACKAGES, arrayListOf(*packages))
    })

    private fun certificateDigest(): ByteArray = MessageDigest.getInstance("SHA-256").digest(packageManager
        .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0].toByteArray())
}
