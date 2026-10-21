package com.destinationalarm.placesearch

internal enum class AlarmPlaybackMode { NONE, TEST, ARRIVAL }

/** One-shot arrival state and test-playback state, independent of journey coordinates. */
internal class AlarmLifecycle {
    var arrived: Boolean = false
        private set
    var playbackMode: AlarmPlaybackMode = AlarmPlaybackMode.NONE
        private set

    fun restore(arrived: Boolean) {
        this.arrived = arrived
        playbackMode = AlarmPlaybackMode.NONE
    }

    fun beginJourney() {
        arrived = false
        playbackMode = AlarmPlaybackMode.NONE
    }

    fun confirmArrival(): Boolean {
        if (arrived) return false
        arrived = true
        playbackMode = AlarmPlaybackMode.ARRIVAL
        return true
    }

    fun beginTest(): Boolean {
        if (playbackMode != AlarmPlaybackMode.NONE) return false
        playbackMode = AlarmPlaybackMode.TEST
        return true
    }

    fun finishTest(): Boolean {
        if (playbackMode != AlarmPlaybackMode.TEST) return false
        playbackMode = AlarmPlaybackMode.NONE
        return true
    }

    fun dismissArrival(): Boolean {
        if (!arrived || playbackMode != AlarmPlaybackMode.ARRIVAL) return false
        playbackMode = AlarmPlaybackMode.NONE
        return true
    }

    fun clearJourney() {
        arrived = false
        playbackMode = AlarmPlaybackMode.NONE
    }
}
