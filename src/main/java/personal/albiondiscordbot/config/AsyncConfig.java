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
}
