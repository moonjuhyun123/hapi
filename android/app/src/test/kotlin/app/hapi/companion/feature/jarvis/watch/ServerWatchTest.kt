package app.hapi.companion.feature.jarvis.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerWatchTest {
    private val fine = ServerStatus(ok = true)

    @Test fun oneMissIsQuiet() {
        val s = watchStep(WatchState(), null)
        assertNull(s.alert)
        assertEquals(1, s.next.fails)
    }

    @Test fun twoMissesAlertOnce() {
        val second = watchStep(WatchState(fails = 1), null)
        assertEquals(Alert.Down, second.alert)
        val third = watchStep(second.next, null)
        assertNull(third.alert)
    }

    @Test fun problemsAlertOncePerSet() {
        val first = watchStep(WatchState(), ServerStatus(problems = listOf("허브")))
        assertEquals(Alert.Problems(listOf("허브")), first.alert)
        assertNull(watchStep(first.next, ServerStatus(problems = listOf("허브"))).alert)
        assertEquals(Alert.Problems(listOf("한도")), watchStep(first.next, ServerStatus(problems = listOf("한도"))).alert)
    }

    @Test fun backAfterAlertThenQuiet() {
        val back = watchStep(WatchState(fails = 3, alerted = DOWN_KEY), fine)
        assertEquals(Alert.Back, back.alert)
        assertEquals(WatchState(), back.next)
        assertNull(watchStep(back.next, fine).alert)
    }
}
