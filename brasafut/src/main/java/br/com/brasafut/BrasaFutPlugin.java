package br.com.brasafut;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import java.util.*;
import java.util.stream.Collectors;

public final class BrasaFutPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private ClubService clubs;
    private ArenaEngine arenas;
    private final Map<UUID, ClubInvite> invites = new HashMap<>();
    private final Map<String, MatchChallenge> challenges = new HashMap<>();
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = color(getConfig().getString("prefix", "&6&lBrasaFut &8» &r"));
        clubs = new ClubService(this);
        clubs.load();
        arenas = new ArenaEngine(this);
        arenas.enable();
        Objects.requireNonNull(getCommand("fut")).setExecutor(this);
        Objects.requireNonNull(getCommand("fut")).setTabCompleter(this);
        getLogger().info("BrasaFut standalone ativado. Clubes: " + clubs.all().size() + ", arenas: " + arenas.all().size());
    }

    @Override
    public void onDisable() {
        if (clubs != null) clubs.save();
        if (arenas != null) arenas.disable();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("brasafut.use")) return msg(sender, "&cVocê não tem permissão.");
        if (args.length == 0) return help(sender);
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "time", "clube" -> handleClub(sender, Arrays.copyOfRange(args, 1, args.length));
                case "arena" -> handleArena(sender, Arrays.copyOfRange(args, 1, args.length));
                case "desafiar" -> handleChallenge(sender, Arrays.copyOfRange(args, 1, args.length));
                case "aceitar" -> acceptChallenge(sender);
                case "recusar" -> rejectChallenge(sender);
                case "admin" -> handleAdmin(sender, Arrays.copyOfRange(args, 1, args.length));
                case "ajuda", "help" -> help(sender);
                default -> msg(sender, "&cComando desconhecido. Use &f/fut ajuda&c.");
            };
        } catch (Exception ex) {
            getLogger().severe("Erro no /fut: " + ex.getMessage());
            ex.printStackTrace();
            return msg(sender, "&cOcorreu um erro. Veja o console.");
        }
    }

    private boolean handleClub(CommandSender sender, String[] args) {
        if (args.length == 0) return msg(sender, "&e/fut time criar <TAG> <nome> | convidar | aceitar | sair | expulsar | info | listar");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "criar" -> createClub(sender, args);
            case "info" -> clubInfo(sender, args);
            case "convidar" -> invite(sender, args);
            case "aceitar" -> acceptInvite(sender, args);
            case "sair" -> leaveClub(sender);
            case "expulsar" -> kickMember(sender, args);
            case "listar" -> listClubs(sender);
            default -> msg(sender, "&cOpção de time inválida.");
        };
    }

    private boolean createClub(CommandSender sender, String[] args) {
        Player p = player(sender); if (p == null) return true;
        if (args.length < 3) return msg(p, "&cUse: /fut time criar <TAG> <nome do clube>");
        if (clubs.byMember(p.getUniqueId()).isPresent()) return msg(p, "&cVocê já participa de um clube.");
        String tag = args[1].toUpperCase(Locale.ROOT);
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (!tag.matches("[A-Z0-9]{2,5}")) return msg(p, "&cA TAG deve ter 2 a 5 letras/números.");
        if (name.length() < 3 || name.length() > 24) return msg(p, "&cO nome deve ter 3 a 24 caracteres.");
        if (clubs.byNameOrTag(tag).isPresent() || clubs.byNameOrTag(name).isPresent()) return msg(p, "&cJá existe clube com esse nome/TAG.");
        Club c = clubs.create(name, tag, p.getUniqueId(), p.getName());
        return msg(p, "&aClube criado: &f" + c.name + " &7[&e" + c.tag + "&7]");
    }

    private boolean clubInfo(CommandSender sender, String[] args) {
        Optional<Club> found = args.length >= 2 ? clubs.byNameOrTag(String.join(" ", Arrays.copyOfRange(args,1,args.length))) :
                (sender instanceof Player p ? clubs.byMember(p.getUniqueId()) : Optional.empty());
        if (found.isEmpty()) return msg(sender, "&cClube não encontrado.");
        Club c = found.get();
        msg(sender, "&6&l" + c.name + " &7[&e" + c.tag + "&7]");
        msg(sender, "&7Dono: &f" + c.ownerName + " &8| &7Membros: &f" + c.members.size());
        msg(sender, "&7Campanha: &a" + c.wins + "V &e" + c.draws + "E &c" + c.losses + "D");
        return msg(sender, "&7Jogadores: &f" + c.members.values().stream().map(m -> m.lastName).collect(Collectors.joining("&7, &f")));
    }

    private boolean invite(CommandSender sender, String[] args) {
        Player owner = player(sender); if (owner == null) return true;
        if (args.length < 2) return msg(owner, "&cUse: /fut time convidar <jogador>");
        Club c = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (c == null || !c.owner.equals(owner.getUniqueId())) return msg(owner, "&cSomente o dono pode convidar.");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) return msg(owner, "&cJogador precisa estar online.");
        if (clubs.byMember(target.getUniqueId()).isPresent()) return msg(owner, "&cEsse jogador já possui clube.");
        invites.put(target.getUniqueId(), new ClubInvite(c.key, Instant.now().getEpochSecond()));
        msg(target, "&eConvite para &f" + c.name + "&e. Use &f/fut time aceitar " + c.tag);
        return msg(owner, "&aConvite enviado.");
    }

    private boolean acceptInvite(CommandSender sender, String[] args) {
        Player p = player(sender); if (p == null) return true;
        if (args.length < 2) return msg(p, "&cUse: /fut time aceitar <TAG>");
        if (clubs.byMember(p.getUniqueId()).isPresent()) return msg(p, "&cVocê já possui clube.");
        Club c = clubs.byNameOrTag(args[1]).orElse(null); ClubInvite i = invites.get(p.getUniqueId());
        if (c == null || i == null || !i.clubKey.equals(c.key)) return msg(p, "&cConvite inválido.");
        clubs.addMember(c, p.getUniqueId(), p.getName(), "PLAYER"); invites.remove(p.getUniqueId());
        return msg(p, "&aVocê entrou no &f" + c.name + "&a.");
    }

    private boolean leaveClub(CommandSender sender) {
        Player p = player(sender); if (p == null) return true;
        Club c = clubs.byMember(p.getUniqueId()).orElse(null);
        if (c == null) return msg(p, "&cVocê não possui clube.");
        if (c.owner.equals(p.getUniqueId())) return msg(p, "&cO dono não pode sair. Um admin pode dissolver o clube.");
        clubs.removeMember(c, p.getUniqueId()); return msg(p, "&eVocê saiu de &f" + c.name + "&e.");
    }

    private boolean kickMember(CommandSender sender, String[] args) {
        Player p = player(sender); if (p == null) return true;
        if (args.length < 2) return msg(p, "&cUse: /fut time expulsar <jogador>");
        Club c = clubs.byMember(p.getUniqueId()).orElse(null);
        if (c == null || !c.owner.equals(p.getUniqueId())) return msg(p, "&cSomente o dono pode expulsar.");
        Member m = c.members.values().stream().filter(x -> x.lastName.equalsIgnoreCase(args[1])).findFirst().orElse(null);
        if (m == null || m.uuid.equals(c.owner)) return msg(p, "&cJogador inválido.");
        clubs.removeMember(c, m.uuid); return msg(p, "&aJogador removido.");
    }

    private boolean listClubs(CommandSender sender) {
        if (clubs.all().isEmpty()) return msg(sender, "&7Nenhum clube criado.");
        msg(sender, "&6Clubes:");
        clubs.all().stream().sorted(Comparator.comparing(c -> c.name)).forEach(c -> msg(sender, "&e["+c.tag+"] &f"+c.name+" &7("+c.members.size()+" jogadores)"));
        return true;
    }

    private boolean handleArena(CommandSender sender, String[] args) {
        if (!sender.hasPermission("brasafut.admin")) return msg(sender, "&cApenas administradores podem configurar arenas.");
        if (args.length == 0) return arenaHelp(sender);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("listar")) {
            if (arenas.all().isEmpty()) return msg(sender, "&7Nenhuma arena criada.");
            arenas.all().forEach(a -> msg(sender, "&e" + a.id + (arenas.isReady(a) ? " &a[PRONTA]" : " &c[INCOMPLETA]")));
            return true;
        }
        if (sub.equals("criar")) {
            if (args.length < 2) return msg(sender, "&cUse: /fut arena criar <id>");
            if (arenas.get(args[1]).isPresent()) return msg(sender, "&cEssa arena já existe.");
            arenas.create(args[1]); return msg(sender, "&aArena criada. Agora marque os pontos com /fut arena marcar " + args[1] + " <ponto>.");
        }
        if (sub.equals("remover")) {
            if (args.length < 2) return msg(sender, "&cUse: /fut arena remover <id>");
            return msg(sender, arenas.delete(args[1]) ? "&aArena removida." : "&cArena não encontrada.");
        }
        if (sub.equals("parar")) {
            if (args.length < 2) return msg(sender, "&cUse: /fut arena parar <id>");
            arenas.stop(args[1]); return msg(sender, "&ePartida encerrada nessa arena.");
        }
        if (sub.equals("marcar")) {
            Player p = player(sender); if (p == null) return true;
            if (args.length < 3) return msg(p, "&cUse: /fut arena marcar <id> <pos1|pos2|spawn|timeA|timeB|golA1|golA2|golB1|golB2>");
            boolean ok = arenas.setPoint(args[1], args[2], p.getLocation());
            return msg(p, ok ? "&aPonto &f" + args[2] + " &amarcado na sua posição." : "&cArena ou ponto inválido.");
        }
        if (sub.equals("info")) {
            if (args.length < 2) return msg(sender, "&cUse: /fut arena info <id>");
            ArenaEngine.Arena a = arenas.get(args[1]).orElse(null);
            if (a == null) return msg(sender, "&cArena não encontrada.");
            msg(sender, "&6Arena: &f" + a.id);
            return msg(sender, arenas.isReady(a) ? "&aArena pronta para partidas." : "&cArena incompleta. Use /fut arena marcar.");
        }
        return arenaHelp(sender);
    }

    private boolean handleChallenge(CommandSender sender, String[] args) {
        Player owner = player(sender); if (owner == null) return true;
        if (args.length < 1) return msg(owner, "&cUse: /fut desafiar <TAG> [arena]");
        Club home = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (home == null || !home.owner.equals(owner.getUniqueId())) return msg(owner, "&cSomente o dono pode desafiar.");
        Club away = clubs.byNameOrTag(args[0]).orElse(null);
        if (away == null || away.key.equals(home.key)) return msg(owner, "&cClube adversário inválido.");
        String arena = args.length >= 2 ? args[1] : getConfig().getString("matches.default-arena", "principal");
        ArenaEngine.Arena a = arenas.get(arena).orElse(null);
        if (a == null || !arenas.isReady(a)) return msg(owner, "&cArena inexistente ou incompleta: &f" + arena);
        challenges.put(away.key, new MatchChallenge(home.key, away.key, arena, Instant.now().getEpochSecond()));
        Player target = Bukkit.getPlayer(away.owner);
        if (target != null) msg(target, "&e" + home.name + " &fdesafiou seu clube na arena &e" + arena + "&f. Use &a/fut aceitar&f.");
        return msg(owner, "&aDesafio enviado para &f" + away.name + "&a.");
    }

    private boolean acceptChallenge(CommandSender sender) {
        Player owner = player(sender); if (owner == null) return true;
        Club away = clubs.byMember(owner.getUniqueId()).orElse(null);
        if (away == null || !away.owner.equals(owner.getUniqueId())) return msg(owner, "&cSomente o dono pode aceitar.");
        MatchChallenge ch = challenges.remove(away.key);
        if (ch == null) return msg(owner, "&cSem desafio pendente.");
        Club home = clubs.byKey(ch.homeKey).orElse(null); if (home == null) return msg(owner, "&cClube desafiante não existe.");
        List<Player> teamA = onlineMembers(home), teamB = onlineMembers(away);
        if (teamA.isEmpty() || teamB.isEmpty()) return msg(owner, "&cOs dois clubes precisam ter pelo menos 1 jogador online.");
        ArenaEngine.StartResult result = arenas.start(ch.arena, home.name, away.name, teamA, teamB);
        if (!result.success()) return msg(owner, "&c" + result.message());
        challenges.remove(away.key);
        return msg(owner, "&aPartida iniciada: &f" + home.name + " &eX &f" + away.name);
    }

    private boolean rejectChallenge(CommandSender sender) {
        Player p = player(sender); if (p == null) return true;
        Club c = clubs.byMember(p.getUniqueId()).orElse(null);
        if (c == null || !c.owner.equals(p.getUniqueId())) return msg(p, "&cSomente o dono pode recusar.");
        return msg(p, challenges.remove(c.key) != null ? "&eDesafio recusado." : "&cSem desafio pendente.");
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("brasafut.admin")) return msg(sender, "&cSem permissão.");
        if (args.length == 0) return msg(sender, "&e/fut admin recarregar | dissolver <TAG>");
        if (args[0].equalsIgnoreCase("recarregar")) { reloadConfig(); prefix = color(getConfig().getString("prefix", "&6&lBrasaFut &8» &r")); clubs.load(); arenas.load(); return msg(sender, "&aRecarregado."); }
        if (args[0].equalsIgnoreCase("dissolver") && args.length >= 2) { Club c = clubs.byNameOrTag(args[1]).orElse(null); if (c == null) return msg(sender,"&cClube não encontrado."); clubs.delete(c); return msg(sender,"&eClube dissolvido."); }
        return msg(sender, "&cComando admin inválido.");
    }

    private List<Player> onlineMembers(Club c) {
        List<Player> result = new ArrayList<>();
        for (UUID id : c.members.keySet()) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) result.add(p); }
        return result;
    }

    private boolean arenaHelp(CommandSender sender) {
        msg(sender, "&6&lEditor de Arena BrasaFut");
        msg(sender, "&f/fut arena criar <id>");
        msg(sender, "&f/fut arena marcar <id> pos1|pos2|spawn|timeA|timeB");
        msg(sender, "&f/fut arena marcar <id> golA1|golA2|golB1|golB2");
        msg(sender, "&f/fut arena info <id> | listar | remover <id> | parar <id>");
        return true;
    }

    private boolean help(CommandSender sender) {
        msg(sender, "&6&lBrasaFut &7- futebol completo em um único plugin");
        msg(sender, "&f/fut time ... &7- clubes");
        msg(sender, "&f/fut desafiar <TAG> [arena] &7- desafiar outro clube");
        msg(sender, "&f/fut aceitar &7- iniciar a partida");
        if (sender.hasPermission("brasafut.admin")) msg(sender, "&f/fut arena ... &7- editor de arenas (substitui /blockball)");
        return true;
    }

    private Player player(CommandSender s) { if (s instanceof Player p) return p; msg(s,"&cComando apenas para jogadores."); return null; }
    private boolean msg(CommandSender s, String t) { s.sendMessage(prefix + color(t)); return true; }
    private static String color(String t) { return ChatColor.translateAlternateColorCodes('&', t == null ? "" : t); }
    private static String normalize(String s) { String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""); return n.isBlank()?UUID.randomUUID().toString().replace("-",""):n; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(args[0], sender.hasPermission("brasafut.admin") ? List.of("time","arena","desafiar","aceitar","recusar","admin","ajuda") : List.of("time","desafiar","aceitar","recusar","ajuda"));
        if (args.length == 2 && args[0].equalsIgnoreCase("time")) return filter(args[1], List.of("criar","info","convidar","aceitar","sair","expulsar","listar"));
        if (args.length == 2 && args[0].equalsIgnoreCase("arena")) return filter(args[1], List.of("criar","marcar","info","listar","remover","parar"));
        if (args.length == 2 && args[0].equalsIgnoreCase("desafiar")) return filter(args[1], clubs.all().stream().map(c->c.tag).toList());
        if (args.length == 3 && args[0].equalsIgnoreCase("desafiar")) return filter(args[2], arenas.all().stream().map(a->a.id).toList());
        if (args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("marcar")) return filter(args[3], List.of("pos1","pos2","spawn","timeA","timeB","golA1","golA2","golB1","golB2"));
        return Collections.emptyList();
    }
    private List<String> filter(String q, List<String> vals) { String l=q.toLowerCase(Locale.ROOT); return vals.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(l)).sorted().toList(); }

    private record ClubInvite(String clubKey, long createdAt) {}
    private record MatchChallenge(String homeKey, String awayKey, String arena, long createdAt) {}
    private static final class Member { final UUID uuid; String lastName, role; Member(UUID u,String n,String r){uuid=u;lastName=n;role=r;} }
    private static final class Club {
        final String key; String name, tag, ownerName; UUID owner; int wins, draws, losses; final Map<UUID,Member> members=new LinkedHashMap<>();
        Club(String key){this.key=key;}
    }

    private static final class ClubService {
        private final BrasaFutPlugin plugin; private final File file; private final Map<String,Club> data=new LinkedHashMap<>();
        ClubService(BrasaFutPlugin p){plugin=p;file=new File(p.getDataFolder(),"clubs.yml");}
        void load(){ data.clear(); if(!file.exists())return; YamlConfiguration y=YamlConfiguration.loadConfiguration(file); ConfigurationSection root=y.getConfigurationSection("clubs"); if(root==null)return; for(String key:root.getKeys(false)){ try{ ConfigurationSection s=root.getConfigurationSection(key); if(s==null)continue; Club c=new Club(key); c.name=s.getString("name",key); c.tag=s.getString("tag",key.toUpperCase(Locale.ROOT)); c.owner=UUID.fromString(Objects.requireNonNull(s.getString("owner"))); c.ownerName=s.getString("owner-name","Jogador"); c.wins=s.getInt("stats.wins"); c.draws=s.getInt("stats.draws"); c.losses=s.getInt("stats.losses"); ConfigurationSection ms=s.getConfigurationSection("members"); if(ms!=null)for(String us:ms.getKeys(false)){UUID u=UUID.fromString(us); c.members.put(u,new Member(u,ms.getString(us+".name","Jogador"),ms.getString(us+".role","PLAYER")));} if(!c.members.containsKey(c.owner))c.members.put(c.owner,new Member(c.owner,c.ownerName,"OWNER")); data.put(c.key,c);}catch(Exception ex){plugin.getLogger().warning("Clube inválido: "+key+" - "+ex.getMessage());}} }
        Club create(String name,String tag,UUID owner,String ownerName){String base=normalize(tag),key=base;int i=2;while(data.containsKey(key))key=base+i++;Club c=new Club(key);c.name=name;c.tag=tag;c.owner=owner;c.ownerName=ownerName;c.members.put(owner,new Member(owner,ownerName,"OWNER"));data.put(key,c);save();return c;}
        void addMember(Club c,UUID u,String n,String r){c.members.put(u,new Member(u,n,r));save();} void removeMember(Club c,UUID u){c.members.remove(u);save();} void delete(Club c){data.remove(c.key);save();}
        Optional<Club> byMember(UUID u){return data.values().stream().filter(c->c.members.containsKey(u)).findFirst();} Optional<Club> byNameOrTag(String v){return data.values().stream().filter(c->c.tag.equalsIgnoreCase(v)||c.name.equalsIgnoreCase(v)||c.key.equalsIgnoreCase(normalize(v))).findFirst();} Optional<Club> byKey(String k){return Optional.ofNullable(data.get(k));} Collection<Club> all(){return data.values();}
        void save(){if(!plugin.getDataFolder().exists())plugin.getDataFolder().mkdirs();YamlConfiguration y=new YamlConfiguration();for(Club c:data.values()){String p="clubs."+c.key; y.set(p+".name",c.name);y.set(p+".tag",c.tag);y.set(p+".owner",c.owner.toString());y.set(p+".owner-name",c.ownerName);y.set(p+".stats.wins",c.wins);y.set(p+".stats.draws",c.draws);y.set(p+".stats.losses",c.losses);for(Member m:c.members.values()){String mp=p+".members."+m.uuid;y.set(mp+".name",m.lastName);y.set(mp+".role",m.role);}}try{y.save(file);}catch(IOException e){plugin.getLogger().severe("Erro salvando clubs.yml: "+e.getMessage());}}
    }
}
