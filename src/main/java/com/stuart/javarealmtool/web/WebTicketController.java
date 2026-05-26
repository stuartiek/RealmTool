package com.stuart.javarealmtool.web;

import com.stuart.javarealmtool.JavaRealmTool;
import com.stuart.javarealmtool.WebServer;
import io.javalin.Javalin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class WebTicketController {
    private final JavaRealmTool plugin;
    private final WebServer webServer;

    public WebTicketController(JavaRealmTool plugin, WebServer webServer) {
        this.plugin = plugin;
        this.webServer = webServer;
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/tickets", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;

            String status = ctx.queryParam("status");
            String priority = ctx.queryParam("priority");

            Callable<List<Map<String, Object>>> task = () -> {
                List<Map<String, Object>> tickets = new ArrayList<>();
                if (plugin.getTicketConfig().contains("tickets")) {
                    for (String key : plugin.getTicketConfig().getConfigurationSection("tickets").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        String ticketStatus = plugin.getTicketConfig().getString("tickets." + key + ".status", "open");
                        String ticketPriority = plugin.getTicketConfig().getString("tickets." + key + ".priority", "medium");

                        if ((status == null || status.isEmpty() || status.equals(ticketStatus)) &&
                            (priority == null || priority.isEmpty() || priority.equals(ticketPriority))) {
                            Map<String, Object> t = new HashMap<>();
                            t.put("id", key);
                            t.put("player", plugin.getTicketConfig().getString("tickets." + key + ".player"));
                            t.put("message", plugin.getTicketConfig().getString("tickets." + key + ".message"));
                            t.put("status", ticketStatus);
                            t.put("priority", ticketPriority);
                            t.put("category", plugin.getTicketConfig().getString("tickets." + key + ".category", "other"));
                            t.put("assignee", plugin.getTicketConfig().getString("tickets." + key + ".assignee", ""));
                            t.put("time", plugin.getTicketConfig().getString("tickets." + key + ".timestamp"));
                            tickets.add(t);
                        }
                    }
                }
                return tickets;
            };

            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
            ctx.json(future.get());
        });

        app.get("/api/appeals", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;

            String status = ctx.queryParam("status");
            String priority = ctx.queryParam("priority");

            Callable<List<Map<String, Object>>> task = () -> {
                List<Map<String, Object>> appeals = new ArrayList<>();
                if (plugin.getTicketConfig().contains("appeals")) {
                    for (String key : plugin.getTicketConfig().getConfigurationSection("appeals").getKeys(false)) {
                        if (key.equals("next_id")) continue;
                        String ticketStatus = plugin.getTicketConfig().getString("appeals." + key + ".status", "open");
                        String ticketPriority = plugin.getTicketConfig().getString("appeals." + key + ".priority", "medium");

                        if ((status == null || status.isEmpty() || status.equals(ticketStatus)) &&
                            (priority == null || priority.isEmpty() || priority.equals(ticketPriority))) {
                            Map<String, Object> t = new HashMap<>();
                            t.put("id", -Integer.parseInt(key));
                            t.put("player", plugin.getTicketConfig().getString("appeals." + key + ".player"));
                            t.put("message", plugin.getTicketConfig().getString("appeals." + key + ".message"));
                            t.put("status", ticketStatus);
                            t.put("priority", ticketPriority);
                            t.put("category", plugin.getTicketConfig().getString("appeals." + key + ".category", "other"));
                            t.put("assignee", plugin.getTicketConfig().getString("appeals." + key + ".assignee", ""));
                            t.put("time", plugin.getTicketConfig().getString("appeals." + key + ".timestamp"));
                            t.put("type", "appeal");
                            appeals.add(t);
                        }
                    }
                }
                return appeals;
            };

            Future<List<Map<String, Object>>> future = Bukkit.getScheduler().callSyncMethod(plugin, task);
            ctx.json(future.get());
        });

        app.post("/api/ticket/close/{id}", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;
            int id = Integer.parseInt(ctx.pathParam("id"));
            plugin.resolveTicket(id, "Closed by web");
            ctx.json(Map.of("status", true));
        });

        app.get("/api/ticket/{id}", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;
            int id = Integer.parseInt(ctx.pathParam("id"));
            ctx.json(plugin.getTicketData(id));
        });

        app.patch("/api/ticket/{id}", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;
            int id = Integer.parseInt(ctx.pathParam("id"));
            var body = ctx.bodyAsClass(Map.class);

            for (String field : List.of("status", "priority", "category", "assignee")) {
                Object value = body.get(field);
                if (value != null) {
                    plugin.updateTicketField(id, field, String.valueOf(value));
                }
            }

            ctx.json(Map.of("status", true, "ticket", plugin.getTicketData(id)));
        });

        app.post("/api/ticket/{id}/response", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.view.tickets")) return;
            int id = Integer.parseInt(ctx.pathParam("id"));
            var body = ctx.bodyAsClass(Map.class);
            String message = (String) body.get("message");
            String admin = (String) body.getOrDefault("admin", "unknown");
            plugin.addTicketResponse(id, admin, message);
            ctx.json(Map.of("status", true));
        });

        app.post("/api/ticket/{id}/resolve", ctx -> {
            if (!webServer.auth(ctx) || !webServer.hasPermission(ctx.header("Authorization"), "webapp.manage.tickets")) return;
            int id = Integer.parseInt(ctx.pathParam("id"));
            var body = ctx.bodyAsClass(Map.class);
            String reason = (String) body.getOrDefault("reason", "Resolved by web");
            plugin.resolveTicket(id, reason);
            ctx.json(Map.of("status", true));
        });
    }
}
