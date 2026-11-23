package com.example.fcmheartbeat

import com.example.fcmheartbeat.core.BindingState
import com.example.fcmheartbeat.core.BindingSummary
import com.example.fcmheartbeat.core.GmsService
import com.example.fcmheartbeat.core.HeartbeatState
import com.example.fcmheartbeat.core.Helper
import com.example.fcmheartbeat.core.HelperSummary
import com.example.fcmheartbeat.core.Phase
import com.example.fcmheartbeat.core.Status
import com.example.fcmheartbeat.ui.formatRunningTime
import com.example.fcmheartbeat.ui.formatShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatStateTest {
    private fun running(
        ads: BindingState,
        fcm: BindingState,
        settled: Boolean = true,
        gms: Boolean = true,
    ) = HeartbeatState(
        phase = Phase.Running,
        gmsAvailable = gms,
        bindings = mapOf(GmsService.AdsId to ads, GmsService.Fcm to fcm),
        bindingsSettled = settled,
    )

    private val err = BindingState.Error("boom")

    @Test
    fun idleShowsStarting() = assertEquals(Status.Starting, HeartbeatState().status)

    @Test
    fun failedPhaseShowsFailedToStart() =
        assertEquals(Status.FailedToStart, HeartbeatState(phase = Phase.Failed, startError = "x").status)

    @Test
    fun allConnectingBeforeSettledShowsStarting() = assertEquals(
        Status.Starting,
        running(BindingState.Connecting, BindingState.Connecting, settled = false).status,
    )

    @Test
    fun reconnectingAfterSettledStaysActive() = assertEquals(
        Status.Active,
        running(BindingState.Connecting, BindingState.Connecting, settled = true).status,
    )

    @Test
    fun partialFailureIsActiveWithSomeFailedSummary() {
        val state = running(BindingState.Connected, err)
        assertEquals(Status.Active, state.status)
        assertTrue(state.bindingSummary is BindingSummary.SomeFailed)
    }

    @Test
    fun totalFailureIsErrorsDetected() {
        val state = running(err, err)
        assertEquals(Status.ErrorsDetected, state.status)
        assertTrue(state.bindingSummary is BindingSummary.AllFailed)
    }

    @Test
    fun gmsUnavailableIsErrorsDetected() = assertEquals(
        Status.ErrorsDetected,
        HeartbeatState(phase = Phase.Running, gmsAvailable = false).status,
    )

    @Test
    fun allConnectedSummaryIsAllOk() =
        assertEquals(BindingSummary.AllOk, running(BindingState.Connected, BindingState.Connected).bindingSummary)

    @Test
    fun freezeThresholdIsExclusive() {
        assertFalse(HeartbeatState(phase = Phase.Running, frozenMillis = 30_000).freezeDetected)
        assertTrue(HeartbeatState(phase = Phase.Running, frozenMillis = 30_001).freezeDetected)
        assertFalse(HeartbeatState(phase = Phase.Failed, frozenMillis = 60_000).freezeDetected)
    }

    @Test
    fun formatsRunningTime() {
        assertEquals("00:00:00", formatRunningTime(999))
        assertEquals("02:03:47", formatRunningTime(7_427_000))
        assertEquals("26:00:01", formatRunningTime(93_601_000))
    }

    @Test
    fun formatsShortDuration() {
        assertEquals("45s", formatShortDuration(45_000))
        assertEquals("2m 5s", formatShortDuration(125_000))
        assertEquals("1h 1m", formatShortDuration(3_660_000))
    }

    @Test
    fun helperFailureAloneIsErrorsDetected() {
        val state = running(BindingState.Connected, BindingState.Connected)
            .copy(helpers = mapOf(Helper.VirtualDisplay to BindingState.Error("boom")))
        assertEquals(Status.ErrorsDetected, state.status)
        assertTrue(state.helperSummary is HelperSummary.Failed)
    }

    @Test
    fun activeHelperKeepsActiveStatus() {
        val state = running(BindingState.Connected, BindingState.Connected)
            .copy(helpers = mapOf(Helper.VirtualDisplay to BindingState.Connected))
        assertEquals(Status.Active, state.status)
        assertEquals(HelperSummary.AllOk, state.helperSummary)
    }

    @Test
    fun startingHelperIsPending() = assertEquals(
        HelperSummary.Pending,
        running(BindingState.Connected, BindingState.Connected)
            .copy(helpers = mapOf(Helper.VirtualDisplay to BindingState.Connecting)).helperSummary,
    )
}
