package br.com.brasafut;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class BrasaFutPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private ClubService clubs;
    private final Map<UUID, ClubInvite> invites = new HashMap<>();
    private final Map<String, MatchChallenge> challenges = new HashMap<>();
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = color(getConfig().getString("prefix", "&6&lBrasaFut &8» &r"));
        clubs = new ClubService(this);
        clubs.load();

        Objects.requireNonNull(getCommand("fut"), "Comando /fut não foi definido no plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("fut"), "Comando /fut não foi definido no plugin.yml").setTabCompleter(this);

        if (Bukkit.getPluginManager().getPlugin("BlockBall") == null) {
            getLogger().warning(strip(getConfig().getString("messages.blockball-missing",
                    "O BlockBall não foi encontrado. Clubes funcionam, mas partidas automáticas ficam desativadas.")));
        } else {
            getLogger().info("BlockBall detectado. Integração de partidas ativada.");
        }

        getLogger().info("BrasaFut 0.1.0 ativado. Clubes carregados: " + clubs.all().size());
    }

    @Override
    public void onDisable() {
        if (clubs != null) clubs.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("brasafut.use")) {
            msg(sender, getConfig().getString("messages.no-permission", "&cVocê não tem permissão."));
            return true;
        }

        if (args.length == 0) {
            help(sender);
            return true;
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (root) {
                case "time", "clube" -> handleClub(sender, Arrays.copyOfRange(args, 1, args.length));
                case "desafiar" -> handleChallenge(sender, Arrays.copyOfRange(args, 1, args.length));
                case "aceitar" -> handleAcceptChallenge(sender);
                case "recusar" -> handleRejectChallenge(sender);
                case "sairpartida" -> handleLeaveMatch(sender);
                case "admin" -> handleAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
                case "ajuda", "help" -> { help(sender); yield true; }
                default -> { msg(sender, "&cSubcomando desconhecido. Use &f/fut ajuda&c."); yield true; }
            };
        } catch (Exception ex) {
            getLogger().severe("Erro ao executar /fut: " + ex.getMessage());
            ex.printStackTrace();
            msg(sender, "&cOcorreu um erro ao executar esse comando. Veja o console.");
            return true;
        }
    }

    private boolean handleClub(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, "&e/fut time criar <TAG> <nome>");
            msg(sender, "&e/fut time info [TAG]");
            msg(sender, "&e/fut time convidar <jogador>");
            msg(sender, "&e/fut time aceitar <TAG>");
            msg(sender, "&e/fut time sair");
            msg(sender, "&e/fut time expulsar <jogador>");
            msg(sender, "&e/fut time listar");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "criar" -> createClub(sender, args);
            case "info" -> clubInfo(sender, args);
            case "convidar" -> invitePlayer(sender, args);
            case "aceitar" -> acceptInvite(sender, args);
            case "sair" -> leaveClub(sender);
            case "expulsar" -> kickMember(sender, args);
            case "listar" -> listClubs(sender);
            default -> {
                msg(sender, "&cOpção de time desconhecida.");
                yield true;
            }
        };
    }

    private boolean createClub(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        if (args.length < 3) {
            msg(player, "&cUse: /fut time criar <TAG> <nome do clube>");
            return true;
        }
        if (clubs.byMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cVocê já participa de um clube.");
            return true;
        }

        String tag = args[1].toUpperCase(Locale.ROOT);
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        int minName = getConfig().getInt("clubs.min-name-length", 3);
        int maxName = getConfig().getInt("clubs.max-name-length", 24);
        int minTag = getConfig().getInt("clubs.min-tag-length", 2);
        int maxTag = getConfig().getInt("clubs.max-tag-length", 5);

        if (name.length() < minName || name.length() > maxName) {
            msg(player, "&cO nome precisa ter entre " + minName + " e " + maxName + " caracteres.");
            return true;
        }
        if (!tag.matches("[A-Z0-9]{" + minTag + "," + maxTag + "}")) {
            msg(player, "&cA TAG precisa ter " + minTag + " a " + maxTag + " letras/números, sem espaços.");
            return true;
        }
        if (clubs.byNameOrTag(name).isPresent() || clubs.byNameOrTag(tag).isPresent()) {
            msg(player, "&cJá existe um clube com esse nome ou TAG.");
            return true;
        }

        Club club = clubs.create(name, tag, player.getUniqueId(), player.getName());
        msg(player, "&aClube criado: &f" + club.name + " &7[&e" + club.tag + "&7]");
        msg(player, "&7Agora convide seus amigos com &f/fut time convidar <jogador>&7.");
        return true;
    }

    private boolean clubInfo(CommandSender sender, String[] args) {
        Optional<Club> found;
        if (args.length >= 2) {
            found = clubs.byNameOrTag(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        } else if (sender instanceof Player p) {
            found = clubs.byMember(p.getUniqueId());
        } else {
            msg(sender, "&cUse: /fut time info <TAG>");
            return true;
        }

        if (found.isEmpty()) {
            msg(sender, "&cClube não encontrado.");
            return true;
        }

        Club c = found.get();
        msg(sender, "&8&m--------------------------------");
        msg(sender, "&6&l" + c.name + " &7[&e" + c.tag + "&7]");
        msg(sender, "&7Dono: &f" + c.ownerName);
        msg(sender, "&7Membros: &f" + c.members.size() + "/" + getConfig().getInt("clubs.max-members", 12));
        msg(sender, "&7Campanha: &a" + c.wins + "V &e" + c.draws + "E &c" + c.losses + "D");
        msg(sender, "&7Integrantes: &f" + c.members.values().stream()
                .map(m -> m.lastName + (m.role.equals("OWNER") ? " &6(Dono)&f" : ""))
                .collect(Collectors.joining("&7, &f")));
        msg(sender, "&8&m--------------------------------");
        return true;
    }

    private boolean invitePlayer(CommandSender sender, String[] args) {
        Player owner = requirePlayer(sender);
        if (owner == null) return true;
        if (args.length < 2) {
            msg(owner, "&cUse: /fut time convidar <jogador>");
            return true;
        }

        Club club = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (club == null) {
            msg(owner, "&cVocê não participa de um clube.");
            return true;
        }
        if (!club.owner.equals(owner.getUniqueId())) {
            msg(owner, "&cSomente o dono pode convidar jogadores.");
            return true;
        }
        if (club.members.size() >= getConfig().getInt("clubs.max-members", 12)) {
            msg(owner, "&cSeu clube atingiu o limite de jogadores.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(owner, "&cEsse jogador precisa estar online.");
            return true;
        }
        if (target.getUniqueId().equals(owner.getUniqueId())) {
            msg(owner, "&cVocê já está no clube.");
            return true;
        }
        if (clubs.byMember(target.getUniqueId()).isPresent()) {
            msg(owner, "&cEsse jogador já participa de um clube.");
            return true;
        }

        invites.put(target.getUniqueId(), new ClubInvite(club.key, Instant.now().getEpochSecond()));
        msg(owner, "&aConvite enviado para &f" + target.getName() + "&a.");
        msg(target, "&eVocê foi convidado para &f" + club.name + " &7[&e" + club.tag + "&7]&e.");
        msg(target, "&7Use &f/fut time aceitar " + club.tag + " &7para entrar.");
        return true;
    }

    private boolean acceptInvite(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        if (args.length < 2) {
            msg(player, "&cUse: /fut time aceitar <TAG>");
            return true;
        }
        if (clubs.byMember(player.getUniqueId()).isPresent()) {
            msg(player, "&cVocê já participa de um clube.");
            return true;
        }

        ClubInvite invite = invites.get(player.getUniqueId());
        Club target = clubs.byNameOrTag(args[1]).orElse(null);
        if (invite == null || target == null || !invite.clubKey.equals(target.key)) {
            msg(player, "&cVocê não possui convite válido para esse clube.");
            return true;
        }
        if (target.members.size() >= getConfig().getInt("clubs.max-members", 12)) {
            msg(player, "&cEsse clube está cheio.");
            return true;
        }

        clubs.addMember(target, player.getUniqueId(), player.getName(), "PLAYER");
        invites.remove(player.getUniqueId());
        msg(player, "&aVocê entrou no &f" + target.name + "&a.");
        broadcastClub(target, "&e" + player.getName() + " &7entrou no clube.");
        return true;
    }

    private boolean leaveClub(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        Club club = clubs.byMember(player.getUniqueId()).orElse(null);
        if (club == null) {
            msg(player, "&cVocê não participa de um clube.");
            return true;
        }
        if (club.owner.equals(player.getUniqueId())) {
            msg(player, "&cO dono não pode sair enquanto possuir o clube. Use /fut admin dissolver <TAG> se quiser removê-lo.");
            return true;
        }
        clubs.removeMember(club, player.getUniqueId());
        msg(player, "&eVocê saiu do clube &f" + club.name + "&e.");
        broadcastClub(club, "&e" + player.getName() + " &7saiu do clube.");
        return true;
    }

    private boolean kickMember(CommandSender sender, String[] args) {
        Player owner = requirePlayer(sender);
        if (owner == null) return true;
        if (args.length < 2) {
            msg(owner, "&cUse: /fut time expulsar <jogador>");
            return true;
        }
        Club club = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (club == null || !club.owner.equals(owner.getUniqueId())) {
            msg(owner, "&cSomente o dono do clube pode expulsar jogadores.");
            return true;
        }

        Member member = club.members.values().stream()
                .filter(m -> m.lastName.equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (member == null) {
            msg(owner, "&cJogador não encontrado no seu clube.");
            return true;
        }
        if (member.uuid.equals(club.owner)) {
            msg(owner, "&cVocê não pode expulsar o dono.");
            return true;
        }

        clubs.removeMember(club, member.uuid);
        msg(owner, "&a" + member.lastName + " foi removido do clube.");
        Player online = Bukkit.getPlayer(member.uuid);
        if (online != null) msg(online, "&cVocê foi removido do clube &f" + club.name + "&c.");
        return true;
    }

    private boolean listClubs(CommandSender sender) {
        List<Club> list = new ArrayList<>(clubs.all());
        list.sort(Comparator.comparingInt((Club c) -> c.wins).reversed().thenComparing(c -> c.name));
        if (list.isEmpty()) {
            msg(sender, "&7Nenhum clube foi criado ainda.");
            return true;
        }
        msg(sender, "&6Clubes cadastrados:");
        for (Club c : list) {
            msg(sender, "&e[" + c.tag + "] &f" + c.name + " &8- &7" + c.members.size() + " jogadores &8- &a" + c.wins + "V");
        }
        return true;
    }

    private boolean handleChallenge(CommandSender sender, String[] args) {
        Player owner = requirePlayer(sender);
        if (owner == null) return true;
        if (args.length < 1) {
            msg(owner, "&cUse: /fut desafiar <TAG> [arena]");
            return true;
        }

        Club home = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (home == null || !home.owner.equals(owner.getUniqueId())) {
            msg(owner, "&cSomente o dono de um clube pode enviar desafios.");
            return true;
        }
        Club away = clubs.byNameOrTag(args[0]).orElse(null);
        if (away == null) {
            msg(owner, "&cClube adversário não encontrado.");
            return true;
        }
        if (home.key.equals(away.key)) {
            msg(owner, "&cVocê não pode desafiar seu próprio clube.");
            return true;
        }

        String arena = args.length >= 2 ? args[1] : getConfig().getString("matches.default-arena", "1");
        long now = Instant.now().getEpochSecond();
        challenges.put(away.key, new MatchChallenge(home.key, away.key, arena, now));

        msg(owner, "&aDesafio enviado: &f" + home.name + " &7x &f" + away.name + " &7na arena &e" + arena + "&a.");
        Player awayOwner = Bukkit.getPlayer(away.owner);
        if (awayOwner != null) {
            msg(awayOwner, "&e" + home.name + " &fdesafiou seu clube para uma partida na arena &e" + arena + "&f.");
            msg(awayOwner, "&7Use &f/fut aceitar &7ou &f/fut recusar&7.");
        }
        return true;
    }

    private boolean handleAcceptChallenge(CommandSender sender) {
        Player owner = requirePlayer(sender);
        if (owner == null) return true;
        Club away = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (away == null || !away.owner.equals(owner.getUniqueId())) {
            msg(owner, "&cSomente o dono de um clube pode aceitar desafios.");
            return true;
        }

        MatchChallenge challenge = challenges.get(away.key);
        if (challenge == null) {
            msg(owner, "&cSeu clube não possui um desafio pendente.");
            return true;
        }
        long expiry = getConfig().getLong("matches.challenge-expire-seconds", 120);
        if (Instant.now().getEpochSecond() - challenge.createdAt > expiry) {
            challenges.remove(away.key);
            msg(owner, "&cEsse desafio expirou.");
            return true;
        }

        Club home = clubs.byKey(challenge.homeKey).orElse(null);
        if (home == null) {
            challenges.remove(away.key);
            msg(owner, "&cO clube desafiante não existe mais.");
            return true;
        }

        challenges.remove(away.key);
        announceMatch(home, away, challenge.arena);
        startBlockBallMatch(home, away, challenge.arena);
        return true;
    }

    private boolean handleRejectChallenge(CommandSender sender) {
        Player owner = requirePlayer(sender);
        if (owner == null) return true;
        Club club = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (club == null || !club.owner.equals(owner.getUniqueId())) {
            msg(owner, "&cSomente o dono pode recusar desafios.");
            return true;
        }
        MatchChallenge removed = challenges.remove(club.key);
        if (removed == null) {
            msg(owner, "&cNão há desafio pendente.");
            return true;
        }
        msg(owner, "&eDesafio recusado.");
        clubs.byKey(removed.homeKey).ifPresent(home -> {
            Player p = Bukkit.getPlayer(home.owner);
            if (p != null) msg(p, "&c" + club.name + " recusou o desafio.");
        });
        return true;
    }

    private boolean handleLeaveMatch(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return true;
        String cmd = getConfig().getString("blockball.leave-command", "bbleave");
        if (cmd == null || cmd.isBlank()) return true;
        player.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("brasafut.admin")) {
            msg(sender, "&cVocê não tem permissão de administrador.");
            return true;
        }
        if (args.length == 0) {
            msg(sender, "&e/fut admin recarregar");
            msg(sender, "&e/fut admin dissolver <TAG>");
            return true;
        }
        if (args[0].equalsIgnoreCase("recarregar")) {
            reloadConfig();
            prefix = color(getConfig().getString("prefix", "&6&lBrasaFut &8» &r"));
            clubs.load();
            msg(sender, "&aConfigurações e clubes recarregados.");
            return true;
        }
        if (args[0].equalsIgnoreCase("dissolver") && args.length >= 2) {
            Club club = clubs.byNameOrTag(args[1]).orElse(null);
            if (club == null) {
                msg(sender, "&cClube não encontrado.");
                return true;
            }
            clubs.delete(club);
            challenges.remove(club.key);
            msg(sender, "&eClube &f" + club.name + " &edissolvido.");
            return true;
        }
        msg(sender, "&cComando administrativo inválido.");
        return true;
    }

    private void announceMatch(Club home, Club away, String arena) {
        String line = "&6&lPARTIDA CONFIRMADA &8- &f" + home.name + " &eX &f" + away.name + " &8(&7Arena " + arena + "&8)";
        broadcastClub(home, line);
        broadcastClub(away, line);
    }

    private void startBlockBallMatch(Club home, Club away, String arena) {
        if (!getConfig().getBoolean("matches.auto-join-online-members", true)) return;
        if (Bukkit.getPluginManager().getPlugin("BlockBall") == null) {
            broadcastClub(home, "&cBlockBall não está instalado; entrada automática não foi executada.");
            broadcastClub(away, "&cBlockBall não está instalado; entrada automática não foi executada.");
            return;
        }

        joinSide(home, arena, "red");
        joinSide(away, arena, "blue");
    }

    private void joinSide(Club club, String arena, String side) {
        String template = getConfig().getString("blockball.join-command", "bbjoin %arena% %side%");
        if (template == null || template.isBlank()) return;
        for (Member member : club.members.values()) {
            Player player = Bukkit.getPlayer(member.uuid);
            if (player == null || !player.isOnline()) continue;
            String cmd = template.replace("%arena%", arena).replace("%side%", side);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            boolean ok = player.performCommand(cmd);
            if (!ok) {
                msg(player, "&cNão foi possível entrar automaticamente na arena. Tente o comando do BlockBall manualmente.");
            }
        }
    }

    private void broadcastClub(Club club, String text) {
        for (Member member : club.members.values()) {
            Player p = Bukkit.getPlayer(member.uuid);
            if (p != null && p.isOnline()) msg(p, text);
        }
    }

    private void help(CommandSender sender) {
        msg(sender, "&8&m--------------------------------");
        msg(sender, "&6&lBrasaFut &7- clubes de jogadores");
        msg(sender, "&f/fut time criar <TAG> <nome> &7- cria seu clube");
        msg(sender, "&f/fut time convidar <jogador> &7- convida um amigo");
        msg(sender, "&f/fut time aceitar <TAG> &7- aceita convite");
        msg(sender, "&f/fut time info [TAG] &7- informações do clube");
        msg(sender, "&f/fut time listar &7- lista clubes");
        msg(sender, "&f/fut desafiar <TAG> [arena] &7- desafia outro clube");
        msg(sender, "&f/fut aceitar &7- aceita desafio de partida");
        msg(sender, "&f/fut recusar &7- recusa o desafio");
        msg(sender, "&8&m--------------------------------");
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        msg(sender, "&cEsse comando só pode ser usado por jogadores.");
        return null;
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(prefix + color(text));
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static String strip(String text) {
        return ChatColor.stripColor(color(text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(args[0], List.of("time", "desafiar", "aceitar", "recusar", "sairpartida", "ajuda"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("time") || args[0].equalsIgnoreCase("clube"))) {
            return filter(args[1], List.of("criar", "info", "convidar", "aceitar", "sair", "expulsar", "listar"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("desafiar")) {
            return filter(args[1], clubs.all().stream().map(c -> c.tag).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("time") && args[1].equalsIgnoreCase("info")) {
            return filter(args[2], clubs.all().stream().map(c -> c.tag).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("time") && args[1].equalsIgnoreCase("convidar")) {
            return filter(args[2], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return Collections.emptyList();
    }

    private List<String> filter(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private static String normalizeKey(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return normalized.isBlank() ? UUID.randomUUID().toString().replace("-", "") : normalized;
    }

    private static final class ClubInvite {
        final String clubKey;
        final long createdAt;
        ClubInvite(String clubKey, long createdAt) {
            this.clubKey = clubKey;
            this.createdAt = createdAt;
        }
    }

    private static final class MatchChallenge {
        final String homeKey;
        final String awayKey;
        final String arena;
        final long createdAt;
        MatchChallenge(String homeKey, String awayKey, String arena, long createdAt) {
            this.homeKey = homeKey;
            this.awayKey = awayKey;
            this.arena = arena;
            this.createdAt = createdAt;
        }
    }

    private static final class Member {
        final UUID uuid;
        String lastName;
        String role;
        Member(UUID uuid, String lastName, String role) {
            this.uuid = uuid;
            this.lastName = lastName;
            this.role = role;
        }
    }

    private static final class Club {
        final String key;
        String name;
        String tag;
        UUID owner;
        String ownerName;
        int wins;
        int draws;
        int losses;
        final Map<UUID, Member> members = new LinkedHashMap<>();

        Club(String key) {
            this.key = key;
        }
    }

    private static final class ClubService {
        private final BrasaFutPlugin plugin;
        private final File file;
        private final Map<String, Club> data = new LinkedHashMap<>();

        ClubService(BrasaFutPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "clubs.yml");
        }

        void load() {
            data.clear();
            if (!file.exists()) return;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yaml.getConfigurationSection("clubs");
            if (root == null) return;

            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                try {
                    Club c = new Club(key);
                    c.name = s.getString("name", key);
                    c.tag = s.getString("tag", key.toUpperCase(Locale.ROOT));
                    c.owner = UUID.fromString(Objects.requireNonNull(s.getString("owner")));
                    c.ownerName = s.getString("owner-name", "Desconhecido");
                    c.wins = s.getInt("stats.wins", 0);
                    c.draws = s.getInt("stats.draws", 0);
                    c.losses = s.getInt("stats.losses", 0);

                    ConfigurationSection ms = s.getConfigurationSection("members");
                    if (ms != null) {
                        for (String uuidText : ms.getKeys(false)) {
                            UUID uuid = UUID.fromString(uuidText);
                            String lastName = ms.getString(uuidText + ".name", "Jogador");
                            String role = ms.getString(uuidText + ".role", "PLAYER");
                            c.members.put(uuid, new Member(uuid, lastName, role));
                        }
                    }
                    if (!c.members.containsKey(c.owner)) {
                        c.members.put(c.owner, new Member(c.owner, c.ownerName, "OWNER"));
                    }
                    data.put(c.key, c);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Clube inválido ignorado em clubs.yml: " + key + " (" + ex.getMessage() + ")");
                }
            }
        }

        Club create(String name, String tag, UUID owner, String ownerName) {
            String base = normalizeKey(tag);
            String key = base;
            int i = 2;
            while (data.containsKey(key)) key = base + i++;
            Club c = new Club(key);
            c.name = name;
            c.tag = tag;
            c.owner = owner;
            c.ownerName = ownerName;
            c.members.put(owner, new Member(owner, ownerName, "OWNER"));
            data.put(key, c);
            save();
            return c;
        }

        void addMember(Club club, UUID uuid, String name, String role) {
            club.members.put(uuid, new Member(uuid, name, role));
            save();
        }

        void removeMember(Club club, UUID uuid) {
            club.members.remove(uuid);
            save();
        }

        void delete(Club club) {
            data.remove(club.key);
            save();
        }

        Optional<Club> byMember(UUID uuid) {
            return data.values().stream().filter(c -> c.members.containsKey(uuid)).findFirst();
        }

        Optional<Club> byNameOrTag(String value) {
            String v = value.trim();
            String normalized = normalizeKey(v);
            return data.values().stream().filter(c ->
                    c.key.equalsIgnoreCase(normalized)
                            || c.tag.equalsIgnoreCase(v)
                            || c.name.equalsIgnoreCase(v)).findFirst();
        }

        Optional<Club> byKey(String key) {
            return Optional.ofNullable(data.get(key));
        }

        Set<Club> all() {
            return Set.copyOf(data.values());
        }

        void save() {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Não foi possível criar a pasta do BrasaFut.");
                return;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            for (Club c : data.values()) {
                String p = "clubs." + c.key;
                yaml.set(p + ".name", c.name);
                yaml.set(p + ".tag", c.tag);
                yaml.set(p + ".owner", c.owner.toString());
                yaml.set(p + ".owner-name", c.ownerName);
                yaml.set(p + ".stats.wins", c.wins);
                yaml.set(p + ".stats.draws", c.draws);
                yaml.set(p + ".stats.losses", c.losses);
                for (Member m : c.members.values()) {
                    String mp = p + ".members." + m.uuid;
                    yaml.set(mp + ".name", m.lastName);
                    yaml.set(mp + ".role", m.role);
                }
            }
            try {
                yaml.save(file);
            } catch (IOException ex) {
                plugin.getLogger().severe("Não foi possível salvar clubs.yml: " + ex.getMessage());
            }
        }
    }
}
