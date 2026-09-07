package personal.albiondiscordbot.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class AsyncConfig {

    /**
     * Runs slash-command bodies off JDA's gateway threads. Bounded on purpose: if commands
     * back up this far, something is wrong upstream and queueing forever only hides it.
     *
     * <p>Rejects rather than running the task on the calling thread. The caller here
     * <em>is</em> a JDA gateway thread, so caller-runs would do the one thing the whole
     * hand-off exists to prevent — block the gateway and stall every server's commands at
     * exactly the moment the bot is already struggling. The listeners catch the rejection
     * and tell the user the bot is busy, which is a far better outcome than a frozen bot.
     */
    @Bean("commandExecutor")
    public Executor commandExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("cmd-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Runs autocomplete lookups, off the gateway threads and off {@link #commandExecutor()}.
     *
     * <p>Its own pool because the two have opposite shapes. A slash command may spend
     * twenty seconds inside the Albion API while the caller watches a "thinking"
     * placeholder; an autocomplete fires once per keystroke, cannot be deferred, and is
     * discarded by Discord after three seconds. Sharing a queue would put the one with a
     * deadline behind up to two hundred of the ones without — at exactly the moment
     * somebody is typing, since a busy bot is a bot people are using.
     */
    @Bean("autoCompleteExecutor")
    public Executor autoCompleteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        // Short on purpose. A suggestion that cannot be delivered inside three seconds is
        // worth less than the queue slot it is sitting in.
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("ac-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
