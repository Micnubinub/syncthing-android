package com.micnubinub.syncthing.util

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.micnubinub.syncthing.R

object PermissionUtil {
    private const val TAG = "PermissionUtil"

    @JvmStatic
    fun haveStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            return permissionState == PackageManager.PERMISSION_GRANTED
        }
        return Environment.isExternalStorageManager()
    }

    fun requestStoragePermission(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = ("package:" + activity.packageName).toUri()
        try {
            val componentName = intent.resolveActivity(activity.packageManager)
            if (componentName != null) {
                // Launch "Allow all files access?" dialog.
                activity.startActivity(intent)
                return
            } else {
                Log.w(TAG, "Request all files access not supported")
            }
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Request all files access not supported", e)
        }
        // Some devices don't support this request.
        Toast.makeText(activity, R.string.dialog_all_files_access_not_supported, Toast.LENGTH_LONG)
            .show()
    }
}
