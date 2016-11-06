package ar.edu.itba.pdc.natto.proxy.handlers.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import ar.edu.itba.pdc.natto.dispatcher.ChannelOperation;
import ar.edu.itba.pdc.natto.dispatcher.DispatcherSubscriber;
import ar.edu.itba.pdc.natto.io.Closeables;
import ar.edu.itba.pdc.natto.protocol.*;
import ar.edu.itba.pdc.natto.protocol.xmpp.NegotiatorServer;
import ar.edu.itba.pdc.natto.proxy.handlers.Connection;
import ar.edu.itba.pdc.natto.proxy.handlers.ConnectionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

// TODO: Se puede sacar <T>? siii por el amor a dios sacar
public class SocketConnectionHandler<T> implements ConnectionHandler, Connection {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionHandler.class);
    private static final int BUFFER_SIZE = 1024;

    private final DispatcherSubscriber subscriber;
    private final ParserFactory<T> parserFactory;
    private final ProtocolFactory<T> protocolFactory;

    private final Parser<T> parser;
    private final Protocol<T> protocol;

    private final SocketChannel channel;
    private Connection connection;

    private final ByteBuffer readBuffer;
    private final Queue<ByteBuffer> messages;

    private boolean closeRequested = false;

    private Negotiator negotiator;

    private boolean actServer = true;

    private boolean connectRequested = false;

    private ChannelOperation afterConnect = null;


    //VOY A TENER QUE RECIBIR UNO
    public SocketConnectionHandler(final SocketChannel channel,
                                   final DispatcherSubscriber subscriber,
                                   final ParserFactory<T> parserFactory,
                                   final ProtocolFactory<T> protocolFactory, Negotiator negotiator) {

        checkNotNull(channel, "Channel can't be null");
        checkArgument(channel.isOpen(), "Channel isn't open");
        checkArgument(!channel.isBlocking(), "Channel is in blocking mode");

        this.subscriber = checkNotNull(subscriber, "Register can't be null");
        this.parserFactory = checkNotNull(parserFactory, "Parser factory can't be null");
        this.protocolFactory = checkNotNull(protocolFactory, "Protocol factory can't be null");

        this.parser = parserFactory.get();
        this.protocol = protocolFactory.get();

        this.channel = channel;
        this.connection = this;

        this.negotiator = negotiator;

        this.messages = new ConcurrentLinkedQueue<>();
        this.readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    //ACA RECIBO UN NEGOTIATOR ASIGNO EL QUE QUIERO
    @Override
    public Connection requestConnect(final InetSocketAddress serverAddress, final Negotiator negotiator) throws IOException {
        checkState(connection == this);
        checkNotNull(serverAddress, "Address can't be null");
        checkArgument(!serverAddress.isUnresolved(), "Invalid address");

        connectRequested = true;

        logger.info("Channel " + channel.socket().getRemoteSocketAddress()
                + " requested connection to: " + serverAddress);

        SocketChannel server = SocketChannel.open();
        server.configureBlocking(false);
        server.connect(serverAddress);

        SocketConnectionHandler<T> serverHandler = new SocketConnectionHandler<>(server, subscriber,
                parserFactory, protocolFactory, negotiator);
        serverHandler.connection = this;
        serverHandler.negotiator = negotiator;
        this.connection = serverHandler;

        subscriber.unsubscribe(channel, ChannelOperation.READWRITE);
        subscriber.subscribe(server, ChannelOperation.CONNECT, serverHandler);

        return serverHandler;
    }

    @Override
    public void handle_connect() {
        try {
            if (channel.finishConnect()) {
                SocketAddress serverAddress = channel.socket().getRemoteSocketAddress();

                logger.info("Established connection with server on " + serverAddress);

                subscriber.unsubscribe(channel, ChannelOperation.CONNECT);
                if(afterConnect != null){
                    subscriber.subscribe(channel, afterConnect, this);
                }else{
                    subscriber.subscribe(channel, ChannelOperation.READ, this); //TODO JPM
                }

                //  connection.requestRead();
            }
        } catch (IOException exception) {
            logger.error("Couldn't establish connection with server", exception);

            logger.info("Closing connection with client");
            // TODO: Cerrar la otra conexion (? && Cerrar key?
            Closeables.closeSilently(channel);
        }
    }

    @Override
    public void requestRead() {
        if(connectRequested){
            afterConnect = ChannelOperation.READ;
        }else{
            if(messages.isEmpty()){
                subscriber.subscribe(channel, ChannelOperation.READ, this); //TODO JPM
            }
        }

    }

    @Override
    public void handle_read() {
        int bytesRead;


        //  logger.info("Channel " + channel.socket().getRemoteSocketAddress()
        //        + " requested read operation");

        //connection de servidor




        try {
            bytesRead = channel.read(readBuffer);
        } catch (IOException exception) {
            logger.error("Can't read channel channel", exception);
            // TODO: Cerrar la otra conexion && Cerrar key?
            Closeables.closeSilently(channel);
            connection.requestClose();
            return;
        }

        // The channel has reached end-of-stream or error
        if (bytesRead == -1) {
            logger.info("Channel reached EOF");

            Closeables.closeSilently(channel);
            connection.requestClose();
            // TODO: Cerrar la otra conexion && Cerrar key?

            return;
        }


        //LEER REQUEST CONNECT TODO
        // Cannot read more bytes than are immediately available
        if (bytesRead > 0) {
            readBuffer.flip();
            subscriber.unsubscribe(channel, ChannelOperation.READ);

         //   readBuffer.limit(readBuffer.limit() - 1);       //TODO: SACAR ESTO QUE PUEDE ROMPER TOOD PARA SACAR EL \n

            //while (readBuffer.hasRemaining()){
            // TODO: ProtocolTask (?

            if(!negotiator.isVerified()){
                negotiator.handshake(this, readBuffer);

            }else{



                T request = parser.fromByteBuffer(readBuffer);

                if (request != null) {
                    T response = protocol.process(request);
                    System.out.println("RESPONSE: " + response); // TODO: Remove
                    if (response != null) {
                        connection.requestWrite(parser.toByteBuffer(response));
                    }
                }
            }

        }
      //
        //}

      //  readBuffer.compact();
        if (messages.isEmpty()) {
            readBuffer.clear();
            this.requestRead();
        }

    }


    @Override
    public void requestWrite(final ByteBuffer buffer) {
        if(connectRequested){
            afterConnect = ChannelOperation.WRITE;
        }else{
            subscriber.subscribe(channel, ChannelOperation.WRITE, this);
        }

        messages.offer(buffer);
    }

    @Override
    public void handle_write() {
        checkState(!messages.isEmpty());

        ByteBuffer buffer = messages.peek();

        try {
            channel.write(buffer);
        } catch (IOException exception) {
            logger.error("Can't write to channel", exception);

            Closeables.closeSilently(channel);
            connection.requestClose();
            // TODO: Cerrar la otra conexion && Cerrar key?

            return;
        }


        if (!buffer.hasRemaining()) {
            messages.remove();

            if (messages.isEmpty()) {
                subscriber.unsubscribe(channel, ChannelOperation.WRITE);

                if (closeRequested) {
                    Closeables.closeSilently(channel);
                } else {
                    connection.requestRead();
                }
            }
        }
    }

    @Override
    public void requestClose() {
        // TODO:
        if (closeRequested) {
            return;
        }

        closeRequested = true;
        subscriber.unsubscribe(channel, ChannelOperation.READ);

        if (messages.isEmpty()) {
            Closeables.closeSilently(channel);
        }
    }
}
