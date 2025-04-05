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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BagRoomPlugin extends JavaPlugin implements Listener {

    // 設定項目
    private int ROOM_MIN = -5000;
    private int ROOM_MAX = 5000;
    private int FLOOR_HEIGHT = 5; // 階層間の高さ
    private int MAX_LEVELS = 3; // バックルームのレベル数
    private int WALL_HEIGHT = 4; // 壁の高さ
    private double EXIT_CHANCE = 0.002; // 出口を見つける確率（0.005から0.002に減少）
    private boolean enableLightFlicker = true;
    private boolean enableAmbientSounds = true;
    private boolean enableFogEffect = true;
    private double difficultyScaling = 1.0; // 難易度スケーリング

    // 世界データ
    private final Map<String, Integer> playerLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlickerTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> sanityLevels = new ConcurrentHashMap<>();
    private final Set<Location> exitLocations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Double> playerDistanceTraveled = new ConcurrentHashMap<>(); // プレイヤーの移動距離を追跡

    // ランダム生成用
    private final Random random = new Random();

    // 各レベルの素材
    private final Material[][] LEVEL_MATERIALS = {
            // レベル0 - 古典的な黄色のバックルーム
            {
                    Material.YELLOW_CONCRETE, // 床
                    Material.YELLOW_TERRACOTTA, // 壁
                    Material.YELLOW_STAINED_GLASS, // 天井
                    Material.GLOWSTONE // 照明
            },
            // レベル1 - より暗く、損傷が激しい
            {
                    Material.YELLOW_TERRACOTTA, // 床
                    Material.YELLOW_CONCRETE, // 壁
                    Material.YELLOW_WOOL, // 天井
                    Material.REDSTONE_LAMP // 照明（点滅可能）
            },
            // レベル2 - 放棄され、植物が生えている
            {
                    Material.YELLOW_CONCRETE_POWDER, // 床
                    Material.STRIPPED_BIRCH_WOOD, // 壁
                    Material.BIRCH_PLANKS, // 天井
                    Material.LANTERN // 照明（まばら）
            }
    };

    @Override
    public void onEnable() {
        // デフォルト設定を保存
        saveDefaultConfig();
        loadConfig();

        // イベントを登録
        getServer().getPluginManager().registerEvents(this, this);

        // コマンドを登録
        getCommand("backroom").setExecutor(new BackroomCommand());
        getCommand("exitbackroom").setExecutor(new ExitBackroomCommand());

        // ワールドが存在しない場合は初期化
        for (int level = 0; level < MAX_LEVELS; level++) {
            String worldName = "backroom_level_" + level;
            if (Bukkit.getWorld(worldName) == null) {
                createBackroomWorld(level);
            }
        }

        // 環境タスクを開始
        if (enableLightFlicker) {
            startLightFlickerTask();
        }

        if (enableAmbientSounds) {
            startAmbientSoundTask();
        }

        if (enableFogEffect) {
            startFogEffectTask();
        }

        getLogger().info("バックルームプラグインが有効化されました。現実からのノークリップを開始します...");
    }

    @Override
    public void onDisable() {
        // すべてのタスクをキャンセル
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("バックルームプラグインが無効化されました。現実に戻りました。");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();

        // デフォルト値が存在しない場合は設定
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

        // 値をロード
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
        world.setTime(18000); // 常に夜間（雰囲気のため）

        return world;
    }

    private void startLightFlickerTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isInBackroom(player)) {
                        // 十分な時間が経過した場合のみ点滅
                        UUID uuid = player.getUniqueId();
                        long currentTime = System.currentTimeMillis();
                        if (!lastFlickerTime.containsKey(uuid) ||
                                currentTime - lastFlickerTime.get(uuid) > 30000) { // 点滅間隔30秒

                            if (random.nextDouble() < 0.2) { // 20%の確率で点滅
                                flickerLightsAroundPlayer(player);
                                lastFlickerTime.put(uuid, currentTime);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100, 100); // 5秒ごとにチェック
    }

    private void startAmbientSoundTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isInBackroom(player)) {
                        // 環境音を再生
                        int level = getPlayerLevel(player);

                        switch (level) {
                            case 0:
                                // 蛍光灯のブーンという音
                                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.2f, 1.0f);
                                break;
                            case 1:
                                // 遠くの足音と機械音
                                if (random.nextBoolean()) {
                                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.2f, 0.8f);
                                } else {
                                    player.playSound(player.getLocation(), Sound.BLOCK_METAL_PLACE, 0.1f, 0.5f);
                                }
                                break;
                            case 2:
                                // 水滴と軋み音
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
        }.runTaskTimer(this, 60, 160); // 8秒ごと
    }

    private void startFogEffectTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isInBackroom(player)) {
                        // 霧効果（非常に短い時間の盲目）を適用
                        int level = getPlayerLevel(player);
                        if (random.nextDouble() < 0.1 * (level + 1)) {
                            player.addPotionEffect(new PotionEffect(
                                    PotionEffectType.BLINDNESS, 40, 0, false, false));
                        }

                        // 深いレベルでは吐き気も
                        if (level > 0 && random.nextDouble() < 0.05 * level) {
                            player.addPotionEffect(new PotionEffect(
                                    PotionEffectType.NAUSEA, 100, 0, false, false));
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100, 400); // 20秒ごと
    }

    private void flickerLightsAroundPlayer(Player player) {
        int radius = 20;
        Location loc = player.getLocation();
        World world = player.getWorld();

        // プレイヤーの現在のレベルを取得
        int level = getPlayerLevel(player);
        Material lightMaterial = LEVEL_MATERIALS[level][3];

        // 半径内の照明ブロックを検索
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

        // 点滅効果 - オフにしてからオンに
        if (!lightBlocks.isEmpty()) {
            // プレイヤーにメッセージを送信
            player.sendMessage(ChatColor.DARK_RED + "【警告】照明システム一時的障害発生。");

            new BukkitRunnable() {
                @Override
                public void run() {
                    // ライトをオフに
                    for (Block light : lightBlocks) {
                        player.sendBlockChange(light.getLocation(), Material.AIR.createBlockData());
                    }

                    // 音を再生
                    player.playSound(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.3f, 1.5f);

                    // 暗闇効果
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));

                    // オンに戻すスケジュール
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            // ライトを元に戻す
                            for (Block light : lightBlocks) {
                                player.sendBlockChange(light.getLocation(), light.getBlockData());
                            }

                            // 音を再生
                            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.2f, 1.2f);
                        }
                    }.runTaskLater(BagRoomPlugin.this, 15); // 0.75秒後
                }
            }.runTaskLater(this, 5); // 0.25秒遅延
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
    public void onPlayerJoin(PlayerJoinEvent event) {
        // プレイヤーがサーバーに参加したときに距離カウンターをリセット
        playerDistanceTraveled.put(event.getPlayer().getUniqueId(), 0.0);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // プレイヤーがサーバーを離れたとき、カウンターを削除
        playerDistanceTraveled.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        // バックルーム内の移動処理
        if (isInBackroom(player)) {
            int level = getPlayerLevel(player);
            World world = player.getWorld();

            // 境界外にいるかチェック
            if (to.getBlockX() < ROOM_MIN || to.getBlockX() > ROOM_MAX ||
                    to.getBlockZ() < ROOM_MIN || to.getBlockZ() > ROOM_MAX) {

                // 範囲内のランダムな場所にテレポート
                int randomX = random.nextInt(ROOM_MAX - ROOM_MIN) + ROOM_MIN;
                int randomZ = random.nextInt(ROOM_MAX - ROOM_MIN) + ROOM_MIN;
                int y = 65 + (level * FLOOR_HEIGHT); // レベル固有の高さ

                Location newLoc = new Location(world, randomX + 0.5, y, randomZ + 0.5);
                player.teleport(newLoc);
                player.sendMessage(ChatColor.RED + "【エラー】境界外移動検知。中央領域へ転送します。");
                return;
            }

            // 出口（エメラルドブロック）のチェック - パフォーマンス向上のためメモリに保存
            Block block = world.getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ());

            // 出口の位置をキャッシュしてチェックを繰り返さないようにする
            Location blockLoc = block.getLocation();
            if (exitLocations.contains(blockLoc)) {
                handleExit(player, level);
                return;
            }

            if (block.getType() == Material.EMERALD_BLOCK) {
                exitLocations.add(blockLoc);
                handleExit(player, level);
            }

            // 特殊な場所：次のレベルへの階段（最大深度でない場合）
            if (level < MAX_LEVELS - 1 && block.getType() == Material.MOSSY_COBBLESTONE) {
                int newLevel = level + 1;

                // 警告を表示
                player.sendMessage(ChatColor.DARK_RED + "【警告】異常な引力感知。");
                player.sendMessage(ChatColor.RED + "【システム】これ以上深く進むことは推奨されません。");

                // 次のレベルにテレポート
                World nextWorld = Bukkit.getWorld("backroom_level_" + newLevel);
                if (nextWorld == null) {
                    nextWorld = createBackroomWorld(newLevel);
                }

                // 次のレベルのランダムな場所
                int x = random.nextInt(100) - 50;
                int z = random.nextInt(100) - 50;
                int y = 65 + (newLevel * FLOOR_HEIGHT);

                // 目的地に空気があることを確認
                Location destination = new Location(nextWorld, x, y, z);
                player.teleport(destination);

                // 効果を適用
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 100, 0));
                player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);

                // メッセージ
                player.sendMessage(ChatColor.DARK_RED + "【位置情報】バックルームレベル " + newLevel + " に降下しました");
                player.sendMessage(ChatColor.RED + "【環境センサー】空気密度が増加しています...");

                // プレイヤーのレベルを更新
                playerLevels.put(player.getName(), newLevel);
            }
        } else {
            // 通常世界での移動を追跡
            UUID playerId = player.getUniqueId();

            // XZ平面上の距離のみを計算（高さ変化を無視）
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double distance = Math.sqrt(dx*dx + dz*dz);

            // すべての移動をカウント（距離制限を撤廃）
            double totalDistance = playerDistanceTraveled.getOrDefault(playerId, 0.0) + distance;
            playerDistanceTraveled.put(playerId, totalDistance);

            // 警告メッセージを10ブロクから表示
            if (totalDistance >= 10.0 && totalDistance < 11.0) {
                player.sendMessage(ChatColor.GRAY + "【注意】現実の不安定性が増加しています... (" + String.format("%.1f", totalDistance) + "/15.0)");
            }

            // 15ブロック歩いたらバックルームに送る（20→15に変更）
            if (totalDistance >= 15.0) {
                // カウンターをリセット
                playerDistanceTraveled.put(playerId, 0.0);

                // プレイヤーがオペレーターでない場合のみ（オプション）
                if (!player.isOp()) {
                    // 88%の確率でバックルームに送る
                    if (random.nextDouble() < 0.88) {
                        // レベル0のバックルームに送る
                        teleportToBackroom(player, 0);

                        player.sendMessage(ChatColor.DARK_RED + "【異常事象発生】空間歪曲検知。現実層からのノークリップが発生しました。");
                        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
                    }
                }
            }
        }
    }

    private void teleportToBackroom(Player player, int level) {
        // バックルームのワールドを取得または作成
        String worldName = "backroom_level_" + level;
        World backroomWorld = Bukkit.getWorld(worldName);
        if (backroomWorld == null) {
            backroomWorld = createBackroomWorld(level);
        }

        // ランダムな場所にテレポート
        int x = random.nextInt(100) - 50; // 初期スポーン用の小さな範囲
        int z = random.nextInt(100) - 50;
        int y = 65 + (level * FLOOR_HEIGHT); // レベル固有の高さ

        Location spawnLoc = new Location(backroomWorld, x + 0.5, y, z + 0.5);
        player.teleport(spawnLoc);

        // プレイヤーレベルを追跡
        playerLevels.put(player.getName(), level);

        // 効果
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 80, 0));
        player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.5f, 0.5f);

        // メッセージ
        player.sendMessage(ChatColor.YELLOW + "【転送完了】あなたは現実からノークリップしました...");
        if (level == 0) {
            player.sendMessage(ChatColor.GOLD + "【システムメッセージ】脱出するにはエメラルドブロックを見つけてください。");
        } else {
            player.sendMessage(ChatColor.GOLD + "【システムメッセージ】上層または下層への経路を発見してください。");
            player.sendMessage(ChatColor.RED + "【位置情報】現在レベル " + level + " に滞在中。");
        }
    }

    private void handleExit(Player player, int level) {
        // レベルに基づいて異なる結果
        switch (level) {
            case 0:
                // レベル0：メインワールドに戻る
                World mainWorld = Bukkit.getWorld("world");
                if (mainWorld == null) mainWorld = Bukkit.getWorlds().get(0);

                player.sendMessage(ChatColor.GREEN + "【異常検知】境界領域に亀裂が発生。現実層へのリンクを確立中...");
                player.teleport(mainWorld.getSpawnLocation());

                // 効果をクリア
                for (PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }

                // 報酬
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                player.sendMessage(ChatColor.GOLD + "【転送完了】バックルーム層との接続が切断されました。現実への再同期を確認。");

                // 距離カウンターをリセット
                playerDistanceTraveled.put(player.getUniqueId(), 0.0);
                break;

            default:
                // 深いレベル：1レベル上に移動
                int newLevel = level - 1;
                World upperWorld = Bukkit.getWorld("backroom_level_" + newLevel);

                player.sendMessage(ChatColor.YELLOW + "【発見】上層への経路を確認しました...");

                // 上層のランダムな場所
                int x = random.nextInt(200) - 100;
                int z = random.nextInt(200) - 100;
                int y = 65 + (newLevel * FLOOR_HEIGHT);

                Location destination = new Location(upperWorld, x, y, z);
                player.teleport(destination);

                // 効果
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
                player.playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.5f, 1.0f);

                player.sendMessage(ChatColor.YELLOW + "【位置情報】バックルームレベル " + newLevel + " に上昇しました");

                // プレイヤーの追跡レベルを更新
                playerLevels.put(player.getName(), newLevel);
                break;
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        Player player = (Player) event.getEntity();
        if (!isInBackroom(player)) return;

        // バックルームでは落下ダメージなし
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isInBackroom(player)) return;

        // クリエイティブモード以外ではブロック破壊を防止
        if (player.getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "【エラー】バックルーム環境の改変は許可されていません。");
        }
    }

    // バックルームに入るコマンド
    private class BackroomCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("このコマンドはプレイヤーのみ使用可能です");
                return true;
            }

            Player player = (Player) sender;

            // レベル引数が提供されている場合は解析
            int level = 0;
            if (args.length > 0) {
                try {
                    level = Integer.parseInt(args[0]);
                    if (level < 0 || level >= MAX_LEVELS) {
                        player.sendMessage(ChatColor.RED + "【エラー】無効なレベル。0から" + (MAX_LEVELS - 1) + "の間でなければなりません");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "【エラー】無効なレベル番号");
                    return true;
                }
            }

            teleportToBackroom(player, level);
            return true;
        }
    }

    // バックルームから強制退出するコマンド（管理者/緊急用）
    private class ExitBackroomCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("このコマンドはプレイヤーのみ使用可能です");
                return true;
            }

            Player player = (Player) sender;

            // オペレーターのみこのコマンドを使用可能
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "【エラー】このコマンドを使用する権限がありません");
                return true;
            }

            if (!isInBackroom(player)) {
                player.sendMessage(ChatColor.RED + "【エラー】あなたはバックルームにいません");
                return true;
            }

            // 対象プレイヤー（自分または他のプレイヤー）
            Player target = player;
            if (args.length > 0) {
                target = Bukkit.getPlayer(args[0]);
                if (target == null || !target.isOnline()) {
                    player.sendMessage(ChatColor.RED + "【エラー】プレイヤーが見つからないか、オンラインではありません");
                    return true;
                }
            }

            // メインワールドにテレポート
            World mainWorld = Bukkit.getWorld("world");
            if (mainWorld == null) mainWorld = Bukkit.getWorlds().get(0);

            target.teleport(mainWorld.getSpawnLocation());

            // 効果をクリア
            for (PotionEffect effect : target.getActivePotionEffects()) {
                target.removePotionEffect(effect.getType());
            }

            // メッセージ
            if (target == player) {
                player.sendMessage(ChatColor.GREEN + "【システム通知】あなたはバックルームから強制的に排除されました");
            } else {
                player.sendMessage(ChatColor.GREEN + "【システム通知】" + target.getName() + "をバックルームから排除しました");
                target.sendMessage(ChatColor.GREEN + "【システム通知】あなたは管理者によってバックルームから強制的に排除されました");
            }

            // 距離カウンターをリセット
            playerDistanceTraveled.put(target.getUniqueId(), 0.0);

            return true;
        }
    }

    // バックルーム用カスタムワールドジェネレータ
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

            // このレベルの素材を取得
            Material floorMaterial = LEVEL_MATERIALS[level][0];
            Material wallMaterial = LEVEL_MATERIALS[level][1];
            Material ceilingMaterial = LEVEL_MATERIALS[level][2];
            Material lightMaterial = LEVEL_MATERIALS[level][3];

            // チャンク座標に基づく決定論的ランダム
            long seed = (long) worldXStart * 341873911L + (long) worldZStart * 132897777L + level * 31;
            Random chunkRandom = new Random(seed);

            // 基本地形を生成
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int absX = worldXStart + x;
                    int absZ = worldZStart + z;

                    // 床と天井
                    chunkData.setBlock(x, baseY, z, floorMaterial);
                    chunkData.setBlock(x, baseY + wallHeight + 1, z, ceilingMaterial);

                    // 深いレベルでの床のバリエーションを追加
                    if (level > 0 && chunkRandom.nextDouble() < 0.05 * level) {
                        // 損傷した床
                        if (chunkRandom.nextDouble() < 0.5) {
                            chunkData.setBlock(x, baseY, z, Material.YELLOW_CONCRETE_POWDER);
                        }

                        // 湿った床
                        if (level == 2 && chunkRandom.nextDouble() < 0.03) {
                            chunkData.setBlock(x, baseY, z, Material.WATER);
                        }
                    }

                    // 迷路のようなパターンを作成するためにノイズを使用
                    double noise1 = noiseGenerator.noise(absX, absZ, 0.5, 0.5, true);
                    double noise2 = noiseGenerator.noise(absZ, absX, 0.5, 0.5, true);

                    // ノイズに基づいて壁を作成
                    boolean isWall = false;

                    // レベルに基づくより複雑な壁パターン
                    switch (level) {
                        case 0:
                            // レベル0：古典的なグリッドパターン
                            if (Math.abs(absX % 8) < 1 || Math.abs(absZ % 8) < 1) {
                                isWall = true;
                            }

                            // 壁に隙間を追加
                            if (isWall && chunkRandom.nextDouble() < 0.15) {
                                isWall = false;
                            }
                            break;

                        case 1:
                            // レベル1：よりカオスな壁
                            if (Math.abs(absX % 7) < 1 || Math.abs(absZ % 7) < 1) {
                                isWall = true;
                            }

                            // より多様なパターンを作成するためにノイズを使用
                            if (noise1 > 0.65 || noise2 > 0.65) {
                                isWall = !isWall;
                            }

                            // ランダムな壁のダメージ
                            if (isWall && chunkRandom.nextDouble() < 0.3) {
                                isWall = false;
                            }
                            break;

                        case 2:
                            // レベル2：ひどく劣化した構造
                            if (Math.abs(absX % 6) < 1 || Math.abs(absZ % 6) < 1) {
                                isWall = true;
                            }

                            // より多くのノイズの影響
                            if ((noise1 > 0.6 && noise2 > 0.3) || (noise2 > 0.6 && noise1 > 0.3)) {
                                isWall = !isWall;
                            }

                            // ランダムな破壊
                            if (isWall && chunkRandom.nextDouble() < 0.4) {
                                isWall = false;
                            }
                            break;
                    }

                    // 壁を作成
                    if (isWall) {
                        for (int y = 1; y <= wallHeight; y++) {
                            chunkData.setBlock(x, baseY + y, z, wallMaterial);
                        }
                    } else {
                        // 壁以外の空間に空気
                        for (int y = 1; y <= wallHeight; y++) {
                            chunkData.setBlock(x, baseY + y, z, Material.AIR);
                        }
                    }

                    // 廊下の天井照明（壁ではない場所）
                    if (!isWall && (
                            (level == 0 && (absX % 8 == 4 && absZ % 8 == 4)) ||
                                    (level == 1 && (absX % 7 == 3 && absZ % 7 == 3)) ||
                                    (level == 2 && (absX % 6 == 3 && absZ % 6 == 3 && chunkRandom.nextDouble() < 0.7))
                    )) {
                        chunkData.setBlock(x, baseY + wallHeight + 1, z, lightMaterial);
                    }

                    // ランダムな出口（エメラルドブロック）
                    if (!isWall && chunkRandom.nextDouble() < EXIT_CHANCE / (level + 1)) {
                        chunkData.setBlock(x, baseY, z, Material.EMERALD_BLOCK);
                    }

                    // 下階への階段を追加（最大深度でない場合）
                    if (level < MAX_LEVELS - 1 && !isWall && chunkRandom.nextDouble() < EXIT_CHANCE / 3) {
                        chunkData.setBlock(x, baseY, z, Material.MOSSY_COBBLESTONE);
                    }

                    // レベル固有の装飾
                    if (!isWall) {
                        switch (level) {
                            case 1:
                                // 床の上の損傷した照明
                                if (chunkRandom.nextDouble() < 0.005) {
                                    chunkData.setBlock(x, baseY + 1, z, Material.REDSTONE_LAMP);
                                }
                                break;

                            case 2:
                                // 植生と腐敗
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