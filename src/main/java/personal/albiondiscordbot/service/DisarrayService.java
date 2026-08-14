package personal.albiondiscordbot.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Converts a group size into the in-game Disarray (zerg debuff) level.
 *
 * <p>Disarray reduces <em>Bonus Damage vs Players</em> and <em>CC Duration vs
 * Players</em>. The effect is relative: {@code debuff% = max(0, yourLevel -
 * targetLevel)}, so there is no penalty against an equal or larger force. Below 21
 * players there is no Disarray at all.
 *
 * <p>Thresholds are from the Albion Online wiki (Version 22.090.1). The table is not
 * arithmetic — it skips sizes (there is no level whose minimum is 35, so 35 players is
 * still level 14) — so the lookup must find the highest level whose minimum is at most
 * the group size, never compute it.
 */
@Service
public class DisarrayService {

    /** Level to the smallest group size that reaches it. */
    private static final int[] MIN_GROUP_SIZE_FOR_LEVEL = {
        0, // level 0 placeholder; no Disarray below 21 players
        21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32, 33, 34, 36, 37, 39, 41, 44, 46,
        48, 49, 51, 54, 56, 58, 61, 64, 67, 70,
        74, 79, 83, 89, 95, 99, 103, 108, 114, 119,
        126, 133, 141, 148, 154, 160, 167, 175, 183, 192,
        200, 207, 215, 223, 232, 242, 252, 264, 276, 290,
        305, 322, 341, 361, 385, 412, 445
    };

    public static final int MAX_LEVEL = MIN_GROUP_SIZE_FOR_LEVEL.length - 1;

    /** Percentage points Homesick adds on top of the normal Disarray effect. */
    public static final int HOMESICK_BONUS = 20;

    /** Battle-mount Disarray points only start counting above this total. */
    public static final int BATTLE_MOUNT_FREE_ALLOWANCE = 15;

    /** Disarray points each battle mount contributes to its group. */
    private static final Map<String, Integer> BATTLE_MOUNT_POINTS = battleMountPoints();

    private static Map<String, Integer> battleMountPoints() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Ancient Ent", 2);
        m.put("Battle Eagle", 4);
        m.put("Battle Rhino", 2);
        m.put("Behemoth", 4);
        m.put("Colossus Beetle", 5);
        m.put("Command Mammoth", 5);
        m.put("Flame Basilisk", 5);
        m.put("Goliath Horseeater", 3);
        m.put("Juggernaut", 4);
        m.put("Phalanx Beetle", 2);
        m.put("Roving Bastion", 5);
        m.put("Siege Ballista", 2);
        m.put("Tower Chariot", 5);
        m.put("Venom Basilisk", 5);
        return Map.copyOf(m);
    }

    public static Map<String, Integer> battleMountDisarrayPoints() {
        return BATTLE_MOUNT_POINTS;
    }

    /**
     * The Disarray level for a group of {@code players}, or 0 below 21 players.
     * Group sizes beyond the largest published threshold clamp to {@link #MAX_LEVEL}.
     */
    public int levelFor(int players) {
        if (players < MIN_GROUP_SIZE_FOR_LEVEL[1]) {
            return 0;
        }
        int level = 0;
        for (int candidate = 1; candidate <= MAX_LEVEL; candidate++) {
            if (players >= MIN_GROUP_SIZE_FOR_LEVEL[candidate]) {
                level = candidate;
            } else {
                break;
            }
        }
        return level;
    }

    /** The smallest group size that reaches {@code level}, or 0 for level 0. */
    public int minGroupSizeForLevel(int level) {
        if (level <= 0) {
            return 0;
        }
        return MIN_GROUP_SIZE_FOR_LEVEL[Math.min(level, MAX_LEVEL)];
    }

    /**
     * How many players a given Disarray level means — the reverse lookup, and the one
     * that is actually useful in a fight: you can see the enemy's Disarray level, and
     * want their headcount.
     *
     * <p>A level covers a range rather than an exact number, because the published table
     * only lists where each level starts. The top level is open-ended.
     */
    public PlayerRange playerRangeForLevel(int level) {
        if (level <= 0) {
            return new PlayerRange(0, MIN_GROUP_SIZE_FOR_LEVEL[1] - 1, false);
        }
        if (level >= MAX_LEVEL) {
            return new PlayerRange(MIN_GROUP_SIZE_FOR_LEVEL[MAX_LEVEL], Integer.MAX_VALUE, true);
        }
        return new PlayerRange(
                MIN_GROUP_SIZE_FOR_LEVEL[level], MIN_GROUP_SIZE_FOR_LEVEL[level + 1] - 1, false);
    }

    /**
     * @param min fewest players that produce the level
     * @param max most players that still produce it
     * @param openEnded true when there is no upper bound (the highest published level)
     */
    public record PlayerRange(int min, int max, boolean openEnded) {

        /** Renders as {@code "154-159"}, {@code "21"} or {@code "445+"}. */
        public String display() {
            if (openEnded) {
                return min + "+";
            }
            return min == max ? Integer.toString(min) : min + "-" + max;
        }

        public int spread() {
            return openEnded ? Integer.MAX_VALUE : max - min + 1;
        }
    }

    /**
     * How many more players are needed to reach the next level, or empty if already at
     * the published ceiling.
     */
    public Integer playersUntilNextLevel(int players) {
        int next = levelFor(players) + 1;
        if (next > MAX_LEVEL) {
            return null;
        }
        return MIN_GROUP_SIZE_FOR_LEVEL[next] - players;
    }

    /**
     * Effective group size once battle mounts are counted. Only the excess above
     * {@link #BATTLE_MOUNT_FREE_ALLOWANCE} contributes.
     */
    public int effectiveGroupSize(int players, int battleMountPoints) {
        return players + Math.max(0, battleMountPoints - BATTLE_MOUNT_FREE_ALLOWANCE);
    }

    /** Computes the debuff both groups suffer against each other. */
    public Matchup matchup(int players, int enemyPlayers) {
        int ourLevel = levelFor(players);
        int enemyLevel = levelFor(enemyPlayers);
        return new Matchup(
                ourLevel,
                enemyLevel,
                Math.max(0, ourLevel - enemyLevel),
                Math.max(0, enemyLevel - ourLevel));
    }

    /**
     * @param ourLevel our Disarray level
     * @param enemyLevel their Disarray level
     * @param ourDebuffPercent damage and CC reduction we suffer when attacking them
     * @param enemyDebuffPercent damage and CC reduction they suffer when attacking us
     */
    public record Matchup(int ourLevel, int enemyLevel, int ourDebuffPercent, int enemyDebuffPercent) {
    }
}
