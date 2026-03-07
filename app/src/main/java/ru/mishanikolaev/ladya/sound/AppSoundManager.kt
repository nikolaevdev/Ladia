package ru.mishanikolaev.ladya.sound

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes
import ru.mishanikolaev.ladya.R

enum class SoundEffect(
    @RawRes val resId: Int,
    val prefKey: String
) {
    IncomingCall(R.raw.incoming_call, "sound_incoming_call"),
    OutgoingCall(R.raw.outgoing_call, "sound_outgoing_call"),
    CallConnected(R.raw.call_connected, "sound_call_connected"),
    CallEnded(R.raw.call_ended, "sound_call_ended"),
    MessageIncoming(R.raw.message_incoming, "sound_message_incoming"),
    MessageOutgoing(R.raw.message_outgoing, "sound_message_outgoing"),
    MessageError(R.raw.message_error, "sound_message_error"),
    FileReceived(R.raw.file_received, "sound_file_received"),
    FileSent(R.raw.file_sent, "sound_file_sent"),
    DeviceFound(R.raw.device_found, "sound_device_found"),
    DeviceVerified(R.raw.device_verified, "sound_device_verified"),
    DeviceWarning(R.raw.device_warning, "sound_device_warning"),
    NotificationSoft(R.raw.notification_soft, "sound_notification_soft")
}

object AppSoundManager {
    private const val PREFS_NAME = "ladya_prefs"
    private const val PREF_MASTER = "sound_master_enabled"
    private const val TAG = "AppSoundManager"

    private var loopingPlayer: MediaPlayer? = null

    fun isMasterEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_MASTER, true)

    fun isEnabled(context: Context, effect: SoundEffect): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(effect.prefKey, true)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_MASTER, enabled)
            .apply()
    }

    fun setEnabled(context: Context, effect: SoundEffect, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(effect.prefKey, enabled)
            .apply()
    }

    fun play(context: Context, effect: SoundEffect) {
        if (!isMasterEnabled(context) || !isEnabled(context, effect)) return
        createPlayer(context, effect.resId, looping = false)?.apply {
            setOnCompletionListener {
                runCatching { it.release() }
            }
            start()
        }
    }

    fun startLoop(context: Context, effect: SoundEffect) {
        if (!isMasterEnabled(context) || !isEnabled(context, effect)) return
        stopLoop()
        loopingPlayer = createPlayer(context, effect.resId, looping = true)?.apply {
            start()
        }
    }

    fun stopLoop() {
        runCatching { loopingPlayer?.stop() }
        runCatching { loopingPlayer?.release() }
        loopingPlayer = null
    }

    private fun createPlayer(context: Context, @RawRes resId: Int, looping: Boolean): MediaPlayer? {
        val afd: AssetFileDescriptor = context.resources.openRawResourceFd(resId) ?: return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = looping
                prepare()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось воспроизвести звук resId=$resId", t)
            null
        } finally {
            runCatching { afd.close() }
        }
    }
}
