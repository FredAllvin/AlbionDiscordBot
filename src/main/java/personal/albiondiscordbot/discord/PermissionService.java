package personal.albiondiscordbot.discord;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.stereotype.Service;
import personal.albiondiscordbot.domain.DiscordGuildConfig;

/**
 * Staff authorisation.
 *
 * <p>Discord's own declarative command permissions cannot express "this custom role
 * stored in our database", so staff checks happen here instead. Server administrators
 * always pass, which keeps the server owner from locking themselves out by
 * misconfiguring or deleting the staff role.
 */
@Service
public class PermissionService {

    public boolean isStaff(Member member, DiscordGuildConfig config) {
        if (member.hasPermission(Permission.ADMINISTRATOR)) {
            return true;
        }
        if (config == null || config.getStaffRoleId() == null) {
            return false;
        }
        long staffRoleId = config.getStaffRoleId();
        return member.getRoles().stream().anyMatch(role -> role.getIdLong() == staffRoleId);
    }

    public boolean isAdministrator(Member member) {
        return member.hasPermission(Permission.ADMINISTRATOR);
    }

    /** @throws CommandException if the member is not staff */
    public void requireStaff(Member member, DiscordGuildConfig config) {
        if (!isStaff(member, config)) {
            throw new CommandException(
                    "You need the staff role to use this command."
                            + (config == null || config.getStaffRoleId() == null
                                    ? " No staff role is configured yet — an administrator should run `/setup`."
                                    : ""));
        }
    }

    /** @throws CommandException if the member is not a server administrator */
    public void requireAdministrator(Member member) {
        if (!isAdministrator(member)) {
            throw new CommandException("Only server administrators can use this command.");
        }
    }
}
