package the_fireplace.clans.network;

import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * @author The_Fireplace
 */
public abstract class AbstractServerMessageHandler<T extends IMessage> extends AbstractMessageHandler<T> {
    @Override
    public final IMessage handleClientMessage(T message, MessageContext ctx) {
        return null;
    }
}