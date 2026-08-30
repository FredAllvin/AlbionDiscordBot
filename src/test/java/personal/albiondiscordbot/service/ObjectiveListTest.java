package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.domain.Objective;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.ObjectiveRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * The objective board: what it shows, in what order, and when it forgets.
 *
 * <p>Real Postgres, because the two things worth checking are both in the database rather
 * than in Java. {@code ux_objective_name_time} is an expression index over
 * {@code lower(name)}, which is the only thing standing between two people relaying the
 * same intel and two identical lines on the board; and the expiry is a bulk {@code DELETE}
 * whose boundary is a SQL comparison. Mocking the repository would assert on neither.
 *
 * <p>Every test drives the clock explicitly. The window under test is thirty minutes wide,
 * so a suite that used the wall clock could only check it by waiting out the window.
 */
@SpringBootTest
class ObjectiveListTest extends PostgresTestBase {

    private static final long GUILD = 8801L;
    private static final long OTHER_GUILD = 8802L;
    private static final long MEMBER = 501L;

    /** A Sunday noon, with room on both sides of it. */
    private static final Instant NOON = Instant.parse("2026-08-30T12:00:00Z");

    @MockitoBean
    private JDA jda;

    @Autowired
    private ObjectiveService objectives;

    @Autowired
    private ObjectiveRepository repository;

    @Autowired
    private DiscordGuildConfigRepository configs;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        // objective.discord_guild_id references discord_guild_config, as every per-server
        // table here does.
        for (long guildId : List.of(GUILD, OTHER_GUILD)) {
            if (!configs.existsById(guildId)) {
                configs.save(new DiscordGuildConfig(guildId));
            }
        }
    }

    private Objective add(String name, String time, Instant now) {
        return objectives.add(GUILD, MEMBER, name, LocalTime.parse(time), now);
    }

    private List<String> namesAt(Instant now) {
        return objectives.list(GUILD, now).stream().map(Objective::getName).toList();
    }

    @Test
    @DisplayName("the soonest objective comes first")
    void ordersBySoonest() {
        add("Castle", "18:00", NOON);
        add("Fort Sterling chest", "13:00", NOON);
        add("Territory", "15:30", NOON);

        assertThat(namesAt(NOON)).containsExactly("Fort Sterling chest", "Territory", "Castle");
    }

    @Test
    @DisplayName("a time already gone by today sorts behind tonight, not in front of it")
    void ordersAcrossTheDayBoundary() {
        // 10:00 has been and gone at noon, so it means tomorrow's — and tomorrow's 10:00
        // is further off than tonight's 20:00. Compared as bare times it would lead.
        add("Morning chest", "10:00", NOON);
        add("Evening cta", "20:00", NOON);

        assertThat(namesAt(NOON)).containsExactly("Evening cta", "Morning chest");
    }

    @Test
    @DisplayName("an objective that has just popped stays on the board")
    void keepsAFreshlyPoppedObjective() {
        add("Fort Sterling chest", "12:30", NOON);

        Instant tenPast = Instant.parse("2026-08-30T12:40:00Z");
        assertThat(namesAt(tenPast)).containsExactly("Fort Sterling chest");
        assertThat(ObjectiveService.hasPopped(objectives.list(GUILD, tenPast).get(0), tenPast))
                .isTrue();
    }

    @Test
    @DisplayName("it is gone once the clock reaches half an hour past")
    void dropsItAtTheGraceBoundary() {
        add("Fort Sterling chest", "12:30", NOON);

        Instant oneSecondShort = Instant.parse("2026-08-30T12:59:59Z");
        assertThat(namesAt(oneSecondShort)).containsExactly("Fort Sterling chest");

        // 12:30 + the grace window exactly. "The clock reaches 13:00" is when it goes.
        Instant exactly = Instant.parse("2026-08-30T13:00:00Z");
        assertThat(namesAt(exactly)).isEmpty();
    }

    @Test
    @DisplayName("expiry deletes the row rather than hiding it")
    void expiryIsADelete() {
        add("Fort Sterling chest", "12:30", NOON);
        assertThat(repository.count()).isEqualTo(1);

        objectives.list(GUILD, NOON.plus(Duration.ofHours(2)));

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("a live objective survives the sweep that clears a dead one")
    void expiresOnlyWhatIsPast() {
        add("Fort Sterling chest", "12:30", NOON);
        add("Evening cta", "20:00", NOON);

        assertThat(namesAt(Instant.parse("2026-08-30T13:30:00Z"))).containsExactly("Evening cta");
    }

    @Test
    @DisplayName("one server's sweep leaves another server's board alone")
    void expiryIsScopedToOneServer() {
        add("Ours", "12:30", NOON);
        objectives.add(OTHER_GUILD, MEMBER, "Theirs", LocalTime.of(12, 30), NOON);

        // Reading this server's board, well past the point both have expired.
        assertThat(namesAt(Instant.parse("2026-08-30T14:00:00Z"))).isEmpty();

        // The other server's row is still there. Its own /objective show sweeps it.
        assertThat(repository.findAll())
                .extracting(Objective::getDiscordGuildId)
                .containsExactly(OTHER_GUILD);
    }

    @Test
    @DisplayName("adding sweeps too, so a board nobody reads does not grow forever")
    void addingExpires() {
        add("Fort Sterling chest", "12:30", NOON);

        add("Evening cta", "20:00", Instant.parse("2026-08-30T13:30:00Z"));

        assertThat(repository.findAll()).extracting(Objective::getName).containsExactly("Evening cta");
    }

    @Test
    @DisplayName("the same objective twice is refused, whatever the casing")
    void refusesDuplicates() {
        add("Fort Sterling chest", "20:00", NOON);

        assertThatThrownBy(() -> add("fort sterling CHEST", "20:00", NOON))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("already on the list");
    }

    @Test
    @DisplayName("the same name at another time is a different objective")
    void allowsTheSameNameAtAnotherTime() {
        add("Fort Sterling chest", "14:00", NOON);
        add("Fort Sterling chest", "20:00", NOON);

        assertThat(namesAt(NOON)).containsExactly("Fort Sterling chest", "Fort Sterling chest");
    }

    @Test
    @DisplayName("an objective is stored as an instant, and reads back as the time that was typed")
    void roundTripsTheTypedTime() {
        Objective saved = add("Evening cta", "20:00", NOON);

        assertThat(saved.getPopsAt()).isEqualTo(Instant.parse("2026-08-30T20:00:00Z"));
        assertThat(saved.popsAtUtc()).isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("a name of only spaces is refused before it reaches the board")
    void refusesABlankName() {
        assertThatThrownBy(() -> add("   ", "20:00", NOON)).isInstanceOf(CommandException.class);
    }
}
