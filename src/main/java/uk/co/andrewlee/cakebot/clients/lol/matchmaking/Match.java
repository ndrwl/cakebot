package uk.co.andrewlee.cakebot.clients.lol.matchmaking;

import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.Immutable;

@Immutable
public class Match {
  private final ImmutableList<PlayerData> team1;
  private final ImmutableList<PlayerData> team2;

  private final int maxStrengthDiff;
  private final double expectedLaneVariance;
  private final int averageStrengthDiff;

  public Match(ImmutableList<PlayerData> team1,
      ImmutableList<PlayerData> team2, int maxStrengthDiff, double expectedLaneVariance,
      int averageStrengthDiff) {
    this.team1 = team1;
    this.team2 = team2;
    this.maxStrengthDiff = maxStrengthDiff;
    this.expectedLaneVariance = expectedLaneVariance;
    this.averageStrengthDiff = averageStrengthDiff;
  }

  public ImmutableList<PlayerData> getTeam1() {
    return team1;
  }

  public ImmutableList<PlayerData> getTeam2() {
    return team2;
  }

  public int getMaxStrengthDiff() {
    return maxStrengthDiff;
  }

  public double getExpectedLaneVariance() {
    return expectedLaneVariance;
  }

  public int getAverageStrengthDiff() {
    return averageStrengthDiff;
  }

  public Match mirror() {
    return new Match(team2, team1, -maxStrengthDiff, -expectedLaneVariance, -averageStrengthDiff);
  }
}
