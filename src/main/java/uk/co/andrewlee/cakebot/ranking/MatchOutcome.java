package uk.co.andrewlee.cakebot.ranking;

import com.google.common.collect.ImmutableList;

import javax.annotation.concurrent.Immutable;

@Immutable
public class MatchOutcome {

  private final Match match;

  private final boolean team1Won;

  public static MatchOutcome createTeam1Won(Match match) {
    return new MatchOutcome(match, /* team1Won */ true);
  }

  public static MatchOutcome createTeam2Won(Match match) {
    return new MatchOutcome(match, /* team1Won */ false);
  }

  private MatchOutcome(Match match, boolean team1Won) {
    this.match = match;
    this.team1Won = team1Won;
  }

  public ImmutableList<Long> getWinningPlayers() {
    return team1Won ? match.getTeam1() : match.getTeam2();
  }

  public ImmutableList<Long> getLosingPlayers() {
    return team1Won ? match.getTeam2() : match.getTeam1();
  }

  public Match getMatch() {
    return match;
  }

  public boolean isTeam1Won() {
    return team1Won;
  }

  public boolean isTeam2Won() {
    return !team1Won;
  }
}
