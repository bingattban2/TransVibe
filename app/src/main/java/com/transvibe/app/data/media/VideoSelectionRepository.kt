package com.transvibe.app.data.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.repository.VideoSelectionGateway

class VideoSelectionRepository(private val context: Context) : VideoSelectionGateway {
    override fun select(uriString: String): SelectedVideo {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val persistable = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(false)
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "فيديو"
        return SelectedVideo(uri.toString(), name, persistable)
    }
}
