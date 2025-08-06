package by.mine.securetransfer;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public class Main {

	public static JedisPool redis = null;
	public static String serverBoundIp = "";
	public static int serverBoundPort = 0;

	public static void init() {
		connect();
	}

	public static void connect() {
		if (redis != null) {
			try {
				redis.close();
			} catch (Exception ignored) {}
		}

		JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setTestOnBorrow(true);

		redis = new JedisPool(Config.redisUrl);
	}

	public static boolean testConn() {
		try (Jedis jedis = redis.getResource()) {
			jedis.ping();
			return true;
		} catch (Exception e1) {
			try {
				connect();
				try (Jedis jedis = redis.getResource()) {
					jedis.ping();
					return true;
				}
			} catch (Exception e2) {
				e1.printStackTrace();
				e2.printStackTrace();
			}
		}
		return false;
	}

	public static String generateToken(UUID uuid, String ip, int port) {
		String timestamp = String.valueOf(Instant.now().toEpochMilli());
		String data = uuid + ":" + ip + ":" + port + ":" + timestamp;
		String signature = sign(data);
		return Base64.getEncoder().encodeToString((data + ":" + signature).getBytes(StandardCharsets.UTF_8));
	}

	public static String sign(String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			SecretKeySpec secretKey = new SecretKeySpec(Config.secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
			mac.init(secretKey);
			byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(raw);
		} catch (Exception e) {
			throw new RuntimeException("Failed to sign", e);
		}
	}
}
