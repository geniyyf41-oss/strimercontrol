package dev.stream;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;

/** Получение донатов через сокет DonationAlerts (секретный токен из настроек сервиса). */
final class DonationAlerts {
	interface Handler { void on(String user, double amount, String msg); }

	private final String token;
	private final Handler handler;
	private volatile boolean run;
	private volatile String st = "подключаюсь…";
	private volatile WebSocket ws;

	DonationAlerts(String token, Handler handler) { this.token = token; this.handler = handler; }

	void start() { run = true; open(); }
	void stop() { run = false; WebSocket w = ws; if (w != null) w.abort(); }
	String status() { return st; }

	private void open() {
		if (!run) return;
		Listener l = new Listener();
		HttpClient.newHttpClient().newWebSocketBuilder()
			.buildAsync(URI.create("wss://socket.donationalerts.ru/socket.io/?EIO=3&transport=websocket"), l)
			.whenComplete((w, e) -> { if (e != null) l.fail("ошибка: " + e.getMessage()); else ws = w; });
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private final class Listener implements WebSocket.Listener {
		private final StringBuilder buf = new StringBuilder();
		private ScheduledFuture<?> ping;
		private boolean dead;

		synchronized void fail(String why) {
			if (dead) return;
			dead = true;
			st = why;
			if (ping != null) ping.cancel(false);
			if (run) StreamControl.POOL.schedule(DonationAlerts.this::open, 5, TimeUnit.SECONDS);
		}

		@Override public void onOpen(WebSocket w) { w.request(1); }

		@Override public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean last) {
			buf.append(d);
			w.request(1);
			if (last) {
				String m = buf.toString();
				buf.setLength(0);
				try { handle(w, m); } catch (Exception e) { StreamControl.LOG.warn("DonationAlerts: {}", e.toString()); }
			}
			return null;
		}

		@Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) { fail("отключено"); return null; }
		@Override public void onError(WebSocket w, Throwable e) { fail("ошибка: " + e.getMessage()); }

		private void handle(WebSocket w, String m) {
			if (m.startsWith("0")) {
				long pi = JsonParser.parseString(m.substring(1)).getAsJsonObject().get("pingInterval").getAsLong();
				ping = StreamControl.POOL.scheduleAtFixedRate(() -> w.sendText("2", true), pi, pi, TimeUnit.MILLISECONDS);
			} else if (m.equals("40")) {
				JsonObject auth = new JsonObject();
				auth.addProperty("token", token);
				auth.addProperty("type", "minor");
				w.sendText("42[\"add-user\"," + auth + "]", true);
				st = "подключено";
			} else if (m.startsWith("42")) {
				JsonArray a = JsonParser.parseString(m.substring(2)).getAsJsonArray();
				if (a.size() > 1 && a.get(0).getAsString().equals("donation")) {
					JsonElement e = a.get(1);
					JsonObject d = e.isJsonPrimitive() ? JsonParser.parseString(e.getAsString()).getAsJsonObject() : e.getAsJsonObject();
					double amount = d.has("amount") ? d.get("amount").getAsDouble() : 0;
					handler.on(str(d, "username"), amount, str(d, "message"));
				}
			}
		}
	}
}
