package com.micnubinub.syncthing.util

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileUriExposedException
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.util.MimeTypes.Companion.mimeTypes
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.lang.reflect.Array.get
import java.lang.reflect.Array.getLength
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Utils for dealing with Storage Access Framework URIs.
 */
object FileUtils {
    private const val TAG = "FileUtils"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_VOLUME_NAME = "downloads"
    private const val PRIMARY_VOLUME_NAME = "primary"
    private const val HOME_VOLUME_NAME = "home"

    fun convertFromDocumentUriToTreeUri(documentUri: Uri): Uri? {
        // IN: content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2F${applicationId}
        // OUT: content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2F${applicationId}
        val authority = documentUri.authority
        val documentId = DocumentsContract.getDocumentId(documentUri)
        return DocumentsContract.buildTreeDocumentUri(authority, documentId)
    }

    fun directoryUriExists(context: Context, documentUri: Uri): Boolean {
        val treeUri = convertFromDocumentUriToTreeUri(documentUri)
        val absPath = getAbsolutePathFromSAFUri(context, treeUri)
        return !absPath.isNullOrEmpty() && File(absPath).exists()
    }

    fun getAbsolutePathFromSAFUri(context: Context, safResultUri: Uri?): String? {
        val treeUri = DocumentsContract.buildDocumentUriUsingTree(
            safResultUri,
            DocumentsContract.getTreeDocumentId(safResultUri)
        )
        return getAbsolutePathFromTreeUri(context, treeUri)
    }

    fun getAbsolutePathFromTreeUri(context: Context, treeUri: Uri?): String? {
        if (treeUri == null) {
            Log.w(TAG, "getAbsolutePathFromTreeUri: called with treeUri == null")
            return null
        }

        // Determine volumeId, e.g. "home", "documents"
        val volumeId = getVolumeIdFromTreeUri(treeUri) ?: return null

        // Handle Uri referring to internal or external storage.
        var volumePath = getVolumePath(volumeId, context) ?: return File.separator
        if (volumePath.endsWith(File.separator)) {
            volumePath = volumePath.substring(0, volumePath.length - 1)
        }
        var documentPath = getDocumentPathFromTreeUri(treeUri)
        if (documentPath.endsWith(File.separator)) {
            documentPath = documentPath.substring(0, documentPath.length - 1)
        }
        return if (documentPath.isNotEmpty()) {
            if (documentPath.startsWith(File.separator)) {
                volumePath + documentPath
            } else {
                volumePath + File.separator + documentPath
            }
        } else {
            volumePath
        }
    }

    private val mountedStoragePaths: MutableList<String>
        get() {
            val mountPaths: MutableList<String> = ArrayList<String>()
            try {
                BufferedReader(FileReader("/proc/mounts")).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        if (line.contains("/storage/") || line.contains("/mnt/media_rw/")) {
                            val parts = line.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                            val mountPoint = parts[1]

                            // Filter
                            if ((mountPoint.startsWith("/storage/") || mountPoint.startsWith("/mnt/media_rw/"))
                                && !mountPoint.contains("emulated") && File(mountPoint).isDirectory
                            ) {
                                mountPaths.add(mountPoint)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(
                    TAG,
                    "getMountedStoragePaths: Error reading /proc/mounts",
                    e
                )
            }
            return mountPaths
        }

    val mountedStoragePathsAsFileArray: List<File>
        get() {
            val files: MutableList<File> = ArrayList(mountedStoragePaths.size)
            for (path in mountedStoragePaths) {
                val f = File(path)
                if (f.canRead()) {
                    files.add(f)
                }
            }
            return files
        }

    @SuppressLint("ObsoleteSdkInt")
    private fun getVolumePath(volumeId: String, context: Context): String? {
        try {
            if (HOME_VOLUME_NAME == volumeId) {
                Log.v(TAG, "getVolumePath: isHomeVolume")
                // Reading the environment var avoids hard coding the case of the "documents" folder.
                return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    .absolutePath
            }
            if (DOWNLOADS_VOLUME_NAME == volumeId) {
                Log.v(TAG, "getVolumePath: isDownloadsVolume")
                return externalStorageDownloadsDirectory
            }

            val storageManager =
                context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumeClazz = Class.forName("android.os.storage.StorageVolume")
            val getVolumeList = storageManager.javaClass.getMethod("getVolumeList")
            val getUuid = storageVolumeClazz.getMethod("getUuid")
            val getPath = storageVolumeClazz.getMethod("getPath")
            val isPrimary = storageVolumeClazz.getMethod("isPrimary")
            val result = getVolumeList.invoke(storageManager)

            val length = getLength(result)
            for (i in 0..<length) {
                val storageVolumeElement = get(result, i)
                val uuid = getUuid.invoke(storageVolumeElement) as String?
                val primary = isPrimary.invoke(storageVolumeElement) as Boolean?
                val isPrimaryVolume = (primary == true && PRIMARY_VOLUME_NAME == volumeId)
                val isExternalVolume = ((uuid != null) && uuid == volumeId)
                Log.d(
                    TAG, "Found volume with uuid='" + uuid +
                            "', volumeId='" + volumeId +
                            "', primary=" + primary +
                            ", isPrimaryVolume=" + isPrimaryVolume +
                            ", isExternalVolume=" + isExternalVolume
                )
                if (isPrimaryVolume || isExternalVolume) {
                    Log.v(TAG, "getVolumePath: isPrimaryVolume || isExternalVolume")
                    // Return path if the correct volume corresponding to volumeId was found.
                    return getPath.invoke(storageVolumeElement) as String?
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getVolumePath exception", e)
        }
        // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Log.w(TAG, "getVolumePath failed for volumeId='$volumeId'")
        if (volumeId == "primary") {
            Log.d(TAG, "volumeId == primary")
            return internalStorageRootAbsolutePath
        }
        return "/storage/$volumeId"
        // }
        // Log.e(TAG, "getVolumePath failed for volumeId='" + volumeId + "'");
        // return null;
    }

    fun getExternalFilesDir(context: Context, type: String?): File? {
        return getExternalFilesDir(context, ExternalStorageDirType.DATA, type)
    }

    fun getExternalFilesDir(
        context: Context,
        extDirType: ExternalStorageDirType,
        type: String?
    ): File? {
        /**
         * Determine the app's private data folder on external storage if present.
         * may return null for an ejected SDcard.
         * e.g. "/storage/abcd-efgh/Android/data/[PACKAGE_NAME]/files"
         * e.g. "/storage/abcd-efgh/Android/media/[PACKAGE_NAME]"
         */
        val externalFilesDir = ArrayList<File?>()
        when (extDirType) {
            ExternalStorageDirType.DATA -> {
                externalFilesDir.addAll(
                    listOf(context.getExternalFilesDir(null))
                )
                if (externalFilesDir.size > 1) {
                    // There is a bug on Huawei devices running Android 7, which returns the wrong external path.
                    // That's why we use ContextCompat here instead of context.
                    // See: https://stackoverflow.com/questions/39895579/fileprovider-error-onhuawei-devices
                    externalFilesDir.remove(context.getExternalFilesDir(null))
                }
            }

            ExternalStorageDirType.INT_MEDIA -> externalFilesDir.add(
                File(
                    Environment.getExternalStorageDirectory()
                        .toString() + "/Android/media/" + context.packageName
                )
            )

            ExternalStorageDirType.EXT_MEDIA -> {
                externalFilesDir.addAll(listOf(*context.externalMediaDirs))
                if (externalFilesDir.isNotEmpty()) {
                    externalFilesDir.remove(externalFilesDir[0])
                }
            }
        }
        externalFilesDir.remove(null) // getExternalFilesDirs may return null for an ejected SDcard.
        if (externalFilesDir.isEmpty()) {
            Log.w(TAG, "Could not determine app's private files directory on external storage.")
            return null
        }
        type?.let {
            when (extDirType) {
                ExternalStorageDirType.EXT_MEDIA, ExternalStorageDirType.INT_MEDIA -> if (type == Environment.DIRECTORY_PICTURES) {
                    return File(externalFilesDir[0], Environment.DIRECTORY_PICTURES)
                }

                else -> {

                }
            }
        }
        return externalFilesDir[0]
    }

    /**
     * FileProvider does not support converting the absolute path from
     * getExternalFilesDir() to a "content://" Uri. As "file://" Uri
     * has been blocked since Android 7+, we need to build the Uri
     * manually after discovering the first external storage.
     * This is crucial to assist the user finding a writeable folder
     * to use Syncthing's two way sync feature.
     * API for getExternalFilesDirs(): 19+ (KITKAT+)
     * API for getExternalMediaDirs(): 21+ (LOLLIPOP+)
     */
    fun getExternalFilesDirUri(context: Context, extDirType: ExternalStorageDirType): Uri? {
        try {
            val externalFilesDir = getExternalFilesDir(context, extDirType, null)
            if (externalFilesDir == null) {
                Log.w(TAG, "Could not determine app's private files directory on external storage.")
                return null
            }
            val absPath = externalFilesDir.absolutePath

            // Log.v(TAG, "getExternalFilesDirUri: absPath=" + absPath);
            val segments = absPath.split("/".toRegex()).dropLastWhile { it.isEmpty() }
            if (segments.size < 2) {
                Log.w(
                    TAG,
                    "Could not extract volumeId from app's private files path '$absPath'"
                )
                return null
            }
            // Extract the volumeId, e.g. "abcd-efgh"
            val volumeId: String = segments[2]
            when (extDirType) {
                ExternalStorageDirType.DATA ->                     // Build the content Uri for our private ".../data/[PKG_NAME]/files" folder.
                    return ("content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                            volumeId + "%3AAndroid%2Fdata%2F" +
                            context.packageName + "%2Ffiles").toUri()

                ExternalStorageDirType.EXT_MEDIA ->                     // Build the content Uri for our private ".../media/[PKG_NAME]" folder.
                    return ("content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                            volumeId + "%3AAndroid%2Fmedia%2F" +
                            context.packageName).toUri()

                ExternalStorageDirType.INT_MEDIA ->                     // Build the content Uri for our private ".../media/[PKG_NAME]" folder.
                    return ("content://" + EXTERNAL_STORAGE_AUTHORITY + "/document/" +
                            "primary" + "%3AAndroid%2Fmedia%2F" +
                            context.packageName).toUri()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getExternalFilesDirUri exception", e)
        }
        return null
    }

    val internalStorageRootUri: Uri
        /**
         * FileProvider does not support converting absolute paths
         * to a "content://" Uri. As "file://" Uri has been blocked
         * since Android 7+, we need to build the Uri manually.
         */
        get() = "content://$EXTERNAL_STORAGE_AUTHORITY/document/primary%3A".toUri()

    private fun getVolumeIdFromTreeUri(treeUri: Uri?): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        return docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.firstOrNull()
    }

    private fun getDocumentPathFromTreeUri(treeUri: Uri?): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)

        /**
         * A document id is "<volume>:<path>" and the path may itself contain colons, e.g.
         * "primary:DCIM:Camera". Splitting on every colon truncated the path at the second
         * one, so picking a directory named e.g. "Photos:2024" resolved to "Photos" and
         * configured a completely different folder.
         */
        return docId.substringAfter(':', "").ifEmpty { File.separator }
    }

    val externalStorageDownloadsDirectory: String
        /**
         * Reading the environment var avoids hard coding the absolute path of the "/Download" folder.
         */
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .absolutePath

    fun cutTrailingSlash(path: String?): String {
        if (path?.endsWith(File.separator) == true) {
            return path.substring(0, path.length - 1)
        }
        return path ?: ""
    }

    /**
     * Deletes a directory recursively.
     *
     * @return true only if the directory and everything below it is confirmed gone.
     * A failure of any single entry is propagated, so a caller can tell a completed
     * deletion from a partial one instead of assuming success.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun deleteDirectoryRecursively(dir: File?): Boolean {
        if (dir?.exists() != true) return false
        if (dir.isFile) return dir.delete()
        if (dir.isSymbolicLink()) {
            // Delete a symlink without following it, exactly as the entries below do.
            return dir.delete()
        }

        var allDeleted = true
        // A null result means the directory could not be read, so nothing below it is
        // confirmed gone and the deletion must not be reported as complete.
        val children = dir.listFiles() ?: return false
        for (entry in children) {
            allDeleted = if (entry.isDirectory && entry.isSymbolicLink()) {
                // Delete symlinks without following them into arbitrary filesystem locations.
                entry.delete() && allDeleted
            } else {
                deleteDirectoryRecursively(entry) && allDeleted
            }
        }
        return dir.delete() && allDeleted
    }

    /**
     * Deletes everything inside [directory], leaving [directory] itself in place.
     *
     * Symlinks are deleted but never followed, so nothing outside the directory can be
     * removed through a link inside it.
     *
     * @return true only if every child was confirmed deleted. false means something is
     * still there, so a caller must not report the cleanup as successful.
     */
    @JvmStatic
    fun deleteDirectoryContents(directory: File): Boolean {
        val children = directory.listFiles() ?: return false
        var allDeleted = true
        for (child in children) {
            allDeleted = deleteDirectoryRecursively(child) && allDeleted
        }
        return allDeleted
    }

    /**
     * True if [candidate] is [root] itself or lies inside it, compared by canonical
     * path.
     *
     * Guards destructive operations against a path component that is a symlink leading
     * out of the tree the caller meant to operate on: deleting "through" such a link
     * would remove content the caller never named.
     */
    @JvmStatic
    fun isWithinDirectory(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalPath
        val candidatePath = candidate.canonicalPath
        return candidatePath == rootPath ||
                candidatePath.startsWith(rootPath + File.separator)
    }

    private fun File.isSymbolicLink(): Boolean {
        // lstat() does not follow the link. A canonical/absolute path comparison
        // cannot be used here as it also differs when an ancestor directory is a
        // symlink, which would flag ordinary files and directories as symlinks.
        return try {
            OsConstants.S_ISLNK(Os.lstat(path).st_mode)
        } catch (_: ErrnoException) {
            false
        }
    }

    @JvmStatic
    val syncthingTildeAbsolutePath: String
        /**
         * Expands the "~" path.
         * Equals SyncthingRunnable env "HOME"
         * Result: e.g. /storage/emulated/0/syncthing
         */
        get() = "$internalStorageRootAbsolutePath/syncthing"

    private val internalStorageRootAbsolutePath: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    /**
     * Derives the mime type from file extension.
     */
    fun getMimeTypeFromFileExtension(fileExtension: String): String {
        val fileMimeType = mimeTypes[fileExtension.lowercase(Locale.ROOT)]
        return fileMimeType ?: ""
    }

    fun safCreateDirectory(
        parentFolder: DocumentFile?,
        folderName: String
    ): DocumentFile? {
        if (parentFolder == null) {
            Log.w(TAG, "safCreateDirectory: parentFolder == null")
            return null
        }
        for (file in parentFolder.listFiles()) {
            if (file.isDirectory && file.name == folderName) {
                Log.v(TAG, "safCreateDirectory: Directory already exists '$folderName'")
                return file
            }
        }
        val dfNewFolder = parentFolder.createDirectory(folderName)
        if (dfNewFolder == null) {
            Log.w(TAG, "safCreateDirectory: Failed to create directory '$folderName'")
            return null
        }
        Log.v(TAG, "safCreateDirectory: Created directory '$folderName'")
        return dfNewFolder
    }

    fun safCreateFile(
        context: Context,
        parentFolder: DocumentFile,
        fileNameAndExtension: String,
        content: String
    ): Boolean {
        for (file in parentFolder.listFiles()) {
            if (file.isFile && file.name == fileNameAndExtension) {
                Log.v(TAG, "safCreateFile: File already exists '$fileNameAndExtension'")
                return true
            }
        }

        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileNameAndExtension)
        val fileMimeType = getMimeTypeFromFileExtension(fileExtension)

        var fileName: String? = fileNameAndExtension
        val dotIndex = fileNameAndExtension.lastIndexOf('.')
        if (dotIndex > 0) {
            fileName = fileNameAndExtension.substring(0, dotIndex)
        }

        var failSuccess = false
        try {
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentFolder.uri,
                fileMimeType,
                fileName ?: ""
            )
            if (fileUri == null) {
                Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #1")
                return false
            }
            context.contentResolver.openOutputStream(fileUri).use { outputStream ->
                if (content.isNotEmpty()) {
                    outputStream?.write(content.toByteArray(StandardCharsets.ISO_8859_1))
                }
            }

            Log.v(
                TAG,
                "safCreateFile: Created file '$fileNameAndExtension', type '$fileMimeType'"
            )
            failSuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "safCreateFile: Failed to create file '$fileNameAndExtension' #2", e)
        }
        return failSuccess
    }

    /**
     * Open file in compatible app.
     */
    fun openFile(context: Context, fullPathAndFilename: String) {
        var fileUri = fullPathAndFilename.toUri()
        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(fileUri.toString())
        val mimeType = getMimeTypeFromFileExtension(fileExtension)
        Log.v(
            TAG,
            "openFile: Detected mime type \'$mimeType\' for file \'$fullPathAndFilename\'"
        )
        val intent = when (fileExtension) {
            "apk" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // ACTION_INSTALL_PACKAGE is deprecated on Android 12+ (API 31).
                // ACTION_VIEW with the APK mime type ("application/vnd.android.package-archive",
                // as returned by getMimeTypeFromFileExtension) achieves the same result.
                Intent(Intent.ACTION_VIEW)
            } else {
                @Suppress("DEPRECATION")
                Intent(Intent.ACTION_INSTALL_PACKAGE)
            }

            else -> Intent(Intent.ACTION_VIEW)
        }


        fileUri = try {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                File(fullPathAndFilename)
            )
        } catch (e: Exception) {
            Log.e(TAG, "openFile: for '$fullPathAndFilename'", e)
            Toast.makeText(context, R.string.open_file_no_compatible_app, Toast.LENGTH_SHORT).show()
            return
        }
        intent.setDataAndType(fileUri, mimeType)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "openFile: ActivityNotFoundException. Falling back to app chooser...")
            val chooserIntent =
                Intent.createChooser(intent, context.getString(R.string.open_file_with))
            try {
                context.startActivity(chooserIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "openFile:", ex)
                Toast.makeText(context, R.string.open_file_no_compatible_app, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /**
     * Open folder in compatible file manager app.
     */
    fun openFolder(context: Context, folderPath: String?) {
        if (folderPath == null) {
            Toast.makeText(
                context,
                context.getString(R.string.state_error_message, "Invalid folder path"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            Toast.makeText(
                context,
                context.getString(R.string.state_error_message, "Invalid folder path"),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.putExtra("org.openintents.extra.ABSOLUTE_PATH", folderPath)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            // Expose the folder via FileProvider (content://) instead of a raw file:// URI,
            // which would throw FileUriExposedException on API 24+ when handed to another app.
            intent.setDataAndType(
                FileProvider.getUriForFile(context, context.packageName + ".provider", folder),
                "resource/folder"
            )
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Launch file manager.
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(
                TAG,
                "openFolder: No compatible file manager app not found or has insufficient permissions (stage #1)",
                e
            )
            openFolderWithDocumentUri(context, intent, folderPath)
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "openFolder: No compatible file manager app not found or has insufficient permissions (stage #1)",
                e
            )
            openFolderWithDocumentUri(context, intent, folderPath)
        } catch (e: IllegalArgumentException) {
            // FileProvider has no configured root for this path (e.g. on a removable SD card),
            // fall back to the external storage DocumentsContract URI.
            Log.e(
                TAG,
                "openFolder: FileProvider cannot expose the folder path, falling back to external storage URI",
                e
            )
            openFolderWithDocumentUri(context, intent, folderPath)
        } catch (e: Exception) {
            // FileUriExposedException is only available on API 24+; it is caught as a
            // defensive fallback even though the URIs above are content:// based.
            if (e is FileUriExposedException) {
                Log.e(
                    TAG,
                    "openFolder: No compatible file manager app not found or has insufficient permissions (stage #1)",
                    e
                )
                openFolderWithDocumentUri(context, intent, folderPath)
            } else {
                throw e
            }
        }
    }

    private fun openFolderWithDocumentUri(context: Context, intent: Intent, folderPath: String?) {
        try {
            val documentId = getDocumentIdFromPath(folderPath)
            val documentUri =
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, documentId)
            intent.setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            context.startActivity(intent)
        } catch (e2: ActivityNotFoundException) {
            Log.e(
                TAG,
                "openFolder: No compatible file manager app not found or has insufficient permissions (stage #2)",
                e2
            )
            // No compatible file manager app found.
            suggestFileManagerApp(context)
        } catch (e2: SecurityException) {
            Log.e(
                TAG,
                "openFolder: No compatible file manager app not found or has insufficient permissions (stage #2)",
                e2
            )
            suggestFileManagerApp(context)
        }
    }

    private fun suggestFileManagerApp(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.suggest_file_manager_app_dialog_title)
            .setMessage(R.string.suggest_file_manager_app_dialog_text)
            .setPositiveButton(
                R.string.yes,
                { _: DialogInterface?, _: Int ->
                    val appPackageName = "me.zhanghai.android.files"
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "market://details?id=$appPackageName".toUri()
                            )
                        )
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://play.google.com/store/apps/details?id=$appPackageName".toUri()
                            )
                        )
                    }
                })
            .setNegativeButton(
                R.string.no,
                null
            )
            .show()
    }

    /**
     * Converts a raw file path into a Storage Access Framework Document ID,
     * including support for removable SD cards and USB OTG drives.
     */
    private fun getDocumentIdFromPath(fullPath: String?): String? {
        var fullPath = fullPath
        if (fullPath.isNullOrEmpty()) {
            return null
        }

        // Clean up trailing slashes for consistent parsing
        if (fullPath.endsWith("/") && fullPath.length > 1) {
            fullPath = fullPath.substring(0, fullPath.length - 1)
        }

        val INTERNAL_STORAGE_PATH = "/storage/emulated/0"
        @SuppressLint("SdCardPath") val SDCARD_PATH = "/sdcard"
        val STORAGE_PREFIX = "/storage/"
        val PRIMARY_AUTHORITY_PREFIX = "primary:"

        // 1. Handle Internal Storage
        if (fullPath == INTERNAL_STORAGE_PATH || fullPath == SDCARD_PATH) {
            return PRIMARY_AUTHORITY_PREFIX // Root of internal storage
        } else if (fullPath.startsWith("$INTERNAL_STORAGE_PATH/")) {
            val relativePath = fullPath.substring(INTERNAL_STORAGE_PATH.length + 1)
            return PRIMARY_AUTHORITY_PREFIX + relativePath
        } else if (fullPath.startsWith("$SDCARD_PATH/")) {
            val relativePath = fullPath.substring(SDCARD_PATH.length + 1)
            return PRIMARY_AUTHORITY_PREFIX + relativePath
        } else if (fullPath.startsWith(STORAGE_PREFIX)) {
            // Path looks something like: /storage/1A2B-3C4D/books/sync
            val withoutStorage = fullPath.substring(STORAGE_PREFIX.length) // "1A2B-3C4D/books/sync"
            val firstSlashIndex = withoutStorage.indexOf('/')

            if (firstSlashIndex != -1) {
                // Extract the UUID and the relative path
                val uuid = withoutStorage.substring(0, firstSlashIndex) // "1A2B-3C4D"
                val relativePath = withoutStorage.substring(firstSlashIndex + 1) // "books/sync"
                return "$uuid:$relativePath"
            } else {
                // It's the absolute root of the SD card: /storage/1A2B-3C4D
                return "$withoutStorage:"
            }
        }

        // Path does not match known external storage patterns
        return null
    }

    enum class ExternalStorageDirType {
        DATA,
        EXT_MEDIA,
        INT_MEDIA
    }
}

class MimeTypes {
    companion object {
        val mimeTypes = hashMapOf(
            "323" to "text/h323",
            "3g2" to "video/3gpp2",
            "3gp" to "video/3gpp",
            "3gp2" to "video/3gpp2",
            "3gpp" to "video/3gpp",
            "7z" to "application/x-7z-compressed",
            "aa" to "audio/audible",
            "aac" to "audio/aac",
            "aaf" to "application/octet-stream",
            "aax" to "audio/vnd.audible.aax",
            "ac3" to "audio/ac3",
            "aca" to "application/octet-stream",
            "accda" to "application/msaccess.addin",
            "accdb" to "application/msaccess",
            "accdc" to "application/msaccess.cab",
            "accde" to "application/msaccess",
            "accdr" to "application/msaccess.runtime",
            "accdt" to "application/msaccess",
            "accdw" to "application/msaccess.webapplication",
            "accft" to "application/msaccess.ftemplate",
            "acx" to "application/internet-property-stream",
            "addin" to "text/xml",
            "ade" to "application/msaccess",
            "adobebridge" to "application/x-bridge-url",
            "adp" to "application/msaccess",
            "adt" to "audio/vnd.dlna.adts",
            "adts" to "audio/aac",
            "afm" to "application/octet-stream",
            "ai" to "application/postscript",
            "aif" to "audio/aiff",
            "aifc" to "audio/aiff",
            "aiff" to "audio/aiff",
            "air" to "application/vnd.adobe.air-application-installer-package+zip",
            "amc" to "application/mpeg",
            "anx" to "application/annodex",
            "apk" to "application/vnd.android.package-archive",
            "application" to "application/x-ms-application",
            "art" to "image/x-jg",
            "asa" to "application/xml",
            "asax" to "application/xml",
            "ascx" to "application/xml",
            "asd" to "application/octet-stream",
            "asf" to "video/x-ms-asf",
            "ashx" to "application/xml",
            "asi" to "application/octet-stream",
            "asm" to "text/plain",
            "asmx" to "application/xml",
            "aspx" to "application/xml",
            "asr" to "video/x-ms-asf",
            "asx" to "video/x-ms-asf",
            "atom" to "application/atom+xml",
            "au" to "audio/basic",
            "avi" to "video/x-msvideo",
            "axa" to "audio/annodex",
            "axs" to "application/olescript",
            "axv" to "video/annodex",
            "bas" to "text/plain",
            "bcpio" to "application/x-bcpio",
            "bin" to "application/octet-stream",
            "bmp" to "image/bmp",
            "c" to "text/plain",
            "cab" to "application/octet-stream",
            "caf" to "audio/x-caf",
            "calx" to "application/vnd.ms-office.calx",
            "cat" to "application/vnd.ms-pki.seccat",
            "cc" to "text/plain",
            "cd" to "text/plain",
            "cdda" to "audio/aiff",
            "cdf" to "application/x-cdf",
            "cer" to "application/x-x509-ca-cert",
            "cfg" to "text/plain",
            "chm" to "application/octet-stream",
            "class" to "application/x-java-applet",
            "clp" to "application/x-msclip",
            "cmd" to "text/plain",
            "cmx" to "image/x-cmx",
            "cnf" to "text/plain",
            "cod" to "image/cis-cod",
            "config" to "application/xml",
            "contact" to "text/x-ms-contact",
            "coverage" to "application/xml",
            "cpio" to "application/x-cpio",
            "cpp" to "text/plain",
            "crd" to "application/x-mscardfile",
            "crl" to "application/pkix-crl",
            "crt" to "application/x-x509-ca-cert",
            "cs" to "text/plain",
            "csdproj" to "text/plain",
            "csh" to "application/x-csh",
            "csproj" to "text/plain",
            "css" to "text/css",
            "csv" to "text/csv",
            "cur" to "application/octet-stream",
            "cxx" to "text/plain",
            "dat" to "application/octet-stream",
            "datasource" to "application/xml",
            "dbproj" to "text/plain",
            "dcr" to "application/x-director",
            "def" to "text/plain",
            "deploy" to "application/octet-stream",
            "der" to "application/x-x509-ca-cert",
            "dgml" to "application/xml",
            "dib" to "image/bmp",
            "dif" to "video/x-dv",
            "dir" to "application/x-director",
            "disco" to "text/xml",
            "divx" to "video/divx",
            "dll" to "application/x-msdownload",
            "dll.config" to "text/xml",
            "dlm" to "text/dlm",
            "dng" to "image/x-adobe-dng",
            "doc" to "application/msword",
            "docm" to "application/vnd.ms-word.document.macroEnabled.12",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "dot" to "application/msword",
            "dotm" to "application/vnd.ms-word.template.macroEnabled.12",
            "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "dsp" to "application/octet-stream",
            "dsw" to "text/plain",
            "dtd" to "text/xml",
            "dtsconfig" to "text/xml",
            "dv" to "video/x-dv",
            "dvi" to "application/x-dvi",
            "dwf" to "drawing/x-dwf",
            "dwp" to "application/octet-stream",
            "dxr" to "application/x-director",
            "eml" to "message/rfc822",
            "emz" to "application/octet-stream",
            "eot" to "application/vnd.ms-fontobject",
            "eps" to "application/postscript",
            "etl" to "application/etl",
            "etx" to "text/x-setext",
            "evy" to "application/envoy",
            "exe" to "application/octet-stream",
            "exe.config" to "text/xml",
            "fdf" to "application/vnd.fdf",
            "fif" to "application/fractals",
            "filters" to "application/xml",
            "fla" to "application/octet-stream",
            "flac" to "audio/flac",
            "flr" to "x-world/x-vrml",
            "flv" to "video/x-flv",
            "fsscript" to "application/fsharp-script",
            "fsx" to "application/fsharp-script",
            "generictest" to "application/xml",
            "gif" to "image/gif",
            "group" to "text/x-ms-group",
            "gsm" to "audio/x-gsm",
            "gtar" to "application/x-gtar",
            "gz" to "application/x-gzip",
            "h" to "text/plain",
            "hdf" to "application/x-hdf",
            "hdml" to "text/x-hdml",
            "hhc" to "application/x-oleobject",
            "hhk" to "application/octet-stream",
            "hhp" to "application/octet-stream",
            "hlp" to "application/winhlp",
            "hpp" to "text/plain",
            "hqx" to "application/mac-binhex40",
            "hta" to "application/hta",
            "htc" to "text/x-component",
            "htm" to "text/html",
            "html" to "text/html",
            "htt" to "text/webviewhtml",
            "hxa" to "application/xml",
            "hxc" to "application/xml",
            "hxd" to "application/octet-stream",
            "hxe" to "application/xml",
            "hxf" to "application/xml",
            "hxh" to "application/octet-stream",
            "hxi" to "application/octet-stream",
            "hxk" to "application/xml",
            "hxq" to "application/octet-stream",
            "hxr" to "application/octet-stream",
            "hxs" to "application/octet-stream",
            "hxt" to "text/html",
            "hxv" to "application/xml",
            "hxw" to "application/octet-stream",
            "hxx" to "text/plain",
            "i" to "text/plain",
            "ico" to "image/x-icon",
            "ics" to "text/calendar",
            "idl" to "text/plain",
            "ief" to "image/ief",
            "iii" to "application/x-iphone",
            "inc" to "text/plain",
            "inf" to "application/octet-stream",
            "ini" to "text/plain",
            "inl" to "text/plain",
            "ins" to "application/x-internet-signup",
            "ipa" to "application/x-itunes-ipa",
            "ipg" to "application/x-itunes-ipg",
            "ipproj" to "text/plain",
            "ipsw" to "application/x-itunes-ipsw",
            "iqy" to "text/x-ms-iqy",
            "isp" to "application/x-internet-signup",
            "ite" to "application/x-itunes-ite",
            "itlp" to "application/x-itunes-itlp",
            "itms" to "application/x-itunes-itms",
            "itpc" to "application/x-itunes-itpc",
            "ivf" to "video/x-ivf",
            "jar" to "application/java-archive",
            "java" to "application/octet-stream",
            "jck" to "application/liquidmotion",
            "jcz" to "application/liquidmotion",
            "jfif" to "image/pjpeg",
            "jnlp" to "application/x-java-jnlp-file",
            "jpb" to "application/octet-stream",
            "jpe" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "jpg" to "image/jpeg",
            "js" to "application/javascript",
            "json" to "application/json",
            "jsx" to "text/jscript",
            "jsxbin" to "text/plain",
            "latex" to "application/x-latex",
            "library-ms" to "application/windows-library+xml",
            "lit" to "application/x-ms-reader",
            "loadtest" to "application/xml",
            "lpk" to "application/octet-stream",
            "lsf" to "video/x-la-asf",
            "lst" to "text/plain",
            "lsx" to "video/x-la-asf",
            "lzh" to "application/octet-stream",
            "m13" to "application/x-msmediaview",
            "m14" to "application/x-msmediaview",
            "m1v" to "video/mpeg",
            "m2t" to "video/vnd.dlna.mpeg-tts",
            "m2ts" to "video/vnd.dlna.mpeg-tts",
            "m2v" to "video/mpeg",
            "m3u" to "audio/x-mpegurl",
            "m3u8" to "audio/x-mpegurl",
            "m4a" to "audio/m4a",
            "m4b" to "audio/m4b",
            "m4p" to "audio/m4p",
            "m4r" to "audio/x-m4r",
            "m4v" to "video/x-m4v",
            "mac" to "image/x-macpaint",
            "mak" to "text/plain",
            "man" to "application/x-troff-man",
            "manifest" to "application/x-ms-manifest",
            "map" to "text/plain",
            "master" to "application/xml",
            "mda" to "application/msaccess",
            "mdb" to "application/x-msaccess",
            "mde" to "application/msaccess",
            "mdp" to "application/octet-stream",
            "me" to "application/x-troff-me",
            "mfp" to "application/x-shockwave-flash",
            "mht" to "message/rfc822",
            "mhtml" to "message/rfc822",
            "mid" to "audio/mid",
            "midi" to "audio/mid",
            "mix" to "application/octet-stream",
            "mk" to "text/plain",
            "mkv" to "video/x-matroska",
            "mmf" to "application/x-smaf",
            "mno" to "text/xml",
            "mny" to "application/x-msmoney",
            "mod" to "video/mpeg",
            "mov" to "video/quicktime",
            "movie" to "video/x-sgi-movie",
            "mp2" to "video/mpeg",
            "mp2v" to "video/mpeg",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
            "mp4v" to "video/mp4",
            "mpa" to "video/mpeg",
            "mpe" to "video/mpeg",
            "mpeg" to "video/mpeg",
            "mpf" to "application/vnd.ms-mediapackage",
            "mpg" to "video/mpeg",
            "mpp" to "application/vnd.ms-project",
            "mpv2" to "video/mpeg",
            "mqv" to "video/quicktime",
            "ms" to "application/x-troff-ms",
            "msi" to "application/octet-stream",
            "mso" to "application/octet-stream",
            "mts" to "video/vnd.dlna.mpeg-tts",
            "mtx" to "application/xml",
            "mvb" to "application/x-msmediaview",
            "mvc" to "application/x-miva-compiled",
            "mxp" to "application/x-mmxp",
            "nc" to "application/x-netcdf",
            "nomedia" to "application/octet-stream",
            "nsc" to "video/x-ms-asf",
            "nws" to "message/rfc822",
            "ocx" to "application/octet-stream",
            "oda" to "application/oda",
            "odb" to "application/vnd.oasis.opendocument.database",
            "odc" to "application/vnd.oasis.opendocument.chart",
            "odf" to "application/vnd.oasis.opendocument.formula",
            "odg" to "application/vnd.oasis.opendocument.graphics",
            "odh" to "text/plain",
            "odi" to "application/vnd.oasis.opendocument.image",
            "odl" to "text/plain",
            "odm" to "application/vnd.oasis.opendocument.text-master",
            "odp" to "application/vnd.oasis.opendocument.presentation",
            "ods" to "application/vnd.oasis.opendocument.spreadsheet",
            "odt" to "application/vnd.oasis.opendocument.text",
            "oga" to "audio/ogg",
            "ogg" to "audio/ogg",
            "ogv" to "video/ogg",
            "ogx" to "application/ogg",
            "one" to "application/onenote",
            "onea" to "application/onenote",
            "onepkg" to "application/onenote",
            "onetmp" to "application/onenote",
            "onetoc" to "application/onenote",
            "onetoc2" to "application/onenote",
            "opus" to "audio/ogg",
            "orderedtest" to "application/xml",
            "osdx" to "application/opensearchdescription+xml",
            "otf" to "application/font-sfnt",
            "otg" to "application/vnd.oasis.opendocument.graphics-template",
            "oth" to "application/vnd.oasis.opendocument.text-web",
            "otp" to "application/vnd.oasis.opendocument.presentation-template",
            "ots" to "application/vnd.oasis.opendocument.spreadsheet-template",
            "ott" to "application/vnd.oasis.opendocument.text-template",
            "oxt" to "application/vnd.openofficeorg.extension",
            "p10" to "application/pkcs10",
            "p12" to "application/x-pkcs12",
            "p7b" to "application/x-pkcs7-certificates",
            "p7c" to "application/pkcs7-mime",
            "p7m" to "application/pkcs7-mime",
            "p7r" to "application/x-pkcs7-certreqresp",
            "p7s" to "application/pkcs7-signature",
            "pbm" to "image/x-portable-bitmap",
            "pcast" to "application/x-podcast",
            "pct" to "image/pict",
            "pcx" to "application/octet-stream",
            "pcz" to "application/octet-stream",
            "pdf" to "application/pdf",
            "pfb" to "application/octet-stream",
            "pfm" to "application/octet-stream",
            "pfx" to "application/x-pkcs12",
            "pgm" to "image/x-portable-graymap",
            "php" to "text/plain",
            "pic" to "image/pict",
            "pict" to "image/pict",
            "pkgdef" to "text/plain",
            "pkgundef" to "text/plain",
            "pko" to "application/vnd.ms-pki.pko",
            "pls" to "audio/scpls",
            "pma" to "application/x-perfmon",
            "pmc" to "application/x-perfmon",
            "pml" to "application/x-perfmon",
            "pmr" to "application/x-perfmon",
            "pmw" to "application/x-perfmon",
            "png" to "image/png",
            "pnm" to "image/x-portable-anymap",
            "pnt" to "image/x-macpaint",
            "pntg" to "image/x-macpaint",
            "pnz" to "image/png",
            "pot" to "application/vnd.ms-powerpoint",
            "potm" to "application/vnd.ms-powerpoint.template.macroEnabled.12",
            "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
            "ppa" to "application/vnd.ms-powerpoint",
            "ppam" to "application/vnd.ms-powerpoint.addin.macroEnabled.12",
            "ppm" to "image/x-portable-pixmap",
            "pps" to "application/vnd.ms-powerpoint",
            "ppsm" to "application/vnd.ms-powerpoint.slideshow.macroEnabled.12",
            "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "prf" to "application/pics-rules",
            "prm" to "application/octet-stream",
            "prx" to "application/octet-stream",
            "ps" to "application/postscript",
            "psc1" to "application/PowerShell",
            "psd" to "application/octet-stream",
            "psess" to "application/xml",
            "psm" to "application/octet-stream",
            "psp" to "application/octet-stream",
            "pub" to "application/x-mspublisher",
            "pwz" to "application/vnd.ms-powerpoint",
            "py" to "text/plain",
            "qht" to "text/x-html-insertion",
            "qhtm" to "text/x-html-insertion",
            "qt" to "video/quicktime",
            "qti" to "image/x-quicktime",
            "qtif" to "image/x-quicktime",
            "qtl" to "application/x-quicktimeplayer",
            "qxd" to "application/octet-stream",
            "ra" to "audio/x-pn-realaudio",
            "ram" to "audio/x-pn-realaudio",
            "rar" to "application/x-rar-compressed",
            "ras" to "image/x-cmu-raster",
            "rat" to "application/rat-file",
            "rb" to "text/plain",
            "rc" to "text/plain",
            "rc2" to "text/plain",
            "rct" to "text/plain",
            "rdlc" to "application/xml",
            "reg" to "text/plain",
            "resx" to "application/xml",
            "rf" to "image/vnd.rn-realflash",
            "rgb" to "image/x-rgb",
            "rgs" to "text/plain",
            "rm" to "application/vnd.rn-realmedia",
            "rmi" to "audio/mid",
            "rmp" to "application/vnd.rn-rn_music_package",
            "roff" to "application/x-troff",
            "rpm" to "audio/x-pn-realaudio-plugin",
            "rqy" to "text/x-ms-rqy",
            "rtf" to "application/rtf",
            "rtx" to "text/richtext",
            "ruleset" to "application/xml",
            "s" to "text/plain",
            "safariextz" to "application/x-safari-safariextz",
            "scd" to "application/x-msschedule",
            "scr" to "text/plain",
            "sct" to "text/scriptlet",
            "sd2" to "audio/x-sd2",
            "sdp" to "application/sdp",
            "sea" to "application/octet-stream",
            "searchConnector-ms" to "application/windows-search-connector+xml",
            "setpay" to "application/set-payment-initiation",
            "setreg" to "application/set-registration-initiation",
            "settings" to "application/xml",
            "sgimb" to "application/x-sgimb",
            "sgml" to "text/sgml",
            "sh" to "application/x-sh",
            "shar" to "application/x-shar",
            "shtml" to "text/html",
            "sit" to "application/x-stuffit",
            "sitemap" to "application/xml",
            "skin" to "application/xml",
            "sldm" to "application/vnd.ms-powerpoint.slide.macroEnabled.12",
            "sldx" to "application/vnd.openxmlformats-officedocument.presentationml.slide",
            "slk" to "application/vnd.ms-excel",
            "sln" to "text/plain",
            "slupkg-ms" to "application/x-ms-license",
            "smd" to "audio/x-smd",
            "smi" to "application/octet-stream",
            "smx" to "audio/x-smd",
            "smz" to "audio/x-smd",
            "snd" to "audio/basic",
            "snippet" to "application/xml",
            "snp" to "application/octet-stream",
            "sol" to "text/plain",
            "sor" to "text/plain",
            "spc" to "application/x-pkcs7-certificates",
            "spl" to "application/futuresplash",
            "spx" to "audio/ogg",
            "src" to "application/x-wais-source",
            "srf" to "text/plain",
            "ssisdeploymentmanifest" to "text/xml",
            "ssm" to "application/streamingmedia",
            "sst" to "application/vnd.ms-pki.certstore",
            "stl" to "application/vnd.ms-pki.stl",
            "sv4cpio" to "application/x-sv4cpio",
            "sv4crc" to "application/x-sv4crc",
            "svc" to "application/xml",
            "svg" to "image/svg+xml",
            "swf" to "application/x-shockwave-flash",
            "t" to "application/x-troff",
            "tar" to "application/x-tar",
            "tcl" to "application/x-tcl",
            "testrunconfig" to "application/xml",
            "testsettings" to "application/xml",
            "tex" to "application/x-tex",
            "texi" to "application/x-texinfo",
            "texinfo" to "application/x-texinfo",
            "tgz" to "application/x-compressed",
            "thmx" to "application/vnd.ms-officetheme",
            "thn" to "application/octet-stream",
            "tif" to "image/tiff",
            "tiff" to "image/tiff",
            "tlh" to "text/plain",
            "tli" to "text/plain",
            "toc" to "application/octet-stream",
            "tr" to "application/x-troff",
            "trm" to "application/x-msterminal",
            "trx" to "application/xml",
            "ts" to "video/vnd.dlna.mpeg-tts",
            "tsv" to "text/tab-separated-values",
            "ttf" to "application/font-sfnt",
            "tts" to "video/vnd.dlna.mpeg-tts",
            "txt" to "text/plain",
            "u32" to "application/octet-stream",
            "uls" to "text/iuls",
            "user" to "text/plain",
            "ustar" to "application/x-ustar",
            "vb" to "text/plain",
            "vbdproj" to "text/plain",
            "vbk" to "video/mpeg",
            "vbproj" to "text/plain",
            "vbs" to "text/vbscript",
            "vcf" to "text/x-vcard",
            "vcproj" to "application/xml",
            "vcs" to "text/plain",
            "vcxproj" to "application/xml",
            "vddproj" to "text/plain",
            "vdp" to "text/plain",
            "vdproj" to "text/plain",
            "vdx" to "application/vnd.ms-visio.viewer",
            "vml" to "text/xml",
            "vscontent" to "application/xml",
            "vsct" to "text/xml",
            "vsd" to "application/vnd.visio",
            "vsi" to "application/ms-vsi",
            "vsix" to "application/vsix",
            "vsixlangpack" to "text/xml",
            "vsixmanifest" to "text/xml",
            "vsmdi" to "application/xml",
            "vspscc" to "text/plain",
            "vss" to "application/vnd.visio",
            "vsscc" to "text/plain",
            "vssettings" to "text/xml",
            "vssscc" to "text/plain",
            "vst" to "application/vnd.visio",
            "vstemplate" to "text/xml",
            "vsto" to "application/x-ms-vsto",
            "vsw" to "application/vnd.visio",
            "vsx" to "application/vnd.visio",
            "vtx" to "application/vnd.visio",
            "wav" to "audio/wav",
            "wave" to "audio/wav",
            "wax" to "audio/x-ms-wax",
            "wbk" to "application/msword",
            "wbmp" to "image/vnd.wap.wbmp",
            "wcm" to "application/vnd.ms-works",
            "wdb" to "application/vnd.ms-works",
            "wdp" to "image/vnd.ms-photo",
            "webarchive" to "application/x-safari-webarchive",
            "webm" to "video/webm",
            "webp" to "image/webp",
            "webtest" to "application/xml",
            "wiq" to "application/xml",
            "wiz" to "application/msword",
            "wks" to "application/vnd.ms-works",
            "wlmp" to "application/wlmoviemaker",
            "wlpginstall" to "application/x-wlpg-detect",
            "wlpginstall3" to "application/x-wlpg3-detect",
            "wm" to "video/x-ms-wm",
            "wma" to "audio/x-ms-wma",
            "wmd" to "application/x-ms-wmd",
            "wmf" to "application/x-msmetafile",
            "wml" to "text/vnd.wap.wml",
            "wmlc" to "application/vnd.wap.wmlc",
            "wmls" to "text/vnd.wap.wmlscript",
            "wmlsc" to "application/vnd.wap.wmlscriptc",
            "wmp" to "video/x-ms-wmp",
            "wmv" to "video/x-ms-wmv",
            "wmx" to "video/x-ms-wmx",
            "wmz" to "application/x-ms-wmz",
            "woff" to "application/font-woff",
            "wpl" to "application/vnd.ms-wpl",
            "wps" to "application/vnd.ms-works",
            "wri" to "application/x-mswrite",
            "wrl" to "x-world/x-vrml",
            "wrz" to "x-world/x-vrml",
            "wsc" to "text/scriptlet",
            "wsdl" to "text/xml",
            "wvx" to "video/x-ms-wvx",
            "x" to "application/directx",
            "xaf" to "x-world/x-vrml",
            "xaml" to "application/xaml+xml",
            "xap" to "application/x-silverlight-app",
            "xbap" to "application/x-ms-xbap",
            "xbm" to "image/x-xbitmap",
            "xdr" to "text/plain",
            "xht" to "application/xhtml+xml",
            "xhtml" to "application/xhtml+xml",
            "xla" to "application/vnd.ms-excel",
            "xlam" to "application/vnd.ms-excel.addin.macroEnabled.12",
            "xlc" to "application/vnd.ms-excel",
            "xld" to "application/vnd.ms-excel",
            "xlk" to "application/vnd.ms-excel",
            "xll" to "application/vnd.ms-excel",
            "xlm" to "application/vnd.ms-excel",
            "xls" to "application/vnd.ms-excel",
            "xlsb" to "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
            "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlt" to "application/vnd.ms-excel",
            "xltm" to "application/vnd.ms-excel.template.macroEnabled.12",
            "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "xlw" to "application/vnd.ms-excel",
            "xml" to "text/xml",
            "xmta" to "application/xml",
            "xof" to "x-world/x-vrml",
            "xoml" to "text/plain",
            "xpm" to "image/x-xpixmap",
            "xps" to "application/vnd.ms-xpsdocument",
            "xrm-ms" to "text/xml",
            "xsc" to "application/xml",
            "xsd" to "text/xml",
            "xsf" to "text/xml",
            "xsl" to "text/xml",
            "xslt" to "text/xml",
            "xsn" to "application/octet-stream",
            "xss" to "application/xml",
            "xspf" to "application/xspf+xml",
            "xtp" to "application/octet-stream",
            "xwd" to "image/x-xwindowdump",
            "z" to "application/x-compress",
            "zip" to "application/zip"
        )
    }
}
