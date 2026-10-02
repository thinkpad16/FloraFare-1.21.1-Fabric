package net.tend1tnuy.florafare.component;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.tend1tnuy.florafare.component.PlayerFoodComponent.EndCause;
import net.tend1tnuy.florafare.component.PlayerFoodComponent.OverdriveStep;
import net.tend1tnuy.florafare.component.PlayerFoodComponent.SynergyWindow;
import net.tend1tnuy.florafare.food.FoodAftermath;
import net.tend1tnuy.florafare.food.FoodSynergyData;
import net.tend1tnuy.florafare.food.SynergyOverdrive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The overdrive and own-timer rules that need no player: when a bite moves a stage, when
 * it overloads one, which aftermath an ending picks, the lockout, and an own timer's
 * independence from its ingredients.
 */
class OverdriveRulesTest {

    private static final int MINUTE = 1200;

    /** Fried Chicken 35 min, Red Rum 35 min, Roasted Red Pepper Slice 3 min — the pepper is the fuse. */
    private static SynergyWindow infernoWindow(int pepperLeft) {
        return PlayerFoodComponent.synergyWindow(pepperLeft, 3 * MINUTE, 0);
    }

    private static FoodAftermath aftermath(int duration, double health) {
        return new FoodAftermath(duration, health, List.of(), List.of());
    }

    private static SynergyOverdrive.Stage stage(double health, FoodAftermath aftermath) {
        return new SynergyOverdrive.Stage(health, List.of(), List.of(), aftermath);
    }

    /** A four-stage overdrive whose stage N's aftermath costs N hearts. */
    private static FoodSynergyData inferno(boolean lockout, String group) {
        return new FoodSynergyData("falleneden:inferno_feast",
                List.of("falleneden:fried_chicken", "falleneden:red_rum", "falleneden:roasted_red_pepper_slice"),
                0, 2.0, List.of(), List.of(), aftermath(2400, -2),
                FoodSynergyData.Timer.MEMBERS,
                new SynergyOverdrive(List.of(
                        stage(4.0, aftermath(3600, -4)),
                        stage(6.0, aftermath(4800, -6)),
                        stage(8.0, aftermath(6000, -8)))), lockout, group);
    }

    private static FoodSynergyData plain(String id) {
        return new FoodSynergyData(id, List.of("minecraft:bread"), 0, 0.0, List.of(), List.of(),
                aftermath(1200, -2));
    }

    @Nested
    @DisplayName("stages come with time, never with bites")
    class StageClock {

        /** An overdrive started with the pepper at {@code pepperLeft} of its 3 minutes. */
        private ActiveFoodBuff started(int pepperLeft) {
            ActiveFoodBuff synergy = ActiveFoodBuff.synergy("falleneden:inferno_feast", "minecraft:apple",
                    pepperLeft, 3 * MINUTE);
            synergy.startStageClock(pepperLeft, 3 * MINUTE);
            return synergy;
        }

        private static void tick(ActiveFoodBuff buff, int ticks) {
            for (int i = 0; i < ticks; i++) buff.tick();
        }

        @Test
        @DisplayName("the first boundary is the shortest ingredient's time left at activation")
        void firstBoundaryIsRemainingAtActivation() {
            ActiveFoodBuff synergy = started(1000);
            tick(synergy, 999);
            assertFalse(synergy.isStageDue());
            tick(synergy, 1);
            assertTrue(synergy.isStageDue());
        }

        @Test
        @DisplayName("every later boundary is one full phase — the ingredient's whole duration — on")
        void laterBoundariesArePhases() {
            ActiveFoodBuff synergy = started(3 * MINUTE);
            assertEquals(3 * MINUTE, synergy.getPhaseLength());
            tick(synergy, 3 * MINUTE);
            assertTrue(synergy.isStageDue(), "3 minutes");
            synergy.restartPhase();
            tick(synergy, 3 * MINUTE - 1);
            assertFalse(synergy.isStageDue());
            tick(synergy, 1);
            assertTrue(synergy.isStageDue(), "6 minutes");
        }

        @Test
        @DisplayName("eating the fuse many times inside one phase does not move the clock, or skip a stage")
        void eatsDoNotSkip() {
            ActiveFoodBuff synergy = started(3 * MINUTE);
            for (int bite = 0; bite < 10; bite++) {
                tick(synergy, 100);
                // What a fresh pepper does to the synergy: its window back to full.
                synergy.resetDuration(3 * MINUTE, 3 * MINUTE);
                assertEquals(OverdriveStep.NONE, PlayerFoodComponent.overdriveStep(
                        3 * MINUTE - 100, infernoWindow(3 * MINUTE), true, 1, 4),
                        "below the last stage a bite never moves the stage");
            }
            assertEquals(3 * MINUTE - 1000, synergy.getTicksToNextStage());
            assertFalse(synergy.isStageDue());
        }

        @Test
        @DisplayName("a boundary moves it up exactly one stage, and at the last stage overloads it")
        void boundarySteps() {
            for (int stage = 1; stage < 4; stage++) {
                assertEquals(OverdriveStep.ADVANCE, PlayerFoodComponent.boundaryStep(stage, 4), "stage " + stage);
            }
            assertEquals(OverdriveStep.OVERLOAD, PlayerFoodComponent.boundaryStep(4, 4));
            assertEquals(OverdriveStep.OVERLOAD, PlayerFoodComponent.boundaryStep(1, 1));
        }

        @Test
        @DisplayName("a synergy without a clock is never due, and clearing one stops it")
        void noClock() {
            ActiveFoodBuff plain = ActiveFoodBuff.synergy("mypack:picnic", "minecraft:apple", 0, 100);
            assertFalse(plain.hasStageClock());
            assertFalse(plain.isStageDue());
            ActiveFoodBuff synergy = started(0);
            assertTrue(synergy.isStageDue());
            synergy.clearStageClock();
            assertFalse(synergy.isStageDue());
        }
    }

    @Nested
    @DisplayName("refusal at the last stage")
    class Refusal {

        @Test
        @DisplayName("prolonging it at the last stage overloads it")
        void lastStageOverloads() {
            assertEquals(OverdriveStep.OVERLOAD,
                    PlayerFoodComponent.overdriveStep(MINUTE, infernoWindow(3 * MINUTE), true, 4, 4));
        }

        @Test
        @DisplayName("the same bite below the last stage does nothing to the stage")
        void belowTheLastStageIsHarmless() {
            for (int stage = 1; stage < 4; stage++) {
                assertEquals(OverdriveStep.NONE,
                        PlayerFoodComponent.overdriveStep(MINUTE, infernoWindow(3 * MINUTE), true, stage, 4));
            }
        }

        @Test
        @DisplayName("an overdrive with no stages past I overloads on the first push")
        void singleStageOverloadsAtOnce() {
            assertEquals(OverdriveStep.OVERLOAD,
                    PlayerFoodComponent.overdriveStep(MINUTE, infernoWindow(3 * MINUTE), true, 1, 1));
        }

        @Test
        @DisplayName("eating the 35-minute chicken does not prolong it, so is harmless even at the last stage")
        void longIngredientIsHarmless() {
            // The pepper is still the shortest, so the window the bite earns is what was left.
            assertEquals(OverdriveStep.NONE,
                    PlayerFoodComponent.overdriveStep(MINUTE, infernoWindow(MINUTE), true, 4, 4));
        }

        @Test
        @DisplayName("a bite that feeds nothing never overloads it")
        void unfedNeverOverloads() {
            assertEquals(OverdriveStep.NONE,
                    PlayerFoodComponent.overdriveStep(MINUTE, infernoWindow(3 * MINUTE), false, 4, 4));
        }

        @Test
        @DisplayName("a re-sync within the drift tolerance is not a push")
        void driftIsNotAPush() {
            int remaining = MINUTE;
            SynergyWindow drifted = infernoWindow(remaining + PlayerFoodComponent.SYNERGY_DRIFT_TOLERANCE);
            assertEquals(OverdriveStep.NONE,
                    PlayerFoodComponent.overdriveStep(remaining, drifted, true, 4, 4));
            assertFalse(PlayerFoodComponent.extendsWindow(remaining, drifted));
            assertTrue(PlayerFoodComponent.extendsWindow(remaining,
                    infernoWindow(remaining + PlayerFoodComponent.SYNERGY_DRIFT_TOLERANCE + 1)));
        }

        @Test
        @DisplayName("an overload starts the aftermath — it is never a way out of paying")
        void overloadStartsTheAftermath() {
            assertTrue(PlayerFoodComponent.startsAftermath(EndCause.OVERLOAD));
        }
    }

    @Nested
    @DisplayName("the aftermath an ending picks")
    class AftermathByStage {

        @Test
        @DisplayName("each stage's own aftermath, stage I being the synergy's")
        void picksTheStageReached() {
            FoodSynergyData synergy = inferno(false, null);
            assertEquals(-2, synergy.aftermathOf(1).healthBonus());
            assertEquals(-4, synergy.aftermathOf(2).healthBonus());
            assertEquals(-6, synergy.aftermathOf(3).healthBonus());
            assertEquals(-8, synergy.aftermathOf(4).healthBonus());
        }

        @Test
        @DisplayName("a stage past the end (a /reload shortened it) reads as the last one")
        void clampsPastTheEnd() {
            FoodSynergyData synergy = inferno(false, null);
            assertEquals(-8, synergy.aftermathOf(9).healthBonus());
            assertEquals(-2, synergy.aftermathOf(0).healthBonus());
            assertEquals(4, synergy.clampStage(7));
        }

        @Test
        @DisplayName("an ordinary synergy has stage I only")
        void plainHasOneStage() {
            FoodSynergyData synergy = plain("mypack:picnic");
            assertEquals(1, synergy.stageCount());
            assertEquals(synergy.aftermath(), synergy.aftermathOf(3));
        }
    }

    @Nested
    @DisplayName("lockout")
    class Lockout {

        private final Map<String, FoodSynergyData> loaded = new HashMap<>();

        private ActiveFoodBuff aftermathOf(String synergyId) {
            return new ActiveFoodBuff(synergyId, "minecraft:apple", 1200, 1200, true);
        }

        @Test
        @DisplayName("its own running aftermath keeps it from starting")
        void ownAftermathLocksOut() {
            FoodSynergyData synergy = inferno(true, null);
            loaded.put(synergy.id(), synergy);
            assertTrue(PlayerFoodComponent.lockedOut(synergy, List.of(aftermathOf(synergy.id())), loaded::get));
            assertFalse(PlayerFoodComponent.lockedOut(synergy, List.of(), loaded::get));
        }

        @Test
        @DisplayName("without lockout it starts as it always has")
        void noLockout() {
            FoodSynergyData synergy = inferno(false, null);
            loaded.put(synergy.id(), synergy);
            assertFalse(PlayerFoodComponent.lockedOut(synergy, List.of(aftermathOf(synergy.id())), loaded::get));
        }

        @Test
        @DisplayName("any synergy of the same group locks out every synergy of it")
        void groupLocksOut() {
            FoodSynergyData inferno = inferno(true, "falleneden:overdrive");
            FoodSynergyData frost = new FoodSynergyData("falleneden:frost_feast", List.of("minecraft:bread"),
                    0, 0.0, List.of(), List.of(), aftermath(1200, -2), FoodSynergyData.Timer.MEMBERS,
                    new SynergyOverdrive(List.of()), true, "falleneden:overdrive");
            loaded.put(inferno.id(), inferno);
            loaded.put(frost.id(), frost);
            assertTrue(PlayerFoodComponent.lockedOut(inferno, List.of(aftermathOf(frost.id())), loaded::get));
            assertTrue(PlayerFoodComponent.lockedOut(frost, List.of(aftermathOf(inferno.id())), loaded::get));
        }

        @Test
        @DisplayName("ordinary synergies lock out too: one group over every synergy, overdrive or not")
        void ordinarySynergiesShareAGroup() {
            FoodSynergyData picnic = new FoodSynergyData("falleneden:picnic", List.of("minecraft:bread"),
                    0, 0.0, List.of(), List.of(), aftermath(1200, -2), FoodSynergyData.Timer.MEMBERS, null,
                    true, "falleneden:all");
            FoodSynergyData tea = new FoodSynergyData("falleneden:tea", List.of("minecraft:apple"),
                    0, 0.0, List.of(), List.of(), aftermath(1200, -1), FoodSynergyData.Timer.MEMBERS, null,
                    true, "falleneden:all");
            FoodSynergyData inferno = inferno(true, "falleneden:all");
            loaded.put(picnic.id(), picnic);
            loaded.put(tea.id(), tea);
            loaded.put(inferno.id(), inferno);

            assertTrue(PlayerFoodComponent.lockedOut(tea, List.of(aftermathOf(picnic.id())), loaded::get));
            assertTrue(PlayerFoodComponent.lockedOut(inferno, List.of(aftermathOf(picnic.id())), loaded::get));
            assertTrue(PlayerFoodComponent.lockedOut(picnic, List.of(aftermathOf(inferno.id())), loaded::get));
        }

        @Test
        @DisplayName("a different group, a plain synergy or a food's aftermath locks nothing out")
        void unrelatedAftermathsDoNot() {
            FoodSynergyData inferno = inferno(true, "falleneden:overdrive");
            FoodSynergyData other = new FoodSynergyData("mypack:other", List.of("minecraft:bread"),
                    0, 0.0, List.of(), List.of(), aftermath(1200, -2), FoodSynergyData.Timer.MEMBERS,
                    new SynergyOverdrive(List.of()), true, "mypack:elsewhere");
            FoodSynergyData picnic = plain("mypack:picnic");
            loaded.put(inferno.id(), inferno);
            loaded.put(other.id(), other);
            loaded.put(picnic.id(), picnic);

            ActiveFoodBuff foodAftermath = new ActiveFoodBuff(inferno.id(), "minecraft:apple", 1200, 1200, false);
            assertFalse(PlayerFoodComponent.lockedOut(inferno,
                    List.of(aftermathOf(other.id()), aftermathOf(picnic.id()), foodAftermath), loaded::get),
                    "a food buff whose target happens to match the synergy id is not the synergy");
        }

        @Test
        @DisplayName("a group member without a group of its own is not blocked by the group")
        void selfLockoutIgnoresGroups() {
            FoodSynergyData loner = inferno(true, null);
            FoodSynergyData grouped = new FoodSynergyData("falleneden:frost_feast", List.of("minecraft:bread"),
                    0, 0.0, List.of(), List.of(), aftermath(1200, -2), FoodSynergyData.Timer.MEMBERS,
                    new SynergyOverdrive(List.of()), true, "falleneden:overdrive");
            loaded.put(loner.id(), loner);
            loaded.put(grouped.id(), grouped);
            assertFalse(PlayerFoodComponent.lockedOut(loner, List.of(aftermathOf(grouped.id())), loaded::get));
        }

        @Test
        @DisplayName("an aftermath whose synergy a /reload removed still locks out the same id")
        void removedDefinitionStillMatchesById() {
            FoodSynergyData synergy = inferno(true, null);
            assertTrue(PlayerFoodComponent.lockedOut(synergy, List.of(aftermathOf(synergy.id())), id -> null));
        }

        @Test
        @DisplayName("lockoutKey: the group, else its own id, else none")
        void lockoutKey() {
            assertEquals("falleneden:overdrive", inferno(true, "falleneden:overdrive").lockoutKey());
            assertEquals("falleneden:inferno_feast", inferno(true, null).lockoutKey());
            assertNull(inferno(false, null).lockoutKey());
            assertNull(plain("mypack:picnic").lockoutKey());
            assertEquals("mypack:picnic", new FoodSynergyData("mypack:picnic", List.of("minecraft:bread"), 0, 0.0,
                    List.of(), List.of(), FoodAftermath.NONE, FoodSynergyData.Timer.MEMBERS, null, true, null)
                    .lockoutKey(), "an ordinary synergy can lock out itself");
        }
    }

    @Nested
    @DisplayName("duration 0: no ceiling")
    class NoCeiling {

        @Test
        @DisplayName("a synergy with duration 0 lasts exactly as long as its shortest ingredient")
        void mirrorsTheShortestIngredient() {
            // 35 minutes of chicken and rum, 3 of pepper with 2:30 left: the synergy gets 2:30.
            SynergyWindow window = PlayerFoodComponent.synergyWindow(3000, 3 * MINUTE, 0);
            assertEquals(3000, window.remaining());
            assertEquals(3 * MINUTE, window.initial());

            SynergyWindow longRun = PlayerFoodComponent.synergyWindow(35 * MINUTE, 35 * MINUTE, 0);
            assertEquals(35 * MINUTE, longRun.remaining(), "not cut to the 2400-tick default");
        }

        @Test
        @DisplayName("the 2400-tick default, for a file that leaves duration out, still caps it")
        void defaultStillCaps() {
            assertEquals(2400, PlayerFoodComponent.synergyWindow(35 * MINUTE, 35 * MINUTE, 2400).remaining());
        }

        @Test
        @DisplayName("re-eating an ingredient keeps an uncapped synergy going, as long as the ingredient")
        void prolongedByReEating() {
            SynergyWindow refreshed = PlayerFoodComponent.refreshWindow(600, 3 * MINUTE,
                    PlayerFoodComponent.synergyWindow(3 * MINUTE, 3 * MINUTE, 0), true);
            assertNotNull(refreshed);
            assertEquals(3 * MINUTE, refreshed.remaining());
        }
    }

    @Nested
    @DisplayName("own timer")
    class OwnTimer {

        @Test
        @DisplayName("it starts at its full duration, whatever its ingredients have left")
        void startsFull() {
            SynergyWindow start = PlayerFoodComponent.ownTimerStart(2400);
            assertEquals(2400, start.remaining());
            assertEquals(2400, start.initial());
        }

        @Test
        @DisplayName("eating an ingredient again resets it to the full duration")
        void fedResets() {
            SynergyWindow next = PlayerFoodComponent.ownTimerWindow(500, 2400, true);
            assertNotNull(next);
            assertEquals(2400, next.remaining());
            assertEquals(2400, next.initial());
        }

        @Test
        @DisplayName("nothing else moves it — an ingredient running out included")
        void independentOfMembers() {
            assertNull(PlayerFoodComponent.ownTimerWindow(500, 2400, false));
            assertNull(PlayerFoodComponent.ownTimerWindow(2300, 2400, false));
        }

        @Test
        @DisplayName("the bite that started it does not also refresh it")
        void startingBiteIsNotARefresh() {
            assertNull(PlayerFoodComponent.ownTimerWindow(2400, 2400, true));
            assertNull(PlayerFoodComponent.ownTimerWindow(
                    2400 - PlayerFoodComponent.SYNERGY_DRIFT_TOLERANCE, 2400, true));
        }

        @Test
        @DisplayName("its duration is held to the buff ceiling like any other")
        void clampedToCeiling() {
            assertEquals(ActiveFoodBuff.MAX_DURATION_TICKS,
                    PlayerFoodComponent.ownTimerStart(Integer.MAX_VALUE).remaining());
        }
    }

    @Nested
    @DisplayName("persistence")
    class Persistence {

        @Test
        @DisplayName("a synergy's stage survives NBT, and stage I writes no key")
        void stageRoundTrips() {
            ActiveFoodBuff synergy = ActiveFoodBuff.synergy("falleneden:inferno_feast", "minecraft:apple", 900, 3600);
            assertFalse(synergy.toNbt().contains("Stage"), "stage I costs nothing on the wire");

            synergy.setStage(3);
            ActiveFoodBuff read = ActiveFoodBuff.fromNbt(synergy.toNbt(), true);
            assertEquals(3, read.getStage());
            assertTrue(read.isSynergy());
        }

        @Test
        @DisplayName("the stage clock survives NBT, and a synergy without one writes no key")
        void clockRoundTrips() {
            ActiveFoodBuff synergy = ActiveFoodBuff.synergy("falleneden:inferno_feast", "minecraft:apple", 900, 3600);
            assertFalse(synergy.toNbt().contains("PhaseLength"));
            synergy.startStageClock(1234, 3600);
            ActiveFoodBuff read = ActiveFoodBuff.fromNbt(synergy.toNbt(), true);
            assertTrue(read.hasStageClock());
            assertEquals(1234, read.getTicksToNextStage());
            assertEquals(3600, read.getPhaseLength());
        }

        @Test
        @DisplayName("an entry saved before overdrives existed loads at stage I, with no clock")
        void oldSaveIsStageOne() {
            NbtCompound old = new NbtCompound();
            old.putString("Target", "falleneden:inferno_feast");
            old.putString("ConsumedItem", "minecraft:apple");
            old.putInt("Duration", 600);
            old.putInt("InitialDuration", 1200);
            assertEquals(1, ActiveFoodBuff.fromNbt(old, true).getStage());
            assertFalse(ActiveFoodBuff.fromNbt(old, true).hasStageClock());
        }

        @Test
        @DisplayName("a stage below I is read as stage I")
        void stageFloor() {
            ActiveFoodBuff buff = ActiveFoodBuff.synergy("x", "minecraft:apple", 10, 10);
            buff.setStage(-4);
            assertEquals(1, buff.getStage());
        }

        @Test
        @DisplayName("discovered stages, the running stage and the aftermath's stage survive a save")
        void componentRoundTrips() {
            PlayerFoodComponent written = new PlayerFoodComponent(null);
            written.getDiscoveredStages().add(FoodSynergyData.stageKey("falleneden:inferno_feast", 2));
            written.getDiscoveredStages().add(FoodSynergyData.aftermathKey("falleneden:inferno_feast", 2));
            ActiveFoodBuff running = ActiveFoodBuff.synergy("falleneden:inferno_feast", "minecraft:apple", 900, 3600);
            running.setStage(2);
            written.getActiveSynergies().add(running);
            ActiveFoodBuff bill = new ActiveFoodBuff("falleneden:frost_feast", "minecraft:apple", 1200, 1200, true);
            bill.setStage(4);
            written.getActiveAftermaths().add(bill);

            PlayerFoodComponent read = new PlayerFoodComponent(null);
            read.readFromNbt(written.writeToNbt(new NbtCompound()));

            assertEquals(written.getDiscoveredStages(), read.getDiscoveredStages());
            assertEquals(2, read.getSynergyStage("falleneden:inferno_feast"));
            assertEquals(0, read.getSynergyStage("falleneden:frost_feast"), "not running");
            assertEquals(4, read.getActiveAftermaths().get(0).getStage());
        }

        @Test
        @DisplayName("a save from before overdrives has nothing discovered and everything at stage I")
        void oldComponentSave() {
            NbtCompound old = new NbtCompound();
            NbtList synergies = new NbtList();
            NbtCompound running = new NbtCompound();
            running.putString("Target", "falleneden:inferno_feast");
            running.putString("ConsumedItem", "minecraft:apple");
            running.putInt("Duration", 600);
            running.putInt("InitialDuration", 1200);
            synergies.add(running);
            old.put("FlorafareActiveSynergies", synergies);
            NbtList known = new NbtList();
            known.add(NbtString.of("falleneden:inferno_feast"));
            old.put("FlorafareDiscoveredSynergies", known);

            PlayerFoodComponent read = new PlayerFoodComponent(null);
            read.readFromNbt(old);
            assertTrue(read.getDiscoveredStages().isEmpty());
            assertEquals(1, read.getSynergyStage("falleneden:inferno_feast"));
        }

        @Test
        @DisplayName("a respawn keeps what the player learned about stages")
        void respawnKeepsDiscoveries() {
            PlayerFoodComponent old = new PlayerFoodComponent(null);
            old.getDiscoveredStages().add("falleneden:inferno_feast#3");
            PlayerFoodComponent fresh = new PlayerFoodComponent(null);
            fresh.copyProgressFrom(old);
            assertTrue(fresh.getDiscoveredStages().contains("falleneden:inferno_feast#3"));
        }
    }
}
