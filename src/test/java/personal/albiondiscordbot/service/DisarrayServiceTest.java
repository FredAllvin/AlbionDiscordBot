package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DisarrayServiceTest {

    private final DisarrayService service = new DisarrayService();

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 15, 19, 20})
    @DisplayName("no Disarray below 21 players")
    void noDisarrayBelowThreshold(int players) {
        assertThat(service.levelFor(players)).isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "21, 1", "22, 2", "25, 5", "30, 10", "34, 14",
        // The table skips 35: level 15 needs 36 players, so 35 is still level 14.
        // This is why the lookup must scan the table rather than compute a level.
        "35, 14",
        "36, 15", "37, 16", "38, 16", "39, 17", "40, 17",
        "44, 19", "47, 20", "70, 30", "99, 36", "192, 50", "445, 67"
    })
    @DisplayName("group size maps to the published Disarray level")
    void levelForKnownSizes(int players, int expectedLevel) {
        assertThat(service.levelFor(players)).isEqualTo(expectedLevel);
    }

    @Test
    @DisplayName("group sizes past the published ceiling clamp to the max level")
    void clampsAboveCeiling() {
        assertThat(service.levelFor(445)).isEqualTo(DisarrayService.MAX_LEVEL);
        assertThat(service.levelFor(1000)).isEqualTo(DisarrayService.MAX_LEVEL);
    }

    @Test
    @DisplayName("levels are monotonic across the whole table")
    void levelsNeverDecrease() {
        int previous = 0;
        for (int players = 0; players <= 500; players++) {
            int level = service.levelFor(players);
            assertThat(level).isGreaterThanOrEqualTo(previous);
            previous = level;
        }
    }

    @Test
    @DisplayName("every level's published minimum resolves back to that level")
    void thresholdsAreSelfConsistent() {
        for (int level = 1; level <= DisarrayService.MAX_LEVEL; level++) {
            int min = service.minGroupSizeForLevel(level);
            assertThat(service.levelFor(min)).as("level %d min size %d", level, min).isEqualTo(level);
            assertThat(service.levelFor(min - 1)).as("one below level %d", level).isLessThan(level);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "1, 21", "20, 46-47", "43, 141-147", "44, 148-153",
        "45, 154-159", "46, 160-166", "66, 412-444", "67, 445+"
    })
    @DisplayName("a Disarray level converts back to a player count range")
    void playerRangeForLevel(int level, String expected) {
        assertThat(service.playerRangeForLevel(level).display()).isEqualTo(expected);
    }

    @Test
    @DisplayName("level 0 covers everything below the Disarray threshold")
    void levelZeroRange() {
        DisarrayService.PlayerRange range = service.playerRangeForLevel(0);

        assertThat(range.min()).isZero();
        assertThat(range.max()).isEqualTo(20);
        assertThat(range.openEnded()).isFalse();
    }

    @Test
    @DisplayName("the top level is open ended")
    void topLevelIsOpenEnded() {
        DisarrayService.PlayerRange range = service.playerRangeForLevel(DisarrayService.MAX_LEVEL);

        assertThat(range.openEnded()).isTrue();
        assertThat(range.min()).isEqualTo(445);
        assertThat(range.display()).isEqualTo("445+");
    }

    @Test
    @DisplayName("ranges tile the table exactly — every player count maps back to its own level")
    void rangesAreConsistentWithForwardLookup() {
        // The two directions must agree, otherwise /disarray would contradict itself.
        for (int level = 0; level < DisarrayService.MAX_LEVEL; level++) {
            DisarrayService.PlayerRange range = service.playerRangeForLevel(level);

            assertThat(service.levelFor(range.min()))
                    .as("bottom of level %d range (%d players)", level, range.min())
                    .isEqualTo(level);
            assertThat(service.levelFor(range.max()))
                    .as("top of level %d range (%d players)", level, range.max())
                    .isEqualTo(level);
            // no gap between this range and the next
            assertThat(service.playerRangeForLevel(level + 1).min())
                    .as("level %d must start where level %d ends", level + 1, level)
                    .isEqualTo(range.max() + 1);
        }
    }

    @Test
    @DisplayName("the bigger group is the one penalised")
    void matchupPenalisesTheLargerForce() {
        // 47 players (level 20) against 20 players (level 0)
        DisarrayService.Matchup m = service.matchup(47, 20);

        assertThat(m.ourLevel()).isEqualTo(20);
        assertThat(m.enemyLevel()).isZero();
        assertThat(m.ourDebuffPercent()).isEqualTo(20);
        assertThat(m.enemyDebuffPercent()).isZero();
    }

    @Test
    @DisplayName("no penalty against an equal or larger force")
    void noPenaltyAgainstEqualOrLarger() {
        assertThat(service.matchup(50, 50).ourDebuffPercent()).isZero();
        assertThat(service.matchup(50, 200).ourDebuffPercent()).isZero();

        // and the larger side takes the difference
        DisarrayService.Matchup m = service.matchup(50, 200);
        assertThat(m.enemyDebuffPercent()).isEqualTo(m.enemyLevel() - m.ourLevel());
    }

    @Test
    @DisplayName("battle mounts only count above the 15-point allowance")
    void battleMountAllowance() {
        assertThat(service.effectiveGroupSize(100, 0)).isEqualTo(100);
        assertThat(service.effectiveGroupSize(100, 15)).isEqualTo(100);
        assertThat(service.effectiveGroupSize(100, 20)).isEqualTo(105);
    }

    @Test
    @DisplayName("players needed for the next level")
    void playersUntilNextLevel() {
        assertThat(service.playersUntilNextLevel(20)).isEqualTo(1);
        // 35 players is level 14; level 15 needs 36
        assertThat(service.playersUntilNextLevel(35)).isEqualTo(1);
        assertThat(service.playersUntilNextLevel(34)).isEqualTo(2);
        assertThat(service.playersUntilNextLevel(445)).isNull();
    }

    @Test
    @DisplayName("all fourteen battle mounts are known")
    void battleMountTable() {
        assertThat(DisarrayService.battleMountDisarrayPoints())
                .hasSize(14)
                .containsEntry("Colossus Beetle", 5)
                .containsEntry("Ancient Ent", 2)
                .containsEntry("Goliath Horseeater", 3)
                .containsEntry("Battle Eagle", 4);
    }
}
