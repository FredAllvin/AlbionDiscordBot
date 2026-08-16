package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.poller.BattleIngestService;
import personal.albiondiscordbot.repository.BalanceRepository;
import personal.albiondiscordbot.repository.BalanceTransactionRepository;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.service.BatchConfirmationService.Recipient;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * One CTA, three killboards, everybody paid once.
 *
 * <p>A CTA breaks apart and reforms, and Albion opens a new battle id each time. Splitting
 * each of those separately paid the people who stayed for the whole thing once per fight
 * and the person who turned up for one fight once — the opposite of what a CTA split is
 * for. So {@code /split-cta} takes the whole list and credits the union.
 *
 * <p>The rosters below deliberately overlap: three of the five registered members fought
 * in two of the three battles.
 */
@SpringBootTest
class SplitCtaMultiBattleTest extends PostgresTestBase {

    private static final long GUILD = 8080L;
    private static final long OTHER_GUILD = 9090L;
    private static final long OFFICER = 1L;
    private static final String OUR_GUILD_ID = "d6HcQHoTSH-2qY1oYVNjEQ";

    private static final long FIRST_FIGHT = 417352406L;
    private static final long SECOND_FIGHT = 417352999L;
    private static final long THIRD_FIGHT = 417353400L;

    private static final List<Long> WHOLE_CTA = List.of(FIRST_FIGHT, SECOND_FIGHT, THIRD_FIGHT);

    @MockitoBean
    private JDA jda;

    @Autowired
    private BatchConfirmationService batches;

    @Autowired
    private BalanceService balances;

    @Autowired
    private BattleIngestService ingestService;

    @Autowired
    private RegistrationRepository registrations;

    @Autowired
    private BalanceRepository balanceRepository;

    @Autowired
    private BalanceTransactionRepository ledger;

    @Autowired
    private DiscordGuildConfigRepository configs;

    @Autowired
    private GuildConfigService guildConfigService;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM split_battle_group").update();
        jdbc.sql("DELETE FROM battle_participation").update();
        jdbc.sql("DELETE FROM killboard_post").update();
        jdbc.sql("DELETE FROM battle").update();
        ledger.deleteAll();
        balanceRepository.deleteAll();
        registrations.deleteAll();
        configs.deleteAll();
        guildConfigService.getOrCreate(GUILD);
        guildConfigService.getOrCreate(OTHER_GUILD);

        // Zvz and Bogul stayed for the first two fights, Kite for the last two, so any
        // per-battle payment would credit three of the five twice.
        ingest(FIRST_FIGHT, "p-zvz", "p-bogul", "p-onefight", "p-unregistered");
        ingest(SECOND_FIGHT, "p-zvz", "p-bogul", "p-kite");
        ingest(THIRD_FIGHT, "p-kite", "p-latecomer");

        register(101L, "p-zvz", "Zvz");
        register(102L, "p-bogul", "Bogul");
        register(103L, "p-onefight", "OneFight");
        register(104L, "p-kite", "Kite");
        register(105L, "p-latecomer", "Latecomer");
        // p-unregistered is in the guild and fought, but never ran /register.
    }

    @Test
    @DisplayName("everyone who fought in any of the fights is credited exactly once")
    void unionIsPaidOnce() {
        List<Recipient> recipients = batches.resolveBattles(GUILD, WHOLE_CTA);

        assertThat(recipients).extracting(Recipient::label)
                .containsExactlyInAnyOrder("Zvz", "Bogul", "OneFight", "Kite", "Latecomer");

        batches.executeSplit(GUILD, recipients, 1_000_000L, OFFICER, "CTA", freshClaim());

        // The regression this exists for: Zvz, Bogul and Kite each fought in two of the
        // three battles, and none of them may be paid two shares for it.
        for (long member : List.of(101L, 102L, 103L, 104L, 105L)) {
            assertThat(balances.balanceOf(GUILD, member))
                    .as("member %d", member)
                    .isEqualTo(1_000_000L);
        }
        assertThat(balances.totalSilver(GUILD)).isEqualTo(5_000_000L);
    }

    @Test
    @DisplayName("someone who came for one fight is paid the same as someone who stayed")
    void oneFightEarnsAFullShare() {
        List<Recipient> recipients = batches.resolveBattles(GUILD, WHOLE_CTA);
        batches.executeSplit(GUILD, recipients, 1_000_000L, OFFICER, "CTA", freshClaim());

        assertThat(balances.balanceOf(GUILD, 103L)).isEqualTo(balances.balanceOf(GUILD, 101L));
    }

    @Test
    @DisplayName("a single battle behaves exactly as it did before")
    void singleBattleUnchanged() {
        List<Recipient> recipients = batches.resolveBattles(GUILD, List.of(FIRST_FIGHT));

        assertThat(recipients).extracting(Recipient::label)
                .containsExactlyInAnyOrder("Zvz", "Bogul", "OneFight");
        assertThat(batches.countOurFighters(List.of(FIRST_FIGHT))).isEqualTo(4);
    }

    @Test
    @DisplayName("the turnout counts people, not participation rows")
    void turnoutCountsDistinctFighters() {
        // Nine participation rows across the three battles, but only six human beings.
        long rows = jdbc.sql("SELECT count(*) FROM battle_participation").query(Long.class).single();
        assertThat(rows).isEqualTo(9);

        assertThat(batches.countOurFighters(WHOLE_CTA)).isEqualTo(6);

        // Which is what makes "X fought but only Y are registered" true: one unregistered
        // member, not four phantom ones.
        long unpayable = batches.countOurFighters(WHOLE_CTA) - batches.resolveBattles(GUILD, WHOLE_CTA).size();
        assertThat(unpayable).isEqualTo(1);
    }

    @Test
    @DisplayName("the battles a preview covers survive being handed to a button and back")
    void battleGroupRoundTrips() {
        String key = batches.rememberBattles(GUILD, WHOLE_CTA);

        assertThat(batches.battlesOf(GUILD, key)).containsExactlyElementsOf(WHOLE_CTA);
    }

    @Test
    @DisplayName("a key from one Discord server resolves nothing in another")
    void battleGroupIsScopedToItsServer() {
        String key = batches.rememberBattles(GUILD, WHOLE_CTA);

        assertThatThrownBy(() -> batches.battlesOf(OTHER_GUILD, key))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Run `/split-cta` again");
    }

    @Test
    @DisplayName("the button id stays inside Discord's 100 characters however many fights are merged")
    void buttonIdsFitRegardlessOfBattleCount() {
        // The point of storing the list: the id carries a fixed-length key, so twenty
        // battles cost exactly what one does. Encoding the ids directly ran out at four.
        List<Long> twenty = java.util.stream.LongStream.range(0, 20)
                .map(i -> FIRST_FIGHT + i)
                .boxed()
                .toList();
        String key = batches.rememberBattles(GUILD, twenty);

        List<Button> buttons = batches.buttons(
                BatchConfirmationService.OP_SPLIT,
                BatchConfirmationService.SOURCE_BATTLES,
                key,
                personal.albiondiscordbot.util.SilverAmountParser.MAX_AMOUNT,
                9223372036854775807L);

        for (Button button : buttons) {
            assertThat(button.getCustomId())
                    .as("custom id %s", button.getCustomId())
                    .hasSizeLessThanOrEqualTo(100);
        }
        assertThat(batches.battlesOf(GUILD, key)).hasSize(20);
    }

    @Test
    @DisplayName("the ledger note names every fight that was paid for")
    void labelNamesTheFights() {
        assertThat(batches.battleLabel(List.of(FIRST_FIGHT))).isEqualTo("CTA 417352406");
        assertThat(batches.battleLabel(WHOLE_CTA))
                .isEqualTo("CTA across 3 fights (417352406, 417352999, 417353400)");
    }

    // ---------------------------------------------------------------- fixtures

    private static String freshClaim() {
        return java.util.UUID.randomUUID().toString();
    }

    private void register(long discordUserId, String albionPlayerId, String name) {
        registrations.save(new Registration(GUILD, discordUserId, albionPlayerId, name));
    }

    private void ingest(long battleId, String... playerIds) {
        Map<String, AlbionBattle.Participant> players = new LinkedHashMap<>();
        for (String playerId : playerIds) {
            players.put(
                    playerId,
                    new AlbionBattle.Participant(
                            playerId, playerId, 2, 1, 500_000L, OUR_GUILD_ID, "Dumbo Elephants", "", ""));
        }

        ingestService.ingest(
                new AlbionBattle(
                        battleId,
                        Instant.parse("2026-08-15T18:00:00Z"),
                        Instant.parse("2026-08-15T18:12:00Z"),
                        Instant.parse("2026-08-15T18:15:00Z"),
                        50_000_000L,
                        120,
                        null,
                        players,
                        Map.of(
                                OUR_GUILD_ID,
                                new AlbionBattle.Side(
                                        OUR_GUILD_ID, "Dumbo Elephants", 10, 5, 1_000_000L, "", "")),
                        Map.of()),
                Set.of(OUR_GUILD_ID));
    }
}
