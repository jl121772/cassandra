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
package org.apache.cassandra.service.pager;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;

import com.google.common.collect.AbstractIterator;

import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.NamesQueryFilter;
import org.apache.cassandra.db.filter.SliceQueryFilter;
import org.apache.cassandra.db.columniterator.IdentityQueryFilter;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;

/**
 * Static utility methods to create query pagers.
 */
public class QueryPagers
{
    private QueryPagers() {};

    private static int maxQueried(ReadCommand command)
    {
        if (command instanceof SliceByNamesReadCommand)
        {
            NamesQueryFilter filter = ((SliceByNamesReadCommand)command).filter;
            return filter.countCQL3Rows() ? 1 : filter.columns.size();
        }
        else
        {
            SliceQueryFilter filter = ((SliceFromReadCommand)command).filter;
            return filter.count;
        }
    }

    public static boolean mayNeedPaging(Pageable command, int pageSize)
    {
        if (command instanceof Pageable.ReadCommands)
        {
            List<ReadCommand> commands = ((Pageable.ReadCommands)command).commands;

            int maxQueried = 0;
            for (ReadCommand readCmd : commands)
                maxQueried += maxQueried(readCmd);

            return maxQueried > pageSize;
        }
        else if (command instanceof ReadCommand)
        {
            return maxQueried((ReadCommand)command) > pageSize;
        }
        else
        {
            assert command instanceof RangeSliceCommand;
            // We can never be sure a range slice won't need paging
            return true;
        }
    }

    private static QueryPager pager(ReadCommand command, ConsistencyLevel consistencyLevel, boolean local)
    {
        if (command instanceof SliceByNamesReadCommand)
            return new NamesQueryPager((SliceByNamesReadCommand)command, consistencyLevel, local);
        else
            return new SliceQueryPager((SliceFromReadCommand)command, consistencyLevel, local);
    }

    private static QueryPager pager(Pageable command, ConsistencyLevel consistencyLevel, boolean local)
    {
        if (command instanceof Pageable.ReadCommands)
        {
            List<ReadCommand> commands = ((Pageable.ReadCommands)command).commands;
            if (commands.size() == 1)
                return pager(commands.get(0), consistencyLevel, local);

            return new MultiPartitionPager(commands, consistencyLevel, local);
        }
        else if (command instanceof ReadCommand)
        {
            return pager((ReadCommand)command, consistencyLevel, local);
        }
        else
        {
            assert command instanceof RangeSliceCommand;
            RangeSliceCommand rangeCommand = (RangeSliceCommand)command;
            if (rangeCommand.predicate instanceof NamesQueryFilter)
                return new RangeNamesQueryPager(rangeCommand, consistencyLevel, local);
            else
                return new RangeSliceQueryPager(rangeCommand, consistencyLevel, local);
        }
    }

    public static QueryPager pager(Pageable command, ConsistencyLevel consistencyLevel)
    {
        return pager(command, consistencyLevel, false);
    }

    public static QueryPager localPager(Pageable command)
    {
        return pager(command, null, true);
    }

    /**
     * Convenience method to (locally) page an internal row.
     * Used to 2ndary index a wide row without dying.
     */
    public static Iterator<ColumnFamily> pageRowLocally(final ColumnFamilyStore cfs, ByteBuffer key, final int pageSize)
    {
        SliceFromReadCommand command = new SliceFromReadCommand(cfs.metadata.ksName, key, cfs.name, System.currentTimeMillis(), new IdentityQueryFilter());
        final SliceQueryPager pager = new SliceQueryPager(command, null, true);

        return new Iterator<ColumnFamily>()
        {
            // We don't use AbstractIterator because we don't want hasNext() to do an actual query
            public boolean hasNext()
            {
                return !pager.isExhausted();
            }

            public ColumnFamily next()
            {
                try
                {
                    List<Row> rows = pager.fetchPage(pageSize);
                    ColumnFamily cf = rows.isEmpty() ? null : rows.get(0).cf;
                    return cf == null ? EmptyColumns.factory.create(cfs.metadata) : cf;
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }

            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * Convenience method that return a column iterator on a given slice of a row, but page underneath.
     *
     * The hasNext() and next() method of the returned iterator might throw RuntimeException whose cause
     * can be the underlying RequestValidationException or RequestExecutionException.
     */
    public static Iterator<Column> pageQuery(String keyspace,
                                             String columnFamily,
                                             ByteBuffer key,
                                             SliceQueryFilter filter,
                                             ConsistencyLevel consistencyLevel,
                                             final int pageSize,
                                             long now)
    {
        SliceFromReadCommand command = new SliceFromReadCommand(keyspace, key, columnFamily, now, filter);
        final SliceQueryPager pager = new SliceQueryPager(command, consistencyLevel, false);

        return new AbstractIterator<Column>()
        {
            private Iterator<Column> currentPage;

            protected Column computeNext()
            {
                if (currentPage != null && currentPage.hasNext())
                    return currentPage.next();

                if (pager.isExhausted())
                    return endOfData();

                try
                {
                    List<Row> next = pager.fetchPage(pageSize);
                    currentPage = next.get(0).cf.iterator();
                    return computeNext();
                }
                catch (RequestExecutionException e)
                {
                    throw new RuntimeException(e);
                }
                catch (RequestValidationException e)
                {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
