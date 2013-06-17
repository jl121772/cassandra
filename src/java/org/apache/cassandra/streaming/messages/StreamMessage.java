/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.streaming.messages;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import org.apache.cassandra.streaming.StreamSession;

/**
 * StreamMessage is an abstract base class that every messages in streaming protocol inherit.
 *
 * Every message carries message type({@link Type}) and streaming protocol version byte.
 */
public abstract class StreamMessage
{
    /** Streaming protocol version */
    public static final int CURRENT_VERSION = 1;

    public static void serialize(StreamMessage message, WritableByteChannel out, int version, StreamSession session) throws IOException
    {
        ByteBuffer buff = ByteBuffer.allocate(1);
        // message type
        buff.put(message.type.type);
        buff.flip();
        out.write(buff);
        message.type.serializer.serialize(message, out, version, session);
    }

    public static StreamMessage deserialize(ReadableByteChannel in, int version, StreamSession session) throws IOException
    {
        ByteBuffer buff = ByteBuffer.allocate(1);
        in.read(buff);
        buff.flip();
        Type type = Type.get(buff.get());
        return type.serializer.deserialize(in, version, session);
    }

    /** StreamMessage serializer */
    public static interface Serializer<V extends StreamMessage>
    {
        V deserialize(ReadableByteChannel in, int version, StreamSession session) throws IOException;
        void serialize(V message, WritableByteChannel out, int version, StreamSession session) throws IOException;
    }

    /** StreamMessage types */
    public static enum Type
    {
        PREPARE(1, 5, PrepareMessage.serializer),
        FILE(2, 0, FileMessage.serializer),
        RETRY(3, 1, RetryMessage.serializer),
        COMPLETE(4, 4, CompleteMessage.serializer),
        SESSION_FAILED(5, 5, SessionFailedMessage.serializer);

        public static Type get(byte type)
        {
            for (Type t : Type.values())
            {
                if (t.type == type)
                    return t;
            }
            throw new IllegalArgumentException("Unknown type " + type);
        }

        private final byte type;
        public final int priority;
        public final Serializer<StreamMessage> serializer;

        @SuppressWarnings("unchecked")
        private Type(int type, int priority, Serializer serializer)
        {
            this.type = (byte) type;
            this.priority = priority;
            this.serializer = serializer;
        }
    }

    public final Type type;

    protected StreamMessage(Type type)
    {
        this.type = type;
    }

    /**
     * @return priority of this message. higher value, higher priority.
     */
    public int getPriority()
    {
        return type.priority;
    }
}
