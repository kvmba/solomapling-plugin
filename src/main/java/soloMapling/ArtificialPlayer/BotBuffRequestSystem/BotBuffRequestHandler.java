package soloMapling.ArtificialPlayer.BotBuffRequestSystem;

import org.gms.client.Character;
import org.gms.constants.skills.Assassin;
import org.gms.constants.skills.Bandit;
import org.gms.constants.skills.Bishop;
import org.gms.constants.skills.Bowmaster;
import org.gms.constants.skills.Cleric;
import org.gms.constants.skills.DarkKnight;
import org.gms.constants.skills.FPArchMage;
import org.gms.constants.skills.FPWizard;
import org.gms.constants.skills.Fighter;
import org.gms.constants.skills.Hero;
import org.gms.constants.skills.ILArchMage;
import org.gms.constants.skills.ILWizard;
import org.gms.constants.skills.Marksman;
import org.gms.constants.skills.NightLord;
import org.gms.constants.skills.Paladin;
import org.gms.constants.skills.Priest;
import org.gms.constants.skills.Shadower;
import org.gms.constants.skills.Spearman;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.Environment.SoloMaplingLanguageConfig;
import soloMapling.server.MethodScheduler;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chat-triggered bot buffs. When a real player says "buff", "加buff", "+++" or similar, every
 * nearby bot that is in the player's party and has usable support buffs reacts - after a short
 * delay it faces the player, emotes, maybe says a line, and hands over a version of
 * <b>every support buff its job owns</b>, each at the skill's natural WZ duration (a Bishop gives
 * Maple Warrior + Holy Symbol + Bless, a Spearman gives Hyper Body + Iron Will, ...). The bot is a
 * puppet here: it never reads chat, this handler drives it, so it just looks like the bot reacting
 * to you.
 *
 * <p>Only party members respond - a stranger bot ignores the request. Named requests ("hs", "mw")
 * are intentionally not supported: the ask is always "give me everything you've got".
 */
public final class BotBuffRequestHandler {

    private BotBuffRequestHandler() {}

    // ---- tunables ----
    private static final int PROXIMITY_X = 300;          // "nearby" horizontal reach (px)
    private static final int PROXIMITY_Y = 200;          // vertical reach (px)
    private static final long COOLDOWN_MIN_MS = 30_000;  // per-bot cooldown floor
    private static final long COOLDOWN_MAX_MS = 60_000;  // per-bot cooldown ceiling
    private static final long MIN_DELAY_MS = 3_000;      // reaction delay window (realness)
    private static final long MAX_DELAY_MS = 5_000;
    private static final double SPEAK_CHANCE = 0.85;     // otherwise the bot just grants in silence
    private static final int[] REACT_EMOTES = {1, 2, 5, 6};

    // Generic "give me a buff" triggers. ASCII tokens are matched whole ("buff", "buffs"); a token
    // that is a run of '+' / '＋' and nothing else ("+", "++", "＋＋＋"), or that run followed by a
    // "buff" word ("+buff", "++buffs"), also fires; the CJK phrases are matched as substrings so
    // "给我加buff" / "加个buff" still work.
    private static final Set<String> GENERIC_TOKENS = Set.of("buff", "buffs", "加buff");
    private static final String[] GENERIC_PHRASES = {
            "加buff", "加个buff", "加個buff", "上buff", "上个buff", "来个buff", "来個buff",
            "给个buff", "給個buff", "buff一下", "加一个buff"
    };

    // botId -> epoch ms the bot may buff again. Per-bot; covers all buffs and all players.
    private static final Map<Integer, Long> cooldownUntil = new ConcurrentHashMap<>();

    /** A grantable support buff and the per-branch skill ids that count as "this buff". */
    private record BuffConcept(int[] skillIds) {}

    // Every support buff a bot may hand over, best first. A generic request grants ALL of these
    // that the bot's job actually owns (not just the first). Self-only buffs a kit also contains
    // (Dragon Blood, Shadow Partner, Berserk, boosters, ...) are deliberately absent - they can't
    // be given to another player.
    private static final List<BuffConcept> SUPPORT_BUFFS = new ArrayList<>();

    static {
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{
                Hero.MAPLE_WARRIOR, Paladin.MAPLE_WARRIOR, DarkKnight.MAPLE_WARRIOR,
                FPArchMage.MAPLE_WARRIOR, ILArchMage.MAPLE_WARRIOR, Bishop.MAPLE_WARRIOR,
                Bowmaster.MAPLE_WARRIOR, Marksman.MAPLE_WARRIOR, NightLord.MAPLE_WARRIOR, Shadower.MAPLE_WARRIOR}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Bowmaster.SHARP_EYES, Marksman.SHARP_EYES}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Priest.HOLY_SYMBOL}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Spearman.HYPER_BODY}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Fighter.RAGE}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Cleric.BLESS}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Assassin.HASTE, Bandit.HASTE}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{FPWizard.MEDITATION, ILWizard.MEDITATION}));
        SUPPORT_BUFFS.add(new BuffConcept(new int[]{Spearman.IRON_WILL}));
    }

    /** A concrete buff a specific bot will cast, identified by its resolved skill id. */
    private record Grant(int skillId) {}

    /**
     * Inspect a real player's chat line; if it's a buff request, schedule every nearby party-member
     * bot that can serve it to grant it. No-op for anything that isn't a request. Cheap and safe to
     * call on every general-chat message.
     */
    public static void tryHandle(Character player, String message) {
        if (player == null || BotHelpers.isBot(player)) {
            return; // real players only - bot chat must never feed this
        }
        if (message == null || message.isBlank()) {
            return;
        }
        if (!isBuffRequest(message.trim().toLowerCase())) {
            return;
        }

        // Snapshot where the player asked from - the bot search is anchored here.
        MapleMap map = player.getMap();
        Point pos = player.getPosition();
        if (map == null || pos == null) {
            return;
        }

        for (Character bot : findEligibleBots(map, pos, player)) {
            long delay = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);
            MethodScheduler.runAfterDelay(() -> grant(bot, player), delay);
        }
    }

    /** A line is a buff request if it carries a generic "buff" word or a plus token ("+", "+++", "+buff"). */
    static boolean isBuffRequest(String message) {
        String[] tokens = message.split("\\s+");
        for (String token : tokens) {
            if (GENERIC_TOKENS.contains(token) || isPlusToken(token)) {
                return true;
            }
        }
        for (String phrase : GENERIC_PHRASES) {
            if (message.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a token that starts with a run of '+' / '＋' and is then either bare (any count, so
     * "+", "++", "＋＋＋") or a "buff" word ("+buff", "++buffs", "＋加buff").
     */
    private static boolean isPlusToken(String token) {
        int i = 0;
        while (i < token.length() && (token.charAt(i) == '+' || token.charAt(i) == '＋')) {
            i++;
        }
        if (i == 0) {
            return false; // no leading plus
        }
        String rest = token.substring(i);
        return rest.isEmpty() || rest.equals("buff") || rest.equals("buffs") || rest.equals("加buff");
    }

    private static List<Character> findEligibleBots(MapleMap map, Point pos, Character player) {
        long now = System.currentTimeMillis();
        Integer partyId = player.getParty() != null ? player.getParty().getId() : null;
        List<Character> result = new ArrayList<>();
        if (partyId == null) {
            return result; // must be in a party - nobody's a teammate, nobody answers
        }

        // Scan everyone on the asker's map, not the active-bot registry: inert "!bot spawn"
        // bots live on the map but were never registered (no bot type assigned), so they'd
        // otherwise be invisible here. isBot is id-based, so it catches registered + inert alike.
        for (Character chr : map.getCharacters()) {
            if (chr == null || !BotHelpers.isBot(chr)) {
                continue;
            }
            // Teammates only.
            if (chr.getParty() == null || chr.getParty().getId() != partyId) {
                continue;
            }
            // Only registered bots have a state; an inert spawn is never "busy".
            BotSM bot = CharacterStorage.getBotById(chr.getId());
            if (bot != null && bot.getState() == BotSM.BotState.TRADING) {
                continue; // trading is the only "busy" - attacking / OPQ / idle are all fine
            }
            Long until = cooldownUntil.get(chr.getId());
            if (until != null && now < until) {
                continue; // recently buffed someone
            }
            if (grantsFor(chr).isEmpty()) {
                continue; // job has no support buff to give
            }
            Point bp = chr.getPosition();
            if (bp == null || Math.abs(bp.x - pos.x) > PROXIMITY_X || Math.abs(bp.y - pos.y) > PROXIMITY_Y) {
                continue;
            }
            result.add(chr);
        }
        return result;
    }

    /** Every support buff this bot's job owns, in priority order (empty if it has none). */
    private static List<Grant> grantsFor(Character chr) {
        List<Grant> grants = new ArrayList<>();
        List<Integer> kit = BotBuffConfig.buffsForJob(chr.getJob());
        for (BuffConcept concept : SUPPORT_BUFFS) {
            for (int id : concept.skillIds()) {
                if (kit.contains(id)) {
                    grants.add(new Grant(id));
                    break;
                }
            }
        }
        return grants;
    }

    private static void grant(Character chr, Character player) {
        if (chr == null || chr.getMap() == null || player == null || player.getMap() == null) {
            return;
        }
        List<Grant> grants = grantsFor(chr);
        if (grants.isEmpty()) {
            return;
        }

        // Claim the cooldown atomically so two near-simultaneous requests can't double-grant.
        long now = System.currentTimeMillis();
        synchronized (cooldownUntil) {
            Long until = cooldownUntil.get(chr.getId());
            if (until != null && now < until) {
                return;
            }
            long cooldown = ThreadLocalRandom.current().nextLong(COOLDOWN_MIN_MS, COOLDOWN_MAX_MS + 1);
            cooldownUntil.put(chr.getId(), now + cooldown);
        }

        // Organic reaction: face the asker (if still co-located), emote, maybe say a line, then
        // hand over every support buff this bot owns.
        if (chr.getMap().getId() == player.getMap().getId() && player.getPosition() != null) {
            MovementCommands.botFaceTowardsPoint(chr, player.getPosition());
        }
        SocialCommands.BotEmote(chr, REACT_EMOTES[ThreadLocalRandom.current().nextInt(REACT_EMOTES.length)]);
        // Not every grant gets a line - sometimes the bot just does it, which reads as natural.
        if (ThreadLocalRandom.current().nextDouble() < SPEAK_CHANCE) {
            SocialCommands.BotChatbubble(chr, reactLine());
        }
        for (Grant grant : grants) {
            // Natural buff length: the skill's WZ duration at max level (the same cadence the bot
            // tops itself up on), not the 10-minute GM-command length.
            int durationMs = BotBuffEffects.durationOf(grant.skillId());
            if (durationMs <= 0) {
                continue; // skill has no resolvable duration - skip rather than invent one
            }
            BotBuffEffects.giveExtendedBuff(chr, grant.skillId(), List.of(player), durationMs);
        }
    }

    // Reply pools. The generic "got it" lines are deliberately buff-agnostic, so the whole bundle
    // reads naturally; Chinese and English each carry 24 (>= the 20 asked for).
    private static final String[] REACT_LINES_ZH = {
            "好了！", "没问题~", "搞定！", "来嘞~", "给你加好了", "拿去用吧",
            "收到！", "这就安排~", "好了，收好", "buff来咯", "加好了，冲吧", "嗯，给你了",
            "行，拿好~", "有了！", "妥了", "拿去吧，加油", "好嘞~", "给你满上",
            "OK，加好了", "搞定，走起", "拿好了哈", "已加，注意时间", "来，加好了", "给你挂上了"
    };

    private static final String[] REACT_LINES_EN = {
            "Done!", "No problem~", "All set!", "There you go~", "Here you go!", "Got it!",
            "Sure thing~", "All yours!", "Consider it done!", "Buffed up!", "Enjoy!", "That's you sorted~",
            "Ready to go!", "All done~", "Here's a little something", "Take it~", "You're good to go!",
            "Done and dusted", "Popped it on~", "Happy grinding!", "There we are~", "You got it!",
            "Sorted!", "Have at it!"
    };

    private static String reactLine() {
        String[] pool = SoloMaplingLanguageConfig.isChinese() ? REACT_LINES_ZH : REACT_LINES_EN;
        return pool[ThreadLocalRandom.current().nextInt(pool.length)];
    }

    /** Drop a despawned bot's cooldown entry so the map doesn't grow unbounded. */
    public static void clearBot(int botId) {
        cooldownUntil.remove(botId);
    }
}
