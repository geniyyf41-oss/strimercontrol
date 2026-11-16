package dev.stream;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/** Анонимное чтение чата Twitch по IRC (вход в аккаунт не нужен). */
final class Twitch {
	private final String channel;
	private final BiConsumer<String, String> onMsg;
	private volatile boolean run;
	private volatile String st = "подключаюсь…";
	private volatile Socket sock;

	Twitch(String channel, BiConsumer<String, String> onMsg) { this.channel = channel; this.onMsg = onMsg; }

	void start() {
		run = true;
		Thread t = new Thread(this::loop, "streamcontrol-twitch");
		t.setDaemon(true);
		t.start();
	}

	void stop() {
		run = false;
		try { if (sock != null) sock.close(); } catch (IOException ignored) { }
	}

	String status() { return st; }

	private void loop() {
		int wait = 2;
		while (run) {
			try (Socket s = new Socket("irc.chat.twitch.tv", 6667)) {
				sock = s;
				s.setSoTimeout(360_000);
				Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				send(out, "PASS SCHMOOPIIE");
				send(out, "NICK justinfan" + (10000 + (int) (Math.random() * 80000)));
				send(out, "JOIN #" + channel);
				st = "подключено (#" + channel + ")";
				wait = 2;
				String line;
				while (run && (line = in.readLine()) != null) {
					if (line.startsWith("PING")) { send(out, "PONG :tmi.twitch.tv"); continue; }
					int pm = line.indexOf(" PRIVMSG ");
					if (pm > 0 && line.startsWith(":")) {
						int e = line.indexOf('!');
						int m = line.indexOf(" :", pm);
						if (e > 1 && m > 0) onMsg.accept(line.substring(1, e), line.substring(m + 2));
					}
				}
			} catch (Exception e) {
				if (run) st = "ошибка: " + e.getMessage();
			}
			if (!run) break;
			try { Thread.sleep(wait * 1000L); } catch (InterruptedException ie) { return; }
			wait = Math.min(wait * 2, 60);
		}
		st = "отключено";
	}

	private static void send(Writer w, String l) throws IOException { w.write(l + "\r\n"); w.flush(); }
}
