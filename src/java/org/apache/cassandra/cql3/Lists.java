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
package org.apache.cassandra.cql3;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.cassandra.db.CellName;
import org.apache.cassandra.db.CellNames;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.Composite;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.ListType;
import org.apache.cassandra.db.marshal.MarshalException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.UUIDGen;

/**
 * Static helper methods and classes for lists.
 */
public abstract class Lists
{
    private Lists() {}

    public static ColumnSpecification indexSpecOf(ColumnSpecification column)
    {
        return new ColumnSpecification(column.ksName, column.cfName, new ColumnIdentifier("idx(" + column.name + ")", true), Int32Type.instance);
    }

    public static ColumnSpecification valueSpecOf(ColumnSpecification column)
    {
        return new ColumnSpecification(column.ksName, column.cfName, new ColumnIdentifier("value(" + column.name + ")", true), ((ListType)column.type).elements);
    }

    public static class Literal implements Term.Raw
    {
        private final List<Term.Raw> elements;

        public Literal(List<Term.Raw> elements)
        {
            this.elements = elements;
        }

        public Value prepare(ColumnSpecification receiver) throws InvalidRequestException
        {
            validateAssignableTo(receiver);

            ColumnSpecification valueSpec = Lists.valueSpecOf(receiver);
            List<ByteBuffer> values = new ArrayList<ByteBuffer>(elements.size());
            for (Term.Raw rt : elements)
            {
                Term t = rt.prepare(valueSpec);

                if (!(t instanceof Constants.Value))
                {
                    if (t instanceof Term.NonTerminal)
                        throw new InvalidRequestException(String.format("Invalid list literal for %s: bind variables are not supported inside collection literals", receiver));
                    else
                        throw new InvalidRequestException(String.format("Invalid list literal for %s: nested collections are not supported", receiver));
                }

                // We don't allow prepared marker in collections, nor nested collections
                assert t instanceof Constants.Value;
                ByteBuffer bytes = ((Constants.Value)t).bytes;
                // We don't support value > 64K because the serialization format encode the length as an unsigned short.
                if (bytes.remaining() > FBUtilities.MAX_UNSIGNED_SHORT)
                    throw new InvalidRequestException(String.format("List value is too long. List values are limited to %d bytes but %d bytes value provided",
                                                                    FBUtilities.MAX_UNSIGNED_SHORT,
                                                                    bytes.remaining()));

                values.add(bytes);
            }
            return new Value(values);
        }

        private void validateAssignableTo(ColumnSpecification receiver) throws InvalidRequestException
        {
            if (!(receiver.type instanceof ListType))
                throw new InvalidRequestException(String.format("Invalid list literal for %s of type %s", receiver, receiver.type.asCQL3Type()));

            ColumnSpecification valueSpec = Lists.valueSpecOf(receiver);
            for (Term.Raw rt : elements)
            {
                if (!rt.isAssignableTo(valueSpec))
                    throw new InvalidRequestException(String.format("Invalid list literal for %s: value %s is not of type %s", receiver, rt, valueSpec.type.asCQL3Type()));
            }
        }

        public boolean isAssignableTo(ColumnSpecification receiver)
        {
            try
            {
                validateAssignableTo(receiver);
                return true;
            }
            catch (InvalidRequestException e)
            {
                return false;
            }
        }

        @Override
        public String toString()
        {
            return elements.toString();
        }
    }

    public static class Value extends Term.Terminal
    {
        public final List<ByteBuffer> elements;

        public Value(List<ByteBuffer> elements)
        {
            this.elements = elements;
        }

        public static Value fromSerialized(ByteBuffer value, ListType type) throws InvalidRequestException
        {
            try
            {
                // Collections have this small hack that validate cannot be called on a serialized object,
                // but compose does the validation (so we're fine).
                List<?> l = type.compose(value);
                List<ByteBuffer> elements = new ArrayList<ByteBuffer>(l.size());
                for (Object element : l)
                    elements.add(type.elements.decompose(element));
                return new Value(elements);
            }
            catch (MarshalException e)
            {
                throw new InvalidRequestException(e.getMessage());
            }
        }

        public ByteBuffer get()
        {
            return CollectionType.pack(elements, elements.size());
        }
    }

    public static class Marker extends AbstractMarker
    {
        protected Marker(int bindIndex, ColumnSpecification receiver)
        {
            super(bindIndex, receiver);
            assert receiver.type instanceof ListType;
        }

        public Value bind(List<ByteBuffer> values) throws InvalidRequestException
        {
            ByteBuffer value = values.get(bindIndex);
            return value == null ? null : Value.fromSerialized(value, (ListType)receiver.type);

        }
    }

    /*
     * For prepend, we need to be able to generate unique but decreasing time
     * UUID, which is a bit challenging. To do that, given a time in milliseconds,
     * we adds a number representing the 100-nanoseconds precision and make sure
     * that within the same millisecond, that number is always decreasing. We
     * do rely on the fact that the user will only provide decreasing
     * milliseconds timestamp for that purpose.
     */
    private static class PrecisionTime
    {
        // Our reference time (1 jan 2010, 00:00:00) in milliseconds.
        private static final long REFERENCE_TIME = 1262304000000L;
        private static final AtomicReference<PrecisionTime> last = new AtomicReference<PrecisionTime>(new PrecisionTime(Long.MAX_VALUE, 0));

        public final long millis;
        public final int nanos;

        PrecisionTime(long millis, int nanos)
        {
            this.millis = millis;
            this.nanos = nanos;
        }

        static PrecisionTime getNext(long millis)
        {
            while (true)
            {
                PrecisionTime current = last.get();

                assert millis <= current.millis;
                PrecisionTime next = millis < current.millis
                    ? new PrecisionTime(millis, 9999)
                    : new PrecisionTime(millis, Math.max(0, current.nanos - 1));

                if (last.compareAndSet(current, next))
                    return next;
            }
        }
    }

    public static class Setter extends Operation
    {
        public Setter(CFDefinition.Name column, Term t)
        {
            super(column, t);
        }

        public void execute(ByteBuffer rowKey, ColumnFamily cf, Composite prefix, UpdateParameters params) throws InvalidRequestException
        {
            // delete + append
            CellName name = CellNames.make(prefix, columnName);
            cf.addAtom(params.makeTombstoneForOverwrite(name.slice()));
            Appender.doAppend(t, cf, name, params);
        }
    }

    public static class SetterByIndex extends Operation
    {
        private final Term idx;

        public SetterByIndex(CFDefinition.Name column, Term idx, Term t)
        {
            super(column, t);
            this.idx = idx;
        }

        @Override
        public boolean requiresRead()
        {
            return true;
        }

        @Override
        public void collectMarkerSpecification(ColumnSpecification[] boundNames)
        {
            super.collectMarkerSpecification(boundNames);
            idx.collectMarkerSpecification(boundNames);
        }

        public void execute(ByteBuffer rowKey, ColumnFamily cf, Composite prefix, UpdateParameters params) throws InvalidRequestException
        {
            Term.Terminal index = idx.bind(params.variables);
            Term.Terminal value = t.bind(params.variables);
            assert index instanceof Constants.Value && value instanceof Constants.Value;

            List<Column> existingList = params.getPrefetchedList(rowKey, columnName.name.key);
            int idx = ByteBufferUtil.toInt(((Constants.Value)index).bytes);
            if (idx < 0 || idx >= existingList.size())
                throw new InvalidRequestException(String.format("List index %d out of bound, list has size %d", idx, existingList.size()));

            ByteBuffer bytes = ((Constants.Value)value).bytes;
            // We don't support value > 64K because the serialization format encode the length as an unsigned short.
            if (bytes.remaining() > FBUtilities.MAX_UNSIGNED_SHORT)
                throw new InvalidRequestException(String.format("List value is too long. List values are limited to %d bytes but %d bytes value provided",
                                                                FBUtilities.MAX_UNSIGNED_SHORT,
                                                                bytes.remaining()));

            CellName elementName = existingList.get(idx).name();
            cf.addColumn(params.makeColumn(elementName, bytes));
        }
    }

    public static class Appender extends Operation
    {
        public Appender(CFDefinition.Name column, Term t)
        {
            super(column, t);
        }

        public void execute(ByteBuffer rowKey, ColumnFamily cf, Composite prefix, UpdateParameters params) throws InvalidRequestException
        {
            doAppend(t, cf, CellNames.make(prefix, columnName), params);
        }

        static void doAppend(Term t, ColumnFamily cf, CellName columnName, UpdateParameters params) throws InvalidRequestException
        {
            Term.Terminal value = t.bind(params.variables);
            // If we append null, do nothing. Note that for Setter, we've
            // already removed the previous value so we're good here too
            if (value == null)
                return;

            assert value instanceof Lists.Value;
            List<ByteBuffer> toAdd = ((Lists.Value)value).elements;
            for (int i = 0; i < toAdd.size(); i++)
            {
                ByteBuffer uuid = ByteBuffer.wrap(UUIDGen.getTimeUUIDBytes());
                cf.addColumn(params.makeColumn(CellNames.makeCollectionCell(columnName, uuid), toAdd.get(i)));
            }
        }
    }

    public static class Prepender extends Operation
    {
        public Prepender(CFDefinition.Name column, Term t)
        {
            super(column, t);
        }

        public void execute(ByteBuffer rowKey, ColumnFamily cf, Composite prefix, UpdateParameters params) throws InvalidRequestException
        {
            Term.Terminal value = t.bind(params.variables);
            if (value == null)
                return;

            assert value instanceof Lists.Value;
            long time = PrecisionTime.REFERENCE_TIME - (System.currentTimeMillis() - PrecisionTime.REFERENCE_TIME);

            List<ByteBuffer> toAdd = ((Lists.Value)value).elements;
            CellName name = CellNames.make(prefix, columnName);
            for (int i = 0; i < toAdd.size(); i++)
            {
                PrecisionTime pt = PrecisionTime.getNext(time);
                ByteBuffer uuid = ByteBuffer.wrap(UUIDGen.getTimeUUIDBytes(pt.millis, pt.nanos));
                cf.addColumn(params.makeColumn(CellNames.makeCollectionCell(name, uuid), toAdd.get(i)));
            }
        }
    }

    public static class Discarder extends Operation
    {
        public Discarder(CFDefinition.Name column, Term t)
        {
            super(column, t);
        }

        @Override
        public boolean requiresRead()
        {
            return true;
        }

        public void execute(ByteBuffer rowKey, ColumnFamily cf, Composite prefix, UpdateParameters params) throws InvalidRequestException
        {
            List<Column> existingList = params.getPrefetchedList(rowKey, columnName.name.key);
            if (existingList.isEmpty())
                return;

            Term.Terminal value = t.bind(params.variables);
            if (value == null)
                return;

            assert value instanceof Lists.Value;

            // Note: below, we will call 'contains' on this toDiscard list for each element of existingList.
            // Meaning that if toDiscard is big, converting it to a HashSet might be more efficient. However,
            // the read-before-write this operation requires limits its usefulness on big lists, so in practice
            // toDiscard will be small and keeping a list will be more efficient.
            List<ByteBuffer> toDiscard = ((Lists.Value)value).elements;
            for (Column cell : existingList)
            {
                if (toDiscard.contains(cell.value()))
                    cf.addColumn(params.makeTombstone(cell.name()));
            }
        }
    }

    public static class DiscarderByIndex extends Operation
    {
        public DiscarderByIndex(CFDefinition.Name column, Term idx)
        {
            super(column, idx);
        }

        @Override
        public boolean requiresRead()
        {
            return true;
        }

        public void execute(ByteBuffer rowKey, ColumnFamily cf, Composite prefix, UpdateParameters params) throws InvalidRequestException
        {
            Term.Terminal index = t.bind(params.variables);
            if (index == null)
                throw new InvalidRequestException("Invalid null value for list index");

            assert index instanceof Constants.Value;

            List<Column> existingList = params.getPrefetchedList(rowKey, columnName.name.key);
            int idx = ByteBufferUtil.toInt(((Constants.Value)index).bytes);
            if (idx < 0 || idx >= existingList.size())
                throw new InvalidRequestException(String.format("List index %d out of bound, list has size %d", idx, existingList.size()));

            CellName elementName = existingList.get(idx).name();
            cf.addColumn(params.makeTombstone(elementName));
        }
    }
}
