package ar.edu.itba.pdc.natto.protocol.xmpp;

import ar.edu.itba.pdc.natto.protocol.Protocol;

import java.nio.ByteBuffer;

/**
 * Created by user on 26/10/16.
 */
public class XmppProtocol implements Protocol<ByteBuffer>{

    @Override
    public ByteBuffer process(ByteBuffer message) {
        //TODO: ver si el usuario esta silenciado
//        System.out.println("El mensaje en el process de protocol es: " + message);
//
//        if(message.isWrongFormat())
//            return message;
//
//        if(message.isTooBig()){
//            //TODO
//            return message;
//        }
//
//        if(message.isMessage()){
//            //TODO: ver si el usuario tiene habilitado el l33t
//            l33tBody((Message) message);
//        }

        return message;
    }


}


