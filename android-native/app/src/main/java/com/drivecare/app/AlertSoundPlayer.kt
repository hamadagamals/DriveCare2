package com.drivecare.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * Centralized Audio & Natural Voice Alert Manager for DriveCare.
 * Plays high-quality concise Arabic human voice announcements and acoustic chimes
 * with independent volume control per hazard category.
 */
class AlertSoundPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AlertSoundPlayer"
        private const val PREFS_NAME = "drivecare_audio_prefs"

        // Sound category keys
        const val KEY_SOUND_BUMP_100M = "sound_bump_100m"
        const val KEY_SOUND_BUMP_25M = "sound_bump_25m"
        const val KEY_SOUND_ENGINE_HEAT = "sound_engine_heat"
        const val KEY_SOUND_DTC_ERROR = "sound_dtc_error"
        const val KEY_SOUND_BATTERY = "sound_battery"
        const val KEY_SOUND_OIL_MAINT = "sound_oil_maint"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentPlayer: MediaPlayer? = null

    data class SoundOption(
        val id: String,
        val title: String,
        val subtitle: String,
        val resId: Int,
        val isVoice: Boolean
    )

    fun getOptionsForCategory(categoryKey: String): List<SoundOption> {
        return when (categoryKey) {
            KEY_SOUND_BUMP_100M -> listOf(
                SoundOption("bump_100m_male", "صوت بشري طبيعي (رجالي)", "\"مطب بعد مائة متر\"", R.raw.bump_100m_male, true),
                SoundOption("bump_100m_female", "صوت بشري طبيعي (نسائي)", "\"مطب صناعي بعد 100 متر\"", R.raw.bump_100m_female, true),
                SoundOption("tone_bump_100m", "نغمة رادار بلورية هادئة", "نغمة ثنائية ناعمة للاقتراب", R.raw.tone_bump_100m, false),
                SoundOption("tone_bump_100m_alt", "نغمة جرس تنبيه متصاعد", "نغمة صاعدة مريحة للأذن", R.raw.tone_bump_100m_alt, false)
            )
            KEY_SOUND_BUMP_25M -> listOf(
                SoundOption("bump_25m_male", "صوت بشري حاسم (رجالي)", "\"مطب الآن\"", R.raw.bump_25m_male, true),
                SoundOption("bump_25m_female", "صوت بشري حاسم (نسائي)", "\"مطب\"", R.raw.bump_25m_female, true),
                SoundOption("tone_bump_25m", "نغمة تحذير ثلاثية سريعة", "إنذار صوتي فوري للاقتراب", R.raw.tone_bump_25m, false),
                SoundOption("tone_bump_25m_alt", "نقرة سونار ثنائية حاسمة", "تنبيه سونار فوري مباشر", R.raw.tone_bump_25m_alt, false)
            )
            KEY_SOUND_ENGINE_HEAT -> listOf(
                SoundOption("voice_engine_heat", "صوت بشري تحذيري", "\"حرارة المحرك مرتفعة\"", R.raw.voice_engine_heat, true),
                SoundOption("tone_engine_heat", "صفارة طوارئ حرارة متقطعة", "نغمة إنذار قوية متناوبة", R.raw.tone_engine_heat, false)
            )
            KEY_SOUND_DTC_ERROR -> listOf(
                SoundOption("voice_dtc_error", "صوت بشري تشخيصي", "\"عطل في المحرك\"", R.raw.voice_dtc_error, true),
                SoundOption("tone_dtc_error", "نغمة فحص وبرمجة كمبيوتر", "نغمة فحص إلكتروني احترافية", R.raw.tone_dtc_error, false)
            )
            KEY_SOUND_BATTERY -> listOf(
                SoundOption("voice_battery_low", "صوت بشري طبيعي", "\"شحن البطارية منخفض\"", R.raw.voice_battery_low, true),
                SoundOption("tone_battery", "نغمة تنبيه كهربائي هادئة", "نغمة انخفاض الجهد الكهربائي", R.raw.tone_battery, false)
            )
            KEY_SOUND_OIL_MAINT -> listOf(
                SoundOption("voice_oil_maint", "صوت بشري تذكيري", "\"موعد غيار الزيت\"", R.raw.voice_oil_maint, true),
                SoundOption("tone_oil_maint", "جرس صيانة مجدول ناعم", "نغمة تذكير صيانة هادئة", R.raw.tone_oil_maint, false)
            )
            else -> emptyList()
        }
    }

    fun getSelectedSoundId(categoryKey: String): String {
        val defaultId = when (categoryKey) {
            KEY_SOUND_BUMP_100M -> "bump_100m_male"
            KEY_SOUND_BUMP_25M -> "bump_25m_male"
            KEY_SOUND_ENGINE_HEAT -> "voice_engine_heat"
            KEY_SOUND_DTC_ERROR -> "voice_dtc_error"
            KEY_SOUND_BATTERY -> "voice_battery_low"
            KEY_SOUND_OIL_MAINT -> "voice_oil_maint"
            else -> ""
        }
        return prefs.getString(categoryKey, defaultId) ?: defaultId
    }

    fun setSelectedSoundId(categoryKey: String, soundId: String) {
        prefs.edit().putString(categoryKey, soundId).apply()
    }

    /**
     * Volume control per hazard category (0.0f to 1.0f).
     * Defaults to 1.0f (100% volume).
     */
    fun getCategoryVolume(categoryKey: String): Float {
        return prefs.getFloat("vol_$categoryKey", 1.0f)
    }

    fun setCategoryVolume(categoryKey: String, volume: Float) {
        val safeVol = volume.coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat("vol_$categoryKey", safeVol).apply()
    }

    fun playRawSound(resId: Int, volume: Float = 1.0f) {
        try {
            stopCurrent()
            val safeVol = volume.coerceIn(0.0f, 1.0f)
            if (safeVol <= 0.02f) return // Volume muted
            val player = MediaPlayer.create(context, resId) ?: return
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build()
            )
            player.setVolume(safeVol, safeVol)
            player.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Exception) {}
                if (currentPlayer === it) currentPlayer = null
            }
            player.start()
            currentPlayer = player
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play sound: ${e.message}")
        }
    }

    fun playCategorySound(categoryKey: String) {
        val vol = getCategoryVolume(categoryKey)
        if (vol <= 0.02f) return // Suppressed/Muted for this hazard
        val selectedId = getSelectedSoundId(categoryKey)
        val option = getOptionsForCategory(categoryKey).firstOrNull { it.id == selectedId }
            ?: getOptionsForCategory(categoryKey).firstOrNull()
        if (option != null) {
            playRawSound(option.resId, vol)
        }
    }

    fun playBump100m() = playCategorySound(KEY_SOUND_BUMP_100M)
    fun playBump25m() = playCategorySound(KEY_SOUND_BUMP_25M)
    fun playEngineHeat() = playCategorySound(KEY_SOUND_ENGINE_HEAT)
    fun playDtcError() = playCategorySound(KEY_SOUND_DTC_ERROR)
    fun playBatteryLow() = playCategorySound(KEY_SOUND_BATTERY)
    fun playOilMaintenance() = playCategorySound(KEY_SOUND_OIL_MAINT)

    fun stopCurrent() {
        try {
            currentPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        currentPlayer = null
    }
}
