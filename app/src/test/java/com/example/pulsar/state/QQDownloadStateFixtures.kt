package com.example.pulsar.state

import com.example.pulsar.AlbumPickerState
import com.example.pulsar.QQDownloadUiState
import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQSongDto

/**
 * QQ 状态测试的共享夹具（#31）。
 *
 * 形状钉子（`com.example.pulsar.QQDownloadUiStateTest`）与清理钉子（[QQDownloadStateClearedTest]）
 * 都要一份「每个字段都**不是**默认值」的 state——两处各抄一遍迟早会漂，各留一份只增不减。
 *
 * **给 slice 新增字段时，把新字段也填进 [populated]**；不填的话反射夹具会红，
 * 提醒你新字段还没纳入通用断言。
 */
internal object QQDownloadStateFixtures {

    val song = QQSongDto(
        title = "晴天",
        singer = "周杰伦",
        album = "叶惠美",
        songMid = "0039MnYb0qxYhV",
        availableQualities = listOf("flac"),
    )

    val album = QQAlbumDto(
        albumMid = "000MkMni19ClKG",
        albumName = "叶惠美",
        singer = "周杰伦",
        songCount = 11,
    )

    val job = QQJobDto(
        jobId = "job-1",
        songMid = song.songMid,
        quality = "flac",
        title = "晴天",
        status = "running",
    )

    /** 四块 slice 都填满非默认值的 state。 */
    val populated = QQDownloadUiState(
        service = QQServiceState(
            serviceUrl = "http://192.168.1.10:8900",
            serviceReachable = true,
            credentialExpired = true,
            isRetryingCredential = true,
            credentialMessage = "凭证已刷新",
            libraryReady = false,
            libraryCount = 42,
            libraryRoot = "E:\\file\\Te_Music",
        ),
        settings = QQSettingsState(
            quality = "320",
            fileOpsKey = "key",
            fileOpsKeyInput = "key-input",
        ),
        search = QQSearchState(
            query = "晴天",
            isSearching = true,
            songs = listOf(song),
            albums = listOf(album),
            searchHadResults = true,
            error = "连不上下载服务",
        ),
        download = QQDownloadState(
            jobs = listOf(job),
            downloadingMids = setOf("down-1"),
            inLibraryMids = setOf("lib-1"),
            isEnqueuingAlbum = true,
            enqueuingAlbumMid = album.albumMid,
            albumPicker = AlbumPickerState(albumMid = album.albumMid, albumName = album.albumName),
            redownloadPrompt = song,
            actionMessage = "该歌曲没有可下载的音质",
        ),
    )
}
