package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.config.AlbionProperties;

/**
 * Alliance-mates are on our side of the killboard, and are not part of our turnout.
 *
 * <p>Dumbo Elephants sit in the MASS alliance with five other guilds, so before this every
 * post from an alliance CTA painted half our own side red — and, because the title names
 * the biggest guild that is not us, could headline the post "vs Humanity" when Humanity
 * was standing next to us.
 *
 * <p>The empty-string case has its own test because it is not a hypothetical: guilds with
 * no alliance report {@code allianceId: ""} rather than null, measured across 20 live EU
 * battles on 15 August 2026. Treating blank as an alliance would make every unallied guild
 * in a fight an ally of every other one.
 */
class KillboardAllyTest {

    private static final String OUR_GUILD = "d6HcQHoTSH-2qY1oYVNjEQ";
    private static final String ALLY_GUILD = "z1W3OA5TS5-b_mr4PgDQQw";
    private static final String ENEMY_GUILD = "NVOVeQwVTt2wOT0rXvusxw";
    private static final String OUR_ALLIANCE = "VWV1nSFRQwOtmgFW04ZO7w";
    private static final String ENEMY_ALLIANCE = "GjtmJo_4T-eVDtb6-hwbCw";

    private static final Set<String> OURS = Set.of(OUR_GUILD);

    private final KillboardService killboards = new KillboardService(
            null, null, null, null, new AlbionProperties(null, null, "https://albionbattles.com/battles/%d"));

    private static AlbionBattle.Side side(String id, String name, String allianceId) {
        return new AlbionBattle.Side(id, name, 10, 5, 1_000_000L, "", allianceId);
    }

    private static AlbionBattle.Participant player(String id, String guildId, String allianceId) {
        return new AlbionBattle.Participant(id, "p" + id, 2, 1, 500_000L, guildId, "g" + guildId, allianceId, "");
    }

    /**
     * @param ourCount how many of our own fought, alongside a fixed 2 allies and 3 enemies
     */
    private static AlbionBattle battle(String ourAllianceId, int ourCount) {
        Map<String, AlbionBattle.Side> guilds = new LinkedHashMap<>();
        guilds.put(OUR_GUILD, side(OUR_GUILD, "Dumbo Elephants", ourAllianceId));
        guilds.put(ALLY_GUILD, side(ALLY_GUILD, "Humanity", OUR_ALLIANCE));
        // Deliberately the highest kill fame in the battle, so any "biggest side that is
        // not us" logic has to actively exclude the ally to still name this one.
        guilds.put(
                ENEMY_GUILD,
                new AlbionBattle.Side(ENEMY_GUILD, "Iron Dome", 40, 20, 9_000_000L, "", ENEMY_ALLIANCE));

        Map<String, AlbionBattle.Participant> players = new LinkedHashMap<>();
        for (int i = 0; i < ourCount; i++) {
            players.put("u" + i, player("u" + i, OUR_GUILD, ourAllianceId));
        }
        for (int i = 0; i < 2; i++) {
            players.put("a" + i, player("a" + i, ALLY_GUILD, OUR_ALLIANCE));
        }
        for (int i = 0; i < 3; i++) {
            players.put("e" + i, player("e" + i, ENEMY_GUILD, ENEMY_ALLIANCE));
        }

        return new AlbionBattle(
                417352406L,
                Instant.parse("2026-08-15T18:00:00Z"),
                Instant.parse("2026-08-15T18:12:00Z"),
                null,
                50_000_000L,
                120,
                null,
                players,
                guilds,
                Map.of());
    }

    private static String field(MessageEmbed embed, String name) {
        return embed.getFields().stream()
                .filter(f -> name.equals(f.getName()))
                .map(MessageEmbed.Field::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field called " + name));
    }

    @Test
    @DisplayName("an alliance-mate is purple and on our side, not a red enemy")
    void alliesAreFriendly() {
        MessageEmbed embed = killboards.buildEmbed(battle(OUR_ALLIANCE, 12), OURS);

        String guilds = field(embed, "Guilds (kills/deaths, fame)");
        assertThat(guilds).contains("🟢 **Dumbo Elephants**");
        assertThat(guilds).contains("🟣 **Humanity**");
        assertThat(guilds).contains("🔴 **Iron Dome**");

        // The ally out-fames nobody here, but the enemy out-fames the ally — the title has
        // to name the guild we fought, not simply the loudest one that is not us.
        assertThat(embed.getTitle()).isEqualTo("17-player battle vs Iron Dome");

        // 12 of ours plus 2 allies, and the split stays legible.
        assertThat(field(embed, "Our side")).startsWith("14 players (12 us, 2 allied)");
    }

    @Test
    @DisplayName("allies do not count toward the turnout that gates a post")
    void alliesDoNotCountTowardTurnout() {
        assertThat(KillboardService.ourPlayerCount(battle(OUR_ALLIANCE, 12), OURS)).isEqualTo(12);
        // Below a threshold of 10 despite 2 allies and 3 enemies also being present.
        assertThat(KillboardService.ourPlayerCount(battle(OUR_ALLIANCE, 8), OURS)).isEqualTo(8);
    }

    @Test
    @DisplayName("guilds with no alliance are not allies of each other")
    void blankAllianceIsNotAnAlliance() {
        // Our guild is in no alliance, so nothing in this fight is allied to us — least of
        // all the other guilds that also report "".
        AlbionBattle unallied = battle("", 12);

        assertThat(KillboardService.allyGuildIds(unallied, OURS)).isEmpty();

        MessageEmbed embed = killboards.buildEmbed(unallied, OURS);
        assertThat(field(embed, "Guilds (kills/deaths, fame)")).contains("🔴 **Iron Dome**").doesNotContain("🟣");
        // No allies, so no breakdown to explain — the line reads as it always did.
        assertThat(field(embed, "Our side")).startsWith("12 players\n");
    }

    @Test
    @DisplayName("our own guild is green, never purple")
    void ourGuildIsNotItsOwnAlly() {
        assertThat(KillboardService.allyGuildIds(battle(OUR_ALLIANCE, 12), OURS))
                .containsExactly(ALLY_GUILD);
    }
}
