package dev.stream;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamControl implements ModInitializer {
	static final Logger LOG = LoggerFactory.getLogger("streamcontrol");
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	static final ScheduledExecutorService POOL = Executors.newScheduledThreadPool(4, r -> {
		Thread t = new Thread(r, "streamcontrol");
		t.setDaemon(true);
		return t;
	});
	static final Pattern WAIT = Pattern.compile("(?i)wait\\s+(\\d+)");
	static final Pattern VOTE = Pattern.compile("!(\\d)");

	public static class Rule { String name; int from, to; String commands; }
	public static class Config {
		List<String> owners = new ArrayList<>(), admins = new ArrayList<>(), mods = new ArrayList<>();
		String twitchChannel = "", donationAlertsToken = "", target = "@a";
		boolean announceVotes = true, announceDonations = true;
		List<Rule> rules = new ArrayList<>();
	}
	static class Vote { List<Rule> opts; Map<String, Integer> votes = new ConcurrentHashMap<>(); ScheduledFuture<?> end; }

	static Path file;
	static Config cfg = new Config();
	static volatile MinecraftServer server;
	static Twitch twitch;
	static DonationAlerts alerts;
	static volatile Vote vote;

	@Override public void onInitialize() {
		file = FabricLoader.getInstance().getConfigDir().resolve("streamcontrol.json");
		load();
		ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; connect(); });
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> { stopNet(); server = null; });
		CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(tree()));
	}

	// ---------- конфиг и подключения ----------
	static void load() {
		try {
			if (!Files.exists(file)) {
				try (InputStream in = StreamControl.class.getResourceAsStream("/default-config.json")) { Files.copy(in, file); }
			}
			try (Reader r = Files.newBufferedReader(file)) {
				Config c = GSON.fromJson(r, Config.class);
				if (c != null) cfg = c;
			}
		} catch (Exception e) { LOG.error("Не удалось прочитать конфиг", e); }
	}

	static void save() {
		try { Files.writeString(file, GSON.toJson(cfg)); } catch (IOException e) { LOG.error("Не удалось сохранить конфиг", e); }
	}

	static void stopNet() {
		if (twitch != null) twitch.stop();
		if (alerts != null) alerts.stop();
		twitch = null;
		alerts = null;
	}

	static void connect() {
		stopNet();
		String ch = cfg.twitchChannel.replace("#", "").trim().toLowerCase();
		if (!ch.isEmpty()) { twitch = new Twitch(ch, StreamControl::onChat); twitch.start(); }
		if (!cfg.donationAlertsToken.isBlank()) { alerts = new DonationAlerts(cfg.donationAlertsToken.trim(), StreamControl::onDonation); alerts.start(); }
	}

	// ---------- запуск команд ----------
	static void exec(String cmd) {
		MinecraftServer s = server;
		if (s == null) return;
		s.execute(() -> {
			try { s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), cmd); }
			catch (Exception e) { LOG.warn("Команда не выполнилась: {}", cmd, e); }
		});
	}

	/** Команды через ;; ; "wait N" — пауза N секунд для следующих команд. {p} заменяется на ник цели. */
	static void runMany(String text) {
		long delay = 0;
		for (String c : text.split(";;")) {
			c = c.trim();
			if (c.isEmpty()) continue;
			Matcher m = WAIT.matcher(c);
			if (m.matches()) { delay += Math.min(Long.parseLong(m.group(1)), 300); continue; }
			String cmd = c.replace("{p}", cfg.target.isBlank() ? "@a" : cfg.target);
			POOL.schedule(() -> exec(cmd), delay, TimeUnit.SECONDS);
		}
	}

	static JsonObject c(String text, String color) {
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		o.addProperty("color", color);
		return o;
	}

	static void tell(JsonObject... parts) {
		JsonArray a = new JsonArray();
		for (JsonObject p : parts) a.add(p);
		exec("tellraw @a " + a);
	}

	static String safe(String s) {
		String r = s == null ? "" : s.replaceAll("[^\\p{L}\\p{N} _.,!?-]", "");
		return r.length() > 40 ? r.substring(0, 40) : r;
	}

	// ---------- донаты ----------
	static void onDonation(String user, double amount, String msg) {
		String u = safe(user);
		LOG.info("Донат: {} — {}", u, amount);
		List<String> names = new ArrayList<>();
		for (Rule r : cfg.rules) {
			if (amount >= r.from && amount < r.to + 1) { names.add(r.name); runMany(r.commands); }
		}
		if (cfg.announceDonations && !names.isEmpty()) {
			String n = String.join(", ", names);
			tell(c("Донат ", "gold"), c(u, "yellow"), c(" (" + (long) amount + "): ", "gold"), c(n, "red"));
			exec("title @a actionbar " + c(u + " запустил: " + n, "gold"));
		}
	}

	// ---------- голосования ----------
	static void onChat(String user, String msg) {
		Vote v = vote;
		Matcher m = VOTE.matcher(msg.trim());
		if (v == null || !m.matches()) return;
		int i = Integer.parseInt(m.group(1)) - 1;
		if (i >= 0 && i < v.opts.size()) v.votes.put(user.toLowerCase(), i);
	}

	static synchronized String startVote(int sec, List<Rule> opts) {
		endVote();
		if (opts.size() < 2) return "Нужно минимум 2 варианта";
		Vote v = new Vote();
		v.opts = opts;
		vote = v;
		v.end = POOL.schedule(StreamControl::endVote, sec, TimeUnit.SECONDS);
		if (cfg.announceVotes) {
			tell(c("Голосование чата! ", "aqua"), c("Пишите в чат Twitch, время: " + sec + " с", "gray"));
			for (int i = 0; i < opts.size(); i++) tell(c("!" + (i + 1) + " ", "yellow"), c(opts.get(i).name, "white"));
		}
		return "Голосование запущено";
	}

	static synchronized void endVote() {
		Vote v = vote;
		if (v == null) return;
		vote = null;
		if (v.end != null) v.end.cancel(false);
		int[] n = new int[v.opts.size()];
		v.votes.values().forEach(i -> n[i]++);
		int max = 0;
		for (int x : n) max = Math.max(max, x);
		if (max == 0) {
			if (cfg.announceVotes) tell(c("Голосование окончено: никто не проголосовал", "gray"));
			return;
		}
		List<Integer> top = new ArrayList<>();
		for (int i = 0; i < n.length; i++) if (n[i] == max) top.add(i);
		Rule w = v.opts.get(top.get(ThreadLocalRandom.current().nextInt(top.size())));
		if (cfg.announceVotes) tell(c("Голосование окончено: ", "green"), c(w.name, "yellow"), c(" (" + max + " гол.)", "gray"));
		runMany(w.commands);
	}

	// ---------- права по нику ----------
	static boolean has(List<String> l, String n) {
		for (String x : l) if (x.equalsIgnoreCase(n)) return true;
		return false;
	}

	/** 0 — нет доступа, 1 — модератор, 2 — админ, 3 — владелец (консоль всегда владелец). */
	static int level(CommandSourceStack s) {
		String n = s.getTextName();
		if (s.getEntity() == null && (n.equals("Server") || n.equals("Rcon"))) return 3;
		if (has(cfg.owners, n)) return 3;
		if (has(cfg.admins, n)) return 2;
		if (has(cfg.mods, n)) return 1;
		return 0;
	}

	static int go(CommandContext<CommandSourceStack> ctx, int need, Supplier<String> action) {
		CommandSourceStack s = ctx.getSource();
		if (level(s) < need) { s.sendFailure(Component.literal("Нет доступа")); return 0; }
		String msg;
		try { msg = action.get(); } catch (Exception e) { msg = "Ошибка: " + e.getMessage(); }
		String out = msg;
		s.sendSuccess(() -> Component.literal(out), false);
		return 1;
	}

	static Rule rule(int n) { return n >= 1 && n <= cfg.rules.size() ? cfg.rules.get(n - 1) : null; }

	static LiteralArgumentBuilder<CommandSourceStack> tree() {
		return Commands.literal("stream")
			.then(Commands.literal("status").executes(x -> go(x, 1, () ->
				"Twitch: " + (twitch == null ? "выкл" : twitch.status())
				+ "\nДонаты: " + (alerts == null ? "выкл" : alerts.status())
				+ "\nГолосование: " + (vote == null ? "нет" : "идёт")
				+ "\nЦель {p}: " + cfg.target + ", правил: " + cfg.rules.size())))
			.then(Commands.literal("rules").executes(x -> go(x, 1, () -> {
				StringBuilder b = new StringBuilder("Номер, сумма, событие:");
				for (int i = 0; i < cfg.rules.size(); i++) {
					Rule r = cfg.rules.get(i);
					b.append("\n#").append(i + 1).append("  ").append(r.from).append("–").append(r.to).append("  ").append(r.name);
				}
				return b.toString();
			})))
			.then(Commands.literal("reload").executes(x -> go(x, 3, () -> { load(); connect(); return "Конфиг перезагружен, подключения обновлены"; })))
			.then(Commands.literal("run").then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(x -> go(x, 2, () -> {
				Rule r = rule(IntegerArgumentType.getInteger(x, "n"));
				if (r == null) return "Нет такого номера";
				runMany(r.commands);
				return "Запущено: " + r.name;
			}))))
			.then(Commands.literal("test").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(x -> go(x, 2, () -> {
				onDonation("Тест", IntegerArgumentType.getInteger(x, "amount"), "");
				return "Тестовый донат отправлен";
			}))))
			.then(Commands.literal("vote")
				.then(Commands.literal("stop").executes(x -> go(x, 1, () -> { endVote(); return "Голосование завершено"; })))
				.then(Commands.literal("random").then(Commands.argument("sec", IntegerArgumentType.integer(10, 300))
					.then(Commands.argument("count", IntegerArgumentType.integer(2, 9)).executes(x -> go(x, 1, () -> {
						List<Rule> all = new ArrayList<>(cfg.rules);
						Collections.shuffle(all);
						int n = Math.min(IntegerArgumentType.getInteger(x, "count"), all.size());
						return startVote(IntegerArgumentType.getInteger(x, "sec"), new ArrayList<>(all.subList(0, n)));
					})))))
				.then(Commands.literal("start").then(Commands.argument("sec", IntegerArgumentType.integer(10, 300))
					.then(Commands.argument("list", StringArgumentType.greedyString()).executes(x -> go(x, 1, () -> {
						List<Rule> opts = new ArrayList<>();
						for (String p : StringArgumentType.getString(x, "list").trim().split("[ ,]+")) {
							Rule r = rule(Integer.parseInt(p));
							if (r == null) return "Нет правила с номером " + p;
							opts.add(r);
						}
						return startVote(IntegerArgumentType.getInteger(x, "sec"), opts);
					}))))))
			.then(Commands.literal("allow").then(Commands.argument("role", StringArgumentType.word())
				.then(Commands.argument("nick", StringArgumentType.word()).executes(x -> go(x, 3, () -> {
					String role = StringArgumentType.getString(x, "role").toLowerCase(), nick = StringArgumentType.getString(x, "nick");
					List<String> l = role.equals("owner") ? cfg.owners : role.equals("admin") ? cfg.admins : role.equals("mod") ? cfg.mods : null;
					if (l == null) return "Роль: owner, admin или mod";
					cfg.owners.removeIf(n -> n.equalsIgnoreCase(nick));
					cfg.admins.removeIf(n -> n.equalsIgnoreCase(nick));
					cfg.mods.removeIf(n -> n.equalsIgnoreCase(nick));
					l.add(nick);
					save();
					return nick + " теперь " + role;
				})))))
			.then(Commands.literal("deny").then(Commands.argument("nick", StringArgumentType.word()).executes(x -> go(x, 3, () -> {
				String nick = StringArgumentType.getString(x, "nick");
				cfg.owners.removeIf(n -> n.equalsIgnoreCase(nick));
				cfg.admins.removeIf(n -> n.equalsIgnoreCase(nick));
				cfg.mods.removeIf(n -> n.equalsIgnoreCase(nick));
				save();
				return "Доступ " + nick + " убран";
			}))));
	}
}
