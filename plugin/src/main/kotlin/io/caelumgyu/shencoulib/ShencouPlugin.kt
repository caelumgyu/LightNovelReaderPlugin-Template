package io.caelumgyu.shencoulib

import android.util.Log
import io.nightfish.lightnovelreader.api.image.ImageTransPostProcessingManagerApi
import io.nightfish.lightnovelreader.api.plugin.LightNovelReaderPlugin
import io.nightfish.lightnovelreader.api.plugin.Plugin
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

@Suppress("unused")
@Plugin(
    version = 1,
    name = "ShencouLib",
    versionName = "0.1",
    author = "caelumgyu",
    description = "神凑轻小说文库数据源（m.wowenku.com）",
    updateUrl = "",
    apiVersion = 4
)
class ShencouPlugin(
    val userDataRepositoryApi: UserDataRepositoryApi,
    val imageTransPostProcessingManagerApi: ImageTransPostProcessingManagerApi
) : LightNovelReaderPlugin {
    override fun onLoad() {
        Log.i("ShencouPlugin", "神凑轻小说数据源插件已加载")
    }
}
