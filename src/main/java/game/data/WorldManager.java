package game.data;

import config.Config;
import game.data.chunk.Chunk;
import game.data.chunk.ChunkBinary;
import game.data.chunk.ChunkFactory;
import game.data.chunk.palette.BlockColors;
import game.data.chunk.palette.BlockState;
import game.data.container.ContainerManager;
import game.data.container.ItemRegistry;
import game.data.container.MenuRegistry;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDim2D;
import game.data.coordinates.CoordinateDouble3D;
import game.data.dimension.Dimension;
import game.data.dimension.DimensionCodec;
import game.data.entity.EntityNames;
import game.data.region.McaFile;
import game.data.region.Region;
import gui.GuiManager;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import util.PathUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manage the world, including saving, parsing and updating the GUI.
 */
public class WorldManager {
    private static final int INIT_SAVE_DELAY = 5 * 1000;
    private static final int SAVE_DELAY = 15 * 1000;
    private static WorldManager instance;
    private final LevelData levelData;
    private final Map<CoordinateDim2D, Queue<Runnable>> chunkLoadCallbacks = new ConcurrentHashMap<>();
    private Map<CoordinateDim2D, Region> regions = new ConcurrentHashMap<>();
    private Set<Dimension> existingLoaded = new HashSet<>();

    private EntityNames entityMap;
    private MenuRegistry menuRegistry;
    private ItemRegistry itemRegistry;
    private BlockColors blockColors;

    private boolean markNewChunks;
    private boolean writeChunks;
    private boolean isStarted;
    private boolean isPaused;
    private boolean isSaving;

    private ContainerManager containerManager;
    private DimensionCodec dimensionCodec;
    private RenderDistanceExtender renderDistanceExtender;

    private BiConsumer<CoordinateDouble3D, Double> playerPosListener;
    private CoordinateDouble3D playerPosition;
    private double playerRotation = 0;
    private Dimension dimension;

    protected WorldManager() {
        this.isStarted = false;

        this.levelData = new LevelData(this);

        try {
            this.levelData.load();
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        this.playerPosition = this.levelData.getPlayerPosition();
        this.dimension = this.levelData.getPlayerDimension();
    }

    public static WorldManager getInstance() {
        if (instance == null) {
            instance = new WorldManager();
        }
        return instance;
    }

    public static void setInstance(WorldManager worldManager) {
        instance = worldManager;
    }

    public void registerChunkLoadCallback(CoordinateDim2D coordinate, Runnable r) {
        chunkLoadCallbacks.putIfAbsent(coordinate, new ConcurrentLinkedQueue<>());
        chunkLoadCallbacks.get(coordinate).add(r);
    }

    public void deregisterChunkLoadCallback(CoordinateDim2D coordinate, Runnable r) {
        if (chunkLoadCallbacks.containsKey(coordinate)) {
            chunkLoadCallbacks.get(coordinate).remove(r);
        }
    }

    public Dimension getDimension() {
        return dimension;
    }

    public void setDimension(Dimension dimension) {
        this.dimension = dimension;

        if (this.renderDistanceExtender != null) {
            this.renderDistanceExtender.invalidateChunks();
        }
        GuiManager.setDimension(this.dimension);
        outlineExistingChunks();
    }

    public double getPlayerRotation() {
        return playerRotation;
    }

    public void setPlayerRotation(double playerRotation) {
        this.playerRotation = playerRotation;

        if (this.playerPosListener != null) {
            this.playerPosListener.accept(this.playerPosition, this.playerRotation);
        }
    }

    public void updateExtendedRenderDistance(int val) {
        if (val == 0 && this.renderDistanceExtender == null) {
            return;
        }

        if (val > 0 && this.renderDistanceExtender == null) {
            this.renderDistanceExtender = new RenderDistanceExtender(this, val);
        } else {
            this.renderDistanceExtender.setExtendedDistance(val);
        }
    }

    public void outlineExistingChunks() {
        if (existingLoaded.contains(this.dimension)) {
            return;
        }
        existingLoaded.add(this.dimension);

        Stream<McaFile> files = null;
        try {
            files = getMcaFiles(dimension, true);
        } catch (IOException e) {
            e.printStackTrace();
        }

        GuiManager.outlineExistingChunks(
                files.flatMap(el -> el.getChunkPositions(this.dimension).stream()).collect(Collectors.toList())
        );
    }

    /**
     * Draw all previously-downloaded chunks in the GUI. We can't just load them all and immediately draw them to the
     * GUI, as the shading requires that we look at neighbouring chunks. We first add them all to the world manager,
     * then draw them, and then delete them. This is more work but ensures proper shading on all chunks.
     */
    public void drawExistingChunks() {
        Stream<McaFile> files = getMcaFiles(8);

        files.forEach(file -> {
            Map<CoordinateDim2D, Chunk> chunks = file.getParsedChunks(this.dimension);

            // Step 2: add all chunks to the WorldManager if it doesn't have them yet
            Set<CoordinateDim2D> toDelete = new HashSet<>();
            chunks.forEach((coord, chunk) -> {
                if (getChunk(coord) == null) {
                    toDelete.add(coord);
                    loadChunk(chunk, true, false);
                }
            });

            // Step 3: draw to GUI
            chunks.forEach(GuiManager::setChunkLoaded);

            // Step 4: delete the newly added chunks
            toDelete.forEach(this::unloadChunk);
        });
    }

    private Stream<McaFile> getMcaFiles(int radius) {
        Path exportDir = PathUtils.toPath(Config.getWorldOutputDir(), dimension.getPath(), "region");

        if (!exportDir.toFile().exists()) {
            return Stream.empty();
        }
        List<File> files = new ArrayList<>();
        Coordinate2D center = playerPosition.discretize().globalToChunk().chunkToRegion().offsetChunk();
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                files.add(McaFile.coordinatesToFile(exportDir, center.add(x, z)));
            }
        }
        return files.stream().filter(Objects::nonNull).map(el -> {
            try {
                return new McaFile(el);
            } catch (IOException e) {
                return null;
            }
        }).filter(Objects::nonNull);
    }

    /**
     * Read from the save path to see which chunks have been saved already.
     */
    private Stream<McaFile> getMcaFiles(Dimension dimension, boolean limit) throws IOException {
        Path exportDir = PathUtils.toPath(Config.getWorldOutputDir(), dimension.getPath(), "region");

        if (!exportDir.toFile().exists()) {
            return Stream.empty();
        }

        Stream<File> stream = Files.walk(exportDir)
                .filter(el -> el.getFileName().toString().endsWith(".mca"))
                .map(Path::toFile);

        if (limit) {
            stream = stream.limit(100); // don't load more than 100 region files
        }

        return stream.filter(el -> el.length() > 0)
                .map(el -> {
                    try {
                        return new McaFile(el);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    return null;
                })
                .filter(Objects::nonNull);
    }

    /**
     * Set the config variables for the save service.
     */
    public void setSaveServiceVariables(boolean markNewChunks, Boolean writeChunks) {
        this.markNewChunks = markNewChunks;
        this.writeChunks = writeChunks;

        blockColors = BlockColors.create();
    }

    /**
     * Start the periodic saving service.
     */
    public void startSaveService() {
        if (isStarted) {
            return;
        }
        isStarted = true;

        instance.start();

        ChunkFactory.getInstance().parseEntities();
    }

    /**
     * Add a parsed chunk to the correct region.
     *
     * @param chunk the chunk
     */
    public void loadChunk(Chunk chunk, boolean drawInGui, boolean overrideExisting) {
        if (!drawInGui || writeChunks) {
            CoordinateDim2D regionCoordinates = chunk.location.chunkToDimRegion();

            if (!regions.containsKey(regionCoordinates)) {
                regions.put(regionCoordinates, new Region(regionCoordinates));
            }

            regions.get(regionCoordinates).addChunk(chunk.location, chunk, overrideExisting);
        }

        if (drawInGui) {
            // draw the chunk once its been parsed
            chunk.whenParsed(() -> {
                GuiManager.setChunkLoaded(chunk.location, chunk);
            });
        }

        if (this.renderDistanceExtender != null) {
            this.renderDistanceExtender.updateDistance(chunk.location);
        }
    }

    public void chunkLoadedCallback(CoordinateDim2D coordinateDim2D) {
        // run callbacks
        Queue<Runnable> callbacks = chunkLoadCallbacks.remove(coordinateDim2D);
        if (callbacks != null) {
            while (!callbacks.isEmpty()) {
                callbacks.remove().run();
            }
        }
    }

    /**
     * Get a chunk from the region its in.
     *
     * @param coordinate the global chunk coordinates
     * @return the chunk
     */
    public Chunk getChunk(CoordinateDim2D coordinate) {
        return regions.getOrDefault(coordinate.chunkToDimRegion(), Region.EMPTY).getChunk(coordinate);
    }

    public void unloadChunk(CoordinateDim2D coordinate) {
        Region r = regions.get(coordinate.chunkToDimRegion());
        if (r != null) {
            r.removeChunk(coordinate);
        }
    }

    public BlockState blockStateAt(Coordinate3D coordinate3D) {
        Chunk c = this.getChunk(coordinate3D.globalToChunk().addDimension(this.dimension));

        if (c == null) {
            return null;
        }

        Coordinate3D pos = coordinate3D.withinChunk();
        return c.getBlockStateAt(pos);
    }

    public EntityNames getEntityMap() {
        return entityMap;
    }

    public void setEntityMap(EntityNames names) {
        entityMap = names;
    }

    public BlockColors getBlockColors() {
        return blockColors;
    }

    public boolean markNewChunks() {
        return markNewChunks;
    }

    public MenuRegistry getMenuRegistry() {
        return menuRegistry;
    }

    public void setMenuRegistry(MenuRegistry menus) {
        menuRegistry = menus;
    }

    public ItemRegistry getItemRegistry() {
        return itemRegistry;
    }

    public void setItemRegistry(ItemRegistry items) {
        itemRegistry = items;
    }

    /**
     * Mark a chunk and it's region as having unsaved changes.
     */
    public void touchChunk(Chunk c) {
        c.touch();
        regions.get(c.location.chunkToDimRegion()).touch();
    }

    public DimensionCodec getDimensionCodec() {
        return dimensionCodec;
    }

    /**
     * Set the dimension codec, used to store information about the dimensions that this server supports.
     */
    public void setDimensionCodec(DimensionCodec codec) {
        dimensionCodec = codec;

        // We can immediately try to write the dimension data to the proper directory.
        try {
            Path p = PathUtils.toPath(Config.getWorldOutputDir(), "datapacks", "downloaded", "data");
            if (codec.write(p)) {

                // we need to copy that pack.mcmeta file from so that Minecraft will recognise the datapack
                Path packMeta = PathUtils.toPath(p.getParent().toString(), "pack.mcmeta");
                InputStream in = WorldManager.class.getClassLoader().getResourceAsStream("pack.mcmeta");
                byte[] bytes = IOUtils.toByteArray(in);
                Files.write(packMeta, bytes);
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Could not write dimension codec. Custom dimensions may not work properly.");
        }
    }

    /**
     * Periodically save the world.
     */
    public void start() {
        ThreadFactory namedThreadFactory = r -> new Thread(r, "World Save Service");
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1, namedThreadFactory);
        executor.scheduleWithFixedDelay(this::save, INIT_SAVE_DELAY, SAVE_DELAY, TimeUnit.MILLISECONDS);
    }


    /**
     * Save the world. Will tell all regions to save their chunks.
     */
    public void save() {
        if (!writeChunks) {
            return;
        }

        // make sure we can't have two saving calls at once (due to save & exit)
        if (isSaving) {
            return;
        }
        isSaving = true;


        if (!regions.isEmpty()) {
            // convert the values to an array first to prevent blocking any threads
            Region[] r = regions.values().toArray(new Region[0]);
            for (Region region : r) {
                McaFile file = region.toFile(getPlayerPosition());
                if (file == null) {
                    continue;
                }

                try {
                    file.write();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // save level.dat
        try {
            levelData.save();
        } catch (Exception e) {
            e.printStackTrace();
        }


        // remove empty regions
        regions.entrySet().removeIf(el -> el.getValue().isEmpty());

        isSaving = false;

        if (this.renderDistanceExtender != null) {
            this.renderDistanceExtender.checkDistance();
        }
    }

    public ContainerManager getContainerManager() {
        if (containerManager == null) {
            containerManager = new ContainerManager();
        }
        return containerManager;
    }

    public void pauseSaving() {
        isPaused = true;
        System.out.println("Pausing");
    }

    public void resumeSaving() {
        isPaused = false;
        System.out.println("Resuming");
    }

    public void deleteAllExisting() {
        regions = new HashMap<>();
        ChunkFactory.getInstance().clear();

        try {
            File dir = PathUtils.toPath(Config.getWorldOutputDir(), this.dimension.getPath(), "region").toFile();

            if (dir.isDirectory()) {
                FileUtils.cleanDirectory(dir);
            }
        } catch (IOException ex) {
            System.out.println("Could not delete region files. Reason: " + ex.getMessage());
        }

        GuiManager.clearChunks();
    }

    public boolean isPaused() {
        return isPaused;
    }

    public void setPlayerPosListener(BiConsumer<CoordinateDouble3D, Double> playerPosListener) {
        this.playerPosListener = playerPosListener;
    }

    public Coordinate3D getPlayerPosition() {
        return playerPosition.discretize();
    }

    public void setPlayerPosition(CoordinateDouble3D newPos) {
        this.playerPosition = newPos;

        if (this.renderDistanceExtender != null) {
            this.renderDistanceExtender.updatePlayerPos(getPlayerPosition());
        }
        if (this.playerPosListener != null) {
            this.playerPosListener.accept(newPos, this.playerRotation);
        }
    }

    public void setServerRenderDistance(int viewDist) {
        if (renderDistanceExtender != null) {
            renderDistanceExtender.setServerReportedRenderDistance(viewDist);
        }

    }

    /**
     * Send unload chunk packets to the client for each of the coordinates. Currently not used as chunk unloading is
     * not really important, the client can figure it out.
     *
     * @param toUnload the set of chunks to unload.
     */
    public void unloadChunks(Collection<Coordinate2D> toUnload) {
        for (Coordinate2D coords : toUnload) {
            unloadChunk(coords.addDimension(getDimension()));
        }
    }

    /**
     * Load chunks from their MCA files and send them to the client.
     *
     * @param desired the set of chunk coordinates which we want to send to the client.
     * @return the set of chunks that was actually sent to the client.
     */
    public Set<Coordinate2D> loadChunks(Collection<Coordinate2D> desired) {
        Set<Coordinate2D> loaded = new HashSet<>();

        // separate into McaFiles
        Map<Coordinate2D, List<Coordinate2D>> mcaFiles = desired.stream().collect(Collectors.groupingBy(Coordinate2D::chunkToRegion));

        // we need to avoid overwhelming the client with tons of chunks all at once, so we insert a small delay every
        // few chunks to avoid this.
        int chunksSent = 0;
        for (Map.Entry<Coordinate2D, List<Coordinate2D>> entry : mcaFiles.entrySet()) {
            Coordinate2D key = entry.getKey().offsetRegion();
            List<Coordinate2D> value = entry.getValue();

            String filename = "r." + key.getX() + "." + key.getZ() + ".mca";
            File f = PathUtils.toPath(Config.getWorldOutputDir(), this.dimension.getPath(), "region", filename).toFile();

            if (!f.exists()) {
                continue;
            }

            // Load the MCA file - if it cannot be loaded for any reason it's skipped.
            McaFile m;
            try {
                m = new McaFile(f);
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("Skipping invalid MCA file.");
                continue;
            }

            // loop through the list of chunks we want to load from this file
            for (Coordinate2D coord : value) {
                CoordinateDim2D withDim = coord.addDimension(this.dimension);
                ChunkBinary chunkBinary = m.getChunkBinary(withDim);

                // skip any chunks not in the MCA file
                if (chunkBinary == null) {
                    continue;
                }

                // send a packet with the chunk to the client
                Chunk chunk = chunkBinary.toChunk(withDim);
                Config.getPacketInjector().accept(chunk.toPacket());
                loaded.add(coord);

                // draw in GUI
                loadChunk(chunk, true, false);

                // periodically sleep so the client doesn't stutter from receiving too many chunks
                chunksSent = (chunksSent + 1) % 5;
                if (chunksSent == 0) {
                    try {
                        Thread.sleep(24);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

        }
        return loaded;
    }

    public void resetConnection() {
        if (this.renderDistanceExtender != null) {
            this.renderDistanceExtender.resetConnection();
        }
    }
}

