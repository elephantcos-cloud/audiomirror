package com.audiomirror

enum class AudioMode(val key: String) {
    DEVICE_ONLY("device_only"),
    MIC_ONLY("mic_only"),
    DEVICE_AND_MIC("device_and_mic"),
    TWO_WAY_MIC("two_way_mic"),
    MUTE_SOURCE("mute_source");   // earbuds mode — source phone muted

    companion object {
        fun fromKey(key: String?): AudioMode = values().find { it.key == key } ?: MIC_ONLY
    }
}
