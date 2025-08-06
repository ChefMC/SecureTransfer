package by.mine.securetransfer;

import java.security.SecureRandom;

public class Config {


	public static String secret;
	public static String redisUrl = "redis://default:@127.0.0.1:6379";
	public static String mainIp = "...";


	public static String generateSecret() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
}
