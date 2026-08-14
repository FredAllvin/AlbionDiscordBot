package personal.albiondiscordbot.report;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;
import personal.albiondiscordbot.util.Formatting;

/**
 * Renders all balances as a standalone HTML file.
 *
 * <p>No template engine: one report does not justify dragging Thymeleaf and its web
 * infrastructure into a bot that runs no web server.
 *
 * <p>The output is fully self-contained — styles are inlined and there are no external
 * scripts, fonts or images — so it renders identically wherever it is opened.
 */
@Component
public class BalanceHtmlReport {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** Beyond this the file stops being useful to read; truncate with a note. */
    private static final int MAX_ROWS = 50_000;

    /**
     * @param rank 1-based position by balance
     * @param displayName Discord display name, or a raw mention if the member is gone
     * @param albionName registered character name, or null
     * @param amount silver held
     */
    public record Row(int rank, String displayName, String albionName, long amount) {
    }

    public String render(String serverName, List<Row> rows, long totalSilver) {
        StringBuilder body = new StringBuilder();
        int shown = Math.min(rows.size(), MAX_ROWS);

        for (int i = 0; i < shown; i++) {
            Row row = rows.get(i);
            body.append(
                    """
                        <tr>
                          <td class="rank">%d</td>
                          <td>%s</td>
                          <td class="ign">%s</td>
                          <td class="amount">%s</td>
                        </tr>
                    """
                            .formatted(
                                    row.rank(),
                                    // Every one of these is user-controlled text.
                                    Formatting.escapeHtml(row.displayName()),
                                    row.albionName() == null
                                            ? "<span class=\"muted\">not registered</span>"
                                            : Formatting.escapeHtml(row.albionName()),
                                    Formatting.silver(row.amount())));
        }

        String truncationNote =
                rows.size() > MAX_ROWS
                        ? "<p class=\"muted\">Showing the first %s of %s entries.</p>"
                                .formatted(Formatting.silver(MAX_ROWS), Formatting.silver(rows.size()))
                        : "";

        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Balances — %s</title>
                <style>
                  :root { color-scheme: light dark; }
                  body {
                    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
                    margin: 0; padding: 2rem; line-height: 1.5;
                    background: #f6f7f9; color: #1c1e21;
                  }
                  .wrap { max-width: 60rem; margin: 0 auto; }
                  h1 { margin: 0 0 .25rem; font-size: 1.5rem; }
                  .meta { color: #6b7280; font-size: .875rem; margin-bottom: 1.5rem; }
                  .cards { display: flex; gap: 1rem; flex-wrap: wrap; margin-bottom: 1.5rem; }
                  .card {
                    background: #fff; border: 1px solid #e5e7eb; border-radius: .5rem;
                    padding: .75rem 1rem; min-width: 10rem;
                  }
                  .card .label { font-size: .75rem; text-transform: uppercase;
                                 letter-spacing: .05em; color: #6b7280; }
                  .card .value { font-size: 1.25rem; font-weight: 600; font-variant-numeric: tabular-nums; }
                  .table-wrap { overflow-x: auto; background: #fff;
                                border: 1px solid #e5e7eb; border-radius: .5rem; }
                  table { border-collapse: collapse; width: 100%%; }
                  th, td { padding: .5rem .75rem; text-align: left;
                           border-bottom: 1px solid #eef0f3; white-space: nowrap; }
                  th { font-size: .75rem; text-transform: uppercase; letter-spacing: .05em;
                       color: #6b7280; background: #fafbfc; position: sticky; top: 0; }
                  tr:last-child td { border-bottom: none; }
                  .rank { color: #9ca3af; font-variant-numeric: tabular-nums; width: 3rem; }
                  .amount { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
                  .ign { color: #374151; }
                  .muted { color: #9ca3af; font-style: italic; }
                  @media (prefers-color-scheme: dark) {
                    body { background: #16181c; color: #e5e7eb; }
                    .card, .table-wrap { background: #1f2226; border-color: #2f3336; }
                    th { background: #24272b; color: #9ca3af; }
                    th, td { border-color: #2f3336; }
                    .ign { color: #d1d5db; }
                  }
                </style>
                </head>
                <body>
                <div class="wrap">
                  <h1>Balances</h1>
                  <div class="meta">%s &middot; generated %s</div>
                  <div class="cards">
                    <div class="card"><div class="label">Members</div><div class="value">%s</div></div>
                    <div class="card"><div class="label">Total silver</div><div class="value">%s</div></div>
                  </div>
                  %s
                  <div class="table-wrap">
                  <table>
                    <thead>
                      <tr><th>#</th><th>Discord</th><th>In-game name</th><th class="amount">Silver</th></tr>
                    </thead>
                    <tbody>
                %s    </tbody>
                  </table>
                  </div>
                </div>
                </body>
                </html>
                """
                .formatted(
                        Formatting.escapeHtml(serverName),
                        Formatting.escapeHtml(serverName),
                        TIMESTAMP.format(Instant.now()),
                        Formatting.silver(rows.size()),
                        Formatting.silver(totalSilver),
                        truncationNote,
                        body);
    }
}
