package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import personal.albiondiscordbot.albion.AlbionApiClient;
import personal.albiondiscordbot.albion.AlbionApiException;
import personal.albiondiscordbot.albion.dto.AlbionPlayerDetail;
import personal.albiondiscordbot.domain.Registration;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.RegistrationRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;
import personal.albiondiscordbot.service.RegistrationService.MembershipCheck;

/**
 * Re-checking whether a registered character is still in the guild.
 *
 * <p>The distinction that matters is between "the API says they left" and "the API did
 * not answer". Treating those the same means one bad API minute strips the verified role
 * from the whole roster, so each case is pinned down here.
 */
@ExtendWith(MockitoExtension.class)
class MembershipCheckTest {

    private static final String OUR_GUILD = "OUR_GUILD_ID";

    @Mock
    private AlbionApiClient albion;

    @Mock
    private RegistrationRepository registrations;

    @Mock
    private TrackedAlbionGuildRepository trackedGuilds;

    private RegistrationService service() {
        return new RegistrationService(albion, registrations, trackedGuilds);
    }

    private List<TrackedAlbionGuild> tracked() {
        return List.of(new TrackedAlbionGuild(1L, OUR_GUILD, "Our Guild", 1L));
    }

    private Registration registration() {
        return new Registration(1L, 100L, "PLAYER_A", "Bogul");
    }

    private AlbionPlayerDetail playerInGuild(String guildId) {
        return new AlbionPlayerDetail(
                "PLAYER_A", "Bogul", guildId, "Some Guild", null, null, 1L, 1L, 1.0, null);
    }

    @Test
    @DisplayName("still in the guild")
    void stillInGuild() {
        when(albion.getPlayer(anyString())).thenReturn(Optional.of(playerInGuild(OUR_GUILD)));

        assertThat(service().checkMembership(registration(), tracked())).isEqualTo(MembershipCheck.IN_GUILD);
    }

    @Test
    @DisplayName("moved to another guild is a definite departure")
    void movedToAnotherGuild() {
        when(albion.getPlayer(anyString())).thenReturn(Optional.of(playerInGuild("SOME_OTHER_GUILD")));

        assertThat(service().checkMembership(registration(), tracked())).isEqualTo(MembershipCheck.LEFT_GUILD);
    }

    @Test
    @DisplayName("guildless is also a departure")
    void leftToNoGuild() {
        when(albion.getPlayer(anyString())).thenReturn(Optional.of(playerInGuild(null)));

        assertThat(service().checkMembership(registration(), tracked())).isEqualTo(MembershipCheck.LEFT_GUILD);
    }

    @Test
    @DisplayName("an API failure is UNKNOWN, never a departure")
    void apiFailureIsNotADeparture() {
        when(albion.getPlayer(anyString())).thenThrow(new AlbionApiException("connection reset"));

        // The bug this guards: returning "not in guild" here meant a Cloudflare blip
        // would queue the entire roster for role removal.
        assertThat(service().checkMembership(registration(), tracked())).isEqualTo(MembershipCheck.UNKNOWN);
    }

    @Test
    @DisplayName("a missing player is UNKNOWN, not a departure")
    void missingPlayerIsUnknown() {
        when(albion.getPlayer(anyString())).thenReturn(Optional.empty());

        assertThat(service().checkMembership(registration(), tracked())).isEqualTo(MembershipCheck.UNKNOWN);
    }

    @Test
    @DisplayName("a force-registration with no resolved character is UNKNOWN")
    void unresolvedCharacterIsUnknown() {
        Registration forced = new Registration(1L, 100L, "UNRESOLVED:Typo", "Typo");

        // No API call should even be attempted for these.
        assertThat(service().checkMembership(forced, tracked())).isEqualTo(MembershipCheck.UNKNOWN);
    }
}
