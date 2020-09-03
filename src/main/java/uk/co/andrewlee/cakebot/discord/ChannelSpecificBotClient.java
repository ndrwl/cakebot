package uk.co.andrewlee.cakebot.discord;

import discord4j.core.object.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

@ThreadSafe
public abstract class ChannelSpecificBotClient implements BotClient {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  protected final BotSystem botSystem;
  protected final ExecutorService executor;

  private final ConcurrentHashMap<Long, Boolean> channels;
  private final ConcurrentHashMap<String, DiscordCommandHandler> handlers;

  public ChannelSpecificBotClient(BotSystem botSystem, ExecutorService executor) {
    this.botSystem = botSystem;
    this.executor = executor;
    this.channels = new ConcurrentHashMap<>();
    this.handlers = new ConcurrentHashMap<>();
  }

  public void init() {
    // Do nothing
  }

  public void handle(List<String> arguments, Message message) {
    if (!channels.containsKey(message.getChannelId().asLong())) {
      return;
    }

    if (arguments.isEmpty()) {
      return;
    }

    Optional<DiscordCommandHandler> handlerOpt = Optional
        .ofNullable(handlers.get(arguments.get(0)));

    if (!handlerOpt.isPresent()) {
      return;
    }

    DiscordCommandHandler handler = handlerOpt.get();

    executor.execute(() -> {
      try {
        handler.handle(arguments, message);
      } catch (Exception e) {
        logger.error("Error while handling command.", e);
        DiscordHelper.respond(message,
            "Error while processing command. Please see server logs.");
      }
    });
  }

  protected void listenToChannel(long channelId) {
    channels.put(channelId, true);
  }

  protected void registerMessageHandler(String commandString, DiscordCommandHandler handler) {
    handlers.put(commandString, handler);
  }

  @FunctionalInterface
  public interface DiscordCommandHandler {

    void handle(List<String> arguments, Message message) throws Exception;
  }
}
