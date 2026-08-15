package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.dto.AlbionPlayerDetail;
import personal.albiondiscordbot.albion.dto.AlbionSearchResponse;
import personal.albiondiscordbot.discord.CommandException;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * Registering a second time over an existing link.
 *
 * <p>This needs a real Postgres. {@code ux_registration_user} is a <em>partial</em> unique
 * index over active rows, so the conflict it catches only exists in the database — no
 * amount of mocking the repository would show it. That is exactly how it went unnoticed:
 * every other registration test is pure Mockito.
 *
 * <p>The bug these pin down: {@code Registration} uses IDENTITY ids, so persisting the new
 * row issues its INSERT immediately, while deactivating the old row sat unflushed until
 * commit. Two active rows for one user existed at insert time and the index rejected it,
 * so changing your character failed with a generic error every time.
 */
@SpringBootTest
class ReRegistrationTest extends PostgresTestBase {

    private static final long GUILD = 7777L;
    private static final long MEMBER = 500L;
    private static final long OTHER_MEMBER = 501L;
    private static final long OFFICER = 900L;

    private static final String OUR_ALBION_GUILD = "OUR_GUILD_ID";

    @MockitoBean
    private JDA jda;

    @MockitoBean
    private AlbionApiClient albion;

    @Autowired
    private RegistrationService registrations;

    @Autowired
    private RegistrationRepository registrationRepository;

    @Autowired
    private TrackedAlbionGuildRepository trackedGuilds;

    @Autowired
    private DiscordGuildConfigRepository configs;

    @Autowired
    private GuildConfigService guildConfigService;

    @BeforeEach
    void reset() {
        registrationRepository.deleteAll();
        trackedGuilds.deleteAll();
        configs.deleteAll();
        guildConfigService.getOrCreate(GUILD);
        trackedGuilds.save(new TrackedAlbionGuild(GUILD, OUR_ALBION_GUILD, "Our Guild", OFFICER));
    }

    /** Wires the API mock so {@code name} resolves to {@code playerId} inside our guild. */
    private void apiKnows(String playerId, String name) {
        apiKnows(playerId, name, OUR_ALBION_GUILD);
    }

    private void apiKnows(String playerId, String name, String albionGuildId) {
        when(albion.findPlayerByExactName(eq(name)))
                .thenReturn(Optional.of(new AlbionSearchResponse.PlayerHit(
                        playerId, name, albionGuildId, "Our Guild", null, null, 10L, 5L)));
        when(albion.getPlayer(eq(playerId)))
                .thenReturn(Optional.of(new AlbionPlayerDetail(
                        playerId, name, albionGuildId, "Our Guild", null, null, 10L, 5L, 2.0, null)));
    }

    private List<Registration> rowsFor(long userId) {
        return registrationRepository.findAll().stream()
                .filter(r -> r.getDiscordUserId() == userId)
                .toList();
    }

    @Test
    @DisplayName("registering a different character replaces the old link instead of failing")
    void reRegisterUnderANewName() {
        apiKnows("PLAYER_OLD", "Oldname");
        apiKnows("PLAYER_NEW", "Newname");

        registrations.register(GUILD, MEMBER, "Oldname");
        Registration second = registrations.register(GUILD, MEMBER, "Newname");

        assertThat(second.getAlbionPlayerName()).isEqualTo("Newname");
        assertThat(registrations.find(GUILD, MEMBER))
                .get()
                .extracting(Registration::getAlbionPlayerId)
                .isEqualTo("PLAYER_NEW");

        // The old row survives deactivated — the audit trail is the whole point of the
        // soft delete, so a replacement must not erase it.
        List<Registration> all = rowsFor(MEMBER);
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(Registration::isActive).hasSize(1);
        assertThat(all)
                .filteredOn(r -> !r.isActive())
                .singleElement()
                .satisfies(old -> {
                    assertThat(old.getAlbionPlayerId()).isEqualTo("PLAYER_OLD");
                    assertThat(old.getUnregisteredAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("registering the same character twice is not an error")
    void reRegisterUnderTheSameName() {
        apiKnows("PLAYER_A", "Samename");

        registrations.register(GUILD, MEMBER, "Samename");
        registrations.register(GUILD, MEMBER, "Samename");

        assertThat(rowsFor(MEMBER)).filteredOn(Registration::isActive).hasSize(1);
        assertThat(registrations.find(GUILD, MEMBER))
                .get()
                .extracting(Registration::getAlbionPlayerName)
                .isEqualTo("Samename");
    }

    @Test
    @DisplayName("a force-register over an existing link credits the officer, not the member")
    void forceRegisterRecordsTheOfficerAsActor() {
        apiKnows("PLAYER_OLD", "Oldname");
        apiKnows("PLAYER_NEW", "Newname");

        registrations.register(GUILD, MEMBER, "Oldname");
        registrations.forceRegister(GUILD, MEMBER, "Newname", OFFICER);

        assertThat(rowsFor(MEMBER))
                .filteredOn(r -> !r.isActive())
                .singleElement()
                // Recording the member here made staff action look self-inflicted in the
                // audit trail, which is the one thing that trail exists to distinguish.
                .extracting(Registration::getUnregisteredBy)
                .isEqualTo(OFFICER);

        assertThat(registrations.find(GUILD, MEMBER))
                .get()
                .extracting(Registration::isVerified)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("someone else's character is still refused")
    void cannotStealAnotherMembersCharacter() {
        apiKnows("PLAYER_A", "Taken");

        registrations.register(GUILD, MEMBER, "Taken");

        assertThatThrownBy(() -> registrations.register(GUILD, OTHER_MEMBER, "Taken"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("already registered");

        assertThat(rowsFor(OTHER_MEMBER)).isEmpty();
    }

    @Test
    @DisplayName("re-registering after unregistering works, which is what the partial index is for")
    void reRegisterAfterUnregister() {
        apiKnows("PLAYER_A", "Backagain");

        registrations.register(GUILD, MEMBER, "Backagain");
        registrations.unregister(GUILD, MEMBER, MEMBER);
        registrations.register(GUILD, MEMBER, "Backagain");

        assertThat(rowsFor(MEMBER)).filteredOn(Registration::isActive).hasSize(1);
        // Two rows, not three: the unregister deactivated the first, so the third call
        // found no active row to replace and simply inserted.
        assertThat(rowsFor(MEMBER)).hasSize(2);
    }
}
