package personal.albiondiscordbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import personal.albiondiscordbot.albion.dto.AlbionBattle;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.domain.DiscordGuildConfig;
import personal.albiondiscordbot.domain.KillboardPost;
import personal.albiondiscordbot.domain.TrackedAlbionGuild;
import personal.albiondiscordbot.repository.DiscordGuildConfigRepository;
import personal.albiondiscordbot.repository.KillboardPostRepository;
import personal.albiondiscordbot.repository.TrackedAlbionGuildRepository;

/**
 * A killboard Discord did not take is posted on the next poll rather than lost.
 *
 * <p>The old order wrote the {@code killboard_post} row and then called {@code queue()},
 * which hands the request off and returns. A rejection — a missing Send Messages
 * permission, a rate limit, a deploy landing between the two — therefore arrived on a JDA
 * thread well after the row was committed, and nothing revisits a battle that is already
 * marked posted. Worse, the {@code catch} around the send could never see any of it. All
 * of those failures looked identical from the channel: no embed, no retry, and a CTA
 * nobody was told about.
 *
 * <p>The fixture is battle 418975628 (368 players, 34 Dumbo Elephants, 17 August 2026),
 * the fight that sent us looking. That one was actually lost to a poller fix that had not
 * been deployed rather than to a refused send, but it is the size of fight this path has
 * to survive, so it is the one worth testing against.
 */
class KillboardRetryTest {

    private static final String OUR_GUILD = "d6HcQHoTSH-2qY1oYVNjEQ";
    private static final String ENEMY_GUILD = "964QJqZuS5-jAigA56FYxw";
    private static final long DISCORD_GUILD = 1234567890L;
    private static final long CHANNEL = 555L;
    private static final long BATTLE = 418975628L;
    private static final long MESSAGE = 998877L;

    private ObjectProvider<JDA> jdaProvider;
    private KillboardPostRepository posts;
    private TextChannel channel;
    private MessageCreateAction action;
    private Message message;
    private KillboardService killboards;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        DiscordGuildConfigRepository configs = mock(DiscordGuildConfigRepository.class);
        TrackedAlbionGuildRepository trackedGuilds = mock(TrackedAlbionGuildRepository.class);
        posts = mock(KillboardPostRepository.class);

        jdaProvider = mock(ObjectProvider.class);
        JDA jda = mock(JDA.class);
        channel = mock(TextChannel.class);
        action = mock(MessageCreateAction.class);
        message = mock(Message.class);

        when(jdaProvider.getIfAvailable()).thenReturn(jda);
        when(jda.getTextChannelById(CHANNEL)).thenReturn(channel);
        when(channel.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(action);
        when(message.getIdLong()).thenReturn(MESSAGE);
        when(action.complete()).thenReturn(message);

        DiscordGuildConfig config = new DiscordGuildConfig(DISCORD_GUILD);
        config.setKillboardChannelId(CHANNEL);
        when(configs.findById(DISCORD_GUILD)).thenReturn(Optional.of(config));
        when(trackedGuilds.findDiscordGuildIdsTracking(any())).thenReturn(List.of(DISCORD_GUILD));
        when(trackedGuilds.findByDiscordGuildId(DISCORD_GUILD))
                .thenReturn(List.of(new TrackedAlbionGuild(DISCORD_GUILD, OUR_GUILD, "Dumbo Elephants", 1L)));

        killboards = new KillboardService(
                jdaProvider,
                configs,
                trackedGuilds,
                posts,
                new AlbionProperties(null, null, "https://europe.albionbb.com/battles/%d"));
    }

    /** JDA surfaces a refused send as an unchecked exception out of {@code complete()}. */
    private static RuntimeException refused() {
        return new IllegalStateException("Missing Permissions");
    }

    private KillboardPost captureSaved() {
        ArgumentCaptor<KillboardPost> saved = ArgumentCaptor.forClass(KillboardPost.class);
        verify(posts).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("a send Discord refuses records nothing")
    void refusedSendRecordsNothing() {
        when(action.complete()).thenThrow(refused());

        killboards.onBattleFinalized(battle());

        verify(channel).sendMessageEmbeds(any(MessageEmbed.class));
        verify(posts, never()).save(any());
    }

    @Test
    @DisplayName("the next poll posts what Discord refused the first time")
    void theNextPollRetries() {
        when(action.complete()).thenThrow(refused()).thenReturn(message);

        killboards.onBattleFinalized(battle()); // refused
        killboards.onBattleFinalized(battle()); // the poller offers it again a minute later

        verify(channel, times(2)).sendMessageEmbeds(any(MessageEmbed.class));
        assertThat(captureSaved().getMessageId())
                .as("recorded once, on the attempt that actually landed")
                .isEqualTo(MESSAGE);
    }

    @Test
    @DisplayName("a post that lands records the id Discord gave it")
    void successRecordsTheRealMessageId() {
        killboards.onBattleFinalized(battle());

        KillboardPost saved = captureSaved();
        assertThat(saved.getMessageId())
                .as("stored as null before, which left nothing to edit or link back to")
                .isEqualTo(MESSAGE);
        assertThat(saved.getKey().getAlbionBattleId()).isEqualTo(BATTLE);
        assertThat(saved.getKey().getDiscordGuildId()).isEqualTo(DISCORD_GUILD);
    }

    @Test
    @DisplayName("a battle already posted is not posted a second time")
    void alreadyPostedIsNotRepeated() {
        when(posts.existsByKey(any())).thenReturn(true);

        killboards.onBattleFinalized(battle());

        verify(channel, never()).sendMessageEmbeds(any(MessageEmbed.class));
        verify(posts, never()).save(any());
    }

    @Test
    @DisplayName("nothing is recorded while the bot is still connecting")
    void noGatewayYetRecordsNothing() {
        when(jdaProvider.getIfAvailable()).thenReturn(null);

        killboards.onBattleFinalized(battle());

        verify(posts, never()).save(any());
    }

    @Test
    @DisplayName("a fight too small to be a CTA is still never posted")
    void belowThresholdIsNotPosted() {
        // Every one of ours turned up and nobody else did: our own turnout clears its bar,
        // total size does not. The cheap already-posted guard runs first now, so this
        // covers that reordering not having quietly dropped the thresholds.
        killboards.onBattleFinalized(battle(12, 0));

        verify(channel, never()).sendMessageEmbeds(any(MessageEmbed.class));
        verify(posts, never()).save(any());
    }

    /** Battle 418975628 as the API reported it. */
    private static AlbionBattle battle() {
        return battle(34, 334);
    }

    private static AlbionBattle battle(int ourCount, int enemyCount) {
        Map<String, AlbionBattle.Participant> players = new LinkedHashMap<>();
        for (int i = 0; i < ourCount; i++) {
            players.put("u" + i, participant("u" + i, OUR_GUILD, "Dumbo Elephants"));
        }
        for (int i = 0; i < enemyCount; i++) {
            players.put("e" + i, participant("e" + i, ENEMY_GUILD, "SZKODA OC"));
        }

        Map<String, AlbionBattle.Side> guilds = new LinkedHashMap<>();
        guilds.put(
                OUR_GUILD,
                new AlbionBattle.Side(
                        OUR_GUILD, "Dumbo Elephants", 97, 21, 45_589_632L, "MASS", "VWV1nSFRQwOtmgFW04ZO7w"));
        if (enemyCount > 0) {
            guilds.put(
                    ENEMY_GUILD,
                    new AlbionBattle.Side(
                            ENEMY_GUILD, "SZKODA OC", 110, 212, 46_910_000L, "PIEC", "t9BnvjYGSraBSDTNcmCi9g"));
        }

        return new AlbionBattle(
                BATTLE,
                Instant.parse("2026-08-17T18:45:25Z"),
                Instant.parse("2026-08-17T19:10:40Z"),
                Instant.parse("2026-08-17T19:13:40Z"),
                192_512_893L,
                417,
                null,
                players,
                guilds,
                Map.of());
    }

    private static AlbionBattle.Participant participant(String id, String guildId, String guildName) {
        return new AlbionBattle.Participant(id, "p" + id, 1, 1, 500_000L, guildId, guildName, null, null);
    }
}
