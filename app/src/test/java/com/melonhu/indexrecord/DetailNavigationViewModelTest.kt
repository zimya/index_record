package com.melonhu.indexrecord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.melonhu.indexrecord.data.model.UniversalRecord
import com.melonhu.indexrecord.viewmodel.DetailNavigationViewModel
import org.junit.Assert.*
import org.junit.Test

class DetailNavigationViewModelTest {
    class ReadingState : ViewModel() {
        var sort = "latest"
        var filter = "review"
        var expanded = true
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadingState() as T
    }
    private fun record(id: String, platform: String = "zhihu") =
        UniversalRecord(platform = platform, recordType = "answer", platformId = id)

    @Test fun returningPreservesEntryAndItsReadingViewModel() {
        val navigation = DetailNavigationViewModel()
        navigation.push(record("a"))
        val first = navigation.entries.single()
        val state = ViewModelProvider(first, factory)[ReadingState::class.java]
        navigation.attach(first)
        navigation.push(record("b"))
        navigation.detach(first)
        assertFalse(state.cleared)
        assertTrue(navigation.pop())
        assertSame(first, navigation.entries.single())
        assertSame(state, ViewModelProvider(first, factory)[ReadingState::class.java])
        assertEquals("latest", state.sort)
        assertEquals("review", state.filter)
        assertTrue(state.expanded)
    }

    @Test fun repeatedVisitsAndPlatformsHaveIndependentEntries() {
        val navigation = DetailNavigationViewModel()
        navigation.push(record("a"))
        navigation.push(record("a"))
        assertEquals(1, navigation.entries.size)
        navigation.push(record("a", "reddit"))
        navigation.push(record("a"))
        assertEquals(3, navigation.entries.size)
        assertEquals(3, navigation.entries.map { it.key }.distinct().size)
        assertNotSame(navigation.entries.first().viewModelStore, navigation.entries.last().viewModelStore)
    }

    @Test fun poppingReleasesStateAfterExitAnimationFinishes() {
        val navigation = DetailNavigationViewModel()
        navigation.push(record("a"))
        val entry = navigation.entries.single()
        val state = ViewModelProvider(entry, factory)[ReadingState::class.java]
        navigation.attach(entry)
        assertTrue(navigation.pop())
        assertFalse(state.cleared)
        navigation.detach(entry)
        assertTrue(state.cleared)
        assertFalse(navigation.pop())
    }

    @Test fun clearingNavigationReleasesCoveredEntries() {
        val ownerStore = ViewModelStore()
        val navigationFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DetailNavigationViewModel() as T
        }
        val navigation = ViewModelProvider(ownerStore, navigationFactory)[DetailNavigationViewModel::class.java]
        navigation.push(record("a"))
        val state = ViewModelProvider(navigation.entries.single(), factory)[ReadingState::class.java]
        navigation.push(record("b"))
        ownerStore.clear()
        assertTrue(state.cleared)
    }

    @Test fun inStackNavigationPreservesEntryIdentityAndKeyOnPop() {
        val navigation = DetailNavigationViewModel()
        navigation.push(record("parent"))
        val parentEntry = navigation.entries.single()
        val parentKey = parentEntry.key
        navigation.attach(parentEntry)

        navigation.push(record("child"))
        assertEquals(2, navigation.entries.size)
        val childEntry = navigation.entries.last()

        assertTrue(navigation.pop())
        assertEquals(1, navigation.entries.size)
        val current = navigation.entries.single()
        assertSame(parentEntry, current)
        assertEquals(parentKey, current.key)
    }
}
