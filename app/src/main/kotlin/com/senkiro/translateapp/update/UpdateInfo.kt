package com.senkiro.translateapp.update

/** GitHub Releases API 응답에서 필요한 정보만 뽑아낸 모델 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val apkDownloadUrl: String
)
