package skadistats.clarity.processor.reader;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.ZeroCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xerial.snappy.Snappy;
import skadistats.clarity.event.Event;
import skadistats.clarity.event.EventListener;
import skadistats.clarity.event.Initializer;
import skadistats.clarity.event.Provides;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.OnInputSource;
import skadistats.clarity.processor.runner.Source;
import skadistats.clarity.wire.PacketTypes;
import skadistats.clarity.wire.proto.Demo;
import skadistats.clarity.wire.proto.Networkbasetypes;

import java.io.IOException;

@Provides({OnMessageContainer.class, OnMessage.class, OnFileInfoOffset.class, OnTickStart.class, OnTickEnd.class })
public class InputSourceProcessor {

    private static final Logger log = LoggerFactory.getLogger(InputSourceProcessor.class);

    private CodedInputStream ms;
    private boolean unpackUserMessages = false;

    @Initializer(OnMessage.class)
    public void initOnMessageListener(final Context ctx, final EventListener<OnMessage> listener) {
        listener.setParameterClasses(listener.getAnnotation().value());
        unpackUserMessages |= PacketTypes.USERMSG.containsValue(listener.getAnnotation().value());
    }

    private ByteString readPacket(int size, boolean isCompressed) throws IOException {
        byte[] data = ms.readRawBytes(size);
        if (isCompressed) {
            data = Snappy.uncompress(data);
        }
        return ZeroCopy.wrap(data);
    }

    @OnInputSource
    public void processStream(Context ctx, Source source) throws IOException {
        ms = CodedInputStream.newInstance(source.getInputStream());
        ms.setSizeLimit(Integer.MAX_VALUE);
        String header = new String(ms.readRawBytes(8));
        if (!"PBUFDEM\0".equals(header)) {
            throw new IOException("replay does not have the proper header");
        }
        ctx.createEvent(OnFileInfoOffset.class, int.class).raise(ms.readFixed32());
        ctx.createEvent(OnTickStart.class).raise();
        while (!ms.isAtEnd()) {
            int kind = ms.readRawVarint32();
            boolean isCompressed = (kind & Demo.EDemoCommands.DEM_IsCompressed_VALUE) == Demo.EDemoCommands.DEM_IsCompressed_VALUE;
            kind &= ~Demo.EDemoCommands.DEM_IsCompressed_VALUE;
            int tick = ms.readRawVarint32();
            int size = ms.readRawVarint32();
            boolean newTick = tick != ctx.getTick();
            if (newTick) {
                ctx.createEvent(OnTickEnd.class).raise();
                source.setTick(tick);
                ctx.createEvent(OnTickStart.class).raise();
            }
            Class<? extends GeneratedMessage> messageClass = PacketTypes.DEMO.get(kind);
            if (messageClass == null) {
                log.warn("unknown top level message of kind {}", kind);
                ms.skipRawBytes(size);
            } else if (messageClass == Demo.CDemoPacket.class) {
                Demo.CDemoPacket message = (Demo.CDemoPacket) PacketTypes.parse(messageClass, readPacket(size, isCompressed));
                ctx.createEvent(OnMessageContainer.class, CodedInputStream.class).raise(message.getData().newCodedInput());
            } else if (messageClass == Demo.CDemoSendTables.class) {
                Demo.CDemoSendTables message = (Demo.CDemoSendTables) PacketTypes.parse(messageClass, readPacket(size, isCompressed));
                ctx.createEvent(OnMessageContainer.class, CodedInputStream.class).raise(message.getData().newCodedInput());
            } else if (messageClass == Demo.CDemoFullPacket.class) {
                // TODO: ignored for now
                ms.skipRawBytes(size);
            } else {
                Event<OnMessage> ev = ctx.createEvent(OnMessage.class, messageClass);
                if (ev.isListenedTo()) {
                    GeneratedMessage message = PacketTypes.parse(messageClass, readPacket(size, isCompressed));
                    ev.raise(message);
                } else {
                    ms.skipRawBytes(size);
                }
            }
        }
        ctx.createEvent(OnTickEnd.class).raise();
    }

    @OnMessageContainer
    public void processEmbedded(Context ctx, CodedInputStream cs) throws IOException {
        while (!cs.isAtEnd()) {
            int kind = cs.readRawVarint32();
            if (kind == 0) {
                // this seems to happen with live replays
                break;
            }
            int size = cs.readRawVarint32();
            Class<? extends GeneratedMessage> messageClass = PacketTypes.EMBED.get(kind);
            if (messageClass == null) {
                log.warn("unknown embedded message of kind {}", kind);
                cs.skipRawBytes(size);
            } else {
                Event<OnMessage> ev = ctx.createEvent(OnMessage.class, messageClass);
                if (ev.isListenedTo() || (unpackUserMessages && messageClass == Networkbasetypes.CSVCMsg_UserMessage.class)) {
                    GeneratedMessage subMessage = PacketTypes.parse(messageClass, ZeroCopy.wrap(cs.readRawBytes(size)));
                    if (ev.isListenedTo()) {
                        ev.raise(subMessage);
                    }
                    if (unpackUserMessages && messageClass == Networkbasetypes.CSVCMsg_UserMessage.class) {
                        Networkbasetypes.CSVCMsg_UserMessage userMessage = (Networkbasetypes.CSVCMsg_UserMessage) subMessage;
                        Class<? extends GeneratedMessage> umClazz = PacketTypes.USERMSG.get(userMessage.getMsgType());
                        if (umClazz == null) {
                            log.warn("unknown usermessage of kind {}", userMessage.getMsgType());
                        } else {
                            ctx.createEvent(OnMessage.class, umClazz).raise(PacketTypes.parse(umClazz, userMessage.getMsgData()));
                        }
                    }
                } else {
                    cs.skipRawBytes(size);
                }
            }
        }
    }

    public void skipBytes(int numBytes) throws IOException {
        ms.skipRawBytes(numBytes);
    }

}