package personal.albiondiscordbot.discord.commands;

import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.discord.CommandContext;
import personal.albiondiscordbot.discord.SlashCommand;
import personal.albiondiscordbot.service.DisarrayService;

/**
 * {@code /disarray <level>} — turns a Disarray level into a headcount.
 *
 * <p>You can read the enemy's Disarray level off their debuff in game; what you actually
 * want to know is how many of them there are. A level maps to a range rather than an
 * exact number, because the published table only lists where each level begins.
 */
@Component
public class DisarrayCommand implements SlashCommand {

    private final DisarrayService disarray;

    public DisarrayCommand(DisarrayService disarray) {
        this.disarray = disarray;
    }

    @Override
    public String name() {
        return "disarray";
    }

    @Override
    public boolean requiresSetup() {
        // Pure lookup against a static table: useful even before /setup has run.
        return false;
    }

    @Override
    public boolean ephemeral() {
        return false;
    }

    @Override
    public SlashCommandData definition() {
        return Commands.slash("disarray", "Convert a Disarray level into how many players that is")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(new OptionData(
                                OptionType.INTEGER, "level", "The Disarray level shown in game", true)
                        .setMinValue(0)
                        .setMaxValue(DisarrayService.MAX_LEVEL));
    }

    @Override
    public void execute(SlashCommandInteractionEvent event, CommandContext context) {
        int level = event.getOption("level", OptionMapping::getAsInt);
        DisarrayService.PlayerRange range = disarray.playerRangeForLevel(level);

        if (level == 0) {
            event.getHook()
                    .sendMessageEmbeds(new EmbedBuilder()
                            .setTitle("Disarray 0")
                            .setColor(new Color(0x2ECC71))
                            .setDescription("**Under %d players.** Disarray has not kicked in yet."
                                    .formatted(disarray.minGroupSizeForLevel(1)))
                            .build())
                    .queue();
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Disarray %d  =  %s players".formatted(level, range.display()))
                .setColor(colorFor(level))
                .setDescription(
                        range.openEnded()
                                ? "**%d or more players.** This is the highest published level, so it cannot narrow further."
                                        .formatted(range.min())
                                : "A group of **%s** players sits at Disarray level **%d**."
                                        .formatted(range.display(), level));

        embed.addField("Players", range.display(), true);
        embed.addField("Max debuff", level + "%", true);

        embed.setFooter(
                "Disarray cuts damage and CC duration vs players by the level difference, "
                        + "so level " + level + " only means -" + level + "% against a group with no Disarray. "
                        + "Homesick adds a further +" + DisarrayService.HOMESICK_BONUS + " points.");

        event.getHook().sendMessageEmbeds(embed.build()).queue();
    }

    private Color colorFor(int level) {
        if (level < 15) {
            return new Color(0xF1C40F);
        }
        if (level < 30) {
            return new Color(0xE67E22);
        }
        return new Color(0xE74C3C);
    }
}
