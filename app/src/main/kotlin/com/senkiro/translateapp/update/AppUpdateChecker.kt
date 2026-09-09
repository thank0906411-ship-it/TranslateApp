package com.senkiro.translateapp.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.senkiro.translateapp.BuildConfig
import com.senkiro.translateapp.R
import com.senkiro.translateapp.utils.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * GitHub Releases API로 최신 릴리스를 확인하고, 새 버전이면 APK를 다운로드해
 * 시스템 설치 화면을 띄운다. 별도 서버 없이 GitHub Releases만으로 동작한다.
 *
 * 이 repo는 public이므로 인증 없이도 Releases API 호출과 asset 다운로드가 된다.
 * BuildConfig.GITHUB_UPDATE_PAT는 과거 private repo였을 때의 잔재로, 로컬
 * 빌드에서 본인이 이 값을 채워 넣으면(예: private으로 되돌린 경우) 계속 인증
 * 헤더를 붙여 쓸 수 있도록 남겨뒀다 — 공개 CI 빌드에는 주입되지 않으므로
 * (release.yml 참고) 공개 배포되는 APK에는 이 토큰이 담기지 않는다.
 *
 * 요구사항:
 *  - GitHub Release의 태그 이름(tag_name)이 버전 코드 숫자를 포함해야 한다. (예: "v3" 또는 "3")
 *  - Release의 에셋(assets) 중 ".apk"로 끝나는 파일이 정확히 하나 있어야 한다.
 */
class AppUpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder().build()

    /** 다운로드 완료 리시버. Activity가 소멸될 때(cancelPendingDownload) 해제해 leak을 막는다. */
    private var downloadReceiver: BroadcastReceiver? = null

    companion object {
        // "owner/repo" 형식.
        private const val GITHUB_REPO = "thank0906411-ship-it/TranslateApp"
        private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    // PAT가 비어 있으면(공개 CI 빌드) null을 반환해 Authorization 헤더 자체를 생략한다.
    // 빈 문자열로 "Bearer "를 그대로 보내면 GitHub API가 이를 유효하지 않은 인증
    // 시도로 보고 401을 던지므로, 헤더를 아예 안 붙이는 것과 다르게 동작한다.
    private fun authHeaderOrNull(): String? =
        BuildConfig.GITHUB_UPDATE_PAT.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    @Throws(IOException::class)
    fun fetchLatestRelease(): UpdateInfo {
        val requestBuilder = Request.Builder()
            .url(API_URL)
            .header("Accept", "application/vnd.github+json")
        authHeaderOrNull()?.let { requestBuilder.header("Authorization", it) }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: 릴리스 정보를 가져오지 못했습니다.")
            }
            val body = response.body?.string() ?: throw IOException("빈 응답")
            return parseRelease(body)
        }
    }

    private fun parseRelease(body: String): UpdateInfo {
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        val versionCode = tagName.filter { it.isDigit() }.toIntOrNull()
            ?: throw IOException("태그 이름에서 버전 코드를 읽지 못했습니다: $tagName")

        val assets = json.getJSONArray("assets")
        // private repo의 asset은 browser_download_url이 아니라 API asset URL(url 필드)을
        // Accept: application/octet-stream + 인증 헤더로 호출해야 다운로드된다.
        var apkApiUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                apkApiUrl = asset.getString("url")
                break
            }
        }
        val downloadUrl = apkApiUrl ?: throw IOException("릴리스에 APK 파일이 없습니다.")

        return UpdateInfo(
            versionName = tagName,
            versionCode = versionCode,
            releaseNotes = json.optString("body", ""),
            apkDownloadUrl = downloadUrl
        )
    }

    /** 현재 설치된 앱보다 새 버전인지 확인 */
    fun isNewerThanCurrent(update: UpdateInfo): Boolean {
        val currentVersionCode = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .let { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
                else @Suppress("DEPRECATION") info.versionCode
            }
        return update.versionCode > currentVersionCode
    }

    /**
     * APK를 DownloadManager로 다운로드하고, 완료되면 설치 화면을 띄운다.
     * 다운로드 완료는 BroadcastReceiver로 감지한다 (호출자가 별도 폴링할 필요 없음).
     */
    fun downloadAndInstall(update: UpdateInfo, onComplete: () -> Unit = {}) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "TranslateApp-${update.versionName}.apk"
        val destFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(update.apkDownloadUrl))
            .setTitle("TranslateApp 업데이트")
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("Accept", "application/octet-stream")
        authHeaderOrNull()?.let { request.addRequestHeader("Authorization", it) }

        val downloadId = downloadManager.enqueue(request)

        // 이전에 등록해둔 리시버가 아직 남아있으면(예: 직전 다운로드가 완료 전에 취소된 경우)
        // 먼저 정리하고 새로 등록한다 — 리시버가 중복 등록되는 것을 방지.
        unregisterDownloadReceiver()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                unregisterDownloadReceiver()

                // ACTION_DOWNLOAD_COMPLETE는 다운로드가 실패해도(네트워크 끊김, 인증 만료,
                // 저장공간 부족 등) 브로드캐스트된다. 상태를 확인하지 않고 바로 설치 화면을
                // 띄우면 불완전하거나 없는 파일로 PackageInstaller를 여는 셈이 되므로,
                // 실제로 성공했는지 DownloadManager.Query로 확인한 뒤에만 설치를 제안한다.
                if (isDownloadSuccessful(downloadId)) {
                    promptInstall(destFile)
                    onComplete()
                } else {
                    Logger.e("업데이트 다운로드 실패 (downloadId=$downloadId)")
                    Toast.makeText(context, context.getString(R.string.update_download_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
        downloadReceiver = receiver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    /** DownloadManager.Query로 실제 다운로드 결과 상태를 확인한다. */
    private fun isDownloadSuccessful(downloadId: Long): Boolean {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return false
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return false
            return cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        }
    }

    /**
     * 등록된 다운로드 완료 리시버가 있으면 해제한다. 다운로드가 끝났을 때 스스로도 호출하지만,
     * Activity가 다운로드 완료 전에 소멸되는 경우(onDestroy)에도 호출해 리시버 leak을 막아야 한다.
     */
    fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // 이미 해제된 리시버 — 무시해도 안전하다.
            }
        }
        downloadReceiver = null
    }

    private fun promptInstall(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
}
