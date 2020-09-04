package uk.co.andrewlee.cakebot.clients.channelregistration;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.Channel;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.andrewlee.cakebot.discord.BotClient;
import uk.co.andrewlee.cakebot.discord.BotSystem;
import uk.co.andrewlee.cakebot.discord.DiscordHelper;

@ThreadSafe
public class ChannelRegistrationBotClient implements BotClient {

  private final static Logger logger = LoggerFactory.getLogger(ChannelRegistrationBotClient.class);

  private final static String REGISTRATION_STRING = "registerchannel";
  private final static String UNREGISTRATION_STRING = "unregisterchannel";

  private final BotSystem botSystem;
  private final ChannelRegistrar channelRegistrar;

  public static ChannelRegistrationBotClient create(BotSystem botSystem, Path saveDirectory) {
    ChannelRegistrar channelRegistrar = ChannelRegistrar.create(saveDirectory);
    channelRegistrar.init();

    return new ChannelRegistrationBotClient(botSystem, channelRegistrar);
  }

  private ChannelRegistrationBotClient(BotSystem botSystem, ChannelRegistrar channelRegistrar) {
    this.botSystem = botSystem;
    this.channelRegistrar = channelRegistrar;
  }

  @Override
  public void init() {
    // Nothing to do
  }

  @Override
  public void handle(List<String> arguments, Message message) {
    if (!DiscordHelper.messageIsFromAdmin(message)) {
      return;
    }

    if (arguments.size() != 2) {
      return;
    }

    if (arguments.get(0).equals(REGISTRATION_STRING)) {
      String registrationTag = arguments.get(1);
      long messageChannel = message.getChannel().map(Channel::getId)
          .map(Snowflake::asLong).block();
      ListenableFuture<Boolean> registrationFuture = channelRegistrar
          .registerChannel(messageChannel, registrationTag);

      Futures.addCallback(registrationFuture, new FutureCallback<Boolean>() {
        @Override
        public void onSuccess(@Nullable Boolean result) {
          if (result) {
            DiscordHelper.respond(message,
                String.format("Successfully registered channel with tag `%s`.", registrationTag));
          } else {
            DiscordHelper.respond(message, 
                String.format("Channel is already registered channel with tag `%s`.",
                    registrationTag));
          }
        }

        @Override
        public void onFailure(Throwable t) {
          logger.error("Error registering channel.", t);
          DiscordHelper.respond(message, "Unexpected error registering the channel. Please check server logs.");
        }
      }, MoreExecutors.directExecutor());
    } else if (arguments.get(0).equals(UNREGISTRATION_STRING)) {
      String registrationTag = arguments.get(1);
      long messageChannel = message.getChannel().map(Channel::getId)
          .map(Snowflake::asLong).block();
      ListenableFuture<Boolean> unregistrationFuture = channelRegistrar
          .unregisterChannel(messageChannel, registrationTag);

      Futures.addCallback(unregistrationFuture, new FutureCallback<Boolean>() {
        @Override
        public void onSuccess(@Nullable Boolean result) {
          if (result) {
            DiscordHelper.respond(message, 
                String.format("Successfully unregistered channel with tag `%s`.", registrationTag));
          } else {
            DiscordHelper.respond(message, 
                String.format("Could not unregister channel as the channel was not "
                    + "registered with tag `%s`.", registrationTag));
          }
        }

        @Override
        public void onFailure(Throwable t) {
          logger.error("Error registering channel.", t);
          DiscordHelper.respond(message, "Unexpected error unregistering the channel. Please check server logs.");
        }
      }, MoreExecutors.directExecutor());
    }
  }

  public ChannelRegistrar getChannelRegistrar() {
    return channelRegistrar;
  }
}
