package uk.co.andrewlee.cakebot.discord;

import discord4j.core.object.entity.Message;
import java.util.List;

public interface BotClient {
  void init();

  void handle(List<String> arguments, Message message);
}
