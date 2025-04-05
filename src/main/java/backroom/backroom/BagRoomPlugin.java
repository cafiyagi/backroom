package backroom.backroom;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BagRoomPlugin extends JavaPlugin implements Listener {

    // Configuration
    private int ROOM_MIN = -5000;
    private int ROOM_MAX = 5000;
    private int FLOOR_HEIGHT = 5; // Height between floors
    private int MAX_LEVELS = 3; // Number of backroom levels
    private int WALL_HEIGHT = 4; // Height of walls
    private double EXIT_CHANCE = 0.005; // Chance of finding an exit in any chunk
    private boolean enableLightFlicker = true;
    private boolean enableAmbientSounds = true;
    private boolean enableFogEffect = true;
    private double difficultyScaling = 1.0; // Scales how difficult higher levels are

    // World data
    private final Map<String, Integer> playerLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlickerTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sanityLevels = new ConcurrentHashMap<>();
    private final Set<Location> exitLocations = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Random generation
    private final Random random = new Random();

    // Materials for different levels
    private final Material[][] LEVEL_MATERIALS = {
            // Level 0 - Classic yellow backrooms
            {
                    Material.YELLOW_CONCRETE, // Floor
                    Material.YELLOW_TERRACOTTA, // Walls
                    Material.YELLOW_STAINED_GLASS, // Ceiling
                    Material.GLOWSTONE // Lights
            },
            // Level 1 - Darker, more damaged
            {
                    Material.YELLOW_TERRACOTTA, // Floor
                    Material.YELLOW_CONCRETE, // Walls
                    Material.YELLOW_WOOL, // Ceiling
                    Material.REDSTONE_LAMP // Lights (can flicker)
            },
            // Level 2 - Abandoned and overgrown
            {
                    Material.YELLOW_CONCRETE_POWDER, // Floor
                    Material.STRIPPED_BIRCH_WOOD, // Walls
                    Material.BIRCH_PLANKS, // Ceiling
                    Material.LANTERN // Lights (sparse)
            }
    };

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();
        loadConfig();

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Register commands
        getCommand("backroom").setExecutor(new BackroomCommand());
        getCommand("exitbackroom").setExecutor(new ExitBackroomCommand());

        // Initialize worlds if they don't exist
        for (int level = 0; level < MAX_LEVELS; level++) {
            String worldName = "backroom_level_" + level;
            if (Bukkit.getWorld(worldName) == null) {
                createBackroomWorld(level);
            }
        }

        // Schedule ambient tasks
        if (enableLightFlicker) {
            startLightFlickerTask();
        }

        if (enableAmbientSounds) {
            startAmbientSoundTask();
        }

        if (enableFogEffect) {
            startFogEffectTask();
        }

        getLogger().info("Backrooms plugin enabled. No-clipping into reality...");
    }

    @Override
    public void onDisable() {
        // Cancel all tasks
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("Backrooms plugin disabled. Returned to reality.");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();

        // Set defaults if not present
        config.addDefault("room_min", ROOM_MIN);
        config.addDefault("room_max", ROOM_MAX);
        config.addDefault("floor_height", FLOOR_HEIGHT);
        config.addDefault("max_levels", MAX_LEVELS);
        config.addDefault("wall_height", WALL_HEIGHT);
        config.addDefault("exit_chance", EXIT_CHANCE);
        config.addDefault("enable_light_flicker", enableLightFlicker);
        config.addDefault("enable_ambient_sounds", enableAmbientSounds);
        config.addDefault("enable_fog_effect", enableFogEffect);
        config.addDefault("difficulty_scaling", difficultyScaling);
        config.options().copyDefaults(true);
        saveConfig();

        // Load values
        ROOM_MIN = config.getInt("room_min");
        ROOM_MAX = config.getInt("room_max");
        FLOOR_HEIGHT = config.getInt("floor_height");
        MAX_LEVELS = config.getInt("max_levels");
        WALL_HEIGHT = config.getInt("wall_height");
        EXIT_CHANCE = config.getDouble("exit_chance");
        enableLightFlicker = config.getBoolean("enable_light_flicker");
        enableAmbientSounds = config.getBoolean("enable_ambient_sounds");
        enableFogEffect = config.getBoolean("enable_fog_effect");
        difficultyScaling = config.getDouble("difficulty_scaling");
    }

    private World createBackroomWorld(int level) {
        String worldName = "backroom_level_" + level;
        WorldCreator creator = new WorldCreator(worldName);
        creator.environment(World.Environment.NORMAL);
        creator.generator(new BackroomGenerator(level));
        creator.type(WorldType.FLAT);
        creator.generateStructures(false);

        World world = Bukkit.createWorld(creator);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setTime(18000); // Perpetual night for atmosphere

        return world;
    }

    private void startLightFlickerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isInBackroom(player)) {
                        // Only flicker if enough time has passed
                        UUID uuid = player.getUniqueId();
                        long currentTime = System.currentTimeMillis();
                        if (!lastFlickerTime.containsKey(uuid) ||
                                currentTime - lastFlickerTime.get(uuid) > 30000) { // 30 seconds between flickers

                            if (random.nextDouble() < 0.2) { // 20% chance to flicker
                                flickerLightsAroundPlayer(player);
                                lastFlickerTime.put(uuid, currentTime);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100, 100); // Check every 5 seconds
    }

    private void startAmbientSoundTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isInBackroom(player)) {
                        // Play ambient sounds
                        int level = getPlayerLevel(player);

                        switch (level) {
                            case 0:
                                // Fluorescent light buzz
                                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.2f, 1.0f);
                                break;
                            case 1:
                                // Distant footsteps and machinery
                                if (random.nextBoolean()) {
                                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.2f, 0.8f);
                                } else {
                                    player.playSound(player.getLocation(), Sound.BLOCK_METAL_PLACE, 0.1f, 0.5f);
                                }
                                break;
                            case 2:
                                // Water drips and creaks
                                if (random.nextBoolean()) {
                                    player.playSound(player.getLocation(), Sound.BLOCK_LADDER_STEP, 0.1f, 0.5f);
                                } else {
                                    player.playSound(player.getLocation(), Sound.AMBIENT_CAVE, 0.2f, 0.7f);
                                }
                                break;
                        }
                    }
                }
            }
        }.runTaskTimer(this, 60, 160); // Every 8 seconds
    }

    private void startFogEffectTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isInBackroom(player)) {
                        // Apply fog effect (blindness with very short duration)
                        int level = getPlayerLevel(player);
                        if (random.nextDouble() < 0.1 * (level + 1)) {
                            player.addPotionEffect(new PotionEffect(
                                    PotionEffectType.BLINDNESS, 20, 0, false, false));
                        }

                        // Apply nausea on deeper levels
                        if (level > 0 && random.nextDouble() < 0.05 * level) {
                            player.addPotionEffect(new PotionEffect(
                                    PotionEffectType.NAUSEA, 100, 0, false, false));
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100, 400); // Every 20 seconds
    }

    private void flickerLightsAroundPlayer(Player player) {
        int radius = 20;
        Location loc = player.getLocation();
        World world = player.getWorld();

        // Get player's current level
        int level = getPlayerLevel(player);
        Material lightMaterial = LEVEL_MATERIALS[level][3];

        // Find light blocks in radius
        List<Block> lightBlocks = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = world.getBlockAt(
                            loc.getBlockX() + x,
                            loc.getBlockY() + y,
                            loc.getBlockZ() + z
                    );
                    if (block.getType() == lightMaterial) {
                        lightBlocks.add(block);
                    }
                }
            }
        }

        // Flicker effect - turn off and on
        if (!lightBlocks.isEmpty()) {
            // Send message to player
            player.sendMessage(ChatColor.DARK_RED + "The lights flicker momentarily...");

            new BukkitRunnable() {
                @Override
                public void run() {
                    // Turn lights off
                    for (Block light : lightBlocks) {
                        player.sendBlockChange(light.getLocation(), Material.AIR.createBlockData());
                    }

                    // Play sound
                    player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.3f, 1.5f);

                    // Darkness effect
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));

                    // Schedule turn back on
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // Turn lights back on
                            for (Block light : lightBlocks) {
                                player.sendBlockChange(light.getLocation(), light.getBlockData());
                            }

                            // Play sound
                            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.2f, 1.2f);
                        }
                    }.runTaskLater(BagRoomPlugin.this, 15); // 0.75 seconds later
                }
            }.runTaskLater(this, 5); // 0.25 seconds delay
        }
    }

    private boolean isInBackroom(Player player) {
        String worldName = player.getWorld().getName();
        return worldName.startsWith("backroom_level_");
    }

    private int getPlayerLevel(Player player) {
        if (!isInBackroom(player)) {
            return 0;
        }

        String worldName = player.getWorld().getName();
        try {
            return Integer.parseInt(worldName.replace("backroom_level_", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isInBackroom(player)) return;

        Location to = event.getTo();
        int level = getPlayerLevel(player);
        World world = player.getWorld();

        // Check if player is outside boundaries
        if (to.getBlockX() < ROOM_MIN || to.getBlockX() > ROOM_MAX ||
                to.getBlockZ() < ROOM_MIN || to.getBlockZ() > ROOM_MAX) {

            // Teleport to a random location within boundaries
            int randomX = random.nextInt(ROOM_MAX - ROOM_MIN) + ROOM_MIN;
            int randomZ = random.nextInt(ROOM_MAX - ROOM_MIN) + ROOM_MIN;
            int y = 65 + (level * FLOOR_HEIGHT); // Level-specific height

            Location newLoc = new Location(world, randomX + 0.5, y, randomZ + 0.5);
            player.teleport(newLoc);
            player.sendMessage(ChatColor.RED + "You can't escape the Backrooms that easily...");
            return;
        }

        // Check for exit (emerald block) - store in memory for better performance
        Block block = world.getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());

        // Cache exit locations to avoid repeated checks
        Location blockLoc = block.getLocation();
        if (exitLocations.contains(blockLoc)) {
            handleExit(player, level);
            return;
        }

        if (block.getType() == Material.EMERALD_BLOCK) {
            exitLocations.add(blockLoc);
            handleExit(player, level);
        }

        // Special locations: Stairs down to the next level (if not at max depth)
        if (level < MAX_LEVELS - 1 && block.getType() == Material.MOSSY_COBBLESTONE) {
            int newLevel = level + 1;

            // Create warning
            player.sendMessage(ChatColor.DARK_RED + "You feel a strange pulling sensation...");
            player.sendMessage(ChatColor.RED + "Something tells you not to go any deeper...");

            // Teleport to the next level down
            World nextWorld = Bukkit.getWorld("backroom_level_" + newLevel);
            if (nextWorld == null) {
                nextWorld = createBackroomWorld(newLevel);
            }

            // Random location in the next level
            int x = random.nextInt(100) - 50;
            int z = random.nextInt(100) - 50;
            int y = 65 + (newLevel * FLOOR_HEIGHT);

            // Ensure there's air at the destination
            Location destination = new Location(nextWorld, x, y, z);
            player.teleport(destination);

            // Apply effects
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);

            // Message
            player.sendMessage(ChatColor.DARK_RED + "You've descended to Backrooms Level " + newLevel);
            player.sendMessage(ChatColor.RED + "The air feels thicker here...");

            // Update player's tracked level
            playerLevels.put(player.getName(), newLevel);
        }
    }

    private void handleExit(Player player, int level) {
        // Different outcomes based on level
        switch (level) {
            case 0:
                // Level 0: Return to the main world
                World mainWorld = Bukkit.getWorld("world");
                if (mainWorld == null) mainWorld = Bukkit.getWorlds().get(0);

                player.sendMessage(ChatColor.GREEN + "You found an exit from the Backrooms!");
                player.teleport(mainWorld.getSpawnLocation());

                // Clear any effects
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }

                // Give reward
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                player.sendMessage(ChatColor.GOLD + "You escaped the Backrooms and returned to reality!");
                break;

            default:
                // Deeper levels: Go up one level
                int newLevel = level - 1;
                World upperWorld = Bukkit.getWorld("backroom_level_" + newLevel);

                player.sendMessage(ChatColor.YELLOW + "You found a way up...");

                // Random location in upper level
                int x = random.nextInt(200) - 100;
                int z = random.nextInt(200) - 100;
                int y = 65 + (newLevel * FLOOR_HEIGHT);

                Location destination = new Location(upperWorld, x, y, z);
                player.teleport(destination);

                // Effects
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
                player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.5f, 1.0f);

                player.sendMessage(ChatColor.YELLOW + "You've ascended to Backrooms Level " + newLevel);

                // Update player's tracked level
                playerLevels.put(player.getName(), newLevel);
                break;
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        if (!isInBackroom(player)) return;

        // No fall damage in backrooms
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isInBackroom(player)) return;

        // Prevent block breaking unless in creative mode
        if (player.getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot modify the Backrooms...");
        }
    }

    // Command to enter the backrooms
    private class BackroomCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players");
                return true;
            }

            Player player = (Player) sender;

            // Parse level argument if provided
            int level = 0;
            if (args.length > 0) {
                try {
                    level = Integer.parseInt(args[0]);
                    if (level < 0 || level >= MAX_LEVELS) {
                        player.sendMessage(ChatColor.RED + "Invalid level. Must be between 0 and " + (MAX_LEVELS - 1));
                        return true;
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Invalid level number");
                    return true;
                }
            }

            // Get or create world
            String worldName = "backroom_level_" + level;
            World backroomWorld = Bukkit.getWorld(worldName);
            if (backroomWorld == null) {
                backroomWorld = createBackroomWorld(level);
            }

            // Teleport player to a random location
            int x = random.nextInt(100) - 50; // Small range for initial spawn
            int z = random.nextInt(100) - 50;
            int y = 65 + (level * FLOOR_HEIGHT); // Level-specific height

            Location spawnLoc = new Location(backroomWorld, x + 0.5, y, z + 0.5);
            player.teleport(spawnLoc);

            // Track player level
            playerLevels.put(player.getName(), level);

            // Effects
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
            player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.5f, 0.5f);

            // Messages
            player.sendMessage(ChatColor.YELLOW + "You've no-clipped into the Backrooms...");
            if (level == 0) {
                player.sendMessage(ChatColor.GOLD + "Find the emerald block to escape!");
            } else {
                player.sendMessage(ChatColor.GOLD + "Find a way up or down... if you dare.");
                player.sendMessage(ChatColor.RED + "You are currently on Level " + level);
            }

            return true;
        }
    }

    // Command to force exit the backrooms (admin/emergency use)
    private class ExitBackroomCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players");
                return true;
            }

            Player player = (Player) sender;

            // Only allow ops to use this command
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "You don't have permission to use this command");
                return true;
            }

            if (!isInBackroom(player)) {
                player.sendMessage(ChatColor.RED + "You are not in the Backrooms");
                return true;
            }

            // Target player (self or another player)
            Player target = player;
            if (args.length > 0) {
                target = Bukkit.getPlayer(args[0]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage(ChatColor.RED + "Player not found or not online");
                    return true;
                }
            }

            // Teleport to main world
            World mainWorld = Bukkit.getWorld("world");
            if (mainWorld == null) mainWorld = Bukkit.getWorlds().get(0);

            target.teleport(mainWorld.getSpawnLocation());

            // Clear effects
            for (PotionEffect effect : target.getActivePotionEffects()) {
                target.removePotionEffect(effect.getType());
            }

            // Messages
            if (target == player) {
                player.sendMessage(ChatColor.GREEN + "You've been forcibly removed from the Backrooms");
            } else {
                player.sendMessage(ChatColor.GREEN + "You've removed " + target.getName() + " from the Backrooms");
                target.sendMessage(ChatColor.GREEN + "You've been forcibly removed from the Backrooms by an admin");
            }

            return true;
        }
    }

    // Custom world generator for the Backrooms
    private class BackroomGenerator extends ChunkGenerator {
        private final int level;
        private final SimplexOctaveGenerator noiseGenerator;

        public BackroomGenerator(int level) {
            this.level = level;
            this.noiseGenerator = new SimplexOctaveGenerator(new Random(level * 31), 8);
            this.noiseGenerator.setScale(0.01);
        }

        @Override
        public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
            int worldXStart = chunkX * 16;
            int worldZStart = chunkZ * 16;
            int baseY = 60 + (level * FLOOR_HEIGHT);
            int wallHeight = WALL_HEIGHT;

            // Get materials for this level
            Material floorMaterial = LEVEL_MATERIALS[level][0];
            Material wallMaterial = LEVEL_MATERIALS[level][1];
            Material ceilingMaterial = LEVEL_MATERIALS[level][2];
            Material lightMaterial = LEVEL_MATERIALS[level][3];

            // Use a deterministic random based on chunk coordinates
            long seed = (long) worldXStart * 341873911L + (long) worldZStart * 132897777L + level * 31;
            Random chunkRandom = new Random(seed);

            // Generate base terrain
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int absX = worldXStart + x;
                    int absZ = worldZStart + z;

                    // Floor and ceiling
                    chunkData.setBlock(x, baseY, z, floorMaterial);
                    chunkData.setBlock(x, baseY + wallHeight + 1, z, ceilingMaterial);

                    // Add some floor variation in deeper levels
                    if (level > 0 && chunkRandom.nextDouble() < 0.05 * level) {
                        // Damaged floor
                        if (chunkRandom.nextDouble() < 0.5) {
                            chunkData.setBlock(x, baseY, z, Material.YELLOW_CONCRETE_POWDER);
                        }

                        // Wet floor
                        if (level == 2 && chunkRandom.nextDouble() < 0.03) {
                            chunkData.setBlock(x, baseY, z, Material.WATER);
                        }
                    }

                    // Use noise to create a maze-like pattern
                    double noise1 = noiseGenerator.noise(absX, absZ, 0.5, 0.5, true);
                    double noise2 = noiseGenerator.noise(absZ, absX, 0.5, 0.5, true);

                    // Create walls based on noise
                    boolean isWall = false;

                    // More complex wall patterns based on level
                    switch (level) {
                        case 0:
                            // Level 0: Classic grid pattern
                            if (Math.abs(absX % 8) < 1 || Math.abs(absZ % 8) < 1) {
                                isWall = true;
                            }

                            // Add some gaps in walls
                            if (isWall && chunkRandom.nextDouble() < 0.15) {
                                isWall = false;
                            }
                            break;

                        case 1:
                            // Level 1: More chaotic walls
                            if (Math.abs(absX % 7) < 1 || Math.abs(absZ % 7) < 1) {
                                isWall = true;
                            }

                            // Use noise to create more varied patterns
                            if (noise1 > 0.65 || noise2 > 0.65) {
                                isWall = !isWall;
                            }

                            // Random wall damage
                            if (isWall && chunkRandom.nextDouble() < 0.3) {
                                isWall = false;
                            }
                            break;

                        case 2:
                            // Level 2: Heavily degraded structure
                            if (Math.abs(absX % 6) < 1 || Math.abs(absZ % 6) < 1) {
                                isWall = true;
                            }

                            // More noise influence
                            if ((noise1 > 0.6 && noise2 > 0.3) || (noise2 > 0.6 && noise1 > 0.3)) {
                                isWall = !isWall;
                            }

                            // Random destruction
                            if (isWall && chunkRandom.nextDouble() < 0.4) {
                                isWall = false;
                            }
                            break;
                    }

                    // Create walls
                    if (isWall) {
                        for (int y = 1; y <= wallHeight; y++) {
                            chunkData.setBlock(x, baseY + y, z, wallMaterial);
                        }
                    } else {
                        // Air in non-wall spaces
                        for (int y = 1; y <= wallHeight; y++) {
                            chunkData.setBlock(x, baseY + y, z, Material.AIR);
                        }
                    }

                    // Ceiling lights in corridors (not in walls)
                    if (!isWall && (
                            (level == 0 && (absX % 8 == 4 && absZ % 8 == 4)) ||
                                    (level == 1 && (absX % 7 == 3 && absZ % 7 == 3)) ||
                                    (level == 2 && (absX % 6 == 3 && absZ % 6 == 3 && chunkRandom.nextDouble() < 0.7))
                    )) {
                        chunkData.setBlock(x, baseY + wallHeight + 1, z, lightMaterial);
                    }

                    // Random exits (emerald blocks)
                    if (!isWall && chunkRandom.nextDouble() < EXIT_CHANCE / (level + 1)) {
                        chunkData.setBlock(x, baseY, z, Material.EMERALD_BLOCK);
                    }

                    // Add stairs down (if not at max depth)
                    if (level < MAX_LEVELS - 1 && !isWall && chunkRandom.nextDouble() < EXIT_CHANCE / 3) {
                        chunkData.setBlock(x, baseY, z, Material.MOSSY_COBBLESTONE);
                    }

                    // Level-specific decorations
                    if (!isWall) {
                        switch (level) {
                            case 1:
                                // Occasional damaged lighting on the floor
                                if (chunkRandom.nextDouble() < 0.005) {
                                    chunkData.setBlock(x, baseY + 1, z, Material.REDSTONE_LAMP);
                                }
                                break;

                            case 2:
                                // Vegetation and decay
                                if (chunkRandom.nextDouble() < 0.01) {
                                    if (chunkRandom.nextBoolean()) {
                                        chunkData.setBlock(x, baseY + 1, z, Material.BROWN_MUSHROOM);
                                    } else {
                                        chunkData.setBlock(x, baseY + 1, z, Material.COBWEB);
                                    }
                                }
                                break;
                        }
                    }
                }
            }
        }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) {
            return Collections.emptyList();
        }
    }
}