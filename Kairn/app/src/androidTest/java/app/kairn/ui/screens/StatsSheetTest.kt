package app.kairn.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kairn.data.ReadingStats
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reading record's three numbers. They are three different sums of the same map, so the test states a
 * map whose today, week and streak are all DIFFERENT numbers - a swapped pair cannot pass by accident.
 */
@RunWith(AndroidJUnit4::class)
class StatsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun stats(): ReadingStats {
        val today = LocalDate.now()
        return ReadingStats().add(today.toString(), 25)
            .add(today.minusDays(1).toString(), 40)
            .add(today.minusDays(2).toString(), 10)
            .add(today.minusDays(9).toString(), 90)     // outside the week window, inside the all-time total
    }

    @Test
    fun today_this_week_and_the_streak_are_three_different_numbers() {
        compose.setContent { StatsSheet(stats(), finishedBooks = 2, onClose = {}) }

        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("This week").assertExists()
        compose.onNodeWithText("Streak").assertExists()

        compose.onNodeWithText("25 min").assertExists()          // today only
        compose.onNodeWithText("1 h 15 min").assertExists()      // 25 + 40 + 10, the last nine days excluded
        compose.onNodeWithText("3 d").assertExists()             // three consecutive days ending today
    }

    @Test
    fun nothing_read_yet_is_zero_and_not_a_blank_sheet() {
        compose.setContent { StatsSheet(ReadingStats(), finishedBooks = 0, onClose = {}) }

        // Today and this week are both zero, so the box appears twice - a count, not a presence check.
        compose.onAllNodesWithText("0 min").assertCountEquals(2)
        compose.onAllNodesWithText("0 d").assertCountEquals(1)
        compose.onNodeWithText("All time: 0 h 0 min across 0 finished books").assertExists()
    }
}
