package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import personal.albiondiscordbot.service.ObjectiveService.Change;
import personal.albiondiscordbot.service.ObjectiveService.Edited;
import personal.albiondiscordbot.service.ObjectiveService.Selector;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * Correcting and clearing lines on the objective board.
 *
 * <p>Two things are being checked, and they pull against each other. One is that a line
 * can be found by what the guild calls it, casing and all, because that is the only handle
 * anyone has on it. The other is that when a name does not pick out exactly one line,
 * nothing is edited or deleted at all — a shared board survives a refusal, and does not
 * survive quietly moving the wrong objective.
 *
 * <p>Real Postgres, for the same reason {@link ObjectiveListTest} uses it: what stops an
 * edit from colliding with another line is {@code ux_objective_slot}, an expression index
 * over {@code lower(name)} and {@code lower(coalesce(zone, ''))}, and a mocked repository
 * would assert on none of it.
 */
@SpringBootTest
class ObjectiveEditTest extends PostgresTestBase {

    private static final long GUILD = 8811L;
    private static final long OTHER_GUILD = 8812L;
    private static final long MEMBER = 511L;

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
        for (long guildId : List.of(GUILD, OTHER_GUILD)) {
            if (!configs.existsById(guildId)) {
                configs.save(new DiscordGuildConfig(guildId));
            }
        }
    }

    private Objective add(String name, String zone, String time) {
        return objectives.add(GUILD, MEMBER, name, zone, LocalTime.parse(time), NOON);
    }

    private static Selector named(String name) {
        return Selector.typed(name, null, null);
    }

    private Edited edit(Selector selector, Change change) {
        return objectives.edit(GUILD, selector, change, NOON);
    }

    private List<String> namesAt(Instant now) {
        return objectives.list(GUILD, now).stream().map(Objective::getName).toList();
    }

    @Test
    @DisplayName("a line is renamed by the name it is up under")
    void renames() {
        add("Chest", null, "20:00");

        Edited edited = edit(named("chest"), new Change("Fort Sterling chest", null, null));

        assertThat(edited.previousName()).isEqualTo("Chest");
        assertThat(edited.objective().getName()).isEqualTo("Fort Sterling chest");
        assertThat(namesAt(NOON)).containsExactly("Fort Sterling chest");
    }

    @Test
    @DisplayName("a new time is resolved the way an added one is, and can roll to tomorrow")
    void movesTheTime() {
        add("Chest", null, "20:00");

        // Corrected at noon to a time that has already gone by today, so it means
        // tomorrow's — not a moment in the past that the next sweep would delete.
        Edited edited = edit(named("Chest"), new Change(null, LocalTime.of(10, 0), null));

        assertThat(edited.objective().getPopsAt()).isEqualTo(Instant.parse("2026-08-31T10:00:00Z"));
        assertThat(edited.previousPopsAt()).isEqualTo(Instant.parse("2026-08-30T20:00:00Z"));
    }

    @Test
    @DisplayName("a zone can be set, corrected, and taken back off with a blank")
    void setsAndClearsTheZone() {
        add("Chest", null, "20:00");

        assertThat(edit(named("Chest"), new Change(null, null, "Fort Sterling"))
                        .objective()
                        .getZone())
                .isEqualTo("Fort Sterling");
        assertThat(edit(named("Chest"), new Change(null, null, "Martlock"))
                        .objective()
                        .getZone())
                .isEqualTo("Martlock");

        Edited cleared = edit(named("Chest"), new Change(null, null, ""));
        assertThat(cleared.previousZone()).isEqualTo("Martlock");
        assertThat(cleared.objective().getZone()).isNull();
    }

    @Test
    @DisplayName("all three move at once")
    void changesEverythingAtOnce() {
        add("Chest", "Fort Sterling", "20:00");

        Edited edited = edit(named("Chest"), new Change("Castle", LocalTime.of(21, 30), "Martlock"));

        Objective after = edited.objective();
        assertThat(after.getName()).isEqualTo("Castle");
        assertThat(after.getZone()).isEqualTo("Martlock");
        assertThat(after.popsAtUtc()).isEqualTo(LocalTime.of(21, 30));
    }

    @Test
    @DisplayName("leaving the name and time alone is not a collision with itself")
    void doesNotCollideWithTheLineBeingEdited() {
        add("Chest", "Fort Sterling", "20:00");

        // Same name, same instant, only the zone moves: the duplicate check has to skip
        // the row it is about to write, or every partial edit would look like a duplicate.
        assertThatCode(() -> edit(named("Chest"), new Change(null, null, "Martlock")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an edit that would land on another line is refused")
    void refusesAnEditOntoAnotherLine() {
        add("Chest", "Fort Sterling", "20:00");
        add("Chest", "Martlock", "20:00");

        assertThatThrownBy(() -> edit(
                        Selector.typed("Chest", null, "Martlock"), new Change(null, null, "fort sterling")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("already on the list");

        // And nothing moved.
        assertThat(objectives.list(GUILD, NOON))
                .extracting(Objective::getZone)
                .containsExactlyInAnyOrder("Fort Sterling", "Martlock");
    }

    @Test
    @DisplayName("an edit that asks for nothing is refused")
    void refusesAnEmptyChange() {
        add("Chest", null, "20:00");

        assertThatThrownBy(() -> edit(named("Chest"), new Change(null, null, null)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Say what to change");
    }

    @Test
    @DisplayName("an edit onto the values a line already has is refused rather than reported as a change")
    void refusesANoOpChange() {
        add("Chest", "Fort Sterling", "20:00");

        assertThatThrownBy(() -> edit(named("Chest"), new Change("Chest", null, null)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("nothing to change");
    }

    @Test
    @DisplayName("fixing only the capitalisation is a real edit")
    void renamesToAnotherCasing() {
        add("fort sterling CHEST", null, "20:00");

        // The selector ignores case, and the duplicate check ignores case, but the stored
        // name is what the board prints — so tidying it up has to go through.
        assertThat(edit(named("Fort Sterling Chest"), new Change("Fort Sterling chest", null, null))
                        .objective()
                        .getName())
                .isEqualTo("Fort Sterling chest");
    }

    @Test
    @DisplayName("removing takes the row out")
    void removes() {
        add("Chest", null, "20:00");
        add("Castle", null, "21:00");

        Objective gone = objectives.remove(GUILD, named("chest"), NOON);

        assertThat(gone.getName()).isEqualTo("Chest");
        assertThat(namesAt(NOON)).containsExactly("Castle");
    }

    @Test
    @DisplayName("an objective in its grace window can still be taken off")
    void removesAFreshlyPoppedObjective() {
        add("Chest", null, "12:30");

        // Ten minutes past. The line is red on the board but it is still a line, and the
        // moment somebody wants it gone is the moment it turned out to be wrong.
        Instant tenPast = Instant.parse("2026-08-30T12:40:00Z");
        objectives.remove(GUILD, named("Chest"), tenPast);

        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("a name that is not up is refused, and says where to look")
    void refusesANameThatIsNotOnTheList() {
        add("Chest", null, "20:00");

        assertThatThrownBy(() -> objectives.remove(GUILD, named("Castle"), NOON))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Nothing called");
    }

    @Test
    @DisplayName("an expired line cannot be edited, because it is not on the board")
    void refusesAnExpiredLine() {
        add("Chest", null, "12:30");

        // Well past the grace window. select() reads the swept list, so the row is gone
        // before it can be found — editing it would appear to work and change nothing.
        assertThatThrownBy(() -> objectives.edit(
                        GUILD,
                        named("Chest"),
                        new Change("Fort Sterling chest", null, null),
                        Instant.parse("2026-08-30T14:00:00Z")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Nothing called");
    }

    @Test
    @DisplayName("a name that is up twice is refused, and both candidates are named")
    void refusesAnAmbiguousName() {
        add("Chest", "Fort Sterling", "20:00");
        add("Chest", "Martlock", "21:00");

        assertThatThrownBy(() -> objectives.remove(GUILD, named("Chest"), NOON))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("on the list 2 times")
                .hasMessageContaining("Fort Sterling")
                .hasMessageContaining("Martlock");

        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("two lines a selector cannot separate are at least told apart in the refusal")
    void marksThePoppedCandidate() {
        add("Chest", null, "12:30");
        // Added at 12:40, so this one is tomorrow's 12:30 — same name, same zone, same
        // time of day as the one still living out its grace window. Nothing in the
        // selector separates them; the refusal has to say which is which.
        Instant tenPast = Instant.parse("2026-08-30T12:40:00Z");
        objectives.add(GUILD, MEMBER, "Chest", null, LocalTime.of(12, 30), tenPast);

        assertThatThrownBy(() -> objectives.remove(GUILD, named("Chest"), tenPast))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("`12:30` UTC (popped), `12:30` UTC");
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("the time picks one of two lines sharing a name")
    void narrowsByTime() {
        add("Chest", null, "20:00");
        add("Chest", null, "21:00");

        objectives.remove(GUILD, Selector.typed("Chest", LocalTime.of(21, 0), null), NOON);

        assertThat(objectives.list(GUILD, NOON))
                .singleElement()
                .extracting(Objective::popsAtUtc)
                .isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("the zone picks one of two lines sharing a name and a time")
    void narrowsByZone() {
        add("Chest", "Fort Sterling", "20:00");
        add("Chest", "Martlock", "20:00");

        objectives.remove(GUILD, Selector.typed("Chest", null, "martlock"), NOON);

        assertThat(objectives.list(GUILD, NOON))
                .singleElement()
                .extracting(Objective::getZone)
                .isEqualTo("Fort Sterling");
    }

    @Test
    @DisplayName("a line inside its grace window is found by the time it is printed under")
    void narrowsByTheTimeOnTheBoard() {
        add("Chest", null, "12:30");
        add("Chest", null, "20:00");

        // 12:40, so the 12:30 has popped and is living out its grace window. Its time of
        // day is what the board shows, but resolving "12:30" against now would give
        // tomorrow's 12:30 and find nothing — the selector matches the printed time.
        Instant tenPast = Instant.parse("2026-08-30T12:40:00Z");
        objectives.remove(GUILD, Selector.typed("Chest", LocalTime.of(12, 30), null), tenPast);

        assertThat(objectives.list(GUILD, tenPast))
                .singleElement()
                .extracting(Objective::popsAtUtc)
                .isEqualTo(LocalTime.of(20, 0));
    }

    @Test
    @DisplayName("narrowing to a time nothing is up at says what the line is actually up at")
    void refusesTheWrongTime() {
        add("Chest", null, "20:00");

        assertThatThrownBy(() -> objectives.remove(GUILD, Selector.typed("Chest", LocalTime.of(21, 0), null), NOON))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("but not at `21:00` UTC")
                .hasMessageContaining("`20:00` UTC");
    }

    @Test
    @DisplayName("the picker names one row, whatever else shares its name")
    void editsThePickedRow() {
        add("Chest", "Fort Sterling", "20:00");
        Objective martlock = add("Chest", "Martlock", "20:00");

        // A typed "Chest" is ambiguous here and would be refused. An id is not.
        Edited edited = edit(Selector.picked(martlock.getId()), new Change(null, LocalTime.of(21, 0), null));

        assertThat(edited.objective().getZone()).isEqualTo("Martlock");
        assertThat(edited.objective().popsAtUtc()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    @DisplayName("a picked row that has gone since says so, rather than reporting nothing by that name")
    void refusesAPickedRowThatIsGone() {
        Objective chest = add("Chest", null, "12:30");

        // The picker listed it, then it expired while the caller was still choosing.
        assertThatThrownBy(() -> objectives.edit(
                        GUILD,
                        Selector.picked(chest.getId()),
                        new Change("Fort Sterling chest", null, null),
                        Instant.parse("2026-08-30T14:00:00Z")))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("not on the list any more");
    }

    @Test
    @DisplayName("a row id from another server's board matches nothing")
    void refusesAPickedRowFromAnotherServer() {
        Objective theirs = objectives.add(OTHER_GUILD, MEMBER, "Chest", null, LocalTime.of(20, 0), NOON);

        assertThatThrownBy(() -> edit(Selector.picked(theirs.getId()), new Change("Ours", null, null)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("not on the list any more");

        assertThat(repository.findAll()).extracting(Objective::getName).containsExactly("Chest");
    }

    @Test
    @DisplayName("a name typed instead of picked still works")
    void stillAcceptsATypedName() {
        // Discord lets an autocompleting option be submitted with whatever is in the box,
        // so the name path has to stay live behind the picker.
        add("Chest", null, "20:00");

        assertThat(edit(named("chest"), new Change(null, null, "Martlock"))
                        .objective()
                        .getZone())
                .isEqualTo("Martlock");
    }

    @Test
    @DisplayName("an ambiguous typed name points at the picker, not at time: and zone:")
    void ambiguityTellsEditToPick() {
        add("Chest", "Fort Sterling", "20:00");
        add("Chest", "Martlock", "20:00");

        assertThatThrownBy(() -> edit(named("Chest"), new Change(null, LocalTime.of(21, 0), null)))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Pick it from the list");

        // remove has no picker, so it still says how to narrow it.
        assertThatThrownBy(() -> objectives.remove(GUILD, named("Chest"), NOON))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Add `time:` or `zone:`");
    }

    @Test
    @DisplayName("the picker reads the board without sweeping it")
    void visibleDoesNotWrite() {
        add("Chest", null, "12:30");
        add("Evening cta", null, "20:00");

        // Well past the grace window for the first one. It must not be offered...
        Instant late = Instant.parse("2026-08-30T14:00:00Z");
        assertThat(objectives.visible(GUILD, late)).extracting(Objective::getName).containsExactly("Evening cta");

        // ...but it must not be deleted either: this runs on every keystroke, and a sweep
        // is a write. The next real read of the board is what clears it.
        assertThat(repository.count()).isEqualTo(2);
        assertThat(objectives.list(GUILD, late)).hasSize(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("one server cannot edit or remove another server's board")
    void isScopedToOneServer() {
        objectives.add(OTHER_GUILD, MEMBER, "Chest", null, LocalTime.of(20, 0), NOON);

        assertThatThrownBy(() -> objectives.remove(GUILD, named("Chest"), NOON))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("Nothing called");

        assertThat(repository.findAll())
                .extracting(Objective::getDiscordGuildId)
                .containsExactly(OTHER_GUILD);
    }
}
