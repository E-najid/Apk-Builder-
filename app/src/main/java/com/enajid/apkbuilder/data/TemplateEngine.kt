package com.enajid.apkbuilder.data

import android.content.Context
import com.enajid.apkbuilder.domain.ProjectSpec

/** Android side of the template engine: reads template files from assets. */
class TemplateEngine(private val context: Context) {

    fun render(spec: ProjectSpec): TemplateRenderer.RenderResult =
        TemplateRenderer.render(spec) { assetPath ->
            context.assets.open(assetPath).use { it.readBytes() }
        }
}
