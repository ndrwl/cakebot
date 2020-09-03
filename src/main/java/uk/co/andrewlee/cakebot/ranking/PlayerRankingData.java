package uk.co.andrewlee.cakebot.ranking;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import de.gesundkrank.jskills.GameInfo;
import de.gesundkrank.jskills.IPlayer;
import de.gesundkrank.jskills.Player;
import de.gesundkrank.jskills.Rating;
import de.gesundkrank.jskills.SkillCalculator;
import de.gesundkrank.jskills.Team;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.annotation.concurrent.NotThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.ranking.serializers.MatchOutcomeSerializer;
import uk.co.andrewlee.cakebot.ranking.serializers.RatingSerializer;

@NotThreadSafe
public class PlayerRankingData {

  private static final Logger logger = LoggerFactory.getLogger(PlayerRankingData.class);
  private static final Gson GSON = new GsonBuilder()
      .registerTypeAdapter(Rating.class, new RatingSerializer())
      .registerTypeAdapter(MatchOutcome.class, new MatchOutcomeSerializer())
      .create();

  private final HashMap<Long, PlayerStats> playerStats;
  // TODO: Perhaps we shouldn't store all of the matches in memory...
  private final List<MatchOutcome> matchHistory;

  private final SkillCalculator skillCalculator;
  private final GameInfo gameInfo;

  PlayerRankingData(SkillCalculator skillCalculator, GameInfo gameInfo) {
    this.skillCalculator = skillCalculator;
    this.gameInfo = gameInfo;
    this.matchHistory = new ArrayList<>();
    this.playerStats = new HashMap<>();
  }

  public boolean hasPlayer(long playerId) {
    return playerStats.containsKey(playerId);
  }

  public Optional<PlayerStats> getPlayerStats(long playerId) {
    return Optional.ofNullable(playerStats.get(playerId));
  }

  public HashMap<Long, PlayerStats> getAllPlayerStats() {
    return playerStats;
  }

  public void createPlayerWithRating(long playerId, double meanRating) {
    Preconditions.checkState(!hasPlayer(playerId));

    getOrCreatePlayerStats(playerId).playerRating = new Rating(meanRating,
        gameInfo.getInitialStandardDeviation());
  }

  public void recordMatchOutcome(MatchOutcome matchOutcome) {
    logger.info("Processing match rating changes.");
    recordMatchRatingChanges(matchOutcome);
    logger.info("Match ratings changes processed.");
    logger.info("Processing match history changes.");
    recordMatchHistoryChanges(matchOutcome);
    logger.info("Match history changes processed.");
  }

  public Rating getPlayerRatingOrDefault(long playerId) {
    return Optional.ofNullable(playerStats.get(playerId))
        .map(PlayerStats::getPlayerRating)
        .orElse(gameInfo.getDefaultRating());
  }

  public void clear() {
    playerStats.clear();
    matchHistory.clear();
  }

  public void save(Writer writer) throws JsonParseException {
    ImmutableMap<Long, Rating> playerRatings = playerStats.entrySet().stream()
        .collect(ImmutableMap.toImmutableMap(Entry::getKey, entry ->
            entry.getValue().playerRating));

    SerializedData serializedData = new SerializedData(playerRatings, matchHistory);
    GSON.toJson(serializedData, writer);
    logger.info("Saved {} player ratings.", serializedData.ratings.size());
    logger.info("Saved {} match histories.", serializedData.matchOutcomes.size());
  }

  public void load(Reader reader) throws JsonParseException {
    clear();

    SerializedData loadedData = GSON.fromJson(reader, SerializedData.class);
    loadedData.ratings.forEach((playerId, rating) -> playerStats.put(playerId,
        new PlayerStats(playerId, rating)));
    logger.info("Loaded {} player ratings.", playerStats.size());
    loadedData.matchOutcomes.forEach(this::recordMatchHistoryChanges);
    logger.info("Loaded {} match histories.", matchHistory.size());
  }

  private void recordMatchRatingChanges(MatchOutcome matchOutcome) {
    Team winningTeam = new Team();
    Team losingTeam = new Team();

    matchOutcome.getWinningPlayers()
        .forEach(playerId -> winningTeam.addPlayer(new Player<Long>(playerId),
            getPlayerRatingOrDefault(playerId)));
    matchOutcome.getLosingPlayers()
        .forEach(playerId -> losingTeam.addPlayer(new Player<Long>(playerId),
            getPlayerRatingOrDefault(playerId)));

    Map<IPlayer, Rating> newRatings = skillCalculator.calculateNewRatings(gameInfo,
        ImmutableList.of(winningTeam, losingTeam), 1, 2);

    newRatings.forEach((playerGeneric, newRating) -> {
      Player<Long> player = (Player<Long>) playerGeneric;
      PlayerStats playerStats = getOrCreatePlayerStats(player.getId());
      playerStats.playerRating = newRating;
    });
  }

  private void recordMatchHistoryChanges(MatchOutcome matchOutcome) {
    matchOutcome.getWinningPlayers().forEach(winningPlayerId -> {
      PlayerStats playerStats = getOrCreatePlayerStats(winningPlayerId);
      playerStats.gamesWon += 1;

      matchOutcome.getWinningPlayers().forEach(otherWinningPlayer -> {
        if (!otherWinningPlayer.equals(winningPlayerId)) {
          PlayedWithStats playedWithStats = playerStats
              .getOrCreatePlayedWithStats(otherWinningPlayer);
          playedWithStats.gamesWonWith += 1;
        }
      });
      matchOutcome.getLosingPlayers().forEach(otherLosingPlayer -> {
        if (!otherLosingPlayer.equals(winningPlayerId)) {
          PlayedWithStats playedWithStats = playerStats
              .getOrCreatePlayedWithStats(otherLosingPlayer);
          playedWithStats.gamesWonAgainst += 1;
        }
      });
    });

    matchOutcome.getLosingPlayers().forEach(losingPlayerId -> {
      PlayerStats playerStats = getOrCreatePlayerStats(losingPlayerId);
      playerStats.gamesLost += 1;

      matchOutcome.getWinningPlayers().forEach(otherWinningPlayer -> {
        if (!otherWinningPlayer.equals(losingPlayerId)) {
          PlayedWithStats playedWithStats = playerStats
              .getOrCreatePlayedWithStats(otherWinningPlayer);
          playedWithStats.gamesLostAgainst += 1;
        }
      });
      matchOutcome.getLosingPlayers().forEach(otherLosingPlayer -> {
        if (!otherLosingPlayer.equals(losingPlayerId)) {
          PlayedWithStats playedWithStats = playerStats
              .getOrCreatePlayedWithStats(otherLosingPlayer);
          playedWithStats.gamesLostWith += 1;
        }
      });
    });

    matchHistory.add(matchOutcome);
  }

  private PlayerStats getOrCreatePlayerStats(long playerId) {
    return playerStats.computeIfAbsent(playerId,
        playerId1 -> new PlayerStats(playerId1, gameInfo.getDefaultRating()));
  }

  public class PlayerStats extends WinLossStat {

    private final long playerId;
    private final HashMap<Long, PlayedWithStats> playedWithStats;

    private Rating playerRating;

    private int gamesWon;
    private int gamesLost;

    private PlayerStats(long playerId, Rating playerRating) {
      this.playerId = playerId;
      this.playerRating = playerRating;
      this.playedWithStats = new HashMap<>();
    }

    public long getPlayerId() {
      return playerId;
    }

    public Rating getPlayerRating() {
      return playerRating;
    }

    @Override
    public int getGamesWon() {
      return gamesWon;
    }

    @Override
    public int getGamesLost() {
      return gamesLost;
    }

    public HashMap<Long, PlayedWithStats> getPlayedWithStats() {
      return playedWithStats;
    }

    private PlayedWithStats getOrCreatePlayedWithStats(long otherPlayerId) {
      return playedWithStats
          .computeIfAbsent(otherPlayerId, other -> new PlayedWithStats(playerId, other));
    }
  }

  public class PlayedWithStats extends WinLossStat {

    private final long playerId;
    private final long otherPlayerId;

    private int gamesWonWith;
    private int gamesLostWith;
    private int gamesWonAgainst;
    private int gamesLostAgainst;

    private PlayedWithStats(long playerId, long otherPlayerId) {
      this.playerId = playerId;
      this.otherPlayerId = otherPlayerId;
    }

    public long getPlayerId() {
      return playerId;
    }

    public long getOtherPlayerId() {
      return otherPlayerId;
    }

    @Override
    public int getGamesWon() {
      return gamesWonWith;
    }

    @Override
    public int getGamesLost() {
      return gamesLostWith;
    }

    public int getGamesWonAgainst() {
      return gamesWonAgainst;
    }

    public int getGamesLostAgainst() {
      return gamesLostAgainst;
    }
  }

  public abstract class WinLossStat {

    public abstract int getGamesWon();

    public abstract int getGamesLost();

    public int totalGamesPlayed() {
      return getGamesWon() + getGamesLost();
    }

    public double winRate() {
      return (double) getGamesWon() / totalGamesPlayed();
    }
  }

  private static class SerializedData {

    public Map<Long, Rating> ratings;
    public List<MatchOutcome> matchOutcomes;

    public SerializedData(Map<Long, Rating> ratings,
        List<MatchOutcome> matchOutcomes) {
      this.ratings = ratings;
      this.matchOutcomes = matchOutcomes;
    }
  }
}
