package uk.co.andrewlee.cakebot.clients.channelregistration;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sx.blah.discord.handle.obj.IMessage;
import uk.co.andrewlee.cakebot.discord.BotClient;
import uk.co.andrewlee.cakebot.discord.BotSystem;

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
  public void handle(List<String> arguments, IMessage message) {
    if (arguments.size() != 2) {
      return;
    }

    if (arguments.get(0).equals(REGISTRATION_STRING)) {
      String registrationTag = arguments.get(1);
      ListenableFuture<Boolean> registrationFuture = channelRegistrar
          .registerChannel(message.getChannel().getLongID(), registrationTag);

      Futures.addCallback(registrationFuture, new FutureCallback<Boolean>() {
        @Override
        public void onSuccess(@Nullable Boolean result) {
          if (result) {
            message.reply(
                String.format("Successfully registered channel with tag `%s`.", registrationTag));
          } else {
            message.reply(
                String.format("Channel is already registered channel with tag `%s`.",
                    registrationTag));
          }
        }

        @Override
        public void onFailure(Throwable t) {
          logger.error("Error registering channel.", t);
          message.reply("Unexpected error registering the channel. Please check server logs.");
        }
      }, MoreExecutors.directExecutor());
    } else if (arguments.get(0).equals(UNREGISTRATION_STRING)) {
      String registrationTag = arguments.get(1);
      ListenableFuture<Boolean> unregistrationFuture = channelRegistrar
          .unregisterChannel(message.getChannel().getLongID(), registrationTag);

      Futures.addCallback(unregistrationFuture, new FutureCallback<Boolean>() {
        @Override
        public void onSuccess(@Nullable Boolean result) {
          if (result) {
            message.reply(
                String.format("Successfully unregistered channel with tag `%s`.", registrationTag));
          } else {
            message.reply(
                String.format("Could not unregister channel as the channel was not "
                    + "registered with tag `%s`.", registrationTag));
          }
        }

        @Override
        public void onFailure(Throwable t) {
          logger.error("Error registering channel.", t);
          message.reply("Unexpected error unregistering the channel. Please check server logs.");
        }
      }, MoreExecutors.directExecutor());
    }
  }

  public ChannelRegistrar getChannelRegistrar() {
    return channelRegistrar;
  }
}
