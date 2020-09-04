package uk.co.andrewlee.cakebot.clients.lol;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import de.vandermeer.asciitable.AsciiTable;
import discord4j.common.util.Snowflake;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.VoiceChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.clients.channelregistration.ChannelRegistrar;
import uk.co.andrewlee.cakebot.clients.channelregistration.ChannelSpecificBotClient;
import uk.co.andrewlee.cakebot.clients.lol.matchmaking.Match;
import uk.co.andrewlee.cakebot.clients.lol.matchmaking.PlayerMatchmakingData.PlayerData;
import uk.co.andrewlee.cakebot.clients.lol.matchmaking.PlayerMatchmakingSystem;
import uk.co.andrewlee.cakebot.discord.BotSystem;
import uk.co.andrewlee.cakebot.discord.DiscordHelper;

public class LeagueOfLegendsBotClient extends ChannelSpecificBotClient {

  private static final Logger logger = LoggerFactory.getLogger(
      LeagueOfLegendsBotClient.class);
  private static final String CHANNEL_REGISTRATION_TAG = "lol";

  @GuardedBy("executor")
  private final PlayerMatchmakingSystem playerMatchmakingSystem;

  public static LeagueOfLegendsBotClient create(BotSystem botSystem,
      ChannelRegistrar channelRegistrar,
      Path saveDirectory) throws Exception {

    ExecutorService executor = Executors.newSingleThreadExecutor();
    PlayerMatchmakingSystem playerMatchmakingSystem = PlayerMatchmakingSystem.create(saveDirectory);

    Future<Boolean> initFuture = executor.submit(() -> {
      playerMatchmakingSystem.init();
      return true;
    });
    initFuture.get();

    return new LeagueOfLegendsBotClient(botSystem, executor, channelRegistrar,
        playerMatchmakingSystem);
  }

  private LeagueOfLegendsBotClient(BotSystem botSystem,
      ExecutorService executor,
      ChannelRegistrar channelRegistrar,
      PlayerMatchmakingSystem playerMatchmakingSystem) {
    super(botSystem, executor, channelRegistrar, CHANNEL_REGISTRATION_TAG);
    this.playerMatchmakingSystem = playerMatchmakingSystem;
  }

  @Override
  public void init() {
    registerMessageHandler("game", this::gameCommand);
    registerMessageHandler("channelgame", this::channelGameCommand);
    registerMessageHandler("register", this::registerPlayerCommand);
    registerMessageHandler("list", this::listPlayerCommand);
    super.init();
  }

  // TODO: Deduplicate code from here and AoEBotClient
  private void gameCommand(List<String> arguments, Message message) {
    HashSet<Long> players = new HashSet<>();
    for (String argument : arguments.subList(1, arguments.size())) {
      Optional<Long> playerIdOpt = DiscordHelper.extractUserId(argument);

      if (!playerIdOpt.isPresent()) {
        DiscordHelper.respond(message, String.format("Unknown player %s.", argument));
        return;
      }

      players.add(playerIdOpt.get());
    }

    findBalancedGame(ImmutableSet.copyOf(players), message);
  }

  private void channelGameCommand(List<String> arguments, Message message) {
    VoiceChannel voiceChannel = message.getAuthorAsMember().flatMap(Member::getVoiceState)
        .flatMap(VoiceState::getChannel).block();

    if (voiceChannel == null) {
      DiscordHelper.respond(message, "Not in voice channel.");
      return;
    }

    ImmutableList<Long> channelUsers = voiceChannel.getVoiceStates()
        .map(VoiceState::getUserId)
        .map(Snowflake::asLong)
        .collect(ImmutableList.toImmutableList())
        .block();

    HashSet<Long> players = new HashSet<>();
    players.addAll(channelUsers);

    for (String argument : arguments.subList(1, arguments.size())) {
      boolean addPlayer = true;
      String playerString = argument;

      if (argument.startsWith("+")) {
        playerString = argument.substring(1);
      } else if (argument.startsWith("-")) {
        addPlayer = false;
        playerString = argument.substring(1);
      }
      Optional<Long> playerIdOpt = DiscordHelper.extractUserId(playerString);

      if (!playerIdOpt.isPresent()) {
        DiscordHelper.respond(message, String.format("Unknown player %s.", playerString));
        return;
      }

      if (addPlayer) {
        players.add(playerIdOpt.get());
      } else if (argument.startsWith("-")) {
        players.remove(playerIdOpt.get());
      }
    }

    findBalancedGame(ImmutableSet.copyOf(players), message);
  }

  private void findBalancedGame(ImmutableSet<Long> playerIds, Message message) {
    for (long playerId : playerIds) {
      if (!playerMatchmakingSystem.hasPlayerData(playerId)) {
        DiscordHelper.respond(message, String.format("Player %s not registered. Please use the "
            + "register command.", DiscordHelper.mentionPlayer(playerId)));
        return;
      }
    }

    ImmutableList<Match> matchCandidates = playerMatchmakingSystem.findMatchCandidates(playerIds);

    // TODO: Print match candidates
  }

  private void registerPlayerCommand(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    if (arguments.size() != 7) {
      DiscordHelper
          .respond(message, String.format("Provide six arguments. Use `?` for unknown ratings."
                  + " Usage: %s register [user] [%s rating] [%s rating] [%s rating] [%s rating]"
                  + " [%s rating]",
              botSystem.selfNicknameMention(),
              Role.roleFromLaneId(0).name.toLowerCase(),
              Role.roleFromLaneId(1).name.toLowerCase(),
              Role.roleFromLaneId(2).name.toLowerCase(),
              Role.roleFromLaneId(3).name.toLowerCase(),
              Role.roleFromLaneId(4).name.toLowerCase()));
    }

    String playerMention = arguments.get(1);
    Optional<Long> playerIdOpt = DiscordHelper.extractUserId(playerMention);

    if (!playerIdOpt.isPresent()) {
      DiscordHelper.respond(message, String
          .format("Unknown player %s. Please mention the player, for example %s register %s",
              playerMention, botSystem.selfNicknameMention(), botSystem.selfNicknameMention()));
      return;
    }

    long playerId = playerIdOpt.get();

    int[] laneRatings = new int[5];
    for (int laneId = 0; laneId < 5; laneId++) {
      String stringRating = arguments.get(laneId + 2);
      Optional<Integer> ratingOpt = parseFloatIfPossible(stringRating)
          .map(floatRating -> Math.round(floatRating * 10))
          .map(intRating -> Math.min(Math.max(intRating, 0), 1000));
      laneRatings[laneId] = ratingOpt.orElse(Integer.MIN_VALUE);
    }

    try {
      playerMatchmakingSystem.updatePlayerData(playerId, laneRatings);
      DiscordHelper.respond(message, String.format("Registered user %s with lane scores: "
              + "%s, %s, %s, %s, %s",
          DiscordHelper.mentionPlayer(playerId),
          formatRating(laneRatings[0]),
          formatRating(laneRatings[1]),
          formatRating(laneRatings[2]),
          formatRating(laneRatings[3]),
          formatRating(laneRatings[4])));
    } catch (Exception e) {
      DiscordHelper.respond(message, "Error registering user. Please check server logs.");
    }
  }

  private void listPlayerCommand(List<String> arguments, Message message) {
    AsciiTable asciiTable = new AsciiTable();

    asciiTable.addRule();
    asciiTable.addRow("Player Name",
        Role.roleFromLaneId(0).name,
        Role.roleFromLaneId(1).name,
        Role.roleFromLaneId(2).name,
        Role.roleFromLaneId(3).name,
        Role.roleFromLaneId(4).name);
    asciiTable.addRule();

    HashMap<Long, PlayerData> allPlayerStats = playerMatchmakingSystem.getAllPlayerStats();
    ImmutableMap<Long, String> playerNames = allPlayerStats.keySet().stream()
        .collect(ImmutableMap.toImmutableMap(
            Function.identity(),
            playerId -> DiscordHelper.playerName(botSystem, playerId, message)));

    playerNames.entrySet().stream()
        .sorted(Entry.comparingByValue())
        .forEach(entry -> {
          PlayerData playerData = allPlayerStats.get(entry.getKey());
          asciiTable.addRow(entry.getValue(),
              formatRating(playerData.laneStrength[0]),
              formatRating(playerData.laneStrength[1]),
              formatRating(playerData.laneStrength[2]),
              formatRating(playerData.laneStrength[3]),
              formatRating(playerData.laneStrength[4]));
        });

    asciiTable.addRule();
    DiscordHelper.respond(message, "```" + asciiTable.render() + "```");
  }

  private Optional<Float> parseFloatIfPossible(String rating) {
    try {
      return Optional.of(Float.parseFloat(rating));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private String formatRating(int rating) {
    if (rating == Integer.MIN_VALUE) {
      return "?";
    }
    return String.format("%,.1f", rating / 10.0);
  }

  public static enum Role {
    TOP("Top", 0),
    JUNGLE("Jungle", 1),
    MID("Mid", 2),
    BOT("Bot", 3),
    SUPPORT("Support", 4);

    private static final Map<Integer, Role> BY_LANE_ID = new HashMap<>();

    static {
      for (Role role : values()) {
        BY_LANE_ID.put(role.laneId, role);
      }
    }

    private final String name;
    private final int laneId;

    Role(String name, int laneId) {
      this.name = name;
      this.laneId = laneId;
    }

    public static Role roleFromLaneId(int laneId) {
      return BY_LANE_ID.get(laneId);
    }
  }
}
