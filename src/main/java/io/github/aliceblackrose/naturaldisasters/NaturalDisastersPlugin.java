package io.github.aliceblackrose.naturaldisasters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point for the Natural Disasters survival-round plugin. */
public final class NaturalDisastersPlugin extends JavaPlugin implements Listener {
  private DisasterController controller;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    controller = new DisasterController(this);

    PluginCommand disasterCommand = getCommand("disaster");
    if (disasterCommand == null) {
      throw new IllegalStateException("Missing /disaster command in plugin.yml");
    }
    disasterCommand.setExecutor(this);
    disasterCommand.setTabCompleter(this);
    getServer().getPluginManager().registerEvents(this, this);
    controller.startLoop();
  }

  @Override
  public void onDisable() {
    if (controller != null) {
      controller.shutdown();
    }
  }

  @EventHandler
  public void onPlayerDeath(PlayerDeathEvent event) {
    if (controller != null) {
      controller.markEliminated(event.getEntity().getUniqueId());
    }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (controller == null) {
      sender.sendMessage(Component.text("Natural Disasters is not ready.", NamedTextColor.RED));
      return true;
    }
    if (args.length == 0) {
      sendHelp(sender);
      return true;
    }

    String subcommand = args[0].toLowerCase(Locale.ROOT);
    if ("status".equals(subcommand)) {
      sender.sendMessage(
          Component.text("Natural Disasters: " + controller.status(), NamedTextColor.GOLD));
      return true;
    }
    if ("list".equals(subcommand)) {
      sendDisasterList(sender);
      return true;
    }
    if (!sender.hasPermission("naturaldisasters.admin")) {
      sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
      return true;
    }

    return switch (subcommand) {
      case "start" -> handleStart(sender, args);
      case "stop" -> handleStop(sender);
      case "reload" -> handleReload(sender);
      default -> {
        sendHelp(sender);
        yield true;
      }
    };
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      List<String> options = new ArrayList<>(List.of("status", "list"));
      if (sender.hasPermission("naturaldisasters.admin")) {
        options.addAll(List.of("start", "stop", "reload"));
      }
      return matching(options, args[0]);
    }
    if (args.length == 2
        && "start".equalsIgnoreCase(args[0])
        && sender.hasPermission("naturaldisasters.admin")) {
      List<String> keys =
          controller == null
              ? List.of()
              : controller.enabledDisasters().stream().map(DisasterType::key).toList();
      return matching(keys, args[1]);
    }
    return List.of();
  }

  private boolean handleStart(CommandSender sender, String[] args) {
    if (args.length < 2) {
      sender.sendMessage(
          Component.text("Usage: /disaster start <type>", NamedTextColor.YELLOW));
      return true;
    }
    DisasterType disaster = DisasterType.fromKey(args[1]).orElse(null);
    if (disaster == null) {
      sender.sendMessage(Component.text("Unknown disaster type.", NamedTextColor.RED));
      sendDisasterList(sender);
      return true;
    }
    controller.startImmediate(disaster);
    sender.sendMessage(
        Component.text("Started " + disaster.displayName() + ".", NamedTextColor.GREEN));
    return true;
  }

  private boolean handleStop(CommandSender sender) {
    if (controller.stopCurrentRound()) {
      sender.sendMessage(Component.text("Stopped the current round.", NamedTextColor.GREEN));
    } else {
      sender.sendMessage(Component.text("No round is active.", NamedTextColor.YELLOW));
    }
    return true;
  }

  private boolean handleReload(CommandSender sender) {
    reloadConfig();
    sender.sendMessage(Component.text("Configuration reloaded.", NamedTextColor.GREEN));
    return true;
  }

  private void sendDisasterList(CommandSender sender) {
    String names =
        controller.enabledDisasters().stream()
            .map(type -> type.key() + " (" + type.displayName() + ")")
            .collect(java.util.stream.Collectors.joining(", "));
    sender.sendMessage(
        Component.text(
            names.isEmpty() ? "No disasters are enabled." : "Enabled disasters: " + names,
            NamedTextColor.AQUA));
  }

  private static void sendHelp(CommandSender sender) {
    sender.sendMessage(Component.text("Natural Disasters commands", NamedTextColor.GOLD));
    sender.sendMessage(Component.text("/disaster status", NamedTextColor.YELLOW));
    sender.sendMessage(Component.text("/disaster list", NamedTextColor.YELLOW));
    if (sender.hasPermission("naturaldisasters.admin")) {
      sender.sendMessage(Component.text("/disaster start <type>", NamedTextColor.YELLOW));
      sender.sendMessage(Component.text("/disaster stop", NamedTextColor.YELLOW));
      sender.sendMessage(Component.text("/disaster reload", NamedTextColor.YELLOW));
    }
  }

  private static List<String> matching(List<String> options, String prefix) {
    String normalized = prefix.toLowerCase(Locale.ROOT);
    return options.stream().filter(option -> option.startsWith(normalized)).toList();
  }
}
