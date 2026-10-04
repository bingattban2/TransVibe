package com.transvibe.app.data.player

import android.content.Context
import android.net.Uri
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.data.subtitle.SubtitleExporter
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.SourceAccessState
import com.transvibe.app.domain.repository.PlayerGateway
import kotlinx.coroutines.CancellationException

class PlayerRepository(private val context: Context) : PlayerGateway {
    override fun checkSourceAccess(session: ProcessingSession): SourceAccessState {
        val accessible = try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(session.videoUri), "r")?.use { true } ?: false
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
        return SourceAccessState(session.id, accessible)
    }

    override fun export(session: ProcessingSession, destinationUri: String, webVtt: Boolean): String = try {
        val destination = Uri.parse(destinationUri)
        if (webVtt) SubtitleExporter.writeVtt(context.contentResolver, destination, session.segments)
        else SubtitleExporter.writeSrt(context.contentResolver, destination, session.segments)
        if (webVtt) "تم حفظ WebVTT بنجاح." else "تم حفظ SRT بنجاح."
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        ProcessingErrorMapper.fromThrowable(t).userMessage
    }
}
