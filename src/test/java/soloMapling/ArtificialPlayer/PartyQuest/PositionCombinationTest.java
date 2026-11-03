package soloMapling.ArtificialPlayer.PartyQuest;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotTypes.Amoria.AmoriaPqData;
import soloMapling.ArtificialPlayer.BotTypes.Kerning.KerningPqData;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the honest search for body-placement puzzles: a quest answers only yes/no, so a candidate
 * may leave the space only when a talk provably judged it wrong - never merely because the bots
 * stood somewhere.
 *
 * <p>The decision function is the entire safety story: a wrong retirement is unrecoverable,
 * because the pattern it removes is the answer and nothing else can bring it back.
 */
class PositionCombinationTest {

    private static final long SETTLE = 2_500L;
    /** A five-area pattern with three bodies. */
    private static final long THREE = PositionCombination.maskToPattern(0b10101);
    private static final int THREE_BODIES = PqActions.bodiesOf(THREE);

    private static long judge(long presented, int bodies, long standingSince, boolean judgedBefore,
                              boolean cleared) {
        return PositionCombination.judgedPattern(presented, bodies,
                PqActions.bodiesOf(presented), standingSince, SETTLE, judgedBefore, cleared);
    }

    @Test
    void aJudgedMissRetiresExactlyThePatternThatStood() {
        assertEquals(THREE, judge(THREE, THREE_BODIES, 9_999L, true, false));
    }

    @Test
    void theIntroTalkNeverRetiresAnything() {
        // The first talk only explains the rules; treating it as a verdict would delete a
        // candidate that was never judged - and if that candidate is the answer, the stage can
        // never be finished.
        assertEquals(-1, judge(THREE, THREE_BODIES, 9_999L, false, false));
    }

    @Test
    void theWinningTalkNeverRetiresAnything() {
        assertEquals(-1, judge(THREE, THREE_BODIES, 9_999L, true, true));
    }

    @Test
    void anExtraBodyInAnOccupiedAreaIsNotAVerdictOnThePattern() {
        // The pattern is unchanged (both bodies are in one area), but the scripts count bodies:
        // this talk answered "too many", not "wrong combination". Retiring the pattern here
        // would throw away the right answer for a mistake a human made.
        assertEquals(-1, judge(THREE, THREE_BODIES + 1, 9_999L, true, false));
        assertEquals(-1, judge(THREE, THREE_BODIES - 1, 9_999L, true, false));
    }

    @Test
    void aPatternStillMovingIsNotWhatTheTalkJudged() {
        // The settle window is measured from when the party was first seen ON the pattern. A talk
        // that lands while the bots are still walking there judged whatever was standing then.
        assertEquals(-1, judge(THREE, THREE_BODIES, 100L, true, false),
                "the pattern has not stood still long enough");
        assertEquals(THREE, judge(THREE, THREE_BODIES, SETTLE, true, false),
                "settled at exactly the window");
    }

    @Test
    void aTalkBeforeTheStageAnsweredJudgesNothing() {
        // The load-bearing guard against the leave-map NPC: every finished conversation advances
        // the leader's clock, so the clock alone is not a verdict. The gate is "the stage has
        // published its answer", which only the stage NPC's intro talk does.
        for (long waited = 0; waited < 60_000; waited += 5_000) {
            assertEquals(-1, judge(THREE, THREE_BODIES, waited, false, false),
                    "unanswered stage at " + waited + "ms");
        }
    }

    @Test
    void onlyARealConversationCounts() {
        // The regression this pins: gating on "the stage has answered" ALONE made every tick a
        // verdict. A party that simply stood on its candidate would burn one candidate per tick.
        assertFalse(PositionCombination.talkFinished(500L, 500L), "same clock: no talk");
        assertFalse(PositionCombination.talkFinished(500L, 100L), "clock went backwards");
        assertTrue(PositionCombination.talkFinished(500L, 501L), "clock advanced: a talk finished");
    }

    @Test
    void theAnswerPropertyGateIsPresenceOnly() {
        // Nothing about the answer may be visible here: the gate is "has the stage answered", and
        // a null property (a stage that does not publish one) must never read as "answered".
        assertFalse(PositionCombination.stageAnswered(null, null));
        assertFalse(PositionCombination.stageAnswered(null, "stage8combo"));
    }

    @Test
    void onlyARealAnswerDiscardRestartsTheSearch() {
        // The trigger must fire on exactly one transition: published -> gone. A stage that never
        // published (the intro still pending) or one that still has its answer must keep the
        // ledger, or every tick would throw the search away and the party would loop.
        assertTrue(PositionCombination.answerWasDiscarded(true, false));
        assertFalse(PositionCombination.answerWasDiscarded(false, false));
        assertFalse(PositionCombination.answerWasDiscarded(false, true));
        assertFalse(PositionCombination.answerWasDiscarded(true, true));
    }

    @Test
    void aRegeneratedAnswerRestartsTheWholeSearch() {
        // Amoria stage 2 blanks stage2combo every seven misses and generates a DIFFERENT answer
        // on the next talk. Everything ruled out was ruled out against the old answer, so the
        // ledger must be dropped - otherwise the real answer may already be in the tried set and
        // the stage can never be finished.
        var ledger = new PositionCombination.Ledger();
        var candidates = AmoriaPqData.stage2Candidates();
        PositionCombination.startWatching(ledger, 7, 2, 1_000L);
        ledger.stageAnswered = true;
        PositionCombination.plan(ledger, candidates, 5);
        ledger.tried.add(ledger.presentedPattern);
        ledger.firstSeenAtMs = 1_000L;
        assertFalse(ledger.tried.isEmpty());

        PositionCombination.resetSearch(ledger);
        assertTrue(ledger.tried.isEmpty(), "the ruled-out set belongs to the old answer");
        assertEquals(0, ledger.index, "the search restarts from the first candidate");
        assertEquals(-1L, ledger.presentedPattern);
        assertEquals(0L, ledger.firstSeenAtMs);
        assertFalse(ledger.stageAnswered, "a fresh answer has not been published yet");
    }

    @Test
    void aNewStageGetsItsOwnIntroTalk() {
        // The ledger counts talks per stage. If that count survived a stage change, the new
        // stage's INTRO would be read as a verdict and a pattern nobody judged would be retired.
        var ledger = new PositionCombination.Ledger();
        PositionCombination.startWatching(ledger, 7, 2, 1_000L);
        ledger.stageAnswered = true;
        PositionCombination.startWatching(ledger, 7, 3, 2_000L);
        assertFalse(ledger.stageAnswered, "a new stage has not answered yet");
        assertEquals(3, ledger.stage);
        ledger.stageAnswered = true;
        PositionCombination.startWatching(ledger, 9, 3, 3_000L);
        assertFalse(ledger.stageAnswered, "a new leader restarts the observation");
        assertEquals(9, ledger.leaderId);
    }

    @Test
    void theSettleClockBelongsToTheObservationNotTheCandidate() {
        // After a candidate change the room still shows the old pattern, so `current != presented`
        // blocks judging until the party actually arrives and sampleWindow starts a fresh clock.
        var ledger = new PositionCombination.Ledger();
        var candidates = KerningPqData.candidatesFor(2);
        PositionCombination.plan(ledger, candidates, 2);
        long presented = ledger.presentedPattern;
        PositionCombination.sampleWindow(ledger, presented, PqActions.bodiesOf(presented));
        assertTrue(ledger.firstSeenAtMs > 0, "the observation arms the clock");
        long armed = ledger.firstSeenAtMs;
        PositionCombination.sampleWindow(ledger, presented, PqActions.bodiesOf(presented));
        assertEquals(armed, ledger.firstSeenAtMs, "a steady pattern keeps its clock");
        PositionCombination.sampleWindow(ledger,
                PositionCombination.maskToPattern(0b00101), 2);
        assertTrue(ledger.firstSeenAtMs >= armed, "a changed pattern restarts it");
    }

    @Test
    void theWalkSkipsRuledOutCandidatesAndWraps() {
        List<PositionCombination.Candidate> candidates = KerningPqData.candidatesFor(2);
        assertEquals(4, candidates.size(), "Kerning stage 2 has four rows");
        Set<Long> tried = new HashSet<>();
        assertEquals(0, PositionCombination.nextUntried(tried, candidates, 0));
        tried.add(candidates.get(0).pattern());
        assertEquals(1, PositionCombination.nextUntried(tried, candidates, 0));
        tried.add(candidates.get(candidates.size() - 1).pattern());
        // From the last row, the walk wraps to the first still-untried one.
        assertEquals(1, PositionCombination.nextUntried(tried, candidates, candidates.size() - 1));
    }

    @Test
    void everyKerningRowIsAValidCandidate() {
        for (int stage = 2; stage <= 4; stage++) {
            List<PositionCombination.Candidate> candidates = KerningPqData.candidatesFor(stage);
            assertFalse(candidates.isEmpty(), "stage " + stage + " has no rows");
            assertEquals(KerningPqData.combinationsFor(stage).length, candidates.size());
            for (PositionCombination.Candidate candidate : candidates) {
                // A row names exactly the spots it wants, so it can always be stood on fully.
                assertEquals(PqActions.bodiesOf(candidate.pattern()), candidate.spots().size());
                assertEquals(candidate.spots().size(), new HashSet<>(candidate.spots()).size(),
                        "a row names the same spot twice");
                for (Point spot : candidate.spots()) {
                    assertTrue(KerningPqData.spotsFor(stage).contains(spot));
                }
            }
        }
    }

    @Test
    void theKerningAreasMatchTheScriptsOwnRectangles() {
        // 9020001.js judges against these literals, not against the map's area data; if a
        // transcript drifts, every bot would stand just outside the rectangle it is counted in.
        assertEquals(4, KerningPqData.areasFor(2).size());
        assertEquals(new java.awt.Rectangle(-755, -132, 4, 218), KerningPqData.areasFor(2).get(0));
        assertEquals(5, KerningPqData.areasFor(3).size());
        assertEquals(6, KerningPqData.areasFor(4).size());
        for (int stage = 2; stage <= 4; stage++) {
            List<Point> spots = KerningPqData.spotsFor(stage);
            List<java.awt.Rectangle> areas = KerningPqData.areasFor(stage);
            assertEquals(spots.size(), areas.size());
            for (int i = 0; i < spots.size(); i++) {
                assertTrue(areas.get(i).contains(spots.get(i)),
                        "stage " + stage + " spot " + i + " is outside its own rectangle");
            }
        }
    }

    @Test
    void theTailIsSeatedFirstSoThePlayerKeepsTheHead() {
        List<Point> spots = List.of(new Point(1, 1), new Point(2, 2), new Point(3, 3));
        assertEquals(spots.get(2), PositionCombination.mine(spots, 0));
        assertEquals(spots.get(1), PositionCombination.mine(spots, 1));
        assertEquals(spots.get(0), PositionCombination.mine(spots, 2));
        assertNull(PositionCombination.mine(spots, 3), "a fourth body has nowhere to stand");
        assertNull(PositionCombination.mine(List.of(), 0));
        assertNull(PositionCombination.mine(spots, -1));
    }

    @Test
    void onlyOneBodyLandsInEachArea() {
        // The scripts count bodies per area, so two bodies in one area is a failed try even when
        // the pattern looks right. The tail-first split must hand out distinct spots.
        for (PositionCombination.Candidate candidate : KerningPqData.candidatesFor(4)) {
            List<Point> taken = new ArrayList<>();
            for (int bot = 0; bot < candidate.spots().size(); bot++) {
                taken.add(PositionCombination.mine(candidate.spots(), bot));
            }
            assertEquals(taken.size(), new HashSet<>(taken).size());
        }
    }
}
