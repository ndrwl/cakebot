package uk.co.andrewlee.cakebot.discord;

import java.util.List;
import sx.blah.discord.handle.obj.IMessage;

public interface BotClient {

  void init();

  void handle(List<String> arguments, IMessage message);
}
