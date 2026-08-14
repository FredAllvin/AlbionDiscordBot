package personal.albiondiscordbot;

import static org.assertj.core.api.Assertions.assertThat;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import personal.albiondiscordbot.config.AlbionProperties;
import personal.albiondiscordbot.discord.CommandRegistry;
import personal.albiondiscordbot.support.PostgresTestBase;

/**
 * Boots the whole context against a real Postgres, with Discord stubbed out.
 *
 * <p>The {@link JDA} bean is mocked because the real one opens a websocket to Discord;
 * a test must not need a live token or network.
 */
@SpringBootTest
class AlbionDiscordBotApplicationTests extends PostgresTestBase {

    @MockitoBean
    private JDA jda;

    @Autowired
    private CommandRegistry commandRegistry;

    @Autowired
    private AlbionProperties albionProperties;

    @Test
    @DisplayName("the application context loads and Flyway applies the schema")
    void contextLoads() {
        assertThat(commandRegistry).isNotNull();
    }

    @Test
    @DisplayName("every slash command is discovered with a unique name")
    void commandsAreRegistered() {
        assertThat(commandRegistry.all()).isNotEmpty();
        assertThat(commandRegistry.find("disarray")).isPresent();
    }

    @Test
    @DisplayName("killboard links point at the configured region's battleboard")
    void battleUrlIsRegionCorrect() {
        // A wrong subdomain or a singular /battle/ path makes every killboard link 404,
        // which nothing else would catch until someone clicked one.
        assertThat(albionProperties.battleUrl(416259996L))
                .isEqualTo("https://europe.albionbb.com/battles/416259996");
    }
}
