@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.hapi.companion.feature.jarvis.drive

import app.hapi.companion.feature.files.FakeFilesGateway
import app.hapi.protocol.wire.DirectoryEntry
import app.hapi.protocol.wire.FileSearchItem
import app.hapi.protocol.wire.FileSearchResponse
import app.hapi.protocol.wire.ListDirectoryResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class DriveViewModelTest {

    private fun dir(name: String) = DirectoryEntry(name = name, type = "directory")
    private fun file(name: String, size: Long = 10) = DirectoryEntry(name = name, type = "file", size = size)

    private fun TestScope.build(gateway: FakeFilesGateway) =
        DriveViewModel("s1", gateway, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), searchDebounceMs = 100)

    @Test
    fun `crumbs and parent`() {
        assertEquals(emptyList(), driveCrumbs(""))
        assertEquals(listOf("jarvis" to "jarvis", "overhaul" to "jarvis/overhaul"), driveCrumbs("jarvis/overhaul"))
        assertEquals("jarvis", driveParent("jarvis/overhaul"))
        assertEquals("", driveParent("jarvis"))
        assertEquals("", driveParent(""))
    }

    @Test
    fun `folders first by name, dot entries hidden`() {
        val sorted = driveSort(
            listOf(
                DriveEntry("b.md", "b.md", isDir = false),
                DriveEntry(".git", ".git", isDir = true),
                DriveEntry("Raw", "Raw", isDir = true),
                DriveEntry("a.md", "a.md", isDir = false),
                DriveEntry("jarvis", "jarvis", isDir = true),
            ),
        )
        assertEquals(listOf("jarvis", "Raw", "a.md", "b.md"), sorted.map { it.name })
    }

    @Test
    fun `opens the root then steps into a folder and back up`() = runTest {
        val gateway = FakeFilesGateway().apply {
            directories[null] = ListDirectoryResponse(success = true, entries = listOf(file("CLAUDE.md"), dir("jarvis")))
            directories["jarvis"] = ListDirectoryResponse(success = true, entries = listOf(file("rules.md")))
        }
        val vm = build(gateway)
        vm.start()
        advanceUntilIdle()
        assertEquals(listOf("jarvis", "CLAUDE.md"), vm.state.value.entries.map { it.name })
        assertEquals("jarvis", vm.state.value.entries.first().path)

        vm.open("jarvis")
        advanceUntilIdle()
        assertEquals("jarvis", vm.state.value.path)
        assertEquals(listOf("jarvis/rules.md"), vm.state.value.entries.map { it.path })

        assertTrue(vm.back())
        advanceUntilIdle()
        assertEquals("", vm.state.value.path)
        assertFalse(vm.back(), "at the root with no search, back leaves the screen")
    }

    @Test
    fun `search replaces the folder view and back clears it first`() = runTest {
        val gateway = FakeFilesGateway().apply {
            directories[null] = ListDirectoryResponse(success = true, entries = listOf(dir("jarvis")))
            directories["jarvis"] = ListDirectoryResponse(success = true, entries = emptyList())
            searchResult = FileSearchResponse(
                success = true,
                files = listOf(FileSearchItem(fileName = "rules.md", filePath = "jarvis", fullPath = "jarvis/rules.md", fileType = "file")),
            )
        }
        val vm = build(gateway)
        vm.start()
        advanceUntilIdle()
        vm.open("jarvis")
        advanceUntilIdle()

        vm.setQuery("rules")
        advanceTimeBy(150)
        advanceUntilIdle()
        assertEquals(listOf("jarvis/rules.md"), vm.state.value.results.map { it.fullPath })
        assertTrue(vm.state.value.searched)

        assertTrue(vm.back())
        advanceUntilIdle()
        assertEquals("", vm.state.value.query)
        assertEquals("jarvis", vm.state.value.path, "clearing the search keeps the folder")
    }

    @Test
    fun `a failed listing shows an error, not an empty folder`() = runTest {
        val gateway = FakeFilesGateway().apply {
            directories[null] = ListDirectoryResponse(success = false, error = "denied")
        }
        val vm = build(gateway)
        vm.start()
        advanceUntilIdle()
        assertEquals("denied", vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }
}
