package uk.co.andrewlee.cakebot.clients.channelregistration;

import discord4j.core.object.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.ThreadSafe;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import uk.co.andrewlee.cakebot.discord.BotClient;
import uk.co.andrewlee.cakebot.discord.BotSystem;
import uk.co.andrewlee.cakebot.discord.DiscordHelper;

@ThreadSafe
public abstract class ChannelSpecificBotClient implements BotClient {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  protected final BotSystem botSystem;
  protected final ExecutorService executor;

  private final ConcurrentHashMap<Long, Boolean> channels;
  private final ConcurrentHashMap<String, DiscordCommandHandler> handlers;
  private final ChannelRegistrar channelRegistrar;
  private final String channelRegistrationTag;

  public ChannelSpecificBotClient(BotSystem botSystem, ExecutorService executor,
      ChannelRegistrar channelRegistrar, String channelRegistrationTag) {
    this.botSystem = botSystem;
    this.executor = executor;
    this.channelRegistrar = channelRegistrar;
    this.channelRegistrationTag = channelRegistrationTag;
    this.channels = new ConcurrentHashMap<>();
    this.handlers = new ConcurrentHashMap<>();
  }

  public void init() {
    channelRegistrar.registerCallback(channelRegistrationTag,
        channelId -> channels.put(channelId, true), channels::remove);
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
