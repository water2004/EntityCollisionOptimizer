package org.edtp.entitycollisionoptimizer.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Normal pet ticks in separate pens: team lookup must stay local to spatial candidates. */
final class TamedAnimalPens extends BenchmarkScenario {
    private static final int DURATION_TICKS = 1000;
    private static final int COLUMNS = 8, ROWS = 4, PEN_PITCH = 6, PEN_SIZE = 4;
    private static final int PETS_PER_PEN = 32;
    private static final int ENTITY_COUNT = COLUMNS * ROWS * PETS_PER_PEN;
    private static final UUID OFFLINE_OWNER = UUID.fromString("ec026200-0000-4000-8000-000000000001");
    private final GameTestHelper helper;
    private final List<Resident> residents = new ArrayList<>(ENTITY_COUNT);
    private final List<ForcedChunk> forcedChunks = new ArrayList<>();
    private final Random random = new Random(0xEC0CA7D06L);
    private BenchmarkPlayer owner;
    private PlayerTeam ownerTeam;

    private record Resident(TamableAnimal animal, int pen, int initialTicks) {}
    private record ForcedChunk(int x, int z) {}

    TamedAnimalPens(GameTestHelper helper) { this.helper = helper; }
    @Override int durationTicks() { return DURATION_TICKS; }
    @Override String name() { return "tamed_animals_separated_pens"; }
    @Override String description() {
        return "cats=512 wolves=512 pens=32 pets_per_pen=32 pen_inner=3x3 "
                + "tamed=true ordered_to_sit=true ai=default gravity=default invulnerable=true "
                + "players=1 online_owner_pets=512 offline_owner_pets=512 collision_rule=always warmup_ticks=0";
    }

    @Override void start() {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // Unlike a bare load ticket, forced chunks also participate in the simulation distance.
        for (int x = origin.getX() >> 4; x <= (origin.getX() + COLUMNS * PEN_PITCH) >> 4; x++) {
            for (int z = origin.getZ() >> 4; z <= (origin.getZ() + ROWS * PEN_PITCH) >> 4; z++) {
                if (helper.getLevel().setChunkForced(x, z, true)) forcedChunks.add(new ForcedChunk(x, z));
                helper.getLevel().getChunk(x, z);
            }
        }
        buildPens();
        owner = new BenchmarkPlayer(helper, "eco-pet-owner", new Vec3(5.5, 1, 5.5));
        ownerTeam = helper.getLevel().getScoreboard().addPlayerTeam(
                "eco_pet_" + owner.player().getUUID().toString().substring(0, 8));
        ownerTeam.setCollisionRule(Team.CollisionRule.ALWAYS);
        helper.getLevel().getScoreboard().addPlayerToTeam(owner.player().getScoreboardName(), ownerTeam);
    }

    private void spawnPets() {
        for (int pen = 0; pen < COLUMNS * ROWS; pen++) {
            int x = pen % COLUMNS * PEN_PITCH, z = pen / COLUMNS * PEN_PITCH;
            UUID ownerId = (pen & 1) == 0 ? owner.player().getUUID() : OFFLINE_OWNER;
            for (int index = 0; index < PETS_PER_PEN; index++) {
                Vec3 position = new Vec3(x + 2.5 + (random.nextDouble() - 0.5) * 0.8,
                        1, z + 2.5 + (random.nextDouble() - 0.5) * 0.8);
                TamableAnimal animal = (index & 1) == 0
                        ? helper.spawn(EntityTypes.CAT, position) : helper.spawn(EntityTypes.WOLF, position);
                animal.setTame(true, true);
                // UUID-only references also cover normal persisted pets whose owner is offline.
                animal.setOwnerReference(EntityReference.of(ownerId));
                animal.setOrderedToSit(true);
                animal.setInSittingPose(true);
                animal.setPermanentlyInvulnerable(true);
                animal.setPersistenceRequired();
                animal.setHealth(animal.getMaxHealth());
                residents.add(new Resident(animal, pen, animal.tickCount));
            }
        }
        verifyPopulation(0);
    }

    private void buildPens() {
        for (int x = 0; x <= COLUMNS * PEN_PITCH; x++) {
            for (int z = 0; z <= ROWS * PEN_PITCH; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        for (int pen = 0; pen < COLUMNS * ROWS; pen++) {
            int baseX = pen % COLUMNS * PEN_PITCH, baseZ = pen / COLUMNS * PEN_PITCH;
            for (int y = 1; y <= 4; y++) {
                for (int x = 0; x <= PEN_SIZE; x++) for (int z = 0; z <= PEN_SIZE; z++) {
                    boolean wall = y == 4 || x == 0 || x == PEN_SIZE || z == 0 || z == PEN_SIZE;
                    helper.setBlock(new BlockPos(baseX + x, y, baseZ + z), wall ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
    }

    @Override boolean ready() {
        if (!residents.isEmpty()) return true;
        for (int pen = 0; pen < COLUMNS * ROWS; pen++) {
            BlockPos position = new BlockPos(pen % COLUMNS * PEN_PITCH + 2, 1, pen / COLUMNS * PEN_PITCH + 2);
            if (!helper.getLevel().isPositionEntityTicking(helper.absolutePos(position))) return false;
        }
        // Chunk loading is asynchronous; admit the pets only after every pen can tick entities.
        spawnPets();
        return true;
    }

    @Override void tick(int tick) { owner.respondToPackets(); }

    @Override void population(int tick) {
        long alive = residents.stream().filter(resident -> resident.animal().isAlive()
                && !resident.animal().isRemoved()).count();
        EntityCollisionOptimizer.LOGGER.info("ECO_PET_POPULATION tick={} resident={} alive={}",
                tick, residents.size(), alive);
    }

    @Override void verify(int ticks) { verifyPopulation(ticks); }

    private void verifyPopulation(int ticks) {
        if (residents.size() != ENTITY_COUNT || owner.player().isRemoved() || !owner.player().isAlive()) {
            throw new IllegalStateException("Incomplete tamed-animal population or missing owner");
        }
        Vec3 origin = helper.absoluteVec(Vec3.ZERO);
        int cats = 0, wolves = 0;
        for (Resident resident : residents) {
            TamableAnimal animal = resident.animal();
            if (animal.getType() == EntityTypes.CAT) cats++;
            else if (animal.getType() == EntityTypes.WOLF) wolves++;
            int x = resident.pen() % COLUMNS * PEN_PITCH, z = resident.pen() / COLUMNS * PEN_PITCH;
            UUID expectedOwner = (resident.pen() & 1) == 0 ? owner.player().getUUID() : OFFLINE_OWNER;
            PlayerTeam expectedTeam = (resident.pen() & 1) == 0 ? ownerTeam : null;
            if (animal.isRemoved() || !animal.isAlive()) {
                throw new IllegalStateException("Pet was removed or died: pen=" + resident.pen());
            }
            if (!animal.isTame() || !animal.isOrderedToSit() || animal.isNoAi() || animal.isNoGravity()) {
                throw new IllegalStateException("Pet lost its tame/sitting state or normal AI/gravity: pen=" + resident.pen());
            }
            if (!animal.isPushable()) {
                throw new IllegalStateException("Pet stopped being a pushable collision candidate: pen=" + resident.pen());
            }
            if (animal.getOwnerReference() == null || !animal.getOwnerReference().getUUID().equals(expectedOwner)
                    || animal.getTeam() != expectedTeam) {
                throw new IllegalStateException("Pet lost its derived owner team: pen=" + resident.pen()
                        + " expected=" + expectedTeam + " actual=" + animal.getTeam());
            }
            if (animal.tickCount - resident.initialTicks() < ticks) {
                throw new IllegalStateException("Pet did not receive every benchmark tick: pen=" + resident.pen()
                        + " expected=" + ticks + " actual=" + (animal.tickCount - resident.initialTicks()));
            }
            if (animal.getX() < origin.x + x + 1 || animal.getX() >= origin.x + x + PEN_SIZE
                    || animal.getZ() < origin.z + z + 1 || animal.getZ() >= origin.z + z + PEN_SIZE
                    || animal.getY() < origin.y + 0.9 || animal.getY() >= origin.y + 2) {
                throw new IllegalStateException("Pet escaped its pen: pen=" + resident.pen()
                        + " position=" + animal.position() + " origin=" + origin);
            }
        }
        if (cats != ENTITY_COUNT / 2 || wolves != ENTITY_COUNT / 2) {
            throw new IllegalStateException("Incorrect cat/wolf split");
        }
    }

    @Override String summary() {
        return "resident=" + residents.size() + " cats=512 wolves=512 online_owner_pets=512 offline_owner_pets=512";
    }

    @Override void cleanup() {
        residents.forEach(resident -> resident.animal().discard());
        residents.clear();
        if (ownerTeam != null) {
            helper.getLevel().getScoreboard().removePlayerTeam(ownerTeam);
            ownerTeam = null;
        }
        if (owner != null) {
            owner.close();
            owner = null;
        }
        forcedChunks.forEach(chunk -> helper.getLevel().setChunkForced(chunk.x(), chunk.z(), false));
        forcedChunks.clear();
    }
}
