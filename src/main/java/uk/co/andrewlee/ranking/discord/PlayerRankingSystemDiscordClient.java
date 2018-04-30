package uk.co.andrewlee.ranking.discord;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import de.gesundkrank.jskills.GameInfo;
import de.gesundkrank.jskills.SkillCalculator;
import de.vandermeer.asciitable.AsciiTable;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.handle.obj.IMessage;
import sx.blah.discord.handle.obj.IUser;
import sx.blah.discord.handle.obj.IVoiceChannel;
import sx.blah.discord.handle.obj.IVoiceState;
import uk.co.andrewlee.discord.BotSystem;
import uk.co.andrewlee.discord.BotSystem.DiscordCommandHandler;
import uk.co.andrewlee.discord.DiscordHelper;
import uk.co.andrewlee.drafter.RandomCivDrafter;
import uk.co.andrewlee.drafter.RankedMapSelector;
import uk.co.andrewlee.ranking.Match;
import uk.co.andrewlee.ranking.MatchOutcome;
import uk.co.andrewlee.ranking.PlayerRankingData.PlayedWithStats;
import uk.co.andrewlee.ranking.PlayerRankingData.PlayerStats;
import uk.co.andrewlee.ranking.PlayerRankingSystem;
import uk.co.andrewlee.ranking.RankingOperation;
import uk.co.andrewlee.ranking.RankingOperation.CreatePlayerRankingOperation;
import uk.co.andrewlee.ranking.RankingOperation.MatchOutcomeRankingOperation;

@ThreadSafe
public class PlayerRankingSystemDiscordClient {

  private static final Logger logger = LoggerFactory.getLogger(
      PlayerRankingSystemDiscordClient.class);

  private final BotSystem botSystem;
  private final ExecutorService executor;
  private final RandomCivDrafter randomCivDrafter;
  private final RankedMapSelector rankedMapSelector;

  @GuardedBy("executor")
  private final PlayerRankingSystem playerRankingSystem;
  @GuardedBy("executor")
  private Optional<Match> lastMatch;

  public static PlayerRankingSystemDiscordClient create(BotSystem botSystem,
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

    return new PlayerRankingSystemDiscordClient(executor,
        playerRankingSystem, botSystem, randomCivDrafter, rankedMapSelector);
  }

  private PlayerRankingSystemDiscordClient(
      ExecutorService executor, PlayerRankingSystem playerRankingSystem,
      BotSystem botSystem, RandomCivDrafter randomCivDrafter, RankedMapSelector rankedMapSelector) {
    this.executor = executor;
    this.playerRankingSystem = playerRankingSystem;
    this.botSystem = botSystem;
    this.randomCivDrafter = randomCivDrafter;
    this.rankedMapSelector = rankedMapSelector;
    this.lastMatch = Optional.empty();
  }

  public void init() {
    registerHandler(botSystem, "game", this::gameCommand);
    registerHandler(botSystem, "channelgame", this::channelGameCommand);
    registerHandler(botSystem, "outcome", this::gameOutcomeCommand);
    registerHandler(botSystem, "team1", this::teamOutcomeCommand);
    registerHandler(botSystem, "team2", this::team2OutcomeCommand);

    registerHandler(botSystem, "register", this::registerPlayerCommand);
    registerHandler(botSystem, "undo", this::undoCommand);
    registerHandler(botSystem, "last", this::lastCommand);
    registerHandler(botSystem, "list", this::listPlayerCommand);
    registerHandler(botSystem, "stats", this::statCommand);
    registerHandler(botSystem, "draft", this::randomDraft);
    registerHandler(botSystem, "maps", this::listMaps);
  }

  private void gameCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    List<String> players = arguments.subList(1, arguments.size());
    try {
      ImmutableList<Long> playerIds = DiscordHelper.parseUserList(players);
      findBalancedGame(playerIds, message);
    } catch (IllegalArgumentException e) {
      message.reply(e.getMessage());
    }
  }

  private void channelGameCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    IVoiceState voiceState = message.getAuthor().getVoiceStateForGuild(message.getGuild());
    if (voiceState == null) {
      message.reply("Not in voice channel.");
      return;
    }

    IVoiceChannel channel = voiceState.getChannel();
    if (channel == null) {
      message.reply("Not in voice channel.");
      return;
    }

    ImmutableList<Long> channelUsers = channel.getConnectedUsers().stream().map(IUser::getLongID)
        .collect(ImmutableList.toImmutableList());

    List<String> extraPlayers = arguments.subList(1, arguments.size());
    try {
      ImmutableList<Long> otherPlayerIds = DiscordHelper.parseUserList(extraPlayers);
      findBalancedGame(ImmutableList.<Long>builder()
          .addAll(channelUsers)
          .addAll(otherPlayerIds)
          .build(), message);
    } catch (IllegalArgumentException e) {
      message.reply(e.getMessage());
    }
  }

  private void findBalancedGame(ImmutableList<Long> playerIds, IMessage message) {
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

    message.reply(outputBuilder.toString());

    lastMatch = Optional.of(match);
  }

  private void gameOutcomeCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    if (!isAdmin) {
      return;
    }

    int split = arguments.indexOf("beat");
    if (split == -1) {
      message.reply(
          String.format("Provide two teams. Usage: %s outcome [player1] [player2] beat [player3] " +
                  "[player4]   or   %s outcome team1 won",
              mentionBot(), mentionBot()));
      return;
    }

    try {
      ImmutableList<Long> winingUserIds = DiscordHelper.parseUserList(arguments.subList(1, split));
      ImmutableList<Long> losingUserIds = DiscordHelper.parseUserList(arguments.subList(split + 1,
          arguments.size()));

      if (winingUserIds.isEmpty()) {
        message.reply("Must be at least 1 winner.");
        return;
      }
      if (losingUserIds.isEmpty()) {
        message.reply("Must be at least 1 loser.");
        return;
      }

      recordMatchOutcome(MatchOutcome.createTeam1Won(new Match(winingUserIds, losingUserIds,
          Optional.empty())), message);
    } catch (IllegalArgumentException e) {
      message.reply(e.getMessage());
      return;
    }
  }

  private void teamOutcomeCommand(boolean isAdmin, List<String> arguments,
      IMessage message) {
    if (!isAdmin) {
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

  private void team2OutcomeCommand(boolean isAdmin, List<String> arguments,
      IMessage message) {
    if (!isAdmin) {
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

  private void registerTeam1Won(IMessage message) {
    if (!lastMatch.isPresent()) {
      message.reply("No previous match.");
      return;
    }
    recordMatchOutcome(MatchOutcome.createTeam1Won(lastMatch.get()), message);
  }

  private void registerTeam2Won(IMessage message) {
    if (!lastMatch.isPresent()) {
      message.reply("No previous match.");
      return;
    }
    recordMatchOutcome(MatchOutcome.createTeam2Won(lastMatch.get()), message);
  }

  private void recordMatchOutcome(MatchOutcome matchOutcome, IMessage message) {
    try {
      playerRankingSystem.recordMatchOutcome(matchOutcome);
      StringBuilder outputBuilder = new StringBuilder();
      outputBuilder.append("Match recorded. Player ratings have been adjusted.");
      outputBuilder.append("\n");
      outputBuilder.append("\n");
      printMatchOutcome(outputBuilder, matchOutcome);
      message.reply(outputBuilder.toString());
    } catch (Exception e) {
      logger.error("Error recording match outcome.", e);
      message.reply("Error recording match outcome. Please check server logs.");
    }
  }

  private void registerPlayerCommand(boolean isAdmin, List<String> arguments,
      IMessage message) {
    if (!isAdmin) {
      return;
    }

    if (arguments.size() != 3) {
      message.reply(String.format("Provide two arguments. Usage: %s register [user] [rating]",
          mentionBot()));
      return;
    }
    String playerMention = arguments.get(1);
    int initialRating = Integer.parseInt(arguments.get(2));

    Optional<Long> playerIdOpt = DiscordHelper.extractUserId(playerMention);
    if (!playerIdOpt.isPresent()) {
      message.reply(String
          .format("Unknown player %s. Please mention the player, for example %s register %s 25",
              playerMention, mentionBot(), mentionBot()));
      return;
    }

    long playerId = playerIdOpt.get();
    if (playerRankingSystem.hasPlayer(playerId)) {
      message.reply(String.format("User %s is already registered.", mentionPlayer(playerId)));
      return;
    }

    try {
      playerRankingSystem.createPlayerWithRanking(playerId, initialRating);
      message.reply(String.format("Registered user %s, with mean rating %s.",
          mentionPlayer(playerId), initialRating));
    } catch (Exception e) {
      logger.error("Error registering user.", e);
      message.reply("Error registering user. Please check server logs.");
    }
  }

  private void undoCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    if (!isAdmin) {
      return;
    }

    try {
      Optional<RankingOperation> rankingOperation = playerRankingSystem.undoLastRankingChange();

      if (!rankingOperation.isPresent()) {
        message.reply("No operations to undo.");
      } else {
        StringBuilder outputBuilder = new StringBuilder();
        outputBuilder.append("**Undo operation**");
        outputBuilder.append("\n");
        outputBuilder.append("\n");
        printRankingOperation(outputBuilder, rankingOperation.get());
        message.reply(outputBuilder.toString());
      }
    } catch (Exception e) {
      logger.error("Error undoing last ranking change.", e);
      message.reply("Error undoing last ranking change. Please check server logs.");
    }
  }

  private void lastCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    if (!isAdmin) {
      return;
    }

    Optional<RankingOperation> rankingOperation = playerRankingSystem.lastOperation();

    if (!rankingOperation.isPresent()) {
      message.reply("No previous operations.");
    } else {
      StringBuilder outputBuilder = new StringBuilder();
      outputBuilder.append("**Last operation**");
      outputBuilder.append("\n");
      outputBuilder.append("\n");
      printRankingOperation(outputBuilder, rankingOperation.get());
      message.reply(outputBuilder.toString());
    }
  }

  private void listPlayerCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    AsciiTable asciiTable = new AsciiTable();

    asciiTable.addRule();
    asciiTable.addRow("Player Name", "Rating", "Std. Dev", "Games Played", "Win Rate");
    asciiTable.addRule();

    playerRankingSystem.getAllPlayerStats().entrySet().stream()
        .sorted(Comparator.comparingDouble(entry -> -entry.getValue().getPlayerRating().getMean()))
        .forEach(entry -> {
          long playerId = entry.getKey();
          PlayerStats playerStats = entry.getValue();
          asciiTable.addRow(playerName(playerId, message),
              String.format("%,.1f", playerStats.getPlayerRating().getMean()),
              String.format("%,.1f", playerStats.getPlayerRating().getStandardDeviation()),
              String.format("%d", playerStats.totalGamesPlayed()),
              String.format("%,.1f%%", playerStats.winRate() * 100));
        });

    asciiTable.addRule();
    message.reply("```" + asciiTable.render() + "```");
  }

  private void randomDraft(boolean isAdmin, List<String> arguments, IMessage message) {
    int numberOfPlayers = 12;
    if (arguments.size() >= 2) {
      numberOfPlayers = Integer.parseInt(arguments.get(1));
    }

    ImmutableList<String> randomCivs = randomCivDrafter.randomDraft(numberOfPlayers);
    message.reply("**Here are your randomly chosen civs:**\n\n" + randomCivs.stream().collect(
        Collectors.joining(", ")));
  }

  private void listMaps(boolean isAdmin, List<String> arguments, IMessage message) {
    ImmutableList<String> maps = rankedMapSelector.allRankedMaps();

    message.reply("The maps in the current ranked pool are:\n" + maps.stream()
            .collect(Collectors.joining(", ")));

  }

  private void statCommand(boolean isAdmin, List<String> arguments, IMessage message) {
    if (arguments.size() > 2) {
      return;
    }

    if (arguments.size() == 1) {
      postStats(message.getAuthor().getLongID(), message);
      return;
    }

    Optional<Long> userIdOpt = DiscordHelper.extractUserId(arguments.get(1));
    if (!userIdOpt.isPresent()) {
      message.reply(String.format("Unknown user %s.", arguments.get(1)));
      return;
    }

    long userId = userIdOpt.get();
    postStats(userId, message);
  }

  private void postStats(long userId, IMessage message) {
    Optional<PlayerStats> playerStatsOpt = playerRankingSystem.getPlayerStats(userId);
    if (!playerStatsOpt.isPresent()) {
      message.reply(String.format("No stats for user %s.", mentionPlayer(userId)));
      return;
    }

    PlayerStats playerStats = playerStatsOpt.get();

    StringBuilder outputBuilder = new StringBuilder();
    outputBuilder.append(String.format("**Stats for %s**", mentionPlayer(userId)));
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
              mentionPlayer(playedWithStats.getOtherPlayerId()), playedWithStats.getGamesWon()));
          outputBuilder.append("\n");
        });

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesLost))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games lost with: ");
          outputBuilder.append(String.format("%s (%d)",
              mentionPlayer(playedWithStats.getOtherPlayerId()), playedWithStats.getGamesLost()));
          outputBuilder.append("\n");
        });

    outputBuilder.append("\n");

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesWonAgainst))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games won against: ");
          outputBuilder.append(String.format("%s (%d)",
              mentionPlayer(playedWithStats.getOtherPlayerId()),
              playedWithStats.getGamesWonAgainst()));
          outputBuilder.append("\n");
        });

    playerStats.getPlayedWithStats().values().stream()
        .max(Comparator.comparingInt(PlayedWithStats::getGamesLostAgainst))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Most games lost against: ");
          outputBuilder.append(String.format("%s (%d)",
              mentionPlayer(playedWithStats.getOtherPlayerId()),
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
              mentionPlayer(playedWithStats.getOtherPlayerId()),
              playedWithStats.winRate() * 100));
          outputBuilder.append("\n");
        });

    playerStats.getPlayedWithStats().values().stream()
        .filter(stats -> stats.totalGamesPlayed() > 0)
        .min(Comparator.comparingDouble(PlayedWithStats::winRate))
        .ifPresent(playedWithStats -> {
          outputBuilder.append("Lowest win rate with: ");
          outputBuilder.append(String.format("%s (%,.1f%%)",
              mentionPlayer(playedWithStats.getOtherPlayerId()),
              playedWithStats.winRate() * 100));
          outputBuilder.append("\n");
        });

    message.reply(outputBuilder.toString());
  }

  private String mentionBot() {
    return botSystem.getDiscordClient().getOurUser().mention();
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
      stringBuilder.append(" with rating ");
      stringBuilder.append(createPlayerRankingOperation.getMeanRating());
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
    stringBuilder.append(mentionListOfPLayers(matchOutcome.getWinningPlayers()));
    stringBuilder.append("\n");
    stringBuilder.append("Losers: ");
    stringBuilder.append(mentionListOfPLayers(matchOutcome.getLosingPlayers()));
  }

  private void printMatch(StringBuilder stringBuilder, Match match) {
    stringBuilder.append("Team 1: ");
    stringBuilder.append(mentionListOfPLayers(match.getTeam1()));
    stringBuilder.append("\n");
    stringBuilder.append("Team 2: ");
    stringBuilder.append(mentionListOfPLayers(match.getTeam2()));
    if (match.getMatchQuality().isPresent()) {
      stringBuilder.append("\n");
      stringBuilder.append("\n");
      stringBuilder.append("Match Quality: ");
      stringBuilder.append(String.format("%,.1f%%", match.getMatchQuality().get() * 100));
    }
  }

  private String mentionListOfPLayers(Collection<Long> playerIds) {
    return playerIds.stream().map(this::mentionPlayer).collect(Collectors.joining(", "));
  }

  private String mentionPlayer(long playerId) {
    IUser user = botSystem.getDiscordClient().getUserByID(playerId);
    if (user == null) {
      return String.format("UnknownPlayer-%s", playerId);
    }
    return user.mention(true);
  }

  private String playerName(long playerId, IMessage message) {
    return botSystem.getDiscordClient().getUserByID(playerId).getDisplayName(message.getGuild());
  }

  private void registerHandler(BotSystem botSystem, String commandString,
      DiscordCommandHandler discordCommandHandler) {
    botSystem
        .registerHandler(commandString, (isAdmin, arguments, message) -> executor.execute(() -> {
          try {
            discordCommandHandler.handle(isAdmin, arguments, message);
          } catch (Exception e) {
            logger.error("Error while handling command.", e);
            message.reply("Error while processing command. Please see server logs.");
          }
        }));
  }
}
