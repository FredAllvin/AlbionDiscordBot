package personal.albiondiscordbot.config;

import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JdaConfig {

    /**
     * Builds and connects the JDA client.
     *
     * <p>Listeners are injected as ordinary beans, so Spring constructs them before
     * this bean and no circular wiring is needed.
     *
     * <p>Deliberately does <em>not</em> call {@code awaitReady()}: that would block
     * context refresh on Discord being reachable, turning a Discord outage into a
     * startup failure. Work that needs a live connection hangs off {@code ReadyEvent}
     * instead — see {@code CommandRegistrar}.
     *
     * <p>{@code GUILD_MEMBERS} is a <strong>privileged intent</strong>. It must be
     * enabled under Bot → Privileged Gateway Intents in the Discord Developer Portal
     * or the bot will fail to connect. It is required because {@code /payout},
     * {@code /role add} and {@code /flush-unregistered} all enumerate role members.
     */
    @Bean(destroyMethod = "shutdown")
    public JDA jda(BotProperties properties, List<EventListener> listeners) {
        return JDABuilder.createLight(properties.token(), GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .setActivity(Activity.watching("Albion Online"))
                .addEventListeners(listeners.toArray())
                .build();
    }
}
