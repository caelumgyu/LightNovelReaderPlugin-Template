package io.caelumgyu.shencoulib

import android.util.Log
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.image.ImageTransPostProcessingManagerApi

@Suppress("unused")
@Plugin(
    version = BuildConfig.VERSION_CODE,
    name = "ShencouLib",
    versionName = BuildConfig.VERSION_NAME,
    author = "caelumgyu",
    description = "神凑轻小说文库数据源（m.wowenku.com）",
    updateUrl = "",
    apiVersion = 4
)
class ShencouPlugin(
    val userDataRepositoryApi: UserDataRepositoryApi,
    val imageTransPostProcessingManagerApi: ImageTransPostProcessingManagerApi
) : LightNovelReaderPlugin {
    companion object {
        const val TAG = "ShencouPlugin"
    }

    override fun onLoad() {
        Log.i(TAG, "神凑轻小说数据源插件已加载")
    }
}
