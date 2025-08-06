package by.mine.securetransfer.paper;

import by.mine.securetransfer.Config;
import by.mine.securetransfer.Main;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

public class SecureTransferPaper extends JavaPlugin implements org.bukkit.event.Listener {

	static Component MSG_USE_IP = null;
	final static Component MSG_REDIS_DOWN = text("Connections checker is temporarily unavailable [Paper side]. Please, come a little bit later", RED);
	final static Component MSG_ALREADY_ONLINE = text("You are already on the server. ", RED).append(text("If that's an error, please wait 1 minute and try again", GRAY));
	static Component MSG_NO_TOKEN_FOUND = null;
	final static Component MSG_INVALID_TOKEN = text("Invalid token", RED);
	final static Component MSG_INVALID_SIGN = text("Invalid token (signature)", RED);

	private String SERVER_ONLINE_KEY = null;

	@Override
	public void onEnable() {
		Main.serverBoundIp = getServer().getIp();
		Main.serverBoundPort = getServer().getPort();
		loadConfig();

		getServer().getPluginManager().registerEvents(this, this);

		Main.init();

		if (!Main.testConn()) {
			getLogger().severe("§cRedis unavailable! All connections will be prevented.");
		}

		if (Main.serverBoundPort > 0) {
			SERVER_ONLINE_KEY = "pl_online:" + Main.serverBoundIp + ":" + Main.serverBoundPort; // To don't join strings on each loop I'm storing it as global variable
			getServer().getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
				@Override
				public void run() {
					try (Jedis jedis = Main.redis.getResource()) {
						int online = Bukkit.getOnlinePlayers().size();
						jedis.setex(SERVER_ONLINE_KEY, 60, String.valueOf(online));
					} catch (Exception e) {
						getLogger().warning("Failed to update player count in Redis: " + e.getMessage());
					}
				}
			}, 0, 5 * 20L);
		}
	}

	public void loadConfig() {
		try {
			File configFolder = new File("plugins/securetransfer");
			if (!configFolder.exists()) configFolder.mkdirs();

			File configFile = new File(configFolder, "config.yml");
			if (!configFile.exists()) {
				configFile.createNewFile();
			}

			FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

			boolean changed = false;

			if (!config.contains("secret")) {
				config.set("secret", Config.generateSecret());
				changed = true;
			}

			config.addDefault("redis.url", Config.redisUrl);
			config.addDefault("main-ip", Config.mainIp);
			config.options().copyDefaults(true);

			if (changed || !configFile.exists()) {
				try {
					config.save(configFile);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			Config.secret = config.getString("secret");
			Config.redisUrl = config.getString("redis.url");
			Config.mainIp = config.getString("main-ip", "[without port?]");

			MSG_USE_IP = text("Wrong IP. ", RED).append(text("Use IP: ", YELLOW)).append(text(Config.mainIp, GREEN));
			MSG_NO_TOKEN_FOUND = text("No token found. Do you joined correct IP and port? ", RED).append(text("Try this one: " + Config.mainIp, GRAY));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onDisable() {
		Main.redis.close();
	}

	@EventHandler
	public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
		if (!event.isTransferred()) {
			event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MSG_USE_IP);
		}

		if (!Main.testConn()) {
			event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MSG_REDIS_DOWN);
			return;
		}

		UUID uuid = event.getUniqueId();
		String activeKey = "is_online:" + uuid;
		String transferKey = "transfer:" + uuid;

		try (Jedis jedis = Main.redis.getResource()) {
			if (jedis.exists(activeKey)) {
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MSG_ALREADY_ONLINE);
				return;
			}

			String encodedToken = jedis.get(transferKey);
			if (encodedToken == null) {
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MSG_NO_TOKEN_FOUND);
				return;
			}

			jedis.del(transferKey);

			String decoded = new String(Base64.getDecoder().decode(encodedToken), StandardCharsets.UTF_8);
			String[] parts = decoded.split(":", 5);
			if (parts.length != 5) {
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MSG_INVALID_TOKEN);
				return;
			}

			String data = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3]; // uuid, ip, port, timestamp
			String sig = parts[4]; // signature
			if (!verifySignature(data, sig)) {
				event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MSG_INVALID_SIGN);
				return;
			}

			jedis.setex(activeKey, 60, getServer().getName());
			startKeepAlive(uuid);
		}
	}

	private boolean verifySignature(String data, String sig) {
		try {
			/*Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec secretKey = new SecretKeySpec(Config.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
			mac.init(secretKey);
			byte[] expectedSig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			String expected = Base64.getEncoder().encodeToString(expectedSig);
			return expected.equals(sig);*/

			String expected = Main.sign(data);
			//getLogger().info(">>> Expected: " + expected);
			//getLogger().info(">>> Actual:   " + sig);
			return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			return false;
		}
	}

	private void startKeepAlive(UUID uuid) {
		getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
			try (Jedis jedis = Main.redis.getResource()) {
				jedis.expire("is_online:" + uuid, 60);
			}
		}, 20L * 30, 20L * 30); // Each 30 s
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		String activeKey = "is_online:" + uuid;
		try (Jedis jedis = Main.redis.getResource()) {
			jedis.del(activeKey);
		}
	}

	// This method is done & ready to Paper-Paper transfers!
	public void transfer(Player player, String serverAddress) { // TODO Maybe add a command for it or smth else
		String ip;
		int port;

		if (serverAddress.contains(":")) {
			String[] split = serverAddress.split(":");
			ip = split[0];
			port = Integer.parseInt(split[1]);
		} else {
			ip = serverAddress;
			port = 25565;
		}

		UUID uuid = player.getUniqueId();
		String token = Main.generateToken(uuid, ip, port);

		try (Jedis jedis = Main.redis.getResource()) {
			jedis.setex("transfer:" + uuid, 30, token);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}

		if (player.getVirtualHost() != null && ip.equals(Main.serverBoundIp)) {
			ip = ip.replace(Main.serverBoundIp, player.getVirtualHost().getHostName());
		}
		player.transfer(ip, port);
	}
}