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
package org.apache.cassandra.db.marshal;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.cql3.ColumnNameBuilder;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.cql3.UpdateParameters;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.utils.ByteBufferUtil;

/**
 * The abstract validator that is the base for maps, sets and lists.
 *
 * Please note that this comparator shouldn't be used "manually" (through thrift for instance).
 *
 */
public abstract class CollectionType extends AbstractType<ByteBuffer>
{
    public enum Kind { MAP, SET, LIST };

    public enum Function
    {
        APPEND     (1, Kind.LIST),
        APPEND_ALL (-1, Kind.LIST),
        ADD        (1, Kind.SET),
        ADD_ALL    (-1, Kind.SET),
        PUT        (2, Kind.MAP),
        DISCARD    (1, Kind.SET, Kind.MAP);

        public final int nbArgs;
        public final EnumSet<Kind> validReceivers;

        private Function(int nbArgs, Kind ... validReceivers)
        {
            this.nbArgs = nbArgs;
            this.validReceivers = EnumSet.copyOf(Arrays.asList(validReceivers));
        }

        public static Function fromString(String str)
        {
            return Function.valueOf(str.trim().toUpperCase());
        }
    }

    public final Kind kind;

    protected CollectionType(Kind kind)
    {
        this.kind = kind;
    }

    protected abstract AbstractType<?> nameComparator();
    protected abstract AbstractType<?> valueComparator();
    protected abstract void appendToStringBuilder(StringBuilder sb);

    public void execute(ColumnFamily cf, ColumnNameBuilder fullPath, Function fct, List<Term> args, UpdateParameters params) throws InvalidRequestException
    {
        if (fct.nbArgs >= 0 && args.size() != fct.nbArgs)
            throw new InvalidRequestException(String.format("Wrong number of argument for %s, expecting %d, got %d", this, fct.nbArgs, args.size()));

        if (!fct.validReceivers.contains(kind))
            throw new InvalidRequestException(String.format("Invalid operation %s for %s collection", fct, kind));

        executeFunction(cf, fullPath, fct, args, params);
    }

    public abstract void executeFunction(ColumnFamily cf, ColumnNameBuilder fullPath, Function fct, List<Term> args, UpdateParameters params) throws InvalidRequestException;

    public abstract ByteBuffer serializeForThrift(Map<ByteBuffer, IColumn> columns);

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        appendToStringBuilder(sb);
        return sb.toString();
    }

    public int compare(ByteBuffer o1, ByteBuffer o2)
    {
        throw new UnsupportedOperationException("CollectionType should not be use directly as a comparator");
    }

    public ByteBuffer compose(ByteBuffer bytes)
    {
        return BytesType.instance.compose(bytes);
    }

    public ByteBuffer decompose(ByteBuffer value)
    {
        return BytesType.instance.decompose(value);
    }

    public String getString(ByteBuffer bytes)
    {
        return BytesType.instance.getString(bytes);
    }

    public ByteBuffer fromString(String source)
    {
        try
        {
            return ByteBufferUtil.hexToBytes(source);
        }
        catch (NumberFormatException e)
        {
            throw new MarshalException(String.format("cannot parse '%s' as hex bytes", source), e);
        }
    }

    public void validate(ByteBuffer bytes)
    {
        valueComparator().validate(bytes);
    }
}
