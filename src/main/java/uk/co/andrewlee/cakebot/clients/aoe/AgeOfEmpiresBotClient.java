package uk.co.andrewlee.cakebot.clients.aoe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import de.gesundkrank.jskills.GameInfo;
import de.gesundkrank.jskills.SkillCalculator;
import de.gesundkrank.jskills.trueskill.FactorGraphTrueSkillCalculator;
import de.vandermeer.asciitable.AsciiTable;
import discord4j.common.util.Snowflake;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.VoiceChannel;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.discord.ChannelSpecificBotClient;
import uk.co.andrewlee.cakebot.discord.BotSystem;
import uk.co.andrewlee.cakebot.discord.DiscordHelper;
import uk.co.andrewlee.cakebot.clients.aoe.drafter.RandomCivDrafter;
import uk.co.andrewlee.cakebot.clients.aoe.drafter.RankedMapSelector;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.Match;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.MatchOutcome;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.PlayerRankingData.PlayedWithStats;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.PlayerRankingData.PlayerStats;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.PlayerRankingSystem;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.RankingOperation;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.RankingOperation.CreatePlayerRankingOperation;
import uk.co.andrewlee.cakebot.clients.aoe.ranking.RankingOperation.MatchOutcomeRankingOperation;

@ThreadSafe
public class AgeOfEmpiresBotClient extends ChannelSpecificBotClient {

  private static final Logger logger = LoggerFactory.getLogger(
      AgeOfEmpiresBotClient.class);

  private final RandomCivDrafter randomCivDrafter;
  private final RankedMapSelector rankedMapSelector;

  // TODO: Add flags?
  private final static boolean HIDE_RATING = true;

  @GuardedBy("executor")
  private final PlayerRankingSystem playerRankingSystem;
  @GuardedBy("executor")
  private Optional<Match> lastMatch;

  public static AgeOfEmpiresBotClient create(BotSystem botSystem, int maxOperationHistory,
      Path saveDirectory) throws Exception {
    return AgeOfEmpiresBotClient.create(botSystem, new FactorGraphTrueSkillCalculator(),
        GameInfo.getDefaultGameInfo(), maxOperationHistory, saveDirectory);
  }

  public static AgeOfEmpiresBotClient create(BotSystem botSystem,
      SkillCalculator skillCalculator, GameInfo gameInfo, int maxOperationHistory,
      Path saveDirectory) throws Exception {

    ExecutorService executor = Executors.newSingleThreadExecutor();
    PlayerRankingSystem playerRankingSystem = PlayerRankingSystem
        .create(skillCalculator, gameInfo, maxOperationHistory, saveDirectory);

    Future<Boolean> initFuture = executor.submit(() -> {
      playerRankingSystem.init();
      return true;
    });
    initFuture.get();

    RandomCivDrafter randomCivDrafter = RandomCivDrafter.create();
    RankedMapSelector rankedMapSelector = RankedMapSelector.create();

    return new AgeOfEmpiresBotClient(botSystem, executor, playerRankingSystem,
        randomCivDrafter, rankedMapSelector);
  }

  private AgeOfEmpiresBotClient(BotSystem botSystem, ExecutorService executor,
      PlayerRankingSystem playerRankingSystem, RandomCivDrafter randomCivDrafter,
      RankedMapSelector rankedMapSelector) {
    super(botSystem, executor);
    this.playerRankingSystem = playerRankingSystem;
    this.randomCivDrafter = randomCivDrafter;
    this.rankedMapSelector = rankedMapSelector;
    this.lastMatch = Optional.empty();
  }

  public void init() {
    registerMessageHandler("game", this::gameCommand);
    registerMessageHandler("channelgame", this::channelGameCommand);
    registerMessageHandler("outcome", this::gameOutcomeCommand);
    registerMessageHandler("team1", this::teamOutcomeCommand);
    registerMessageHandler("team2", this::team2OutcomeCommand);

    registerMessageHandler("register", this::registerPlayerCommand);
    registerMessageHandler("undo", this::undoCommand);
    registerMessageHandler("last", this::lastCommand);
    registerMessageHandler("list", this::listPlayerCommand);
    registerMessageHandler("stats", this::statCommand);
    registerMessageHandler("draft", this::randomDraft);
    registerMessageHandler("maps", this::listMaps);
    // TODO: Register Channels
    super.init();
  }

  private void gameCommand(List<String> arguments, Message message) {
    List<String> players = arguments.subList(1, arguments.size());
    try {
      ImmutableList<Long> playerIds = DiscordHelper.parseUserList(players);
      findBalancedGame(playerIds, message);
    } catch (IllegalArgumentException e) {
      DiscordHelper.respond(message, e.getMessage());
    }
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

    List<String> extraPlayers = arguments.subList(1, arguments.size());
    try {
      ImmutableList<Long> otherPlayerIds = DiscordHelper.parseUserList(extraPlayers);
      findBalancedGame(ImmutableList.<Long>builder()
          .addAll(channelUsers)
          .addAll(otherPlayerIds)
          .build(), message);
    } catch (IllegalArgumentException e) {
      DiscordHelper.respond(message, e.getMessage());
    }
  }

  private void findBalancedGame(ImmutableList<Long> playerIds, Message message) {
    Match match = playerRankingSystem.findBalancedMatch(ImmutableSet.copyOf(playerIds));

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder.append("**Recommended Teams**");
    outputBuilder.append("\n");
    outputBuilder.append("\n");
    printMatch(outputBuilder, match);
    outputBuilder.append("\n");
    outputBuilder.append("\n");
    outputBuilder.append(new Random().nextBoolean() ? "Team 1 picks first." :
        "Team 2 picks first.");
    outputBuilder.append("\n");
    outputBuilder.append("Map: ");
    outputBuilder.append(rankedMapSelector.randomMap());

    DiscordHelper.respond(message, outputBuilder.toString());

    lastMatch = Optional.of(match);
  }

  private void gameOutcomeCommand(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    int split = arguments.indexOf("beat");
    if (split == -1) {
      DiscordHelper.respond(message,
          String.format("Provide two teams. Usage: %s outcome [player1] [player2] beat [player3] " +
                  "[player4]   or   %s outcome team1 won",
              botSystem.selfMention(), botSystem.selfMention()));
      return;
    }

    try {
      ImmutableList<Long> winingUserIds = DiscordHelper.parseUserList(arguments.subList(1, split));
      ImmutableList<Long> losingUserIds = DiscordHelper.parseUserList(arguments.subList(split + 1,
          arguments.size()));

      if (winingUserIds.isEmpty()) {
        DiscordHelper.respond(message, "Must be at least 1 winner.");
        return;
      }
      if (losingUserIds.isEmpty()) {
        DiscordHelper.respond(message, "Must be at least 1 loser.");
        return;
      }

      recordMatchOutcome(MatchOutcome.createTeam1Won(new Match(winingUserIds, losingUserIds,
          Optional.empty())), message);
    } catch (IllegalArgumentException e) {
      DiscordHelper.respond(message, e.getMessage());
    }
  }

  private void teamOutcomeCommand(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    if (arguments.size() != 2) {
      return;
    }

    boolean team1 = arguments.get(0).equals("team1");
    boolean team2 = arguments.get(0).equals("team2");
    boolean won = arguments.get(1).equals("won");
    boolean lost = arguments.get(1).equals("lost");

    if (team1) {
      if (won) {
        registerTeam1Won(message);
      } else if (lost) {
        registerTeam2Won(message);
      }
    } else if (team2) {
      if (won) {
        registerTeam2Won(message);
      } else if (lost) {
        registerTeam1Won(message);
      }
    }
  }

  private void team2OutcomeCommand(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    if (arguments.size() != 2) {
      return;
    }

    if (arguments.get(1).equals("won")) {
      registerTeam2Won(message);
    }

    if (arguments.get(1).equals("lost")) {
      registerTeam1Won(message);
    }
  }

  private void registerTeam1Won(Message message) {
    if (!lastMatch.isPresent()) {
      DiscordHelper.respond(message, "No previous match.");
      return;
    }
    recordMatchOutcome(MatchOutcome.createTeam1Won(lastMatch.get()), message);
  }

  private void registerTeam2Won(Message message) {
    if (!lastMatch.isPresent()) {
      DiscordHelper.respond(message, "No previous match.");
      return;
    }
    recordMatchOutcome(MatchOutcome.createTeam2Won(lastMatch.get()), message);
  }

  private void recordMatchOutcome(MatchOutcome matchOutcome, Message message) {
    try {
      playerRankingSystem.recordMatchOutcome(matchOutcome);
      StringBuilder outputBuilder = new StringBuilder();
      outputBuilder.append("Match recorded. Player ratings have been adjusted.");
      outputBuilder.append("\n");
      outputBuilder.append("\n");
      printMatchOutcome(outputBuilder, matchOutcome);
      DiscordHelper.respond(message, outputBuilder.toString());
    } catch (Exception e) {
      logger.error("Error recording match outcome.", e);
      DiscordHelper.respond(message, "Error recording match outcome. Please check server logs.");
    }
  }

  private void registerPlayerCommand(List<String> arguments,
      Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    if (arguments.size() != 3 && arguments.size() != 2) {
      if (HIDE_RATING) {
        DiscordHelper
            .respond(message, String.format("Provide one arguments. Usage: %s register [user]",
                botSystem.selfMention()));
      } else {
        DiscordHelper.respond(message,
            String.format("Provide two arguments. Usage: %s register [user] [rating]",
                botSystem.selfMention()));
      }
      return;
    }

    String playerMention = arguments.get(1);
    Optional<Integer> initialRating = Optional.empty();

    if (arguments.size() == 3) {
      initialRating = Optional.of(Integer.parseInt(arguments.get(2)));
    }

    Optional<Long> playerIdOpt = DiscordHelper.extractUserId(playerMention);
    if (!playerIdOpt.isPresent()) {
      DiscordHelper.respond(message, String
          .format("Unknown player %s. Please mention the player, for example %s register %s 25",
              playerMention, botSystem.selfMention(), botSystem.selfMention()));
      return;
    }

    long playerId = playerIdOpt.get();
    if (playerRankingSystem.hasPlayer(playerId)) {
      DiscordHelper.respond(message, String.format("User %s is already registered.",
          DiscordHelper.mentionPlayer(botSystem, playerId)));
      return;
    }

    try {
      if (initialRating.isPresent()) {
        playerRankingSystem.createPlayerWithRating(playerId, initialRating.get());
      } else {
        playerRankingSystem.createPlayerWithDefaultRating(playerId);
      }

      if (HIDE_RATING || !initialRating.isPresent()) {
        DiscordHelper.respond(message,
            String.format("Registered user %s.", DiscordHelper.mentionPlayer(botSystem, playerId)));
      } else {
        DiscordHelper.respond(message, String.format("Registered user %s, with mean rating %s.",
            DiscordHelper.mentionPlayer(botSystem, playerId), initialRating.get()));
      }
    } catch (Exception e) {
      logger.error("Error registering user.", e);
      DiscordHelper.respond(message, "Error registering user. Please check server logs.");
    }
  }

  private void undoCommand(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    try {
      Optional<RankingOperation> rankingOperation = playerRankingSystem.undoLastRankingChange();

      if (!rankingOperation.isPresent()) {
        DiscordHelper.respond(message, "No operations to undo.");
      } else {
        StringBuilder outputBuilder = new StringBuilder();
        outputBuilder.append("**Undo operation**");
        outputBuilder.append("\n");
        outputBuilder.append("\n");
        printRankingOperation(outputBuilder, rankingOperation.get());
        DiscordHelper.respond(message, outputBuilder.toString());
      }
    } catch (Exception e) {
      logger.error("Error undoing last ranking change.", e);
      DiscordHelper
          .respond(message, "Error undoing last ranking change. Please check server logs.");
    }
  }

  private void lastCommand(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    Optional<RankingOperation> rankingOperation = playerRankingSystem.lastOperation();

    if (!rankingOperation.isPresent()) {
      DiscordHelper.respond(message, "No previous operations.");
    } else {
      StringBuilder outputBuilder = new StringBuilder();
      outputBuilder.append("**Last operation**");
      outputBuilder.append("\n");
      outputBuilder.append("\n");
      printRankingOperation(outputBuilder, rankingOperation.get());
      DiscordHelper.respond(message, outputBuilder.toString());
    }
  }

  private void listPlayerCommand(List<String> arguments, Message message) {
    AsciiTable asciiTable = new AsciiTable();

    if (HIDE_RATING) {
      asciiTable.addRule();
      asciiTable.addRow("Player Name", "Games Played", "Win Rate");
      asciiTable.addRule();

      playerRankingSystem.getAllPlayerStats().entrySet().stream()
          .sorted(
              Comparator.comparingDouble(entry -> -entry.getValue().winRate()))
          .forEach(entry -> {
            long playerId = entry.getKey();
            PlayerStats playerStats = entry.getValue();
            asciiTable.addRow(DiscordHelper.playerName(botSystem, playerId, message),
                String.format("%d", playerStats.totalGamesPlayed()),
                String.format("%,.1f%%", playerStats.winRate() * 100));
          });

      asciiTable.addRule();
    } else {
      asciiTable.addRule();
      asciiTable.addRow("Player Name", "Rating", "Std. Dev", "Games Played", "Win Rate");
      asciiTable.addRule();

      playerRankingSystem.getAllPlayerStats().entrySet().stream()
          .sorted(
              Comparator.comparingDouble(entry -> -entry.getValue().getPlayerRating().getMean()))
          .forEach(entry -> {
            long playerId = entry.getKey();
            PlayerStats playerStats = entry.getValue();
            asciiTable.addRow(DiscordHelper.playerName(botSystem, playerId, message),
                String.format("%,.1f", playerStats.getPlayerRating().getMean()),
                String.format("%,.1f", playerStats.getPlayerRating().getStandardDeviation()),
                String.format("%d", playerStats.totalGamesPlayed()),
                String.format("%,.1f%%", playerStats.winRate() * 100));
          });

      asciiTable.addRule();
      DiscordHelper.respond(message, "```" + asciiTable.render() + "```");
    }
    DiscordHelper.respond(message, "```" + asciiTable.render() + "```");
  }

  private void randomDraft(List<String> arguments, Message message) {
    int numberOfPlayers = 12;
    if (arguments.size() >= 2) {
      numberOfPlayers = Integer.parseInt(arguments.get(1));
    }

    ImmutableList<String> randomCivs = randomCivDrafter.randomDraft(numberOfPlayers);
    DiscordHelper.respond(message,
        "**Here are your randomly chosen civs:**\n\n" + String.join(", ", randomCivs));
  }

  private void listMaps(List<String> arguments, Message message) {
    ImmutableList<String> maps = rankedMapSelector.allRankedMaps();

    DiscordHelper
        .respond(message, "The maps in the current ranked pool are:\n" + String.join(", ", maps));

  }

  private void statCommand(List<String> arguments, Message message) {
    if (arguments.size() > 2) {
      return;
    }

    if (arguments.size() == 1) {
      if (message.getAuthor().isPresent()) {
        DiscordHelper.respond(message, "Could not get message author.");
        return;
      }
      postStats(message.getAuthor().get().getId().asLong(), message);
      return;
    }

    Optional<Long> userIdOpt = DiscordHelper.extractUserId(arguments.get(1));
    if (!userIdOpt.isPresent()) {
      DiscordHelper.respond(message, String.format("Unknown user %s.", arguments.get(1)));
      return;
    }

    long userId = userIdOpt.get();
    postStats(userId, message);
  }

  private void postStats(long userId, Message message) {
    Optional<PlayerStats> playerStatsOpt = playerRankingSystem.getPlayerStats(userId);
    if (!playerStatsOpt.isPresent()) {
      DiscordHelper.respond(message,
          String.format("No stats for user %s.", DiscordHelper.mentionPlayer(botSystem, userId)));
      return;
    }

    PlayerStats playerStats = playerStatsOpt.get();

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder
        .append(String.format("**Stats for %s**", DiscordHelper.mentionPlayer(botSystem, userId)));
    outputBuilder.append("\n");
    outputBuilder.append("\n");

    outputBuilder.append("Total games played: ");
    outputBuilder.append(playerStats.totalGamesPlayed());
    outputBuilder.append("\n");

    outputBuilder.append("Win Rate: ");
    outputBuilder.append(String.format("%,.1f%%", playerStats.winRate() * 100));
    outputBuilder.append("\n");
    outputBuilder.append("\n");

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesWon))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games won with: ");
          outputBuilder.append(String.format("%s (%d)",
              DiscordHelper.mentionPlayer(botSystem, playedWithStats.getOtherPlayerId()),
              playedWithStats.getGamesWon()));
          outputBuilder.append("\n");
        });

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesLost))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games lost with: ");
          outputBuilder.append(String.format("%s (%d)",
              DiscordHelper.mentionPlayer(botSystem, playedWithStats.getOtherPlayerId()),
              playedWithStats.getGamesLost()));
          outputBuilder.append("\n");
        });

    outputBuilder.append("\n");

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesWonAgainst))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games won against: ");
          outputBuilder.append(String.format("%s (%d)",
              DiscordHelper.mentionPlayer(botSystem, playedWithStats.getOtherPlayerId()),
              playedWithStats.getGamesWonAgainst()));
          outputBuilder.append("\n");
        });

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesLostAgainst))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games lost against: ");
          outputBuilder.append(String.format("%s (%d)",
              DiscordHelper.mentionPlayer(botSystem, playedWithStats.getOtherPlayerId()),
              playedWithStats.getGamesLostAgainst()));
          outputBuilder.append("\n");
        });

    outputBuilder.append("\n");

    playerStats.getPlayedWithStats().values().stream()
        .filter(stats -> stats.totalGamesPlayed() > 0)
        .max(Comparator.comparingDouble(PlayedWithStats::winRate))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Highest win rate with: ");
          outputBuilder.append(String.format("%s (%,.1f%%)",
              DiscordHelper.mentionPlayer(botSystem, playedWithStats.getOtherPlayerId()),
              playedWithStats.winRate() * 100));
          outputBuilder.append("\n");
        });

    playerStats.getPlayedWithStats().values().stream()
        .filter(stats -> stats.totalGamesPlayed() > 0)
        .min(Comparator.comparingDouble(PlayedWithStats::winRate))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Lowest win rate with: ");
          outputBuilder.append(String.format("%s (%,.1f%%)",
              DiscordHelper.mentionPlayer(botSystem, playedWithStats.getOtherPlayerId()),
              playedWithStats.winRate() * 100));
          outputBuilder.append("\n");
        });

    DiscordHelper.respond(message, outputBuilder.toString());
  }

  private void printRankingOperation(StringBuilder stringBuilder,
      RankingOperation rankingOperation) {
    if (rankingOperation instanceof CreatePlayerRankingOperation) {
      CreatePlayerRankingOperation createPlayerRankingOperation =
          (CreatePlayerRankingOperation) rankingOperation;
      stringBuilder.append("Player registration:");
      stringBuilder.append("\n");
      stringBuilder.append("\n");
      stringBuilder.append(createPlayerRankingOperation.getPlayerId());

      if (HIDE_RATING) {
        stringBuilder.append(" with rating ");
        stringBuilder.append(createPlayerRankingOperation.getMeanRating());
      }

      stringBuilder.append(".");
    } else if (rankingOperation instanceof MatchOutcomeRankingOperation) {
      MatchOutcomeRankingOperation matchOutcomeRankingOperation =
          (MatchOutcomeRankingOperation) rankingOperation;
      stringBuilder.append("Match outcome:");
      stringBuilder.append("\n");
      stringBuilder.append("\n");
      printMatchOutcome(stringBuilder, matchOutcomeRankingOperation.getMatchOutcome());
    } else {
      logger.error(String.format("Unknown ranking operation, %s.", rankingOperation.getClass()));
      stringBuilder.append("Unknown operation. Please check server logs.");
    }
  }

  private void printMatchOutcome(StringBuilder stringBuilder, MatchOutcome matchOutcome) {
    stringBuilder.append("Winners: ");
    stringBuilder
        .append(DiscordHelper.mentionListOfPLayers(botSystem, matchOutcome.getWinningPlayers()));
    stringBuilder.append("\n");
    stringBuilder.append("Losers: ");
    stringBuilder
        .append(DiscordHelper.mentionListOfPLayers(botSystem, matchOutcome.getLosingPlayers()));
  }

  private void printMatch(StringBuilder stringBuilder, Match match) {
    stringBuilder.append("Team 1: ");
    stringBuilder.append(DiscordHelper.mentionListOfPLayers(botSystem, match.getTeam1()));
    stringBuilder.append("\n");
    stringBuilder.append("Team 2: ");
    stringBuilder.append(DiscordHelper.mentionListOfPLayers(botSystem, match.getTeam2()));
    if (match.getMatchQuality().isPresent()) {
      stringBuilder.append("\n");
      stringBuilder.append("\n");
      stringBuilder.append("Match Quality: ");
      stringBuilder.append(String.format("%,.1f%%", match.getMatchQuality().get() * 100));
    }
  }
}
