package uk.co.andrewlee.cakebot.ranking;

public interface RankingOperation {

  class CreatePlayerRankingOperation implements RankingOperation {

    private final long playerId;
    private final double meanRating;

    CreatePlayerRankingOperation(long playerId, double meanRating) {
      this.playerId = playerId;
      this.meanRating = meanRating;
    }

    public long getPlayerId() {
      return playerId;
    }

    public double getMeanRating() {
      return meanRating;
    }
  }

  class MatchOutcomeRankingOperation implements RankingOperation {

    private final MatchOutcome matchOutcome;

    MatchOutcomeRankingOperation(MatchOutcome matchOutcome) {
      this.matchOutcome = matchOutcome;
    }

    public MatchOutcome getMatchOutcome() {
      return matchOutcome;
    }
  }
}
