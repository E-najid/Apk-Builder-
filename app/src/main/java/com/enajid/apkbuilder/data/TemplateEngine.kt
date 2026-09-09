package com.enajid.apkbuilder.data

import android.content.Context
import com.enajid.apkbuilder.domain.ProjectSpec
import java.io.IOException

/** Thrown when one of the bundled template files is missing from the APK. */
class TemplateAssetMissingException(path: String) :
    Exception("This build of APK Builder is missing a template file: $path")

/** Android side of the template engine: reads template files from assets. */
class TemplateEngine(private val context: Context) {

    /** Reads template files from assets. */
    fun workflowFile(): TemplateRenderer.RenderedFile {
        val bytes = try {
            context.assets.open("${TemplateRenderer.TEMPLATE_ROOT}/dot-github/workflows/build.yml")
                .use { it.readBytes() }
        } catch (e: IOException) {
            throw TemplateAssetMissingException("$TEMPLATE_ROOT/dot-github/workflows/build.yml")
        }
        return TemplateRenderer.RenderedFile(
            path = ".github/workflows/build.yml",
            content = bytes,
        )
    }

    fun render(spec: ProjectSpec): TemplateRenderer.RenderResult =
        TemplateRenderer.render(spec) { assetPath ->
            try {
                context.assets.open(assetPath).use { it.readBytes() }
            } catch (e: IOException) {
                // Surface this as a build problem, not a network problem —
                // a missing bundled asset has nothing to do with connectivity.
                throw TemplateAssetMissingException(assetPath)
            }
        }
}
