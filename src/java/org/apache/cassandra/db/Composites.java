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
package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.cassandra.db.filter.ColumnSlice;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.utils.Allocator;
import org.apache.cassandra.utils.ByteBufferUtil;

public abstract class Composites
{
    private Composites() {}

    public static final Composite EMPTY = new EmptyComposite();

    // TODO: this should go away. Just used temporally by SelectStatement
    public static CType simpleType(AbstractType<?> type)
    {
        return new SimpleCType(type);
    }

    // TODO: this should go away. Just used temporally by SelectStatement
    public static CType compositeType(List<AbstractType<?>> types)
    {
        return new CompositeCType(types);
    }

    private static class EmptyComposite implements Composite
    {
        public boolean isEmpty()
        {
            return true;
        }

        public int size()
        {
            return 0;
        }

        public ByteBuffer get(int i)
        {
            throw new IndexOutOfBoundsException();
        }

        public EOC eoc()
        {
            return EOC.NONE;
        }

        public Composite start()
        {
            return this;
        }

        public Composite end()
        {
            return this;
        }

        public Composite withEOC(EOC newEoc)
        {
            return this;
        }

        public ColumnSlice slice()
        {
            return ColumnSlice.ALL_COLUMNS;
        }

        public ByteBuffer toByteBuffer()
        {
            return ByteBufferUtil.EMPTY_BYTE_BUFFER;
        }

        public int dataSize()
        {
            return 0;
        }

        public boolean isPrefixOf(Composite c)
        {
            return true;
        }

        public boolean isPacked()
        {
            return true;
        }

        @Override
        public boolean equals(Object o)
        {
            if(!(o instanceof Composite))
                return false;

            return ((Composite)o).size() == 0;
        }

        @Override
        public int hashCode()
        {
            return 31;
        }

        public Composite copy(Allocator allocator)
        {
            return this;
        }
    }
}
