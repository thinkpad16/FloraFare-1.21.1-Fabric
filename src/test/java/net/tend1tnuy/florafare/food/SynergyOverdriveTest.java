package net.tend1tnuy.florafare.food;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Category;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Issue;
import net.tend1tnuy.florafare.food.DatapackDiagnostics.Severity;
import net.tend1tnuy.florafare.network.FoodSynergySyncPayload;
import net.tend1tnuy.florafare.network.StageDiscoveredPayload;
import net.tend1tnuy.florafare.util.RomanNumerals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code timer} and {@code overdrive} keys of a synergy: how they load and what the
 * loader says about them, how the definition travels in NBT to clients, and the discovery
 * keys the journal gates drawbacks with.
 */
class SynergyOverdriveTest {

    private static final Identifier MAX_HEALTH = Identifier.of("minecraft", "generic.max_health");
    private static final Identifier SPEED      = Identifier.of("minecraft", "speed");

    /** A three-stage overdrive in a lockout group, every stage with an aftermath of its own. */
    private static FoodSynergyData inferno() {
        return new FoodSynergyData("falleneden:inferno_feast",
                List.of("falleneden:fried_chicken", "falleneden:red_rum", "falleneden:roasted_red_pepper_slice"),
                0, 2.0,
                List.of(new FoodBuffData.EffectData(SPEED, 0, 0)),
                List.of(new FoodBuffData.AttributeData(MAX_HEALTH, 0.1, "add_multiplied_base")),
                new FoodAftermath(2400, -2, List.of(), List.of()),
                FoodSynergyData.Timer.MEMBERS,
                new SynergyOverdrive(List.of(
                        new SynergyOverdrive.Stage(4, List.of(new FoodBuffData.EffectData(SPEED, 0, 1)), List.of(),
                                new FoodAftermath(3600, -4, List.of(), List.of())),
                        new SynergyOverdrive.Stage(-2, List.of(), List.of(
                                new FoodBuffData.AttributeData(MAX_HEALTH, -0.2, "add_multiplied_total")),
                                new FoodAftermath(6000, 0, List.of(new FoodBuffData.EffectData(SPEED, 200, 0)),
                                        List.of(new FoodBuffData.AttributeData(MAX_HEALTH, -0.3,
                                                "add_multiplied_total")))))),
                true, "falleneden:overdrive");
    }

    @Nested
    @DisplayName("NBT and the wire")
    class Nbt {

        @Test
        @DisplayName("an overdrive synergy round-trips through NBT, every stage and the lockout included")
        void roundTrips() {
            FoodSynergyData original = inferno();
            assertEquals(original, FoodSynergyData.fromNbt(original.toNbt()));
        }

        @Test
        @DisplayName("an own-timer synergy round-trips")
        void ownTimerRoundTrips() {
            FoodSynergyData original = new FoodSynergyData("mypack:tea_time", List.of("minecraft:bread"), 6000,
                    0, List.of(), List.of(), FoodAftermath.NONE, FoodSynergyData.Timer.OWN, null);
            FoodSynergyData read = FoodSynergyData.fromNbt(original.toNbt());
            assertEquals(original, read);
            assertTrue(read.ownTimer());
        }

        @Test
        @DisplayName("an ordinary synergy writes neither key, and an older payload reads as one")
        void ordinaryCostsNothing() {
            FoodSynergyData plain = new FoodSynergyData("mypack:picnic", List.of("minecraft:bread"), 2400,
                    0, List.of(), List.of());
            NbtCompound nbt = plain.toNbt();
            assertFalse(nbt.contains("timer"));
            assertFalse(nbt.contains("overdrive"));

            FoodSynergyData read = FoodSynergyData.fromNbt(nbt);
            assertEquals(FoodSynergyData.Timer.MEMBERS, read.timer());
            assertNull(read.overdrive());
            assertEquals(1, read.stageCount());
        }

        @Test
        @DisplayName("an overdrive synergy survives the synergy sync payload")
        void survivesTheSyncCodec() {
            NbtCompound map = new NbtCompound();
            map.put("falleneden:inferno_feast", inferno().toNbt());
            RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
            try {
                FoodSynergySyncPayload.CODEC.encode(buf, new FoodSynergySyncPayload(map, 0, 1));
                NbtCompound decoded = FoodSynergySyncPayload.CODEC.decode(buf).nbt();
                assertEquals(inferno(), FoodSynergyData.fromNbt(decoded.getCompound("falleneden:inferno_feast")));
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("a stage discovery key survives its payload")
        void stagePayloadRoundTrips() {
            RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), null);
            try {
                String key = FoodSynergyData.aftermathKey("falleneden:inferno_feast", 3);
                StageDiscoveredPayload.CODEC.encode(buf, new StageDiscoveredPayload(key));
                assertEquals(key, StageDiscoveredPayload.CODEC.decode(buf).key());
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    @DisplayName("stages")
    class Stages {

        @Test
        @DisplayName("stage I is the synergy's own fields; the overdrive's list is II and up")
        void stageOneIsTopLevel() {
            FoodSynergyData synergy = inferno();
            assertEquals(3, synergy.stageCount());
            SynergyOverdrive.Stage one = synergy.stage(1);
            assertEquals(2.0, one.healthBonus());
            assertEquals(synergy.effects(), one.effects());
            assertEquals(synergy.attributes(), one.attributes());
            assertEquals(synergy.aftermath(), one.aftermath());
            assertEquals(4, synergy.stage(2).healthBonus());
            assertEquals(-2, synergy.stage(3).healthBonus());
        }

        @Test
        @DisplayName("each stage is a full replacement, not a delta on the one before")
        void fullReplacement() {
            FoodSynergyData synergy = inferno();
            assertTrue(synergy.stage(2).attributes().isEmpty(),
                    "stage II lists no attributes, so stage I's +10% max health does not carry over");
        }
    }

    @Nested
    @DisplayName("discovery keys")
    class DiscoveryKeys {

        @Test
        @DisplayName("one key for reaching a stage, another for suffering its aftermath")
        void keyShapes() {
            assertEquals("falleneden:inferno_feast#3", FoodSynergyData.stageKey("falleneden:inferno_feast", 3));
            assertEquals("falleneden:inferno_feast#3/aftermath",
                    FoodSynergyData.aftermathKey("falleneden:inferno_feast", 3));
        }

        @Test
        @DisplayName("every key of a synergy, and only its own, is recognised as its")
        void ownership() {
            List<String> keys = inferno().discoveryKeys();
            assertEquals(6, keys.size());
            assertTrue(keys.contains("falleneden:inferno_feast#1/aftermath"));
            for (String key : keys) assertTrue(FoodSynergyData.isKeyOf(key, "falleneden:inferno_feast"));
            assertFalse(FoodSynergyData.isKeyOf("falleneden:inferno_feast_deluxe#1", "falleneden:inferno_feast"));
        }

        @Test
        @DisplayName("an ordinary synergy's aftermath is a stage-I key")
        void plainSynergy() {
            FoodSynergyData plain = new FoodSynergyData("mypack:picnic", List.of("minecraft:bread"), 0, 0,
                    List.of(), List.of());
            assertEquals(List.of("mypack:picnic#1", "mypack:picnic#1/aftermath"), plain.discoveryKeys());
        }
    }

    @Test
    @DisplayName("Roman numerals for stages, Arabic past what any pack will reach")
    void romanNumerals() {
        assertEquals("I", RomanNumerals.of(1));
        assertEquals("IV", RomanNumerals.of(4));
        assertEquals("IX", RomanNumerals.of(9));
        assertEquals("XIV", RomanNumerals.of(14));
        assertEquals("MCMXCIV", RomanNumerals.of(1994));
        assertEquals("0", RomanNumerals.of(0));
        assertEquals("4000", RomanNumerals.of(4000));
    }

    @Test
    @DisplayName("timer names, any case")
    void timerNames() {
        assertEquals(FoodSynergyData.Timer.OWN, FoodSynergyData.Timer.parse(" Own "));
        assertEquals(FoodSynergyData.Timer.MEMBERS, FoodSynergyData.Timer.parse("members"));
        assertNull(FoodSynergyData.Timer.parse("mine"));
        assertEquals("own", FoodSynergyData.Timer.OWN.jsonName());
    }

    // -------------------------------------------------------------------------
    // LOADING
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("loading")
    class Loading {

        private final Comparator<Identifier> byId = Comparator.comparing(Identifier::toString);
        private FoodSynergyReloadListener listener;
        private Map<Identifier, JsonElement> files;

        @BeforeEach
        void setUp() {
            listener = new FoodSynergyReloadListener(new StubLookup());
            files = new LinkedHashMap<>();
        }

        private DatapackDiagnostics.Collector load(String json) {
            files.put(Identifier.of("mypack:synergies"), JsonParser.parseString(json));
            return listener.load(files, byId);
        }

        private List<Issue> of(DatapackDiagnostics.Collector report, Category category) {
            return report.issues().stream().filter(issue -> issue.category() == category).toList();
        }

        private static final String HEAD =
                "{\"id\": \"mypack:feast\", \"requirements\": [\"minecraft:apple\", \"minecraft:bread\"], ";

        @Test
        @DisplayName("a full overdrive loads stage by stage, cleanly")
        void fullOverdrive() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"duration\": 0, \"health_bonus\": 2,"
                    + " \"aftermath\": {\"duration\": 1200, \"health_bonus\": -2},"
                    + " \"lockout\": true, \"lockout_group\": \"mypack:overdrive\","
                    + " \"overdrive\": {\"stages\": ["
                    + "  {\"health_bonus\": 4, \"effects\": [{\"id\": \"minecraft:speed\", \"amplifier\": 1}],"
                    + "   \"aftermath\": {\"duration\": 2400, \"health_bonus\": -4}},"
                    + "  {\"attributes\": [{\"attribute\": \"minecraft:generic.max_health\", \"amount\": -0.2,"
                    + "   \"operation\": \"add_multiplied_total\"}]}"
                    + "]}}");

            assertTrue(report.issues().isEmpty(), report.issues().toString());
            FoodSynergyData synergy = FoodSynergyManager.getSynergy("mypack:feast");
            assertNotNull(synergy);
            assertEquals(3, synergy.stageCount());
            assertEquals(-2, synergy.aftermathOf(1).healthBonus());
            assertEquals(-4, synergy.aftermathOf(2).healthBonus());
            assertEquals(1, synergy.stage(2).effects().get(0).amplifier());
            assertTrue(synergy.aftermathOf(3).isEmpty());
            assertEquals("mypack:overdrive", synergy.lockoutKey());
        }

        /** The README's Inferno Feast, verbatim: four stages, a lockout group, no ceiling. */
        private static final String README_INFERNO = """
                {
                  "id": "falleneden:inferno_feast",
                  "requirements": ["mypack:fried_chicken", "mypack:red_rum", "mypack:roasted_red_pepper_slice"],
                  "duration": 0,
                  "lockout_group": "falleneden:all",
                  "health_bonus": 2,
                  "attributes": [
                    { "attribute": "minecraft:generic.attack_damage", "amount": 0.1, "operation": "add_multiplied_total" }
                  ],
                  "effects": [
                    { "id": "minecraft:fire_resistance" }
                  ],
                  "aftermath": {
                    "duration": 1200,
                    "attributes": [
                      { "attribute": "minecraft:generic.movement_speed", "amount": -0.1, "operation": "add_multiplied_total" }
                    ]
                  },
                  "overdrive": {
                    "stages": [
                      {
                        "health_bonus": 4,
                        "attributes": [
                          { "attribute": "minecraft:generic.attack_damage", "amount": 0.25, "operation": "add_multiplied_total" },
                          { "attribute": "minecraft:generic.armor", "amount": -2, "operation": "add_value" }
                        ],
                        "effects": [
                          { "id": "minecraft:fire_resistance" },
                          { "id": "minecraft:strength" }
                        ],
                        "aftermath": {
                          "duration": 2400,
                          "health_bonus": -4,
                          "effects": [ { "id": "minecraft:weakness", "duration": 600 } ]
                        }
                      },
                      {
                        "health_bonus": 2,
                        "attributes": [
                          { "attribute": "minecraft:generic.attack_damage", "amount": 0.45, "operation": "add_multiplied_total" },
                          { "attribute": "minecraft:generic.armor", "amount": -4, "operation": "add_value" }
                        ],
                        "effects": [
                          { "id": "minecraft:fire_resistance" },
                          { "id": "minecraft:strength", "amplifier": 1 },
                          { "id": "minecraft:hunger" }
                        ],
                        "aftermath": {
                          "duration": 3600,
                          "health_bonus": -6,
                          "attributes": [
                            { "attribute": "minecraft:generic.movement_speed", "amount": -0.2, "operation": "add_multiplied_total" }
                          ],
                          "effects": [
                            { "id": "minecraft:weakness", "duration": 1200 },
                            { "id": "minecraft:nausea", "duration": 200 }
                          ]
                        }
                      },
                      {
                        "attributes": [
                          { "attribute": "minecraft:generic.attack_damage", "amount": 0.75, "operation": "add_multiplied_total" },
                          { "attribute": "minecraft:generic.max_health", "amount": -0.25, "operation": "add_multiplied_total" }
                        ],
                        "effects": [
                          { "id": "minecraft:strength", "amplifier": 2 },
                          { "id": "minecraft:wither" }
                        ],
                        "aftermath": {
                          "duration": 6000,
                          "health_bonus": -8,
                          "attributes": [
                            { "attribute": "minecraft:generic.max_health", "amount": -0.25, "operation": "add_multiplied_total" }
                          ],
                          "effects": [
                            { "id": "minecraft:weakness", "duration": 2400, "amplifier": 1 },
                            { "id": "minecraft:slowness", "duration": 1200 }
                          ]
                        }
                      }
                    ]
                  }
                }
                """;

        @Test
        @DisplayName("the README's Inferno Feast loads without a single finding")
        void readmeExample() {
            StubLookup lookup = new StubLookup();
            for (String item : List.of("mypack:fried_chicken", "mypack:red_rum", "mypack:roasted_red_pepper_slice")) {
                lookup.items.put(item, true);
            }
            lookup.effects.addAll(List.of("minecraft:fire_resistance", "minecraft:strength", "minecraft:hunger",
                    "minecraft:weakness", "minecraft:nausea", "minecraft:wither", "minecraft:slowness"));
            lookup.attributes.addAll(List.of("minecraft:generic.attack_damage", "minecraft:generic.armor",
                    "minecraft:generic.movement_speed"));
            listener = new FoodSynergyReloadListener(lookup);

            DatapackDiagnostics.Collector report = load(README_INFERNO);
            assertTrue(report.issues().isEmpty(), report.issues().toString());

            FoodSynergyData inferno = FoodSynergyManager.getSynergy("falleneden:inferno_feast");
            assertEquals(4, inferno.stageCount());
            assertEquals(0, inferno.duration(), "no ceiling: as long as the pepper");
            assertEquals("falleneden:all", inferno.lockoutKey());
            assertEquals(-8, inferno.aftermathOf(4).healthBonus());
            assertEquals(2, inferno.stage(4).effects().get(0).amplifier());
        }

        @Test
        @DisplayName("a lockout_group alone turns the lockout on, on an ordinary synergy too")
        void groupImpliesLockout() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"lockout_group\": \"falleneden:all\"}");
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            FoodSynergyData synergy = FoodSynergyManager.getSynergy("mypack:feast");
            assertFalse(synergy.hasOverdrive());
            assertTrue(synergy.lockout());
            assertEquals("falleneden:all", synergy.lockoutKey());
        }

        @Test
        @DisplayName("lockout: true without a group locks out only the synergy itself")
        void selfLockout() {
            load(HEAD + "\"lockout\": true}");
            assertEquals("mypack:feast", FoodSynergyManager.getSynergy("mypack:feast").lockoutKey());
        }

        @Test
        @DisplayName("a group with lockout switched off is reported and dropped")
        void groupWithoutLockout() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"lockout\": false,"
                    + " \"lockout_group\": \"mypack:overdrive\"}");
            assertEquals(1, of(report, Category.INVALID_LOCKOUT).size());
            assertNull(FoodSynergyManager.getSynergy("mypack:feast").lockoutKey());
            assertNull(FoodSynergyManager.getSynergy("mypack:feast").lockoutGroup());
        }

        @Test
        @DisplayName("an invalid lockout_group is an error, and the synergy locks out only itself")
        void invalidGroup() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"lockout\": true,"
                    + " \"lockout_group\": \"Not A Valid Id!\"}");
            List<Issue> issues = of(report, Category.INVALID_LOCKOUT);
            assertEquals(1, issues.size());
            assertEquals(Severity.ERROR, issues.get(0).severity());
            assertEquals("mypack:feast", FoodSynergyManager.getSynergy("mypack:feast").lockoutKey());
        }

        @Test
        @DisplayName("lockout keys written inside the overdrive are reported as misplaced and ignored")
        void lockoutInsideOverdrive() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"overdrive\": {\"lockout\": true,"
                    + " \"lockout_group\": \"mypack:overdrive\", \"stages\": [{}]}}");
            List<Issue> issues = of(report, Category.INVALID_OVERDRIVE);
            assertEquals(1, issues.size());
            assertTrue(issues.get(0).message().contains("top level"), issues.get(0).message());
            assertTrue(of(report, Category.UNKNOWN_KEY).isEmpty());
            assertNull(FoodSynergyManager.getSynergy("mypack:feast").lockoutKey());
        }

        @Test
        @DisplayName("duration 0 loads cleanly as 'no ceiling'; leaving it out still means 2400")
        void durationZero() {
            DatapackDiagnostics.Collector report = load("{\"entries\": ["
                    + "{\"id\": \"mypack:open\", \"requirements\": [\"minecraft:apple\"], \"duration\": 0,"
                    + " \"effects\": [{\"id\": \"minecraft:speed\"}]},"
                    + "{\"id\": \"mypack:default\", \"requirements\": [\"minecraft:apple\"]}]}");
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            assertEquals(0, FoodSynergyManager.getSynergy("mypack:open").duration());
            assertEquals(2400, FoodSynergyManager.getSynergy("mypack:default").duration());
        }

        @Test
        @DisplayName("unknown keys in the overdrive and in a stage are reported with a suggestion")
        void unknownKeys() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"overdrive\": {\"stage\": [],"
                    + " \"stages\": [{\"healthBonus\": 2}]}}");
            List<Issue> unknown = of(report, Category.UNKNOWN_KEY);
            assertEquals(2, unknown.size(), unknown.toString());
            assertTrue(unknown.get(1).message().contains("overdrive stage II"), unknown.get(1).message());
        }

        @Test
        @DisplayName("a stage that is not an object is skipped, and the report says the rest moved up")
        void stageNotAnObject() {
            DatapackDiagnostics.Collector report = load(HEAD
                    + "\"overdrive\": {\"stages\": [\"hot\", {\"health_bonus\": 6}]}}");
            List<Issue> issues = of(report, Category.INVALID_OVERDRIVE);
            assertEquals(1, issues.size());
            assertTrue(issues.get(0).message().contains("move up"), issues.get(0).message());
            FoodSynergyData synergy = FoodSynergyManager.getSynergy("mypack:feast");
            assertEquals(2, synergy.stageCount());
            assertEquals(6, synergy.stage(2).healthBonus());
        }

        @Test
        @DisplayName("a stage attribute that does not exist costs that line, with the stage named")
        void stageAttributeMissing() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"overdrive\": {\"stages\": [{\"attributes\":"
                    + " [{\"attribute\": \"minecraft:generic.flight\", \"amount\": 1}]}]}}");
            List<Issue> issues = of(report, Category.INVALID_ATTRIBUTE);
            assertEquals(1, issues.size());
            assertTrue(issues.get(0).message().startsWith("overdrive stage II: "), issues.get(0).message());
            assertTrue(FoodSynergyManager.getSynergy("mypack:feast").stage(2).attributes().isEmpty());
        }

        @Test
        @DisplayName("an overdrive that is not an object is ignored with an error")
        void overdriveNotAnObject() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"overdrive\": [1, 2]}");
            assertEquals(1, of(report, Category.INVALID_OVERDRIVE).size());
            assertFalse(FoodSynergyManager.getSynergy("mypack:feast").hasOverdrive());
        }

        @Test
        @DisplayName("an overdrive with no stages past I loads, with a warning that it overloads at once")
        void noStages() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"overdrive\": {}}");
            List<Issue> issues = of(report, Category.INVALID_OVERDRIVE);
            assertEquals(1, issues.size());
            assertEquals(Severity.WARNING, issues.get(0).severity());
            assertEquals(1, FoodSynergyManager.getSynergy("mypack:feast").stageCount());
        }

        @Test
        @DisplayName("timer: own loads, with its duration")
        void ownTimer() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"timer\": \"own\", \"duration\": 6000}");
            assertTrue(report.issues().isEmpty(), report.issues().toString());
            assertTrue(FoodSynergyManager.getSynergy("mypack:feast").ownTimer());
        }

        @Test
        @DisplayName("timer: own with no positive duration is an error, and falls back to members")
        void ownTimerWithoutDuration() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"timer\": \"own\", \"duration\": 0}");
            List<Issue> issues = of(report, Category.INVALID_TIMER);
            assertEquals(1, issues.size());
            assertEquals(Severity.ERROR, issues.get(0).severity());
            assertFalse(FoodSynergyManager.getSynergy("mypack:feast").ownTimer());
        }

        @Test
        @DisplayName("timer: own on an overdrive is an error; the overdrive is kept on the members' timer")
        void ownTimerWithOverdrive() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"timer\": \"own\", \"duration\": 6000,"
                    + " \"overdrive\": {\"stages\": [{}]}}");
            List<Issue> issues = of(report, Category.INVALID_TIMER);
            assertEquals(1, issues.size());
            assertEquals(Severity.ERROR, issues.get(0).severity());
            FoodSynergyData synergy = FoodSynergyManager.getSynergy("mypack:feast");
            assertFalse(synergy.ownTimer());
            assertTrue(synergy.hasOverdrive());
        }

        @Test
        @DisplayName("an unknown timer is an error, and falls back to members")
        void unknownTimer() {
            DatapackDiagnostics.Collector report = load(HEAD + "\"timer\": \"forever\"}");
            assertEquals(1, of(report, Category.INVALID_TIMER).size());
            assertEquals(FoodSynergyData.Timer.MEMBERS, FoodSynergyManager.getSynergy("mypack:feast").timer());
        }
    }
}
