package com.stuart.javarealmtool;

import io.javalin.Javalin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public class WebServer {

    private final JavaRealmTool plugin;
    private Javalin app;

    public WebServer(JavaRealmTool plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // Create and configure the server on the main thread
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        });

        // --- API Endpoints ---
        app.get("/api/players", ctx -> {
            // To safely access Bukkit API, we must run the code on the main server thread.
            // callSyncMethod will schedule the task and return a Future with the result.
            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                List<Map<String, Object>> playerList = new ArrayList<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Map<String, Object> playerMap = new HashMap<>();
                    playerMap.put("name", player.getName());
                    playerMap.put("uuid", player.getUniqueId().toString());
                    playerMap.put("gamemode", player.getGameMode().toString());
                    playerMap.put("health", player.getHealth());
                    playerList.add(playerMap);
                }
                return playerList;
            });

            try {
                // future.get() will wait for the main thread to finish its task and return the list.
                // We can then send this list as a JSON response.
                ctx.json(future.get());
            } catch (Exception e) {
                ctx.status(500).result("Error retrieving player list.");
                plugin.getLogger().severe("Error in /api/players endpoint:");
                e.printStackTrace();
            }
        });

        app.post("/api/actions/kick", ctx -> {
            String providedApiKey = ctx.header("Authorization");
            if (providedApiKey == null || !providedApiKey.equals("Bearer " + plugin.getApiKey())) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String playerName = ctx.queryParam("playerName");
            if (playerName == null) {
                ctx.status(400).result("Missing 'playerName' query parameter.");
                return;
            }

            // Safely perform the kick on the main server thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayer(playerName);
                if (target != null) {
                    target.kick(Component.text("You have been kicked by a remote administrator."));
                    ctx.result("Successfully kicked " + playerName);
                } else {
                    ctx.status(404).result("Player not found.");
                }
            });
        });

        // Start the server on a new thread to avoid blocking the main server
        Thread webServerThread = new Thread(() -> {
            app.start(7070);
        });
        webServerThread.setContextClassLoader(this.plugin.getClass().getClassLoader());
        webServerThread.setName("RMT-WebServer-Thread");
        webServerThread.start();
        plugin.getLogger().info("Web server started on port 7070.");
    }

    public void stop() {
        if (app != null) {
            app.stop();
            plugin.getLogger().info("Web server stopped.");
        }
    }
}
