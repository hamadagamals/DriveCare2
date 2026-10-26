package com.drivecare.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * Centralized Audio & Natural Voice Alert Manager for DriveCare.
 * Plays high-quality Arabic human voice announcements and acoustic chimes.
 */
class AlertSoundPlayer(private val context: Context) {

    companion object {
        private const val TAG = "AlertSoundPlayer"
        private const val PREFS_NAME = "drivecare_audio_prefs"

        // Sound keys
        const val KEY_SOUND_BUMP_100M = "sound_bump_100m"
        const val KEY_SOUND_BUMP_25M = "sound_bump_25m"
        const val KEY_SOUND_ENGINE_HEAT = "sound_engine_heat"
        const val KEY_SOUND_DTC_ERROR = "sound_dtc_error"
        const val KEY_SOUND_BATTERY = "sound_battery"
        const val KEY_SOUND_OIL_MAINT = "sound_oil_maint"
        const val KEY_SOUND_SPEED_LIMIT = "sound_speed_limit"
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
                SoundOption("bump_100m_male", "صوت بشري طبيعي (رجالي)", "\"انتبه، مطب بعد مائة متر\"", R.raw.bump_100m_male, true),
                SoundOption("bump_100m_female", "صوت بشري طبيعي (نسائي)", "\"انتبه، يوجد مطب على بعد مائة متر\"", R.raw.bump_100m_female, true),
                SoundOption("tone_bump_100m", "نغمة رادار تنبيهية ناعمة", "نغمة بلورية هادئة ثنائية", R.raw.tone_bump_100m, false)
            )
            KEY_SOUND_BUMP_25M -> listOf(
                SoundOption("bump_25m_male", "صوت بشري حاسم (رجالي)", "\"مطب الآن، هدئ السرعة\"", R.raw.bump_25m_male, true),
                SoundOption("bump_25m_female", "صوت بشري حاسم (نسائي)", "\"مطب الآن، خفف السرعة\"", R.raw.bump_25m_female, true),
                SoundOption("tone_bump_25m", "نغمة تحذير ثلاثية سريعة", "إنذار صوتي فوري للاقتراب", R.raw.tone_bump_25m, false)
            )
            KEY_SOUND_ENGINE_HEAT -> listOf(
                SoundOption("voice_engine_heat", "صوت بشري تحذيري (رجالي)", "\"تحذير طارئ، حرارة المحرك مرتفعة جداً...\"", R.raw.voice_engine_heat, true),
                SoundOption("tone_engine_heat", "صفارة طوارئ حرارة متقطعة", "نغمة إنذار قوية متناوبة", R.raw.tone_engine_heat, false)
            )
            KEY_SOUND_DTC_ERROR -> listOf(
                SoundOption("voice_dtc_error", "صوت بشري تشخيصي (رجالي)", "\"تنبيه، تم رصد كود عطل في كمبيوتر السيارة\"", R.raw.voice_dtc_error, true),
                SoundOption("tone_dtc_error", "نغمة فحص إلكتروني احترافية", "نغمة فحص وبرمجة كمبيوتر", R.raw.tone_dtc_error, false)
            )
            KEY_SOUND_BATTERY -> listOf(
                SoundOption("voice_battery_low", "صوت بشري طبيعي (نسائي)", "\"تنبيه، انخفاض في جهد بطارية السيارة\"", R.raw.voice_battery_low, true),
                SoundOption("tone_battery", "نغمة تنبيه كهربائي هادئة", "نغمة انخفاض الشهد الكهربائي", R.raw.tone_battery, false)
            )
            KEY_SOUND_OIL_MAINT -> listOf(
                SoundOption("voice_oil_maint", "صوت بشري تذكيري (رجالي)", "\"تذكير، حان موعد الصيانة وتغيير زيت المحرك\"", R.raw.voice_oil_maint, true),
                SoundOption("tone_oil_maint", "جرس صيانة مجدول هادئ", "نغمة تذكير صيانة ناعمة", R.raw.tone_oil_maint, false)
            )
            KEY_SOUND_SPEED_LIMIT -> listOf(
                SoundOption("voice_speed_limit", "صوت بشري طبيعي (نسائي)", "\"تنبيه، لقد تجاوزت السرعة المحددة للطريق\"", R.raw.voice_speed_limit, true)
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
            KEY_SOUND_SPEED_LIMIT -> "voice_speed_limit"
            else -> ""
        }
        return prefs.getString(categoryKey, defaultId) ?: defaultId
    }

    fun setSelectedSoundId(categoryKey: String, soundId: String) {
        prefs.edit().putString(categoryKey, soundId).apply()
    }

    fun playRawSound(resId: Int) {
        try {
            stopCurrent()
            val player = MediaPlayer.create(context, resId) ?: return
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .build()
            )
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
        val selectedId = getSelectedSoundId(categoryKey)
        val option = getOptionsForCategory(categoryKey).firstOrNull { it.id == selectedId }
            ?: getOptionsForCategory(categoryKey).firstOrNull()
        if (option != null) {
            playRawSound(option.resId)
        }
    }

    fun playBump100m() = playCategorySound(KEY_SOUND_BUMP_100M)
    fun playBump25m() = playCategorySound(KEY_SOUND_BUMP_25M)
    fun playEngineHeat() = playCategorySound(KEY_SOUND_ENGINE_HEAT)
    fun playDtcError() = playCategorySound(KEY_SOUND_DTC_ERROR)
    fun playBatteryLow() = playCategorySound(KEY_SOUND_BATTERY)
    fun playOilMaintenance() = playCategorySound(KEY_SOUND_OIL_MAINT)
    fun playSpeedLimit() = playCategorySound(KEY_SOUND_SPEED_LIMIT)

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
