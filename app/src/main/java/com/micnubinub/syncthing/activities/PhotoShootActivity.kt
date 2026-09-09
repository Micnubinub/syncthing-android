package com.micnubinub.syncthing.activities

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.SyncthingApp
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.ui.screens.PhotoShootActivityScreen
import com.micnubinub.syncthing.ui.viewModels.PhotoShootActivityAction
import com.micnubinub.syncthing.ui.viewModels.PhotoShootActivityViewModel
import com.micnubinub.syncthing.util.FileUtils
import com.micnubinub.syncthing.util.FileUtils.ExternalStorageDirType
import com.micnubinub.syncthing.util.PermissionUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class PhotoShootActivity : ComponentActivity() {
    protected val TAG: String
        get() = this::class.simpleName ?: "Syncthing"
    private val viewModel by viewModels<PhotoShootActivityViewModel>()

    private var lastPhotoURI: Uri? = null

    @Inject
    lateinit var preferences: SharedPreferences

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted CAMERA permission.")
        } else {
            Log.i(TAG, "User denied CAMERA permission.")
        }
        updatePermissions()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            Log.i(TAG, "User granted WRITE_EXTERNAL_STORAGE permission.")
        } else {
            Log.i(TAG, "User denied WRITE_EXTERNAL_STORAGE permission.")
        }
        updatePermissions()
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (!success) {
            Log.d(TAG, "User cancelled or failed to take a picture.")
            Toast.makeText(this, R.string.photo_shoot_take_picture_failure, Toast.LENGTH_SHORT)
                .show()
            lastPhotoURI = null
            return@registerForActivityResult
        }

        // Verify image bounds on IO thread instead of decoding the full bitmap on Main.
        val uri = lastPhotoURI
        lifecycleScope.launch {
            val valid = withContext(Dispatchers.IO) {
                uri?.let {
                    try {
                        contentResolver.openInputStream(it)?.use { stream ->
                            val options = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            BitmapFactory.decodeStream(stream, null, options)
                            options.outWidth > 0 && options.outHeight > 0
                        } ?: false
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Failed to verify captured image", e)
                        false
                    }
                } ?: false
            }

            if (valid) {
                Log.d(TAG, "User took a picture.")
                lastPhotoURI = null
                Toast.makeText(
                    this@PhotoShootActivity,
                    R.string.photo_shoot_take_picture_success,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                Log.d(TAG, "Captured image failed verification.")
                Toast.makeText(
                    this@PhotoShootActivity,
                    R.string.photo_shoot_take_picture_failure,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as SyncthingApp).component().inject(this)

        // Check if required camera hardware is present.
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(
                this@PhotoShootActivity,
                getString(R.string.photo_shoot_intro_no_camera), Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // Check if user granted permissions before and consented to use this feature.
        val prefEnableSyncthingCamera =
            preferences.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)
        val haveRequiredPermissions =
            PermissionUtil.haveStoragePermission(this) && haveCameraPermission()
        Log.v(
            TAG,
            "prefEnableSyncthingCamera=$prefEnableSyncthingCamera, haveRequiredPermissions=$haveRequiredPermissions"
        )
        if (haveRequiredPermissions && prefEnableSyncthingCamera) {
            // Take a shortcut and offer to take a picture instantly.
            Log.v(TAG, "User completed intro and consented before. Warp to take a picture.")
            openCameraIntent()
            return
        }

        // Show photo shoot intro UI to request required permissions.
        updatePermissions()

        setContent {
            PhotoShootActivityScreen(
                viewModel = viewModel,
                onGrantCameraPermission = ::requestCameraPermission,
                onGrantStoragePermission = ::requestStoragePermission,
                onContinue = ::onContinue,
                onBack = ::finish
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
    }

    private fun onContinue() {
        // Re-check permissions.
        val haveRequiredPermissions =
            PermissionUtil.haveStoragePermission(this) && haveCameraPermission()
        if (!haveRequiredPermissions) {
            Toast.makeText(
                this@PhotoShootActivity,
                getString(R.string.photo_shoot_intro_missing_permissions), Toast.LENGTH_LONG
            ).show()
            return
        }

        // Store user consent.
        preferences.edit {
            putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, true)
        }

        // Warp to take a picture.
        Log.v(TAG, "User completed intro and consented.")
        openCameraIntent()
    }

    private fun requestStoragePermission() {
        if (!PermissionUtil.haveStoragePermission(this)) {
            PermissionUtil.requestStoragePermission(this, storagePermissionLauncher)
        }
    }

    private fun updatePermissions() {
        viewModel.onAction(
            PhotoShootActivityAction.UpdatePermissions(
                haveCameraPermission = haveCameraPermission(),
                haveStoragePermission = PermissionUtil.haveStoragePermission(this)
            )
        )
    }

    private fun openCameraIntent() {
        val pictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (pictureIntent.resolveActivity(packageManager) == null) {
            Log.e(TAG, "This system does not support the ACTION_IMAGE_CAPTURE intent.")
            Toast.makeText(
                this@PhotoShootActivity,
                R.string.photo_shoot_no_camera_intent, Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        //Create a file to store the image
        var photoFile: File?
        try {
            photoFile = createImageFile()
            if (photoFile == null) {
                Log.e(TAG, "openCameraIntent: photoFile == null")
                Toast.makeText(this, R.string.photo_shoot_take_picture_failure, Toast.LENGTH_LONG)
                    .show()
                finish()
                return
            }
        } catch (ex: IOException) {
            Log.e(TAG, "Error occurred while creating the temp image file", ex)
            Toast.makeText(this, R.string.photo_shoot_take_picture_failure, Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        val photoURI =
            FileProvider.getUriForFile(this, "$packageName.provider", photoFile)

        Log.d(TAG, "Launching take picture intent ...")
        lastPhotoURI = photoURI
        takePictureLauncher.launch(photoURI)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File? {
        val timeStamp =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            ).format(Date())
        val imageFileName = "IMG_" + timeStamp + "_"
        val storageDir = FileUtils.getExternalFilesDir(
            this@PhotoShootActivity,
            ExternalStorageDirType.INT_MEDIA,
            Environment.DIRECTORY_PICTURES
        )
        if (storageDir == null) {
            Log.e(TAG, "createImageFile: storageDir == null")
            return null
        }
        if (!storageDir.mkdirs() && !storageDir.exists()) {
            Log.e(TAG, "createImageFile: failed to create directory ${storageDir.absolutePath}")
            return null
        }
        val image = File.createTempFile(
            imageFileName,  /* prefix */
            ".jpg",  /* suffix */
            storageDir /* directory */
        )
        return image
    }

    /**
     * Permission check and request functions
     */
    private fun haveCameraPermission(): Boolean {
        val permissionState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        )
        return permissionState == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
}
