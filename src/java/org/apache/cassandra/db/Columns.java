package org.apache.cassandra.db;

import org.apache.cassandra.db.marshal.AbstractType;

public class Columns {

    private Columns() { }

    public static IColumn fromThrift(
            org.apache.cassandra.thrift.ColumnOrSuperColumn cosc,
            AbstractType subComparator) {
        return (cosc.column == null) ?
               fromThrift(cosc.super_column, subComparator) :
               fromThrift(cosc.column);
    }

    public static IColumn fromThrift(
            org.apache.cassandra.thrift.SuperColumn superColumn,
            AbstractType subComparator) {
        SuperColumn sc = new SuperColumn(superColumn.name, subComparator);
        for (org.apache.cassandra.thrift.Column column : superColumn.columns) {
            sc.addColumn(fromThrift(column));
        }
        return sc;
    }

    public static IColumn fromThrift(
            org.apache.cassandra.thrift.Column column) {
        return new Column(column.name, column.value, column.timestamp);
    }
}
