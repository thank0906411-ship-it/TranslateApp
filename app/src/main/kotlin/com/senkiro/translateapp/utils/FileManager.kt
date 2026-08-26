package com.senkiro.translateapp.utils

import android.content.Context
import java.io.File

/**
 * 추후 NLLB 등 커스텀 모델 파일을 내부 저장소에 보관할 때 사용할 유틸리티.
 * 지금은 ML Kit이 자체적으로 모델을 관리하므로 당장은 쓰이지 않지만,
 * 엔진 교체 시 이 자리에 다운로드/로딩 로직을 추가하면 된다.
 */
object FileManager {

    fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
