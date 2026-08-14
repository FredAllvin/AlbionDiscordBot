package personal.albiondiscordbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlbionDiscordBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlbionDiscordBotApplication.class, args);
    }

}
