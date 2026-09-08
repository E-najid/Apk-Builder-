package com.enajid.apkbuilder.domain

import com.enajid.apkbuilder.data.StepInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class StepMapperTest {

    private fun step(name: String, status: String, conclusion: String? = null) =
        StepInfo(name = name, status = status, conclusion = conclusion)

    @Test
    fun `maps real actions steps to friendly labels`() {
        val mapped = StepMapper.map(
            listOf(
                step("Set up job", "completed", "success"),
                step("actions/checkout@v4", "completed", "success"),
                step("Set up JDK 17", "in_progress"),
                step("Grant execute permission for gradlew", "queued"),
                step("Build APK", "queued"),
                step("Upload APK", "queued"),
                step("Post actions/checkout@v4", "queued"),
                step("Complete job", "queued"),
            )
        )
        assertEquals(
            listOf(
                "Preparing the build environment",
                "Downloading your code",
                "Setting up the environment",
                "Preparing build tools",
                "Compiling your app",
                "Packaging your APK",
            ),
            mapped.map { it.label },
        )
        assertEquals(StepStatus.SUCCESS, mapped[0].status)
        assertEquals(StepStatus.SUCCESS, mapped[1].status)
        assertEquals(StepStatus.RUNNING, mapped[2].status)
        assertEquals(StepStatus.QUEUED, mapped[4].status)
        assertFalseMajor(mapped[3])
    }

    private fun assertFalseMajor(step: MappedStep) {
        assertEquals(false, step.major)
    }

    @Test
    fun `unknown steps are shown with their real name`() {
        val mapped = StepMapper.map(listOf(step("Run custom script", "completed", "failure")))
        assertEquals("Run custom script", mapped[0].label)
        assertEquals(StepStatus.FAILURE, mapped[0].status)
    }

    @Test
    fun `skipped and cancelled conclusions map`() {
        assertEquals(StepStatus.SKIPPED, StepMapper.statusOf("completed", "skipped"))
        assertEquals(StepStatus.CANCELLED, StepMapper.statusOf("completed", "cancelled"))
        assertEquals(StepStatus.NEUTRAL, StepMapper.statusOf("completed", null))
        assertEquals(StepStatus.NEUTRAL, StepMapper.statusOf(null, null))
    }

    @Test
    fun `durations are computed from timestamps`() {
        val step = step(
            "Build APK",
            "completed",
            "success",
        ).copy(
            started_at = "2024-05-01T10:00:00Z",
            completed_at = "2024-05-01T10:00:42Z",
        )
        val mapped = StepMapper.map(listOf(step))
        assertEquals(42_000L, mapped[0].durationMs)
    }
}
