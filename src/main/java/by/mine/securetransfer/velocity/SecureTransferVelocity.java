package by.mine.securetransfer.velocity;

import by.mine.securetransfer.Config;
import by.mine.securetransfer.Main;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import redis.clients.jedis.*;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Plugin(id = "securetransfer", name = "SecureTransfer", version = "1.0")
public class SecureTransferVelocity {

	private final ProxyServer server;
	private final Logger logger;

	final static Component MSG_REDIS_DOWN = text("Connections checker is temporarily unavailable [Velocity side]. Please, come a little bit later", RED);

	@Inject
	public SecureTransferVelocity(ProxyServer server, Logger logger) {
		this.server = server;
		this.logger = logger;
	}

	@Subscribe
	public void onProxyInitialization(ProxyInitializeEvent event) {
		String serverBoundIp = server.getBoundAddress().getHostName();
		if (serverBoundIp != null) {
			Main.serverBoundIp = serverBoundIp;
			Main.serverBoundPort = server.getBoundAddress().getPort();
		}

		loadConfig();

		Main.init();

		if (!Main.testConn()) {
			logger.error("§cRedis unavailable! All connections will be prevented.");
		}
	}

	public void loadConfig() {
		File configDir = new File("plugins/securetransfer");
		if (!configDir.exists()) configDir.mkdirs();

		File configFile = new File(configDir, "config.yml");
		try {
			if (!configFile.exists()) {
				configFile.createNewFile();
			}

			YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
					.file(configFile)
					.build();

			ConfigurationNode root = loader.load();

			boolean changed = false;

			if (root.node("secret").virtual()) {
				root.node("secret").set(Config.generateSecret());
				changed = true;
			}

			if (root.node("redis", "url").virtual()) {
				root.node("redis", "url").set(Config.redisUrl);
				changed = true;
			}

			if (root.node("main-ip").virtual()) {
				root.node("main-ip").set(Config.mainIp);
				changed = true;
			}

			if (changed) {
				loader.save(root);
			}

			Config.secret = root.node("secret").getString();
			Config.redisUrl = root.node("redis", "url").getString();
			Config.mainIp = root.node("main-ip").getString();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		Main.redis.close();
	}

	@Subscribe
	public void onServerPreConnect(ServerPreConnectEvent event) {

		event.setResult(ServerPreConnectEvent.ServerResult.denied());

		Player player = event.getPlayer();

		/*ProtocolVersion v = player.getProtocolVersion();
		logger.info("-------------------------------------------------------------------------");
		logger.info("ProtocolVersion of player " + player.getUsername() + ":");
		logger.info(v.toString());
		logger.info("getProtocol: " + v.getProtocol());
		logger.info("isUnknown: " + v.isUnknown());
		logger.info("compare to 1.20.5: " + v.compareTo(ProtocolVersion.MINECRAFT_1_20_5));
		logger.info("-------------------------------------------------------------------------");*/

		if (!Main.testConn()) {
			player.disconnect(MSG_REDIS_DOWN);
			return;
		}

		UUID uuid = player.getUniqueId();
		RegisteredServer target = event.getOriginalServer();
		if (target == null) return;

		InetSocketAddress address = target.getServerInfo().getAddress();
		String ip = address.getAddress().getHostAddress();
		if (player.getVirtualHost().isPresent() && ip.equals(Main.serverBoundIp)) {
			ip = ip.replace(Main.serverBoundIp, player.getVirtualHost().get().getHostName());
		}
		int port = address.getPort();

		String token = Main.generateToken(uuid, ip, port);
		//logger.info("~ generateToken: " + token);

		try (Jedis jedis = Main.redis.getResource()) {
			//logger.info("~ try setex: " + "transfer:" + uuid + " | 30 | " + token);
			jedis.setex("transfer:" + uuid, 30, token);
			//logger.info("~ -----------------------------------------------------");
		}

		logger.info("Transfer " + player.getUsername() + " (" + player.getProtocolVersion() + ")" + " to host " + ip + ":" + port);
		InetSocketAddress correct = new InetSocketAddress(ip, port);
		player.transferToHost(correct);
	}

	/*@Subscribe(order = PostOrder.LAST)
	public void onProxyPing(ProxyPingEvent event) {
		ServerPing ping = event.getPing().asBuilder()
			.onlinePlayers(123)
			.nullPlayers()
			.build();

		event.setPing(ping);
	}*/
}