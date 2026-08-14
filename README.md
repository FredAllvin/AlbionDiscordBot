# Albion Discord Bot

A guild-management Discord bot for Albion Online (EU server): silver balances, payouts,
in-game name verification, Disarray lookup, player stats and automatic killboard posts.

Spring Boot 4.1 · Java 21 · JDA 6 · PostgreSQL 17 · Flyway

---

## Setup

### 1. Create the Discord application

1. Go to <https://discord.com/developers/applications> → **New Application**.
2. **Bot** → **Reset Token**, copy it.
3. **Bot → Privileged Gateway Intents**: enable **SERVER MEMBERS INTENT**.
   This is required — without it the bot cannot connect. `/split`, `/payout`, `/role add` and
   `/flush-unregistered` all need to enumerate role members.
4. **OAuth2 → URL Generator**:
   - **Scopes**: tick `bot` **and** `applications.commands`.
     Without `applications.commands` the bot joins but no slash commands ever appear.
   - **Bot Permissions**: tick **View Channels**, **Send Messages**, **Embed Links**,
     **Attach Files**, **Manage Roles**.

5. **Invite it.** A **Generated URL** box appears at the bottom of that page — copy it
   and open it in a browser. Pick your server from the dropdown, **Continue** →
   **Authorize** → captcha.

   You need **Manage Server** on the target server to add a bot. If your server is not
   in the dropdown, that permission is why.

   Instead of the generator you can paste this, replacing `YOUR_APP_ID` with the
   **Application ID** from the **General Information** page:

   ```
   https://discord.com/api/oauth2/authorize?client_id=YOUR_APP_ID&permissions=268487680&scope=bot%20applications.commands
   ```

   `268487680` is exactly the five permissions above and nothing more.

6. **Expect it to be offline.** The bot appears in the member list greyed out until you
   actually run it in step 4 below — inviting it does not start it. Slash commands only
   register once it connects for the first time, so `/setup` will not exist until then.

7. In **Server Settings → Roles**, drag the bot's role **above** any role it must assign
   (the verified role, and any roles you use with `/role add`). Discord refuses to let a
   bot touch a role positioned above its own, no matter what permissions it has.

### 2. Configure secrets

```bash
cp .env.example .env
```

Fill in `BOT_TOKEN` and set a `POSTGRES_PASSWORD`. `.env` is gitignored — never commit it.

### 3. Start the database

```bash
docker compose up -d
```

Postgres 17 on `127.0.0.1:5432`, data in the `albion-pgdata` volume. Flyway creates the
schema on first run of the bot.

### 4. Run the bot

```bash
# Windows PowerShell
.\mvnw.cmd spring-boot:run

# bash
set -a && . ./.env && set +a && ./mvnw spring-boot:run
```

The app reads configuration from environment variables. In an IDE, put `BOT_TOKEN`,
`POSTGRES_USER` and `POSTGRES_PASSWORD` in the run configuration.

### 5. Configure the server

```
/setup staff_role:@Officer verified_role:@Member killboard_channel:#killboard
/guild add <your exact in-game guild name>
```

Then members can `/register <their character name>`.

---

## Commands

| Command | Who | What |
|---|---|---|
| `/setup` | Admin | Staff role, verified role, log + killboard channels, CTA threshold |
| `/disarray <level>` | Everyone | How many players a Disarray level means, e.g. `45` → `154-159` |
| `/register <name>` | Everyone | Claim a character; verified against the live API |
| `/unregister [@user]` | Self / staff | Remove a registration |
| `/force-register @user <name>` | Staff | Register without verifying guild membership |
| `/flush-unregistered [confirm] [recheck_guild]` | Staff | Audit verified-role holders. **Dry run unless `confirm: true`** |
| `/guild add\|remove\|list` | Staff | Which in-game guilds count as yours |
| `/balance check [@user]` | Everyone / staff | Show a balance |
| `/balance add\|remove\|reset @user <amount>` | Staff | Adjust a balance |
| `/balance give @user <amount>` | Everyone | Transfer your own silver |
| `/balance history [@user]` | Self / staff | Last 15 changes, with who made them and why |
| `/balance stats [public]` | Staff | All balances as an HTML attachment |
| `/role add <name> @user…` | Staff | Create a role and add everyone mentioned |
| `/split @role <amount>` | Staff | Credit a share of loot to **each** member's balance |
| `/split-cta <amount> [battle]` | Staff | Credit everyone who fought, from killboard data |
| `/payout user:@member` | Staff | **Cash out** one member — send their balance, then clear it |
| `/payout role:@role` | Staff | **Cash out** everyone in a role |
| `/undo <batch>` | Staff | Reverse a split or cashout by its batch id |
| `/stats [@user]` | Everyone | Kills, deaths, fame, CTA attendance |
| `/status` | Staff | Poller health, config checklist, totals |

Amounts accept `1m`, `1.5m`, `500k`, `1kk`, `1,000,000` and `1000000`. The parsed value
is always echoed back.

### Split and payout run in opposite directions

**`/split` puts silver on the books.** A share of loot is credited to each member's
balance, and the guild now owes it. It is per person, not a total to divide — 15 members
at `1m` adds 15,000,000 to what you owe.

**`/payout` takes silver off the books.** This is a **cashout**: the member is handed
their balance in game, and the bot then clears it to zero. Nothing is sent automatically —
the bot cannot make an in-game trade — so the order is: copy the list, send the silver,
*then* confirm. Confirming is an officer stating the transfer happened.

`/payout` takes **no amount**. A cashout settles the whole debt, so the balance *is* the
amount; passing a number would only let it disagree with the ledger. Use `user:` for one
person or `role:` for a group — one or the other, not both.

A typical run:

```
/role add zvz-2026-08-14 @a @b @c     ← tag who showed up
/split @zvz-2026-08-14 6m             ← +6m each, guild now owes it
/balance add @a 4m reason:hellgate    ← other silver lands too
/payout user:@a                       ← preview shows 10,000,000 owed
                                        copy → send in game → confirm
```

**Crediting a CTA** normally needs no role at all — `/split-cta 1m` credits everyone the
poller recorded in the most recent tracked CTA. Pass a battle id (shown in the footer of
every killboard post) for an older one. `/role add` + `/split` is still there for splits
that don't map to a single fight.

### Both ask before they move anything

`/split`, `/split-cta` and `/payout` all show a private preview — who is affected and
what each balance becomes — with **Confirm**, **Deny** and **📋 Copy list**. Nothing moves
until Confirm, and only whoever ran the command can press it. The result is then
announced publicly.

**📋 Copy list** prints character name and full balance owed as a code block, which
Discord renders with its own copy button. This is the list an officer types from while
sending silver in game, so it shows the *total* owed rather than one split's share, and
leaves out anyone owed nothing. Long lists come back as a `.txt` file instead.

**Everything is reversible.** Both operations print a batch id; `/undo <batch>` reverses
either. Undoing a split takes the silver back; undoing a cashout puts it back on the
books, for when the in-game trade never actually happened. Reversal is once-only, and if
someone already spent the silver their balance goes negative rather than the reversal
quietly returning less than it took.

---

## How things work, and what they do not mean

**Registration is not proof of identity.** `/register` confirms a character exists and
is in a tracked guild. It cannot confirm the Discord user owns it. Impersonation is
handled after the fact with `/unregister`; `registered_by` and `forced` are recorded for
audit.

**A CTA has to satisfy two conditions at once** — it was a big fight, *and* enough of
your own were in it:

```
/setup cta_min_total_players:30 cta_min_guild_players:10
```

| Fight | Ours | CTA? |
|---|---|---|
| 200 players | 45 | ✅ |
| 200 players | 3 | ❌ swept into someone else's zerg |
| 12 players | 11 | ❌ ganking, not a call to arms |
| 40 players | 15 | ✅ |

Either test on its own lets the wrong thing through, which is why both are required. The
same pair drives killboard posts, `/stats` attendance and `/split-cta`, so nothing can
disagree about what counted.

**CTA attendance is a lower bound.** The Albion API only lists players who scored a
kill, died, or earned assist fame in a battle. Someone who showed up and contributed
nothing does not appear.

**Stats cannot reach back before the bot's first run.** The battles API retains roughly
one month and the poller only sees battles while running, so `/stats` reports a
"tracked since" date rather than implying lifetime coverage.

**`/stats` shows two different things.** "In tracked battles" comes from battle
participation; "All activity" is the difference between the fame snapshot taken at
registration and the live profile. Battle kill fame is a *subset* of total kill fame —
the two are shown separately and must never be added.

**Disarray gives a range, not an exact headcount.** The published table only lists where
each level begins, so level 45 means "somewhere between 154 and 159 players". Levels get
wider as they climb — level 66 spans 412-444 — and level 67 is open-ended at 445+.

The thresholds come from the wiki (Version 22.090.1) and are not arithmetic: there is no
level whose minimum is 35 players, so a 35-player group is still level 14. The lookup
scans the published table rather than computing from a formula.

---

## Running it somewhere always-on

The bot has to stay running. Slash commands need a live gateway connection, and the
battle poller only sees fights while it is up.

**Everything the bot has already seen is stored permanently** in your Postgres database.
Battles and attendance are never expired or pruned, so `/stats` covers a player's whole
history since they registered, however far back that goes.

The 24-hour figure is about how far the bot can *look*, not how long it *keeps* things.
The poller reads `/battles?range=day`, which reaches back one day and no further. Each
run pages back to just before its last success, so:

| Outage | Result |
|---|---|
| minutes to hours | fully caught up on the next poll |
| up to ~24 hours | fully caught up, the first poll just digs deeper |
| beyond 24 hours | the excess is **gone for good** — the API no longer has it |

A CTA that starts and finishes inside a gap longer than a day is never recorded, and
`/stats` attendance and `/split-cta` will never know it happened. Everything before and
after the gap is unaffected.

### Preparing a fresh server

On a clean Debian or Ubuntu box, all it needs is Docker and git:

```bash
sudo apt update && sudo apt install -y git
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # then log out and back in
```

**If the server has 1 GB of RAM, add swap before building.** Compiling the project is
far more memory-hungry than running it, and the build gets OOM-killed without headroom:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

No firewall rules are needed for the bot. It makes only outbound connections, and
Postgres is bound to `127.0.0.1` so it is never reachable from the internet.

### Deploy with Docker

Everything is already containerised. On the server:

```bash
git clone https://github.com/FredAllvin/AlbionDiscordBot.git
cd AlbionDiscordBot
cp .env.example .env
nano .env                 # real BOT_TOKEN, and a generated POSTGRES_PASSWORD
docker compose --profile full up -d --build
```

Generate the database password rather than inventing one — nothing needs to memorise it:

```bash
openssl rand -base64 24
```

Use an editor for `.env` rather than `echo`, so the token never lands in your shell
history.

That runs Postgres and the bot together. `restart: unless-stopped` brings both back
after a crash or a reboot, so nothing needs babysitting.

```bash
docker compose logs -f bot          # watch it
docker compose --profile full up -d --build   # deploy an update
docker compose down                # stop everything
```

The `full` profile is what separates the two modes — plain `docker compose up -d` still
starts Postgres alone, which is what you want locally while running the bot from the IDE.

### What it needs

**2 GB RAM is comfortable, 1 GB is tight.** The JVM is told to size its heap from the
container limit rather than the host's RAM, so it will not get OOM-killed on a small box.
CPU is near-idle between polls; disk growth is slow and dominated by battle attendance.

Reasonable options, cheapest first:

- **Oracle Cloud always-free tier** — ARM Ampere, genuinely free, no time limit. Signup
  and capacity can be awkward. Works here: both `eclipse-temurin` and `postgres` publish
  arm64 images, so the build needs no changes.
- **Hetzner / DigitalOcean / Vultr VPS** — roughly €4–6 a month for 2 vCPU and 4 GB.
  The least fuss.
- **A Raspberry Pi 4 or 5 at home** — fine, and arm64 works, but the bot is then only as
  reliable as your power and internet.

Whatever you pick, it just needs Docker. No Java or Postgres install required.

### Back up the database

This database holds your guild's silver, and a Docker volume is not a backup. A nightly
dump is enough:

```bash
docker exec albionbot-postgres pg_dump -U albionbot albionbot \
  | gzip > "albionbot-$(date +%F).sql.gz"
```

Wire it up as a nightly cron job (`crontab -e`), keeping 14 days. Run
`mkdir -p /root/backups` first:

```cron
PATH=/usr/local/bin:/usr/bin:/bin
0 4 * * * docker exec albionbot-postgres pg_dump -U albionbot albionbot | gzip > /root/backups/albionbot-$(date +\%F).sql.gz && find /root/backups -name '*.sql.gz' -mtime +14 -delete
```

Three details, each a way this silently writes nothing for months:

- **Absolute paths, not `~`.** Cron's shell does not reliably expand it.
- **The `PATH=` line.** Cron's default PATH is bare, so `docker` is often not found even
  though it works interactively.
- **`\%` must be escaped.** Cron reads a bare `%` as a newline and truncates the command.

Confirm the next morning that a dated file actually appeared — cron fails silently.

Verify a dump is real rather than an empty file (11 = 10 tables plus Flyway's own):

```bash
zcat /root/backups/albionbot-*.sql.gz | grep -c 'CREATE TABLE'   # expect 11
```

Copy the files off the machine periodically — a backup that only exists on the server it
protects is not a backup. To restore:

```bash
gunzip -c albionbot-2026-08-14.sql.gz | docker exec -i albionbot-postgres psql -U albionbot albionbot
```

---

## Development

```bash
./mvnw test          # 128 tests; integration tests start Postgres via Testcontainers
./mvnw -q compile
```

Integration tests use a real Postgres container, not H2 — the schema depends on partial
unique indexes, `ON CONFLICT … RETURNING (xmax = 0)` and `timestamptz`, all of which H2's
compatibility mode gets wrong.

Notable tests:
- `BalanceServiceConcurrencyTest` — 2,000 concurrent credits sum exactly; bidirectional
  transfers conserve silver and never deadlock.
- `UndoBatchTest` — a reversal moves exactly what the batch moved, only once, in the
  right direction for both splits and cashouts, and cannot reach another server's batch.
- `BatchConfirmationServiceTest` — previews move nothing; cashouts clear balances and
  skip anyone owed nothing; button ids stay inside Discord's 100-character limit.
- `BattleIngestServiceTest` — re-ingesting a battle cannot inflate anyone's stats.
- `AlbionDtoDeserializationTest` — runs against captured real API payloads.
- `DisarrayServiceTest` — every published threshold, including the gaps.

Schema changes go in `src/main/resources/db/migration` as a new `V<n>__*.sql`.
`ddl-auto` is `validate`; Flyway owns the schema because this database holds silver.
