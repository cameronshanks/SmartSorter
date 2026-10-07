package net.shaddii.smartsorter.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/**
 * Smart Sorter's config, stored in config/smartsorter.properties.
 *
 * Values are read once at startup into plain
 * static fields so the hot path never touches the Properties object. After
 * loading, the file is rewritten with every known key (and its comment) so new
 * options show up for the user with their defaults.
 */
public final class SmartSorterConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("smartsorter");
    private static final String FILE_NAME = "smartsorter.properties";

    /** Keep chunks of controllers, intakes, output probes and their target chests loaded. */
    public static boolean keepChunksLoaded = false;

    /** Upper bound on chunks Smart Sorter keeps loaded across all dimensions. */
    public static int maxForcedChunks = 256;

    /**
     * Safety net for chest-contents caches: a snapshot older than this is
     * rebuilt even if its chest never reported a change (covers modded
     * inventories that mutate stacks without markDirty). 0 disables it.
     */
    public static int chestCacheMaxAgeTicks = 200;

    /** How long an unroutable item waits before the intake tries it again. */
    public static int stuckRetryTicks = 30;

    /** Buffered stacks an intake holds before it pushes remainders back to its source. */
    public static int intakeBufferSlots = 9;

    /** Log a line when an intake item first becomes stuck. */
    public static boolean logStuckItems = true;

    /**
     * Optional "x y z" of a chest (or an output probe, meaning its target chest)
     * that stuck intake items are sent to. Same dimension as the intake. Null
     * when unset.
     */
    public static BlockPos stuckOverflowTarget = null;

    private SmartSorterConfig() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties props = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                LOGGER.warn("[Smart Sorter] Could not read {}, using defaults", path, e);
            }
        }

        keepChunksLoaded = parseBoolean(props, "keepChunksLoaded", keepChunksLoaded);
        maxForcedChunks = Math.max(0, parseInt(props, "maxForcedChunks", maxForcedChunks));
        chestCacheMaxAgeTicks = Math.max(0, parseInt(props, "chestCacheMaxAgeTicks", chestCacheMaxAgeTicks));
        stuckRetryTicks = Math.max(1, parseInt(props, "stuckRetryTicks", stuckRetryTicks));
        intakeBufferSlots = Math.max(1, parseInt(props, "intakeBufferSlots", intakeBufferSlots));
        logStuckItems = parseBoolean(props, "logStuckItems", logStuckItems);
        stuckOverflowTarget = parsePos(props.getProperty("stuckOverflowTarget", ""));

        save(path);
    }

    private static void save(Path path) {
        String target = stuckOverflowTarget == null ? ""
                : stuckOverflowTarget.getX() + " " + stuckOverflowTarget.getY() + " " + stuckOverflowTarget.getZ();
        String text = """
                # Smart Sorter config. Restart the game/server after editing.

                # Keep chunks loaded for storage controllers, intakes, output probes and
                # their target chests (uses chunk tickets; released when the last block in a
                # chunk is removed).
                keepChunksLoaded=%s

                # Maximum chunks the option above may keep loaded. Extra chunks are skipped
                # with a warning in the log.
                maxForcedChunks=%d

                # Chest-contents caches are rebuilt when their chest changes. As a safety net
                # for inventories that change without notifying, they are also rebuilt when
                # older than this many ticks. 0 = never.
                chestCacheMaxAgeTicks=%d

                # Ticks an item with no destination waits before the intake retries it (20-40
                # recommended).
                stuckRetryTicks=%d

                # Stacks an intake can buffer when chests fill up mid-insert.
                intakeBufferSlots=%d

                # Log a line when an intake item first gets stuck.
                logStuckItems=%s

                # Optional "x y z" of a chest or output probe that receives stuck intake items
                # (same dimension as the intake). Leave empty to disable.
                stuckOverflowTarget=%s
                """.formatted(keepChunksLoaded, maxForcedChunks, chestCacheMaxAgeTicks, stuckRetryTicks,
                intakeBufferSlots, logStuckItems, target);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write(text);
            }
        } catch (IOException e) {
            LOGGER.warn("[Smart Sorter] Could not write {}", path, e);
        }
    }

    private static boolean parseBoolean(Properties props, String key, boolean fallback) {
        String value = props.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int parseInt(Properties props, String key, int fallback) {
        String value = props.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("[Smart Sorter] Invalid number for {}: '{}', using {}", key, value, fallback);
            return fallback;
        }
    }

    private static BlockPos parsePos(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("[\\s,]+");
        if (parts.length != 3) {
            LOGGER.warn("[Smart Sorter] stuckOverflowTarget must be 'x y z', got '{}'", value);
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            LOGGER.warn("[Smart Sorter] stuckOverflowTarget must be 'x y z', got '{}'", value);
            return null;
        }
    }
}
