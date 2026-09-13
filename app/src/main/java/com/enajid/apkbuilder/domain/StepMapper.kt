package com.enajid.apkbuilder.domain

import com.enajid.apkbuilder.data.StepInfo
import com.enajid.apkbuilder.util.TimeUtils

enum class StepStatus { QUEUED, RUNNING, SUCCESS, FAILURE, SKIPPED, CANCELLED, NEUTRAL }

data class MappedStep(
    val label: String,
    val realName: String,
    val status: StepStatus,
    val major: Boolean,
    val durationMs: Long? = null,
)

/**
 * Maps real GitHub Actions step names to short, human-friendly labels.
 * The real step name is always shown as a subtitle — nothing is faked.
 */
object StepMapper {

    /** Returns (friendly label, isMajor) or null when the step should be hidden. */
    fun friendlyLabel(stepName: String): Pair<String, Boolean>? {
        val name = stepName.trim()
        return when {
            name.startsWith("Post ") || name == "Complete job" -> null
            name == "Set up job" -> "Preparing the build environment" to true
            name.startsWith("actions/checkout") -> "Downloading your code" to true
            name.startsWith("Set up JDK") -> "Setting up the environment" to true
            name.startsWith("Grant execute") -> "Preparing build tools" to false
            name.startsWith("Build APK") -> "Compiling your app" to true
            name.startsWith("Upload APK") -> "Packaging your APK" to true
            else -> name to false
        }
    }

    fun map(steps: List<StepInfo>): List<MappedStep> =
        steps.mapNotNull { step ->
            val (label, major) = friendlyLabel(step.name) ?: return@mapNotNull null
            MappedStep(
                label = label,
                realName = step.name,
                status = statusOf(step.status, step.conclusion),
                major = major,
                durationMs = TimeUtils.durationBetween(step.started_at, step.completed_at),
            )
        }

    fun statusOf(status: String?, conclusion: String?): StepStatus = when (status) {
        "queued" -> StepStatus.QUEUED
        "in_progress" -> StepStatus.RUNNING
        "completed" -> when (conclusion) {
            "success" -> StepStatus.SUCCESS
            "failure" -> StepStatus.FAILURE
            "skipped" -> StepStatus.SKIPPED
            "cancelled" -> StepStatus.CANCELLED
            else -> StepStatus.NEUTRAL
        }
        else -> StepStatus.NEUTRAL
    }
}
