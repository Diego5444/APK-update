package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.example.data.SavedVideo
import com.example.data.VideoDatabase
import com.example.data.VideoRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class AudioTrackInfo(
    val id: String,
    val name: String,
    val language: String?,
    val isSelected: Boolean,
    val trackGroup: TrackGroup,
    val groupIndex: Int,
    val trackIndex: Int
)

data class SubtitleTrackInfo(
    val id: String,
    val name: String,
    val language: String?,
    val isSelected: Boolean,
    val trackGroup: TrackGroup,
    val groupIndex: Int,
    val trackIndex: Int
)

data class VideoTrackInfo(
    val id: String,
    val name: String,
    val isSelected: Boolean,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val trackGroup: TrackGroup,
    val groupIndex: Int,
    val trackIndex: Int
)

data class VideoPlayerState(
    val currentUrl: String = "",
    val currentTitle: String = "Sintel (DASH Stream)",
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val errorMessage: String? = null,
    val availableAudioTracks: List<AudioTrackInfo> = emptyList(),
    val availableSubtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val availableVideoTracks: List<VideoTrackInfo> = emptyList(),
    val isSubtitleEnabled: Boolean = false,
    val currentAudioTrackId: String? = null,
    val currentSubtitleTrackId: String? = null,
    val currentVideoTrackId: String? = null,
    val isMuted: Boolean = false,
    val duration: Long = 0L,
    val currentPosition: Long = 0L
)

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val database = VideoDatabase.getDatabase(application)
    private val repository = VideoRepository(database.videoDao())

    val savedVideos: StateFlow<List<SavedVideo>> = repository.allVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _uiState = MutableStateFlow(VideoPlayerState())
    val uiState: StateFlow<VideoPlayerState> = _uiState.asStateFlow()

    init {
        trustAllSSL()
        setupPlayerListener()
        // Load default preset videos if history is empty
        viewModelScope.launch {
            savedVideos.collectLatest { list ->
                if (list.isEmpty()) {
                    // Populate with awesome presets so the user has beautiful test cases immediately!
                    insertVideo("Vídeo de Belleza (MP4)", "https://zmedghe.rqsglwdvh.com/vod/4D1ADC1E31E6478AA5C395FA40DEDB5A_media.mp4", true)
                    insertVideo("Sintel (DASH - Multi-idioma)", "https://storage.googleapis.com/shaka-demo-assets/sintel/dash.mpd", false)
                    insertVideo("Tears of Steel (DASH - Multi-idioma)", "https://storage.googleapis.com/shaka-demo-assets/tears-of-steel/dash.mpd", false)
                    insertVideo("Big Buck Bunny (MP4 - Corto)", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", false)
                    insertVideo("Elephants Dream (MP4 - Corto)", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4", false)
                }
            }
        }
    }

    private fun trustAllSSL() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                }
            )
            val sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _uiState.update { 
                    it.copy(
                        playbackState = playbackState,
                        isLoading = playbackState == Player.STATE_BUFFERING,
                        duration = player.duration.coerceAtLeast(0L)
                    ) 
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentUrl = _uiState.value.currentUrl
                if (currentUrl.startsWith("https://", ignoreCase = true)) {
                    val fallbackUrl = currentUrl.replace("https://", "http://", ignoreCase = true)
                    _uiState.update { 
                        it.copy(
                            currentUrl = fallbackUrl,
                            errorMessage = "Error SSL/TLS detectado. Reintentando con conexión HTTP clara...",
                            isLoading = true
                        ) 
                    }
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(MediaItem.fromUri(fallbackUrl))
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    _uiState.update { 
                        it.copy(
                            errorMessage = "Error de reproducción: ${error.localizedMessage}", 
                            isLoading = false 
                        ) 
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                parseTracks(tracks)
            }
        })
    }

    fun playUrl(url: String, title: String = "Video Stream") {
        if (url.isBlank()) return
        
        // Sanitize URL: if it is from zmedghe.rqsglwdvh.com, rewrite to http:// and remove :80
        // because the server's SSL configuration is misconfigured.
        val sanitizedUrl = if (url.contains("zmedghe.rqsglwdvh.com", ignoreCase = true)) {
            url.replace("https://", "http://", ignoreCase = true)
               .replace(":80", "")
        } else if (url.startsWith("https://", ignoreCase = true) && url.contains(":80")) {
            url.replace("https://", "http://", ignoreCase = true)
        } else {
            url
        }
        
        _uiState.update { 
            it.copy(
                currentUrl = sanitizedUrl, 
                currentTitle = title,
                errorMessage = null, 
                isLoading = true 
            ) 
        }

        player.stop()
        player.clearMediaItems()
        
        val mediaItem = MediaItem.fromUri(sanitizedUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        // Record to history if it doesn't already exist or to keep it updated
        viewModelScope.launch {
            repository.insert(SavedVideo(title = title, url = sanitizedUrl, isFavorite = false, timestamp = System.currentTimeMillis()))
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _uiState.update { it.copy(currentPosition = positionMs) }
    }

    fun updatePosition() {
        _uiState.update { 
            it.copy(
                currentPosition = player.currentPosition.coerceAtLeast(0L),
                duration = player.duration.coerceAtLeast(0L)
            ) 
        }
    }

    fun selectAudioTrack(track: AudioTrackInfo) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(track.trackGroup, track.trackIndex)
            )
            .build()
    }

    fun selectSubtitleTrack(track: SubtitleTrackInfo) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(
                TrackSelectionOverride(track.trackGroup, track.trackIndex)
            )
            .build()
        _uiState.update { it.copy(isSubtitleEnabled = true, currentSubtitleTrackId = track.id) }
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _uiState.update { it.copy(isSubtitleEnabled = false, currentSubtitleTrackId = null) }
    }

    fun selectVideoTrack(track: VideoTrackInfo) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                TrackSelectionOverride(track.trackGroup, track.trackIndex)
            )
            .build()
    }

    fun clearVideoTrackOverride() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    }

    private fun parseTracks(tracks: Tracks) {
        val audioList = mutableListOf<AudioTrackInfo>()
        val subtitleList = mutableListOf<SubtitleTrackInfo>()
        val videoList = mutableListOf<VideoTrackInfo>()

        var selectedAudioId: String? = null
        var selectedSubtitleId: String? = null
        var selectedVideoId: String? = null

        var groupIndex = 0
        for (group in tracks.groups) {
            val mediaTrackGroup = group.mediaTrackGroup
            val trackType = group.type

            for (trackIndex in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(trackIndex)
                val isTrackSelected = group.isTrackSelected(trackIndex)
                val isSupported = group.isTrackSupported(trackIndex)

                if (!isSupported) continue

                val id = "${groupIndex}_$trackIndex"

                when (trackType) {
                    C.TRACK_TYPE_AUDIO -> {
                        val lang = format.language ?: "und"
                        val label = format.label ?: ""
                        val bitrateText = if (format.bitrate > 0) " (${format.bitrate / 1000} kbps)" else ""
                        val name = if (label.isNotEmpty()) "$label$bitrateText" else "${getLanguageName(lang)}$bitrateText"
                        
                        val trackInfo = AudioTrackInfo(id, name, format.language, isTrackSelected, mediaTrackGroup, groupIndex, trackIndex)
                        audioList.add(trackInfo)
                        if (isTrackSelected) {
                            selectedAudioId = id
                        }
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val lang = format.language ?: "und"
                        val label = format.label ?: ""
                        val name = if (label.isNotEmpty()) label else getLanguageName(lang)
                        
                        val trackInfo = SubtitleTrackInfo(id, name, format.language, isTrackSelected, mediaTrackGroup, groupIndex, trackIndex)
                        subtitleList.add(trackInfo)
                        if (isTrackSelected) {
                            selectedSubtitleId = id
                        }
                    }
                    C.TRACK_TYPE_VIDEO -> {
                        val width = format.width
                        val height = format.height
                        val bitrateText = if (format.bitrate > 0) " (${(format.bitrate / 1000000.0 * 10).toInt() / 10.0} Mbps)" else ""
                        val name = if (width > 0 && height > 0) "${height}p${bitrateText}" else "Quality $trackIndex$bitrateText"
                        
                        val trackInfo = VideoTrackInfo(id, name, isTrackSelected, width, height, format.bitrate, mediaTrackGroup, groupIndex, trackIndex)
                        videoList.add(trackInfo)
                        if (isTrackSelected) {
                            selectedVideoId = id
                        }
                    }
                }
            }
            groupIndex++
        }

        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        _uiState.update { 
            it.copy(
                availableAudioTracks = audioList,
                availableSubtitleTracks = subtitleList,
                availableVideoTracks = videoList,
                currentAudioTrackId = selectedAudioId,
                currentSubtitleTrackId = if (!textDisabled) selectedSubtitleId else null,
                currentVideoTrackId = selectedVideoId,
                isSubtitleEnabled = !textDisabled && selectedSubtitleId != null
            )
        }
    }

    private fun getLanguageName(code: String): String {
        return when (code.lowercase()) {
            "en", "eng" -> "English"
            "es", "spa" -> "Español"
            "fr", "fra", "fre" -> "Français"
            "de", "deu", "ger" -> "Deutsch"
            "it", "ita" -> "Italiano"
            "pt", "por" -> "Português"
            "ja", "jpn" -> "日本語"
            "zh", "zho", "chi" -> "中文"
            "ru", "rus" -> "Русский"
            "ar", "ara" -> "العربية"
            "und" -> "Unknown"
            else -> code.uppercase()
        }
    }

    fun insertVideo(title: String, url: String, isFavorite: Boolean = false) {
        viewModelScope.launch {
            repository.insert(SavedVideo(title = title, url = url, isFavorite = isFavorite, timestamp = System.currentTimeMillis()))
        }
    }

    fun deleteVideo(video: SavedVideo) {
        viewModelScope.launch {
            repository.delete(video)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun toggleMute() {
        val nextMuted = !_uiState.value.isMuted
        player.volume = if (nextMuted) 0f else 1f
        _uiState.update { it.copy(isMuted = nextMuted) }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
