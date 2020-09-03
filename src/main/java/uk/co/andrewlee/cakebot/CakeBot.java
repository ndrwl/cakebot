package uk.co.andrewlee.cakebot;

import de.gesundkrank.jskills.GameInfo;
import de.gesundkrank.jskills.trueskill.FactorGraphTrueSkillCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.discord.BotSystem;

import java.nio.file.Path;
import java.nio.file.Paths;
import uk.co.andrewlee.cakebot.ranking.discord.PlayerRankingSystemDiscordClient;

public class CakeBot {

  private static final Logger logger = LoggerFactory.getLogger(CakeBot.class);

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      logger.info("Usage: CakeBot [DiscordToken] [SaveDirectory].");
      System.exit(1);
    }

    Path saveDirectory = Paths.get(args[1]);

    BotSystem botSystem = BotSystem.create(args[0]);

    PlayerRankingSystemDiscordClient playerRankingClient = PlayerRankingSystemDiscordClient
        .create(botSystem, new FactorGraphTrueSkillCalculator(),
            GameInfo.getDefaultGameInfo(), 10, saveDirectory);
    playerRankingClient.init();

    botSystem.start();
  }
}
