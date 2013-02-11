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
import java.util.Collection;
import java.util.Iterator;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;

import com.google.common.base.Function;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.filter.ColumnSlice;
import org.apache.cassandra.db.index.SecondaryIndexManager;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.utils.Allocator;

public class TreeMapBackedSortedColumns extends AbstractThreadUnsafeSortedColumns
{
    private final TreeMap<CellName, Column> map;

    public static final ColumnFamily.Factory<TreeMapBackedSortedColumns> factory = new Factory<TreeMapBackedSortedColumns>()
    {
        public TreeMapBackedSortedColumns create(CFMetaData metadata, boolean insertReversed)
        {
            assert !insertReversed;
            return new TreeMapBackedSortedColumns(metadata);
        }
    };

    public CellNameType getComparator()
    {
        return (CellNameType)map.comparator();
    }

    private TreeMapBackedSortedColumns(CFMetaData metadata)
    {
        super(metadata);
        this.map = new TreeMap<CellName, Column>(metadata.comparator);
    }

    private TreeMapBackedSortedColumns(CFMetaData metadata, SortedMap<CellName, Column> columns)
    {
        super(metadata);
        this.map = new TreeMap<CellName, Column>(columns);
    }

    public ColumnFamily.Factory getFactory()
    {
        return factory;
    }

    public ColumnFamily cloneMe()
    {
        return new TreeMapBackedSortedColumns(metadata, map);
    }

    public boolean isInsertReversed()
    {
        return false;
    }

    public void addColumn(Column column, Allocator allocator)
    {
        addColumn(column, allocator, SecondaryIndexManager.nullUpdater);
    }

    /*
     * If we find an old column that has the same name
     * the ask it to resolve itself else add the new column
    */
    public long addColumn(Column column, Allocator allocator, SecondaryIndexManager.Updater indexer)
    {
        CellName name = column.name();
        // this is a slightly unusual way to structure this; a more natural way is shown in ThreadSafeSortedColumns,
        // but TreeMap lacks putAbsent.  Rather than split it into a "get, then put" check, we do it as follows,
        // which saves the extra "get" in the no-conflict case [for both normal and super columns],
        // in exchange for a re-put in the SuperColumn case.
        Column oldColumn = map.put(name, column);
        if (oldColumn == null)
            return column.dataSize();

        // calculate reconciled col from old (existing) col and new col
        Column reconciledColumn = column.reconcile(oldColumn, allocator);
        map.put(name, reconciledColumn);
        // for memtable updates we only care about oldcolumn, reconciledcolumn, but when compacting
        // we need to make sure we update indexes no matter the order we merge
        if (reconciledColumn == column)
            indexer.update(oldColumn, reconciledColumn);
        else
            indexer.update(column, reconciledColumn);
        return reconciledColumn.dataSize() - oldColumn.dataSize();
    }

    /**
     * We need to go through each column in the column container and resolve it before adding
     */
    public void addAll(ColumnFamily cm, Allocator allocator, Function<Column, Column> transformation)
    {
        delete(cm.deletionInfo());
        for (Column column : cm)
            addColumn(transformation.apply(column), allocator);
    }

    public boolean replace(Column oldColumn, Column newColumn)
    {
        if (!oldColumn.name().equals(newColumn.name()))
            throw new IllegalArgumentException();

        // We are not supposed to put the newColumn is either there was not
        // column or the column was not equal to oldColumn (to be coherent
        // with other implementation). We optimize for the common case where
        // oldColumn do is present though.
        Column previous = map.put(oldColumn.name(), newColumn);
        if (previous == null)
        {
            map.remove(oldColumn.name());
            return false;
        }
        if (!previous.equals(oldColumn))
        {
            map.put(oldColumn.name(), previous);
            return false;
        }
        return true;
    }

    public Column getColumn(CellName name)
    {
        return map.get(name);
    }

    public void clear()
    {
        map.clear();
    }

    public int getColumnCount()
    {
        return map.size();
    }

    public Collection<Column> getSortedColumns()
    {
        return map.values();
    }

    public Collection<Column> getReverseSortedColumns()
    {
        return map.descendingMap().values();
    }

    public SortedSet<CellName> getColumnNames()
    {
        return map.navigableKeySet();
    }

    public Iterator<Column> iterator()
    {
        return map.values().iterator();
    }

    public Iterator<Column> iterator(ColumnSlice[] slices)
    {
        return new ColumnSlice.NavigableMapIterator(map, slices);
    }

    public Iterator<Column> reverseIterator(ColumnSlice[] slices)
    {
        return new ColumnSlice.NavigableMapIterator(map.descendingMap(), slices);
    }
}
