package kr.hs.minwonshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private var pendingUpload: CallRecording? = null

    private val drivePreferences by lazy {
        getSharedPreferences(DRIVE_PREFERENCES, MODE_PRIVATE)
    }

    private val authorizationClient by lazy {
        Identity.getAuthorizationClient(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createScreen())
        updatePermissionStatus()
        openPickerWhenRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openPickerWhenRequested(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DRIVE_AUTHORIZATION) {
            return
        }

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.drive_authorization_failed, Toast.LENGTH_LONG).show()
            return
        }

        try {
            val result = authorizationClient.getAuthorizationResultFromIntent(data)
            handleAuthorizationResult(result)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.drive_authorization_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()

        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (hasAudioPermission()) {
                openAudioPicker()
            } else {
                Toast.makeText(this, R.string.audio_permission_needed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createScreen(): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()

        statusText = TextView(this).apply {
            textSize = 18f
        }

        val permissionButton = Button(this).apply {
            text = getString(R.string.permission_button)
            setOnClickListener { requestRequiredPermissions() }
        }

        val driveFolderButton = Button(this).apply {
            text = getString(R.string.drive_folder_button)
            setOnClickListener {
                pendingUpload = null
                requestDriveAuthorization(selectFolder = true)
            }
        }

        val testButton = Button(this).apply {
            text = getString(R.string.test_button)
            setOnClickListener { openAudioPicker() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(statusText)
            addView(permissionButton)
            addView(driveFolderButton)
            addView(testButton)
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            audioPermission()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun updatePermissionStatus() {
        val phoneGranted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val audioGranted = hasAudioPermission()
        val folderConnected = savedDriveFolderId() != null

        statusText.text = when {
            !phoneGranted || !notificationGranted || !audioGranted ->
                getString(R.string.status_permission_needed)
            !folderConnected -> getString(R.string.status_drive_folder_needed)
            else -> getString(R.string.status_ready)
        }
    }

    private fun openPickerWhenRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PICKER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PICKER)
            openAudioPicker()
        }
    }

    private fun openAudioPicker() {
        if (!hasAudioPermission()) {
            requestPermissions(arrayOf(audioPermission()), REQUEST_AUDIO_PERMISSION)
            return
        }

        val recordings = queryRecentCallRecordings()
        if (recordings.isEmpty()) {
            Toast.makeText(this, R.string.no_recent_recording, Toast.LENGTH_LONG).show()
            return
        }

        val fileNames = recordings.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.recent_recording_title)
            .setItems(fileNames) { _, index ->
                prepareDriveUpload(recordings[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun queryRecentCallRecordings(): List<CallRecording> {
        val recordings = mutableListOf<CallRecording>()
        val oneHourAgo = System.currentTimeMillis() / 1000L - RECENT_WINDOW_SECONDS
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val selection =
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Audio.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(CALL_RECORDINGS_PATH, oneHourAgo.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val mimeType = cursor.getString(typeColumn) ?: DEFAULT_AUDIO_MIME_TYPE
                val uri = ContentUris.withAppendedId(collection, id)
                recordings.add(CallRecording(name, uri, size, mimeType))
            }
        }

        return recordings
    }

    private fun prepareDriveUpload(recording: CallRecording) {
        pendingUpload = recording
        requestDriveAuthorization(selectFolder = savedDriveFolderId() == null)
    }

    private fun requestDriveAuthorization(selectFolder: Boolean) {
        val requestBuilder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .setOptOutIncludingGrantedScopes(true)

        if (selectFolder) {
            requestBuilder
                .setPrompt(
                    AuthorizationRequest.Prompt.CONSENT or
                        AuthorizationRequest.Prompt.SELECT_ACCOUNT
                )
                .addResourceParameter(
                    AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER,
                    "true"
                )
                .addResourceParameter(
                    AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION,
                    "true"
                )
                .addResourceParameter(
                    AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE,
                    "false"
                )
                .addResourceParameter(
                    AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES,
                    DRIVE_FOLDER_MIME_TYPE
                )
        }

        authorizationClient.authorize(requestBuilder.build())
            .addOnSuccessListener { result -> continueAuthorization(result) }
            .addOnFailureListener {
                Toast.makeText(
                    this,
                    R.string.drive_authorization_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    @Suppress("DEPRECATION")
    private fun continueAuthorization(result: AuthorizationResult) {
        if (!result.hasResolution()) {
            handleAuthorizationResult(result)
            return
        }

        val pendingIntent = result.pendingIntent
        if (pendingIntent == null) {
            Toast.makeText(this, R.string.drive_authorization_failed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startIntentSenderForResult(
                pendingIntent.intentSender,
                REQUEST_DRIVE_AUTHORIZATION,
                null,
                0,
                0,
                0
            )
        } catch (_: IntentSender.SendIntentException) {
            Toast.makeText(this, R.string.drive_authorization_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleAuthorizationResult(result: AuthorizationResult) {
        val pickedFolderId = result.tokenResponseParams
            ?.getString(PICKED_FILE_IDS)
            ?.split(',')
            ?.firstOrNull { it.isNotBlank() }

        if (pickedFolderId != null) {
            drivePreferences.edit()
                .putString(DRIVE_FOLDER_ID, pickedFolderId)
                .apply()
        }

        val folderId = savedDriveFolderId()
        if (folderId == null) {
            Toast.makeText(this, R.string.drive_folder_not_selected, Toast.LENGTH_LONG).show()
            return
        }

        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) {
            Toast.makeText(this, R.string.drive_authorization_failed, Toast.LENGTH_LONG).show()
            return
        }

        updatePermissionStatus()
        val recording = pendingUpload
        if (recording == null) {
            Toast.makeText(this, R.string.drive_folder_connected, Toast.LENGTH_LONG).show()
            return
        }

        pendingUpload = null
        uploadRecording(recording, accessToken, folderId)
    }

    private fun uploadRecording(
        recording: CallRecording,
        accessToken: String,
        folderId: String
    ) {
        statusText.text = getString(R.string.status_uploading)

        Thread {
            val result = runCatching {
                performResumableUpload(recording, accessToken, folderId)
            }

            runOnUiThread {
                if (result.isSuccess) {
                    updatePermissionStatus()
                    Toast.makeText(
                        this,
                        R.string.drive_upload_complete,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    statusText.text = getString(R.string.status_upload_failed)
                    val message = result.exceptionOrNull()?.message
                        ?: getString(R.string.drive_upload_failed)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun performResumableUpload(
        recording: CallRecording,
        accessToken: String,
        folderId: String
    ) {
        val uploadSize = recording.size.takeIf { it > 0L }
            ?: contentResolver.openAssetFileDescriptor(recording.uri, "r")?.use { it.length }
            ?: -1L
        if (uploadSize <= 0L) {
            error("녹음 파일 크기를 확인할 수 없습니다.")
        }

        val metadata = JSONObject()
            .put("name", recording.name)
            .put("parents", JSONArray().put(folderId))
            .toString()
            .toByteArray(Charsets.UTF_8)

        val uploadUrl = createUploadSession(
            metadata,
            recording.mimeType,
            uploadSize,
            accessToken
        )
        uploadFile(uploadUrl, recording, uploadSize, accessToken)
    }

    private fun createUploadSession(
        metadata: ByteArray,
        mimeType: String,
        uploadSize: Long,
        accessToken: String
    ): String {
        val connection = URL(DRIVE_UPLOAD_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("X-Upload-Content-Type", mimeType)
            connection.setRequestProperty("X-Upload-Content-Length", uploadSize.toString())
            connection.setFixedLengthStreamingMode(metadata.size)
            connection.outputStream.use { it.write(metadata) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("Drive 업로드 준비 실패($responseCode)")
            }

            return connection.getHeaderField("Location")
                ?: error("Drive 업로드 주소를 받지 못했습니다.")
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadFile(
        uploadUrl: String,
        recording: CallRecording,
        uploadSize: Long,
        accessToken: String
    ) {
        val connection = URL(uploadUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", recording.mimeType)
            connection.setRequestProperty(
                "Content-Range",
                "bytes 0-${uploadSize - 1}/$uploadSize"
            )
            connection.setFixedLengthStreamingMode(uploadSize)

            val input = contentResolver.openInputStream(recording.uri)
                ?: error("녹음 파일을 열 수 없습니다.")
            input.use { source ->
                connection.outputStream.use { target ->
                    source.copyTo(target)
                }
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("Drive 업로드 실패($responseCode)")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun savedDriveFolderId(): String? {
        return drivePreferences.getString(DRIVE_FOLDER_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun audioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasAudioPermission(): Boolean {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private data class CallRecording(
        val name: String,
        val uri: Uri,
        val size: Long,
        val mimeType: String
    )

    companion object {
        const val EXTRA_OPEN_PICKER = "open_picker"

        private const val REQUEST_PERMISSIONS = 1002
        private const val REQUEST_AUDIO_PERMISSION = 1003
        private const val REQUEST_DRIVE_AUTHORIZATION = 1004
        private const val RECENT_WINDOW_SECONDS = 60L * 60L
        private const val CALL_RECORDINGS_PATH = "Recordings/Call%"
        private const val DEFAULT_AUDIO_MIME_TYPE = "audio/mp4"

        private const val DRIVE_PREFERENCES = "drive_settings"
        private const val DRIVE_FOLDER_ID = "drive_folder_id"
        private const val PICKED_FILE_IDS = "picked_file_ids"
        private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val DRIVE_UPLOAD_ENDPOINT =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id"
    }
}
