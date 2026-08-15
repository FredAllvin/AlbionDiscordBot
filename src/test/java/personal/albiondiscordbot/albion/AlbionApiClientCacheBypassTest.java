package personal.albiondiscordbot.albion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import personal.albiondiscordbot.config.AlbionProperties;

/**
 * Every request must miss the API's shared cache.
 *
 * <p>Measured against the live EU host: {@code /players/{id}} and {@code /search} carry
 * {@code max-age=600} and were seen answering with {@code Age} up to 160s, while
 * {@code /battles} was seen answering {@code Age: 28954} — eight hours stale. That made
 * {@code /register} judge guild membership on ten-minute-old data, and made the poller
 * think it had paged far enough back when it had not. A unique throwaway query parameter
 * is what avoids it; a {@code Cache-Control: no-cache} request header does nothing.
 *
 * <p>These assert on the URI the client actually builds, because that parameter going
 * missing would be completely silent — everything keeps working, just on stale data.
 */
class AlbionApiClientCacheBypassTest {

    /** Deliberately contains the '-' and '_' that Albion ids really use. */
    private static final String PLAYER_ID = "d6HcQHoTSH-2qY1oYVNjEQ";

    private final List<URI> requested = new ArrayList<>();

    private AlbionApiClient clientWith(boolean bypassCache) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(manyTimes(), request -> requested.add(request.getURI()))
                .andRespond(request -> MockRestResponseCreators.withSuccess(
                                request.getURI().getPath().endsWith("/battles") ? "[]" : "{}",
                                MediaType.APPLICATION_JSON)
                        .createResponse(request));

        AlbionProperties properties = new AlbionProperties(
                new AlbionProperties.Api(
                        "https://albion.test/api/gameinfo",
                        "test-agent",
                        // No spacing: these tests make several calls and the real 500ms
                        // gap would only make them slow.
                        Duration.ZERO,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(20),
                        bypassCache),
                null,
                "https://albion.test/battles/%d");

        return new AlbionApiClient(builder, properties);
    }

    /** Empty rather than null, since a request with no parameters at all has no query. */
    private List<String> queries() {
        return requested.stream()
                .map(uri -> uri.getQuery() == null ? "" : uri.getQuery())
                .toList();
    }

    @Test
    @DisplayName("every endpoint carries a cache-missing parameter")
    void allEndpointsBypassTheCache() {
        AlbionApiClient client = clientWith(true);

        client.getPlayer(PLAYER_ID);
        client.getGuild("GUILD_ID");
        client.search("Bogul");
        client.getBattles("day", AlbionApiClient.MAX_BATTLE_PAGE_SIZE, 0);

        assertThat(requested).hasSize(4);
        assertThat(queries()).allSatisfy(query -> assertThat(query).contains("_="));
    }

    @Test
    @DisplayName("the parameter differs every time, or the cache would just store that too")
    void theBusterIsNeverReused() {
        AlbionApiClient client = clientWith(true);

        for (int i = 0; i < 25; i++) {
            client.getPlayer(PLAYER_ID);
        }

        List<String> busters = queries().stream()
                .map(query -> query.substring(query.indexOf("_=") + 2))
                .toList();

        assertThat(busters).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the player id still reaches the path intact")
    void pathVariableSurvivesTheRewrite() {
        // The cache-buster moved these calls from uri(template, arg) onto the builder, so
        // an id containing '-' or '_' getting mangled is a live risk worth pinning.
        clientWith(true).getPlayer(PLAYER_ID);

        assertThat(requested).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).isEqualTo("/api/gameinfo/players/" + PLAYER_ID);
            assertThat(uri.getQuery()).startsWith("_=");
        });
    }

    @Test
    @DisplayName("the existing query parameters are still there alongside it")
    void realParametersAreNotLost() {
        clientWith(true).getBattles("day", 51, 102);

        assertThat(requested).singleElement().satisfies(uri -> assertThat(uri.getQuery())
                .contains("range=day")
                .contains("limit=51")
                .contains("offset=102")
                .contains("sort=recent")
                .contains("_="));
    }

    @Test
    @DisplayName("bypass-cache=false leaves the requests alone")
    void canBeSwitchedOff() {
        AlbionApiClient client = clientWith(false);

        client.getPlayer(PLAYER_ID);
        client.getBattles("day", 51, 0);

        assertThat(queries()).allSatisfy(query -> assertThat(query).doesNotContain("_="));
    }
}
